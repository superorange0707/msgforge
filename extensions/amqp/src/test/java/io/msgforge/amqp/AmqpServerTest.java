package io.msgforge.amqp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/**
 * Against a real broker. Runs only when MSGFORGE_IT_AMQP is set, for example
 *   MSGFORGE_IT_AMQP=amqp://127.0.0.1:5672 MSGFORGE_IT_AMQP_USER=… MSGFORGE_IT_AMQP_PASSWORD=… MSGFORGE_IT_AMQP_QUEUE=/queues/q
 * MSGFORGE_IT_AMQP_QUEUE is the address of a queue that exists (RabbitMQ 4 does not create one for an AMQP 1.0 link).
 * With MSGFORGE_IT_AMQP_TLS=amqps://localhost:5671 and MSGFORGE_IT_TLS_DIR (ca.pem, client.p12 with password changeit)
 * it also connects over TLS with a client certificate.
 */
@EnabledIfEnvironmentVariable(named = "MSGFORGE_IT_AMQP", matches = ".+")
class AmqpServerTest {

    private final AmqpType type = new AmqpType();
    private final String queue = System.getenv("MSGFORGE_IT_AMQP_QUEUE");

    private Map<String, String> settings() {
        return new HashMap<>(Map.of("uri", System.getenv("MSGFORGE_IT_AMQP"), "user", System.getenv("MSGFORGE_IT_AMQP_USER"),
                "password", System.getenv("MSGFORGE_IT_AMQP_PASSWORD")));
    }

    @Test
    void sendsAndReceivesByConsumingAndRefusesAWrongPassword() throws Exception {
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        BlockingQueue<Exception> errors = new LinkedBlockingQueue<>();
        try (TransportType.Sender out = type.open(settings());
             TransportType.Subscription in = type.subscribe(settings(), queue, got::add, errors::add)) {
            out.check();
            out.send(queue, "one", Map.of("JMSCorrelationID", "c-1", "region", "EU"));
            out.send(queue, "two", Map.of());
            assertEquals("one", got.poll(10, TimeUnit.SECONDS));
            assertEquals("two", got.poll(10, TimeUnit.SECONDS));
            in.check();
        }
        assertTrue(errors.isEmpty(), errors.toString());

        Map<String, String> wrong = settings();
        wrong.put("password", "not-the-pw-1234");
        String message = assertThrows(Exception.class, () -> type.open(wrong)).getMessage();
        assertFalse(message.contains("not-the-pw-1234"), message);

        // an address the broker does not know is refused at the send, not dropped
        try (TransportType.Sender out = type.open(settings())) {
            assertThrows(Exception.class, () -> out.send("/queues/msgforge-no-such-queue", "x", Map.of()));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MSGFORGE_IT_AMQP_TLS", matches = ".+")
    void connectsOverTlsWithAClientCertificateAndChecksTheServerName() throws Exception {
        String dir = System.getenv("MSGFORGE_IT_TLS_DIR");
        Map<String, String> tls = settings();
        tls.putAll(Map.of("uri", System.getenv("MSGFORGE_IT_AMQP_TLS"), "trustStore", dir + "/ca.pem", "keyStore", dir + "/client.p12",
                "keyStorePassword", "changeit"));
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        try (TransportType.Sender out = type.open(tls); TransportType.Subscription in = type.subscribe(tls, queue, got::add, e -> { })) {
            out.send(queue, "over tls", Map.of());
            assertEquals("over tls", got.poll(10, TimeUnit.SECONDS));
        }
        Map<String, String> noClientCertificate = new HashMap<>(tls);
        noClientCertificate.remove("keyStore");
        assertThrows(Exception.class, () -> type.open(noClientCertificate));
        Map<String, String> defaultTrust = new HashMap<>(tls);
        defaultTrust.remove("trustStore");
        assertThrows(Exception.class, () -> type.open(defaultTrust));
        Map<String, String> otherName = new HashMap<>(tls);
        otherName.put("uri", tls.get("uri").replace("localhost", "127.0.0.1"));
        assertThrows(Exception.class, () -> type.open(otherName));
    }
}
