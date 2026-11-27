package io.msgforge.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import io.msgforge.TransportType;

class KafkaTypeTest {

    private static final java.time.Duration SOON = java.time.Duration.ofMillis(200);

    @Test
    void isDiscoverableAsAPlugin() {
        assertTrue(ServiceLoader.load(TransportType.class).stream().anyMatch(p -> p.type() == KafkaType.class));
    }

    @Test
    void theKeyHeaderBecomesTheRecordKeyAndTheRestBecomeHeaders() throws Exception {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        TransportType.Sender sender = KafkaType.sender(producer, new Properties());

        sender.send("orders", "{\"a\":1}", Map.of("key", "K-1", "trace", "t-7"));
        sender.send("orders", "plain", Map.of());

        ProducerRecord<String, String> first = producer.history().get(0), second = producer.history().get(1);
        assertEquals("orders", first.topic());
        assertEquals("K-1", first.key());
        assertEquals("{\"a\":1}", first.value());
        assertEquals("t-7", new String(first.headers().lastHeader("trace").value()));
        assertNull(first.headers().lastHeader("key"));
        assertNull(second.key());
        sender.close();
        assertTrue(producer.closed());
    }

    @Test
    void settingsBecomeClientProperties() {
        Properties plain = KafkaType.properties(Map.of("bootstrap", "a:9092, b:9092"));
        assertEquals("a:9092,b:9092", plain.get("bootstrap.servers"));
        assertFalse(plain.containsKey("security.protocol"));
        assertFalse(plain.containsKey("sasl.jaas.config"));

        Properties secured = KafkaType.properties(Map.of("bootstrap", "a:9093", "securityProtocol", "SASL_SSL",
                "saslMechanism", "SCRAM-SHA-512", "user", "svc", "password", "p\"w", "trustStore", "/certs/t.p12", "trustStorePassword", "tp"));
        assertEquals("SASL_SSL", secured.get("security.protocol"));
        assertEquals("SCRAM-SHA-512", secured.get("sasl.mechanism"));
        assertEquals("org.apache.kafka.common.security.scram.ScramLoginModule required username=\"svc\" password=\"p\\\"w\";", secured.get("sasl.jaas.config"));
        assertEquals("/certs/t.p12", secured.get("ssl.truststore.location"));
        assertEquals("SASL_SSL", new KafkaType().summary(Map.of("bootstrap", "a:9093", "securityProtocol", "SASL_SSL")).split(" · ")[1]);
    }

    @Test
    void receivingReadsEveryPartitionFromItsEndAndClosesTheConsumer() throws Exception {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.LATEST);
        TopicPartition p0 = new TopicPartition("out", 0), p1 = new TopicPartition("out", 1);
        consumer.updatePartitions("out", List.of(new PartitionInfo("out", 0, null, null, null), new PartitionInfo("out", 1, null, null, null)));
        consumer.updateEndOffsets(Map.of(p0, 5L, p1, 0L));
        BlockingQueue<String> got = new LinkedBlockingQueue<>();

        List<Exception> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        TransportType.Subscription subscription = KafkaType.subscription(consumer, "out", a -> got.add(a.body()), errors::add, SOON);
        assertEquals(Set.of(p0, p1), consumer.assignment());
        assertEquals(5, consumer.position(p0), "positioned at the end before the first poll");

        consumer.addRecord(new ConsumerRecord<>("out", 0, 4, null, "sent before the run"));
        consumer.addRecord(new ConsumerRecord<>("out", 0, 5, null, "first"));
        consumer.addRecord(new ConsumerRecord<>("out", 1, 0, "key", "second"));
        consumer.addRecord(new ConsumerRecord<>("out", 1, 1, "key", null));
        assertEquals(Set.of("first", "second"), Set.of(got.poll(5, TimeUnit.SECONDS), got.poll(5, TimeUnit.SECONDS)));
        Thread.sleep(500);   // longer than SOON: the broker is asked whether it is there, and it is
        subscription.check();   // and asked by the run, it answers
        subscription.close();
        assertTrue(consumer.closed());
        assertTrue(got.isEmpty(), "nothing from before the end, and no null bodies");
        assertTrue(errors.isEmpty(), "closing is not an error");

        // a poll that fails is reported, where the loop used to end without a word; the consumer is still closed
        MockConsumer<String, String> failing = new MockConsumer<>(OffsetResetStrategy.LATEST);
        failing.updatePartitions("out", List.of(new PartitionInfo("out", 0, null, null, null)));
        failing.updateEndOffsets(Map.of(p0, 0L));
        BlockingQueue<Exception> reported = new LinkedBlockingQueue<>();
        failing.setPollException(new org.apache.kafka.common.KafkaException("not authorised to read out"));
        TransportType.Subscription broken = KafkaType.subscription(failing, "out", a -> got.add(a.body()), reported::add, SOON);
        assertEquals("not authorised to read out", reported.poll(5, TimeUnit.SECONDS).getMessage());
        broken.close();
        assertTrue(failing.closed());

        MockConsumer<String, String> other = new MockConsumer<>(OffsetResetStrategy.LATEST);
        assertThrows(IllegalArgumentException.class, () -> KafkaType.subscription(other, "no-such-topic", a -> got.add(a.body()), errors::add, SOON));
        assertTrue(other.closed());

        // A topic the broker is still creating: unknown at the first ask, there a moment later. It is waited for.
        MockConsumer<String, String> late = new MockConsumer<>(OffsetResetStrategy.LATEST);
        late.updateEndOffsets(Map.of(p0, 0L));
        Thread creating = new Thread(() -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {
                return;
            }
            late.updatePartitions("out", List.of(new PartitionInfo("out", 0, null, null, null)));
        });
        creating.start();
        TransportType.Subscription waited = KafkaType.subscription(late, "out", a -> got.add(a.body()), errors::add, java.time.Duration.ofSeconds(5));
        assertEquals(Set.of(p0), late.assignment());
        waited.close();

        // A broker that goes away is not reported by poll(), which only retries. After a silence the broker is asked
        // for the end offsets, and no answer ends the subscription with an error.
        MockConsumer<String, String> gone = new MockConsumer<>(OffsetResetStrategy.LATEST) {
            @Override
            public synchronized Map<TopicPartition, Long> endOffsets(java.util.Collection<TopicPartition> partitions, java.time.Duration timeout) {
                throw new org.apache.kafka.common.errors.TimeoutException("Failed to get offsets by times in " + timeout.toMillis() + "ms");
            }
        };
        gone.updatePartitions("out", List.of(new PartitionInfo("out", 0, null, null, null)));
        gone.updateEndOffsets(Map.of(p0, 0L));
        TransportType.Subscription deaf = KafkaType.subscription(gone, "out", a -> got.add(a.body()), reported::add, SOON);
        assertEquals("The Kafka broker stopped answering", reported.poll(5, TimeUnit.SECONDS).getMessage());
        deaf.close();
        assertTrue(gone.closed());

        // The run does not wait for that silence: when its timeout passes with messages still awaited it asks at once.
        // Patience is a minute here, so only the question can have produced the answer.
        MockConsumer<String, String> justGone = new MockConsumer<>(OffsetResetStrategy.LATEST) {
            @Override
            public synchronized Map<TopicPartition, Long> endOffsets(java.util.Collection<TopicPartition> partitions, java.time.Duration timeout) {
                throw new org.apache.kafka.common.errors.TimeoutException("Failed to get offsets by times in " + timeout.toMillis() + "ms");
            }
        };
        justGone.updatePartitions("out", List.of(new PartitionInfo("out", 0, null, null, null)));
        justGone.updateEndOffsets(Map.of(p0, 0L));
        TransportType.Subscription asked = KafkaType.subscription(justGone, "out", a -> got.add(a.body()), reported::add, java.time.Duration.ofMinutes(1));
        long before = System.nanoTime();
        assertEquals("The Kafka broker stopped answering", assertThrows(IllegalStateException.class, asked::check).getMessage());
        assertTrue(System.nanoTime() - before < 5_000_000_000L, "answered in seconds, not after the minute of patience");
        asked.close();
        assertTrue(justGone.closed());
        assertTrue(errors.isEmpty(), "and a broker that does answer is not reported: the first consumer above was asked more than once");
        assertTrue(new KafkaType().canReceive());
    }

    @Test
    void anArrivalCarriesTheRecordHeadersAndItsKey() throws Exception {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.LATEST);
        TopicPartition p0 = new TopicPartition("out", 0);
        consumer.updatePartitions("out", List.of(new PartitionInfo("out", 0, null, null, null)));
        consumer.updateEndOffsets(Map.of(p0, 0L));
        BlockingQueue<TransportType.Arrival> got = new LinkedBlockingQueue<>();
        TransportType.Subscription subscription = KafkaType.subscription(consumer, "out", got::add, e -> { }, java.time.Duration.ofSeconds(5));

        ConsumerRecord<String, String> keyed = new ConsumerRecord<>("out", 0, 0, "K-1", "shipped");
        keyed.headers().add("trace", "t-7".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        keyed.headers().add("région", "é".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        keyed.headers().add("empty", null);
        consumer.addRecord(keyed);
        consumer.addRecord(new ConsumerRecord<>("out", 0, 1, null, "bare"));

        TransportType.Arrival first = got.poll(5, TimeUnit.SECONDS), second = got.poll(5, TimeUnit.SECONDS);
        subscription.close();
        assertEquals("shipped", first.body());
        assertEquals(Map.of("key", "K-1", "trace", "t-7", "région", "é"), first.headers(), "what the sender takes as headers, a header without a value left out");
        assertEquals(List.of("key", "trace", "région"), List.copyOf(first.headers().keySet()), "in the record's order");
        assertNull(first.correlationId(), "Kafka has no correlation field");
        assertEquals(Map.of(), second.headers());


        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        assertNull(KafkaType.sender(producer, new Properties()).sendForId("orders", "x", Map.of()), "no id a consumer could echo, so none is claimed");
    }

    @Test
    void aQuestionAskedAsTheReceiverGivesUpGetsItsReasonAtOnce() throws Exception {
        // The broker went away. After its silence the receiver asks for the end offsets on its own; that request is
        // in flight when the run, whose timeout has just passed, asks too. The request then times out and the poll
        // thread ends. The run's question was left with a thread that will never pick it up.
        java.util.concurrent.CountDownLatch inFlight = new java.util.concurrent.CountDownLatch(1), timesOut = new java.util.concurrent.CountDownLatch(1);
        TopicPartition p0 = new TopicPartition("out", 0);
        MockConsumer<String, String> gone = new MockConsumer<>(OffsetResetStrategy.LATEST) {
            @Override
            public Map<TopicPartition, Long> endOffsets(java.util.Collection<TopicPartition> partitions, java.time.Duration timeout) {
                inFlight.countDown();
                try {
                    timesOut.await();
                } catch (InterruptedException ignored) {
                    // the test is over
                }
                throw new org.apache.kafka.common.errors.TimeoutException("Failed to get offsets by times in " + timeout.toMillis() + "ms");
            }
        };
        gone.updatePartitions("out", List.of(new PartitionInfo("out", 0, null, null, null)));
        gone.updateEndOffsets(Map.of(p0, 0L));
        BlockingQueue<Exception> reported = new LinkedBlockingQueue<>();
        // five seconds of patience: an unanswered question would be waited on for eleven
        TransportType.Subscription subscription = KafkaType.subscription(gone, "out", body -> { }, reported::add, java.time.Duration.ofSeconds(5));
        assertTrue(inFlight.await(20, TimeUnit.SECONDS), "the receiver's own request is under way");

        java.util.concurrent.CompletableFuture<Exception> answer = new java.util.concurrent.CompletableFuture<>();
        Thread run = new Thread(() -> {
            try {
                subscription.check();
                answer.complete(null);
            } catch (Exception e) {
                answer.complete(e);
            }
        });
        run.start();
        Thread.sleep(300);   // the question is out
        long before = System.nanoTime();
        timesOut.countDown();
        assertEquals("The Kafka broker stopped answering", answer.get(3, TimeUnit.SECONDS).getMessage(), "the reason, within moments");
        assertTrue(System.nanoTime() - before < 2_000_000_000L);
        assertEquals("The Kafka broker stopped answering", reported.poll(5, TimeUnit.SECONDS).getMessage());

        // and asked again later, of a receiver that has long ended, the same reason comes back without any wait
        before = System.nanoTime();
        assertEquals("The Kafka broker stopped answering", assertThrows(IllegalStateException.class, subscription::check).getMessage());
        assertTrue(System.nanoTime() - before < 1_000_000_000L);
        subscription.close();
        assertTrue(gone.closed());
    }

    @Test
    void aReceiverThatDiesOfAnErrorStillReportsItAndClosesItsConsumer() throws Exception {
        MockConsumer<String, String> broken = new MockConsumer<>(OffsetResetStrategy.LATEST);
        broken.updatePartitions("out", List.of(new PartitionInfo("out", 0, null, null, null)));
        broken.updateEndOffsets(Map.of(new TopicPartition("out", 0), 0L));
        broken.schedulePollTask(() -> {
            throw new NoClassDefFoundError("org/example/Missing");
        });
        BlockingQueue<Exception> reported = new LinkedBlockingQueue<>();
        TransportType.Subscription subscription = KafkaType.subscription(broken, "out", body -> { }, reported::add, java.time.Duration.ofSeconds(5));

        assertTrue(reported.poll(5, TimeUnit.SECONDS).getMessage().contains("org/example/Missing"));
        assertTrue(assertThrows(IllegalStateException.class, subscription::check).getMessage().contains("org/example/Missing"));
        subscription.close();
        assertTrue(broken.closed());
    }

    @Test
    void aRefusalOfCredentialsIsToldFromOtherFailures() {
        // what this client throws: a monitor stops on the first, and goes on trying after the second
        assertTrue(io.msgforge.Refusals.refused(new org.apache.kafka.common.errors.SaslAuthenticationException("Invalid username or password")));
        assertTrue(io.msgforge.Refusals.refused(new java.util.concurrent.ExecutionException(new org.apache.kafka.common.errors.TopicAuthorizationException("orders"))));
        assertFalse(io.msgforge.Refusals.refused(new org.apache.kafka.common.errors.TimeoutException("Topic orders not present in metadata after 5000 ms.")));
    }
}
