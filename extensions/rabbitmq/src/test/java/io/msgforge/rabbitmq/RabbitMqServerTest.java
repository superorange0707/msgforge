package io.msgforge.rabbitmq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;

import io.msgforge.TransportType;

/**
 * Against a real broker. Runs only when MSGFORGE_IT_RABBITMQ is set, for example
 *   MSGFORGE_IT_RABBITMQ=amqp://127.0.0.1:5672 MSGFORGE_IT_RABBITMQ_USER=… MSGFORGE_IT_RABBITMQ_PASSWORD=…
 * With MSGFORGE_IT_RABBITMQ_TLS=amqps://localhost:5671 and MSGFORGE_IT_TLS_DIR (ca.pem, client.p12 with password changeit)
 * it also connects over TLS with a client certificate.
 */
@EnabledIfEnvironmentVariable(named = "MSGFORGE_IT_RABBITMQ", matches = ".+")
class RabbitMqServerTest {

    private final RabbitMqType type = new RabbitMqType();

    private Map<String, String> settings() {
        return Map.of("uri", System.getenv("MSGFORGE_IT_RABBITMQ"), "user", System.getenv("MSGFORGE_IT_RABBITMQ_USER"),
                "password", System.getenv("MSGFORGE_IT_RABBITMQ_PASSWORD"));
    }

    @Test
    void sendsToAQueueAndToAnExchangeAndReceivesBoth() throws Exception {
        String queue = "msgforge-it-" + System.nanoTime(), exchange = queue + "-x";
        try (Connection admin = RabbitMqType.factory(settings()).newConnection(); Channel ch = admin.createChannel()) {
            ch.queueDeclare(queue, true, false, false, null);   // RabbitMQ 4 refuses transient shared queues
            ch.exchangeDeclare(exchange, "topic", false, true, null);
            ch.queueBind(queue, exchange, "order.*");

            BlockingQueue<String> fromQueue = new LinkedBlockingQueue<>(), fromExchange = new LinkedBlockingQueue<>();
            BlockingQueue<Exception> errors = new LinkedBlockingQueue<>();
            try (TransportType.Sender sender = type.open(settings());
                 TransportType.Subscription copies = type.subscribe(settings(), exchange + "/order.*", fromExchange::add, errors::add)) {
                sender.check();
                sender.send(exchange + "/order.created", "via exchange", Map.of("correlation_id", "c-1", "region", "EU"));
                assertEquals("via exchange", fromExchange.poll(10, TimeUnit.SECONDS), "the bound copy got it");
                // and the real queue still has it: a subscription to an exchange takes nothing away
                GetResponse kept = ch.basicGet(queue, true);
                assertEquals("via exchange", new String(kept.getBody()));
                assertEquals("c-1", kept.getProps().getCorrelationId());
                assertEquals("EU", kept.getProps().getHeaders().get("region").toString());

                try (TransportType.Subscription consuming = type.subscribe(settings(), queue, fromQueue::add, errors::add)) {
                    sender.send(queue, "direct", Map.of());
                    assertEquals("direct", fromQueue.poll(10, TimeUnit.SECONDS));
                    consuming.check();
                }
                assertNull(ch.basicGet(queue, true), "consumed");

                String unroutable = assertThrows(Exception.class, () -> sender.send(exchange + "/nobody.listens", "x", Map.of())).getMessage();
                assertTrue(unroutable.contains("could not route"), unroutable);
                copies.check();
            }
            assertTrue(errors.isEmpty(), errors.toString());
            assertThrows(Exception.class, () -> type.subscribe(settings(), queue + "-missing", body -> { }, e -> { }));
            ch.queueDelete(queue);
        }
    }

    @Test
    void aWrongPasswordIsRefusedAndABrokerThatGoesAwayIsReported() throws Exception {
        Map<String, String> wrong = new HashMap<>(settings());
        wrong.put("password", "not-the-pw-1234");
        String refused = assertThrows(Exception.class, () -> type.open(wrong)).getMessage();
        assertFalse(refused.contains("not-the-pw-1234"), refused);

        // The broker closes the receiving connection (as when it goes down): the run must hear of it.
        String queue = "msgforge-it-" + System.nanoTime();
        try (Connection admin = RabbitMqType.factory(settings()).newConnection(); Channel ch = admin.createChannel()) {
            ch.queueDeclare(queue, true, false, false, null);
            BlockingQueue<Exception> errors = new LinkedBlockingQueue<>();
            try (TransportType.Subscription subscription = type.subscribe(settings(), queue, body -> { }, errors::add)) {
                ch.queueDelete(queue);
                assertTrue(errors.poll(10, TimeUnit.SECONDS) != null, "a deleted queue cancels the consumer, and that is reported");
                assertThrows(Exception.class, subscription::check);
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MSGFORGE_IT_RABBITMQ_TLS", matches = ".+")
    void connectsOverTlsWithAClientCertificateAndChecksTheServerName() throws Exception {
        String dir = System.getenv("MSGFORGE_IT_TLS_DIR");
        Map<String, String> tls = new HashMap<>(settings());
        tls.put("uri", System.getenv("MSGFORGE_IT_RABBITMQ_TLS"));
        tls.put("trustStore", dir + "/ca.pem");
        tls.put("keyStore", dir + "/client.p12");
        tls.put("keyStorePassword", "changeit");
        String queue = "msgforge-it-tls-" + System.nanoTime();
        try (Connection admin = RabbitMqType.factory(settings()).newConnection(); Channel ch = admin.createChannel()) {
            ch.queueDeclare(queue, true, false, true, null);
            BlockingQueue<String> got = new LinkedBlockingQueue<>();
            try (TransportType.Sender sender = type.open(tls); TransportType.Subscription in = type.subscribe(tls, queue, got::add, e -> { })) {
                sender.check();
                sender.send(queue, "over tls", Map.of());
                assertEquals("over tls", got.poll(10, TimeUnit.SECONDS));
            }
        }

        Map<String, String> noClientCertificate = new HashMap<>(tls);
        noClientCertificate.remove("keyStore");
        assertThrows(Exception.class, () -> type.open(noClientCertificate));

        Map<String, String> defaultTrust = new HashMap<>(tls);
        defaultTrust.remove("trustStore");
        assertThrows(Exception.class, () -> type.open(defaultTrust), "a private CA is not in the JVM's trust store");

        // the certificate names localhost, not 127.0.0.1: the host name is checked
        Map<String, String> otherName = new HashMap<>(tls);
        otherName.put("uri", tls.get("uri").replace("localhost", "127.0.0.1"));
        assertThrows(Exception.class, () -> type.open(otherName));
    }
}
