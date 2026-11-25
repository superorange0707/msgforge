package io.msgforge.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.msgforge.TransportType;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Against something that answers the SQS API. Runs only when MSGFORGE_IT_SQS names its endpoint, for example
 *   MSGFORGE_IT_SQS=http://127.0.0.1:9324   (ElasticMQ, which accepts any credentials)
 * MSGFORGE_IT_SQS_REGION, MSGFORGE_IT_SQS_KEY and MSGFORGE_IT_SQS_SECRET default to values ElasticMQ takes.
 */
@EnabledIfEnvironmentVariable(named = "MSGFORGE_IT_SQS", matches = ".+")
class SqsServerTest {

    private final SqsType type = new SqsType();

    private Map<String, String> settings() {
        Map<String, String> env = System.getenv();
        return new HashMap<>(Map.of("endpoint", env.get("MSGFORGE_IT_SQS"), "region", env.getOrDefault("MSGFORGE_IT_SQS_REGION", "elasticmq"),
                "accessKeyId", env.getOrDefault("MSGFORGE_IT_SQS_KEY", "x"), "secretAccessKey", env.getOrDefault("MSGFORGE_IT_SQS_SECRET", "x")));
    }

    @Test
    void sendsWithAttributesAndReceivesAndDeletes() throws Exception {
        String name = "msgforge-it-" + System.nanoTime();
        try (SqsClient admin = SqsType.client(settings())) {
            String url = admin.createQueue(r -> r.queueName(name)).queueUrl();
            try (TransportType.Sender out = type.open(settings())) {
                out.check();
                out.send(name, "kept for a look", Map.of("region", "EU"));
                Message seen = admin.receiveMessage(r -> r.queueUrl(url).messageAttributeNames("All").waitTimeSeconds(5)).messages().get(0);
                assertEquals("EU", seen.messageAttributes().get("region").stringValue());
                admin.deleteMessage(r -> r.queueUrl(url).receiptHandle(seen.receiptHandle()));

                BlockingQueue<String> got = new LinkedBlockingQueue<>();
                BlockingQueue<Exception> errors = new LinkedBlockingQueue<>();
                try (TransportType.Subscription in = type.subscribe(settings(), name, got::add, errors::add)) {
                    for (int i = 0; i < 15; i++) out.send(url, "m" + i, Map.of());   // by URL this time
                    for (int i = 0; i < 15; i++) assertTrue(got.poll(10, TimeUnit.SECONDS).startsWith("m"));
                    in.check();
                }
                assertTrue(errors.isEmpty(), errors.toString());
                Thread.sleep(500);
                Map<QueueAttributeName, String> left = admin.getQueueAttributes(r -> r.queueUrl(url).attributeNames(
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)).attributes();
                assertEquals("0", left.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES), "received messages are deleted");
                assertEquals("0", left.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));

                assertThrows(Exception.class, () -> out.send(name + "-missing", "x", Map.of()));
                assertThrows(Exception.class, () -> type.subscribe(settings(), name + "-missing", b -> { }, e -> { }));
            } finally {
                admin.deleteQueue(r -> r.queueUrl(url));
            }
        }
    }

    @Test
    void aFifoQueueGetsAGroupAndADeduplicationId() throws Exception {
        String name = "msgforge-it-" + System.nanoTime() + ".fifo";
        try (SqsClient admin = SqsType.client(settings())) {
            String url = admin.createQueue(r -> r.queueName(name).attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true"))).queueUrl();
            BlockingQueue<String> got = new LinkedBlockingQueue<>();
            try (TransportType.Sender out = type.open(settings()); TransportType.Subscription in = type.subscribe(settings(), name, got::add, e -> { })) {
                out.send(name, "same body", Map.of());
                out.send(name, "same body", Map.of());   // not deduplicated: each send has an id of its own
                assertEquals("same body", got.poll(10, TimeUnit.SECONDS));
                assertEquals("same body", got.poll(10, TimeUnit.SECONDS));
            } finally {
                admin.deleteQueue(r -> r.queueUrl(url));
            }
        }
    }
}
