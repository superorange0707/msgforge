package io.msgforge.kafka;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import io.msgforge.TransportType;

/**
 * Kafka as a msgforge connection type. The destination is the topic.
 * A header named "key" becomes the record key; every other header becomes a record header.
 * Receiving reads the topic from its end without a consumer group, so it takes nothing away from other consumers;
 * an arrival carries the record headers, and the record key under the same name "key".
 */
public class KafkaType implements TransportType {

    @Override
    public String id() {
        return "kafka";
    }

    @Override
    public String label() {
        return "Kafka";
    }

    @Override
    public List<Field> fields() {
        return List.of(
                new Field("bootstrap", "Bootstrap servers", "broker1:9092, broker2:9092", true, false),
                new Field("securityProtocol", "Security protocol", "PLAINTEXT", false, false),
                new Field("saslMechanism", "SASL mechanism", "PLAIN", false, false),
                new Field("user", "User", "", false, false),
                new Field("password", "Password", "${MSGFORGE_KAFKA_PASSWORD}", false, true),
                new Field("trustStore", "Trust store file", "/certs/truststore.p12", false, false),
                new Field("trustStorePassword", "Trust store password", "${MSGFORGE_TRUSTSTORE_PASSWORD}", false, true),
                new Field("keyStore", "Key store file", "/certs/keystore.p12", false, false),
                new Field("keyStorePassword", "Key store password", "${MSGFORGE_KEYSTORE_PASSWORD}", false, true));
    }

    @Override
    public String summary(Map<String, String> s) {
        String protocol = s.getOrDefault("securityProtocol", "");
        return s.getOrDefault("bootstrap", "") + (protocol.isBlank() || protocol.equals("PLAINTEXT") ? "" : " · " + protocol);
    }

    @Override
    public Sender open(Map<String, String> s) {
        Properties config = properties(s);
        return sender(new KafkaProducer<>(config, new StringSerializer(), new StringSerializer()), config);
    }

    @Override
    public boolean canReceive() {
        return true;
    }

    @Override
    public Subscription receive(Map<String, String> s, String topic, java.util.function.Consumer<Arrival> onArrival,
                                java.util.function.Consumer<Exception> onError) {
        Properties config = properties(s);
        List.of("client.id", "acks", "max.block.ms", "delivery.timeout.ms").forEach(config::remove);   // the producer's own
        config.put("default.api.timeout.ms", "10000");
        return subscription(new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer()), topic, onArrival, onError, Duration.ofSeconds(5));
    }

    /**
     * Takes over the consumer: it is polled on its own thread and closed there when the subscription closes.
     * patience is how long a topic that is not there yet is given to appear, how long nothing may be heard
     * before the broker is asked whether it is still there, and how long it has to answer.
     */
    static Subscription subscription(org.apache.kafka.clients.consumer.Consumer<String, String> consumer, String topic,
                                     java.util.function.Consumer<Arrival> onArrival, java.util.function.Consumer<Exception> onError, Duration patience) {
        List<TopicPartition> partitions;
        try {
            // A broker that creates topics on demand starts creating one when it is first asked about it, and answers
            // "no such topic" meanwhile. Asking again for a few seconds makes the first run against a new topic work like the second.
            List<PartitionInfo> known = consumer.partitionsFor(topic);
            for (long until = System.nanoTime() + patience.toNanos(); (known == null || known.isEmpty()) && System.nanoTime() < until;) {
                LockSupport.parkNanos(100_000_000);
                known = consumer.partitionsFor(topic);
            }
            if (known == null || known.isEmpty()) throw new IllegalArgumentException("Kafka topic not found: " + topic);
            // ponytail: the partitions the topic has now; ones added while a run is verifying are not read.
            partitions = known.stream().map(p -> new TopicPartition(topic, p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToEnd(partitions);
            // seekToEnd is lazy. Asking for the position settles it now; left to the first poll,
            // it would skip whatever the run sent before that poll.
            partitions.forEach(consumer::position);
        } catch (RuntimeException e) {
            consumer.close(Duration.ofSeconds(1));
            throw e;
        }
        // check() leaves its question here; the consumer may only be used by the thread that polls it
        AtomicReference<CompletableFuture<Void>> asked = new AtomicReference<>();
        // why the poll thread ended, once it has: a question asked of a thread that is gone gets this for an answer, at once
        AtomicReference<Exception> ended = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                long heard = System.nanoTime();
                while (true) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    for (ConsumerRecord<String, String> record : records) {
                        // Kafka has no correlation field of its own; applications that correlate do it in a header, which is delivered as one.
                        if (record.value() != null) onArrival.accept(new Arrival(record.value(), headers(record), null));
                    }
                    CompletableFuture<Void> question = asked.getAndSet(null);
                    if (!records.isEmpty()) heard = System.nanoTime();
                    // poll() goes on retrying a broker that has gone away and never says so. The end offsets are a question
                    // the broker has to answer. It is put when the run asks, and after a silence, so that a run with a long
                    // timeout does not sit out all of it in front of a dead broker.
                    if (question != null || System.nanoTime() - heard > patience.toNanos()) {
                        try {
                            consumer.endOffsets(partitions, patience);
                        } catch (org.apache.kafka.common.errors.TimeoutException gone) {
                            IllegalStateException e = new IllegalStateException("The Kafka broker stopped answering");
                            if (question != null) question.completeExceptionally(e);
                            throw e;
                        }
                        if (question != null) question.complete(null);
                        heard = System.nanoTime();
                    }
                }
            } catch (WakeupException closing) {
                ended.set(new IllegalStateException("The Kafka receiver was closed"));
            } catch (Throwable e) {
                // An Error too (a class the plugin cannot load). The loop ends here; the run must hear of it, or it would report every message as missing.
                Exception reason = e instanceof Exception x ? x : new IllegalStateException(e.toString());
                ended.set(reason);
                onError.accept(reason);
            } finally {
                // A question left while the last request was in flight was never picked up. ended is set before this
                // and read by check() after it leaves its question, so one of the two always delivers the answer.
                CompletableFuture<Void> unanswered = asked.getAndSet(null);
                if (unanswered != null) unanswered.completeExceptionally(ended.get());
                consumer.close(Duration.ofSeconds(5));
            }
        }, "msgforge-kafka-receive");
        thread.setDaemon(true);
        thread.start();
        return new Subscription() {
            @Override
            public void check() throws Exception {
                CompletableFuture<Void> question = new CompletableFuture<>();
                asked.set(question);
                if (ended.get() != null) throw ended.get();
                try {
                    // the poll in progress, a check already under way, and this one
                    question.get(patience.toMillis() * 2 + 1000, TimeUnit.MILLISECONDS);
                } catch (ExecutionException e) {
                    throw (Exception) e.getCause();
                } catch (TimeoutException e) {
                    throw new IllegalStateException("The Kafka broker stopped answering");
                }
            }

            @Override
            public void close() {
                consumer.wakeup();   // the one consumer call that is safe from another thread
                try {
                    thread.join(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
    }

    /** What the sender turns into a record, read back the same way: the key under "key", the record headers as UTF-8 text. */
    static Map<String, String> headers(ConsumerRecord<String, String> record) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (record.key() != null) headers.put("key", record.key());
        // ponytail: a header name that occurs twice keeps its last value, and a record header named "key" replaces the record key.
        for (Header h : record.headers()) {
            if (h.value() != null) headers.put(h.key(), new String(h.value(), StandardCharsets.UTF_8));
        }
        return headers;
    }

    static Properties properties(Map<String, String> s) {
        Properties p = new Properties();
        p.put("bootstrap.servers", s.getOrDefault("bootstrap", "").replaceAll("\\s+", ""));
        p.put("client.id", "msgforge");
        p.put("acks", "all");
        // Fail within seconds, not the client's default minutes, when a broker is unreachable.
        p.put("max.block.ms", "10000");
        p.put("request.timeout.ms", "10000");
        p.put("delivery.timeout.ms", "30000");
        put(p, "security.protocol", s.get("securityProtocol"));
        String user = s.getOrDefault("user", "");
        if (!user.isBlank()) {
            String mechanism = s.getOrDefault("saslMechanism", "PLAIN");
            String module = mechanism.startsWith("SCRAM") ? "org.apache.kafka.common.security.scram.ScramLoginModule"
                    : "org.apache.kafka.common.security.plain.PlainLoginModule";
            p.put("sasl.mechanism", mechanism);
            p.put("sasl.jaas.config", module + " required username=\"" + quote(user) + "\" password=\"" + quote(s.getOrDefault("password", "")) + "\";");
        }
        put(p, "ssl.truststore.location", s.get("trustStore"));
        put(p, "ssl.truststore.password", s.get("trustStorePassword"));
        put(p, "ssl.keystore.location", s.get("keyStore"));
        put(p, "ssl.keystore.password", s.get("keyStorePassword"));
        return p;
    }

    static Sender sender(Producer<String, String> producer, Properties config) {
        return new Sender() {
            // ponytail: waits for each acknowledgement, so a failure stops the run at the right message.
            // That caps throughput at a few thousand a second; send asynchronously if more is needed.
            @Override
            public void send(String destination, String body, Map<String, String> headers) throws Exception {
                ProducerRecord<String, String> record = new ProducerRecord<>(destination, headers.get("key"), body);
                headers.forEach((name, value) -> {
                    if (!name.equals("key")) record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
                });
                producer.send(record).get(30, TimeUnit.SECONDS);
            }

            @Override
            public void check() throws Exception {
                Admin admin = Admin.create(config);
                try {
                    admin.describeCluster().nodes().get(10, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    throw new IOException("No Kafka broker answered within 10 seconds");   // the address may come from the environment: not quoted
                } finally {
                    admin.close(Duration.ofSeconds(1));   // the default close waits for the pending call, up to a minute
                }
            }

            @Override
            public void close() {
                producer.close(Duration.ofSeconds(5));
            }
        };
    }

    private static void put(Properties p, String key, String value) {
        if (value != null && !value.isBlank()) p.put(key, value.trim());
    }

    private static String quote(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
