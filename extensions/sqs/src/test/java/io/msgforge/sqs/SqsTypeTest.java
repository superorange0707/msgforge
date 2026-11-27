package io.msgforge.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.msgforge.TransportType;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.ListQueuesRequest;
import software.amazon.awssdk.services.sqs.model.ListQueuesResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

/** SqsClient is an interface: a small fake in memory stands in for the service. */
class SqsTypeTest {

    /** One queue, "orders", that hands out what was put on it and forgets what was deleted. */
    static class FakeSqs implements SqsClient {
        final List<SendMessageRequest> sent = new CopyOnWriteArrayList<>();
        final BlockingQueue<Message> waiting = new LinkedBlockingQueue<>();
        final List<String> deleted = new CopyOnWriteArrayList<>();
        final AtomicBoolean closed = new AtomicBoolean();
        final List<ReceiveMessageRequest> receives = new CopyOnWriteArrayList<>();
        volatile RuntimeException failReceive;
        volatile RuntimeException failList;

        @Override
        public GetQueueUrlResponse getQueueUrl(GetQueueUrlRequest r) {
            if (!r.queueName().equals("orders")) throw QueueDoesNotExistException.builder().message("The specified queue does not exist.").build();
            return GetQueueUrlResponse.builder().queueUrl("http://sqs.local/000000000000/orders").build();
        }

        @Override
        public SendMessageResponse sendMessage(SendMessageRequest r) {
            sent.add(r);
            return SendMessageResponse.builder().messageId("m-" + sent.size()).build();
        }

        @Override
        public ReceiveMessageResponse receiveMessage(ReceiveMessageRequest r) {
            if (failReceive != null) throw failReceive;
            receives.add(r);
            try {
                Message first = waiting.poll(r.waitTimeSeconds(), TimeUnit.SECONDS);
                List<Message> batch = new java.util.ArrayList<>();
                if (first != null) batch.add(first);
                waiting.drainTo(batch, r.maxNumberOfMessages() - batch.size());
                return ReceiveMessageResponse.builder().messages(batch).build();
            } catch (InterruptedException e) {
                throw software.amazon.awssdk.core.exception.AbortedException.create("Thread was interrupted");
            }
        }

        @Override
        public DeleteMessageBatchResponse deleteMessageBatch(DeleteMessageBatchRequest r) {
            r.entries().forEach(e -> deleted.add(e.receiptHandle()));
            return DeleteMessageBatchResponse.builder().build();
        }

        @Override
        public ListQueuesResponse listQueues(ListQueuesRequest r) {
            if (failList != null) throw failList;
            return ListQueuesResponse.builder().build();
        }

        @Override
        public GetQueueAttributesResponse getQueueAttributes(GetQueueAttributesRequest r) {
            return GetQueueAttributesResponse.builder().build();
        }

        @Override
        public String serviceName() {
            return "sqs";
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    @Test
    void isDiscoverableAsAPluginAndCanReceive() {
        assertTrue(ServiceLoader.load(TransportType.class).stream().anyMatch(p -> p.type() == SqsType.class));
        assertTrue(new SqsType().canReceive());
        assertTrue(new SqsType().fields().stream().filter(f -> f.key().matches("(?i).*(password|secret|token).*")).allMatch(TransportType.Field::secret));
    }

    @Test
    void credentialsComeFromTheConnectionOnly() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> SqsType.client(Map.of("region", "eu-west-1"))).getMessage().contains("required"));
        assertThrows(IllegalArgumentException.class, () -> SqsType.client(Map.of("accessKeyId", "a", "secretAccessKey", "b")));
        assertThrows(IllegalArgumentException.class, () -> SqsType.client(Map.of("region", "eu-west-1", "accessKeyId", "a", "secretAccessKey", "b",
                "endpoint", "http://k:S3CRET@localhost:9324")));
        assertThrows(IllegalArgumentException.class, () -> SqsType.client(Map.of("region", "eu-west-1", "accessKeyId", "a", "secretAccessKey", "b",
                "endpoint", "http://localhost:9324", "trustStore", "/ca.pem")));
        SqsType.client(Map.of("region", "eu-west-1", "accessKeyId", "a", "secretAccessKey", "b", "sessionToken", "t", "endpoint", "http://localhost:9324")).close();
    }

    @Test
    void headersBecomeAttributesAndAFifoQueueGetsAGroupAndADeduplicationId() throws Exception {
        FakeSqs sqs = new FakeSqs();
        TransportType.Sender sender = SqsType.sender(sqs);
        sender.send("orders", "{\"a\":1}", Map.of("region", "EU"));
        sender.send("https://sqs.eu-west-1.amazonaws.com/123456789012/orders.fifo", "x", Map.of("MessageGroupId", "g-7"));

        SendMessageRequest plain = sqs.sent.get(0), fifo = sqs.sent.get(1);
        assertEquals("http://sqs.local/000000000000/orders", plain.queueUrl());
        assertEquals("EU", plain.messageAttributes().get("region").stringValue());
        assertEquals("String", plain.messageAttributes().get("region").dataType());
        assertNull(plain.messageGroupId());
        assertEquals("g-7", fifo.messageGroupId());
        assertTrue(fifo.messageDeduplicationId() != null && !fifo.messageAttributes().containsKey("MessageGroupId"));
        assertThrows(QueueDoesNotExistException.class, () -> sender.send("missing", "x", Map.of()));

        sender.check();
        // a user who may send but not list queues: the credentials are still proven good
        sqs.failList = (RuntimeException) SqsException.builder().statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build()).build();
        sender.check();
        sqs.failList = (RuntimeException) SqsException.builder().statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("InvalidClientTokenId").build()).build();
        assertThrows(SqsException.class, sender::check);
        sender.close();
        assertTrue(sqs.closed.get());
    }

    @Test
    void anArrivalCarriesItsAttributesAndASendReportsNoId() throws Exception {
        FakeSqs sqs = new FakeSqs();
        TransportType.Sender sender = SqsType.sender(sqs);
        // SQS answers a send with a MessageId, but no arrival could carry it back as a correlation id, so none is reported
        assertNull(sender.sendForId("orders", "one", Map.of()));
        sender.send("orders", "two", Map.of());
        assertEquals(2, sqs.sent.size(), "each sends once");

        FakeSqs receiving = new FakeSqs();
        BlockingQueue<TransportType.Arrival> got = new LinkedBlockingQueue<>();
        TransportType.Subscription subscription = SqsType.subscription(receiving, "orders", got::add, e -> { }, 1);
        receiving.waiting.add(Message.builder().body("shipped").receiptHandle("r1").messageId("m-9").messageAttributes(Map.of(
                "region", software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder().dataType("String").stringValue("EU").build(),
                "parcels", software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder().dataType("Number").stringValue("3").build(),
                "label", software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder().dataType("Binary")
                        .binaryValue(software.amazon.awssdk.core.SdkBytes.fromUtf8String("café")).build())).build());
        receiving.waiting.add(Message.builder().body("bare").receiptHandle("r2").build());

        TransportType.Arrival first = got.poll(5, TimeUnit.SECONDS), second = got.poll(5, TimeUnit.SECONDS);
        subscription.close();
        assertEquals("shipped", first.body());
        assertEquals(Map.of("region", "EU", "parcels", "3", "label", "café"), first.headers());
        assertNull(first.correlationId(), "SQS has no correlation field");
        assertEquals(Map.of(), second.headers());
        assertTrue(receiving.receives.stream().allMatch(r -> r.messageAttributeNames().equals(List.of("All"))), "the service leaves attributes out unless they are asked for");
    }

    @Test
    void receivingHandsOnEachBodyDeletesItAndReportsAFailure() throws Exception {
        FakeSqs sqs = new FakeSqs();
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        BlockingQueue<Exception> errors = new LinkedBlockingQueue<>();
        TransportType.Subscription subscription = SqsType.subscription(sqs, "orders", a -> got.add(a.body()), errors::add, 1);
        sqs.waiting.add(Message.builder().body("one").receiptHandle("r1").build());
        sqs.waiting.add(Message.builder().body("two").receiptHandle("r2").build());
        assertEquals("one", got.poll(5, TimeUnit.SECONDS));
        assertEquals("two", got.poll(5, TimeUnit.SECONDS));
        for (int i = 0; i < 50 && sqs.deleted.size() < 2; i++) Thread.sleep(20);
        assertEquals(List.of("r1", "r2"), sqs.deleted);
        subscription.check();
        subscription.close();
        assertTrue(sqs.closed.get());
        assertTrue(errors.isEmpty(), "closing is not an error: " + errors);

        FakeSqs failing = new FakeSqs();
        failing.failReceive = (RuntimeException) SqsException.builder().message("Access to the resource is denied.").build();
        TransportType.Subscription broken = SqsType.subscription(failing, "orders", a -> got.add(a.body()), errors::add, 1);
        assertEquals("Access to the resource is denied.", errors.poll(5, TimeUnit.SECONDS).getMessage());
        assertThrows(SqsException.class, broken::check);
        broken.close();

        FakeSqs other = new FakeSqs();
        assertThrows(QueueDoesNotExistException.class, () -> SqsType.subscription(other, "missing", a -> got.add(a.body()), errors::add, 1));
        assertTrue(other.closed.get());
    }

    @Test
    void aRefusalOfCredentialsIsToldFromOtherFailures() {
        // what this client throws: a monitor stops on the first, and goes on trying after the second
        for (String code : java.util.List.of("AccessDenied", "InvalidClientTokenId")) {
            assertTrue(io.msgforge.Refusals.refused(software.amazon.awssdk.services.sqs.model.SqsException.builder().message("refused").statusCode(403)
                    .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder().errorCode(code).errorMessage("refused").build()).build()), code);
        }
        org.junit.jupiter.api.Assertions.assertFalse(io.msgforge.Refusals.refused(software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException.builder().message("no such queue").statusCode(400)
                .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder().errorCode("AWS.SimpleQueueService.NonExistentQueue").build()).build()));
    }
}
