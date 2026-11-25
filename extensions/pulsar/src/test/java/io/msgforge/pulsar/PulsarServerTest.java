package io.msgforge.pulsar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.msgforge.TransportType;

/**
 * Against a real broker. Runs only when MSGFORGE_IT_PULSAR is set, for example MSGFORGE_IT_PULSAR=pulsar://127.0.0.1:6650.
 * With MSGFORGE_IT_PULSAR_TLS=pulsar+ssl://localhost:6651 (client certificates required) and MSGFORGE_IT_TLS_DIR
 * (ca.pem, ca.p12 and client.p12, passwords changeit) it also connects over TLS. MSGFORGE_IT_PULSAR_TLS_WRONG_NAME names a TLS
 * port whose certificate is for another host name: connecting must fail. (An IP address is no test here: the client
 * looks the name up for it, and 127.0.0.1 becomes localhost.)
 */
@EnabledIfEnvironmentVariable(named = "MSGFORGE_IT_PULSAR", matches = ".+")
class PulsarServerTest {

    private final PulsarType type = new PulsarType();

    private Map<String, String> settings() {
        return new HashMap<>(Map.of("serviceUrl", System.getenv("MSGFORGE_IT_PULSAR")));
    }

    @Test
    void sendsKeyAndPropertiesAndReadsWithoutTakingFromASubscription() throws Exception {
        String topic = "persistent://public/default/msgforge-it-" + System.nanoTime();
        try (PulsarClient raw = PulsarType.client(settings());
             Consumer<String> other = raw.newConsumer(Schema.STRING).topic(topic).subscriptionName("system-under-test")
                     .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest).subscribe()) {
            BlockingQueue<String> got = new LinkedBlockingQueue<>();
            BlockingQueue<Exception> errors = new LinkedBlockingQueue<>();
            try (TransportType.Subscription in = type.subscribe(settings(), topic, got::add, errors::add); TransportType.Sender out = type.open(settings())) {
                out.check();
                out.send(topic, "hello", Map.of("key", "K-1", "region", "EU"));
                assertEquals("hello", got.poll(15, TimeUnit.SECONDS));
                in.check();
            }
            assertTrue(errors.isEmpty(), errors.toString());
            Message<String> m = other.receive(15, TimeUnit.SECONDS);
            assertEquals("hello", m.getValue(), "the durable subscription still has it: the reader took nothing");
            assertEquals("K-1", m.getKey());
            assertEquals("EU", m.getProperty("region"));
            assertNull(m.getProperty("key"));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MSGFORGE_IT_PULSAR_TLS", matches = ".+")
    void connectsOverTlsWithAClientCertificate() throws Exception {
        String dir = System.getenv("MSGFORGE_IT_TLS_DIR");
        String topic = "persistent://public/default/msgforge-it-tls-" + System.nanoTime();
        // the broker asks for a client certificate; with one, the trust store has to be a key store too
        Map<String, String> tls = Map.of("serviceUrl", System.getenv("MSGFORGE_IT_PULSAR_TLS"), "trustStore", dir + "/ca.p12",
                "trustStorePassword", "changeit", "keyStore", dir + "/client.p12", "keyStorePassword", "changeit");
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        try (TransportType.Subscription in = type.subscribe(tls, topic, got::add, e -> { }); TransportType.Sender out = type.open(tls)) {
            out.check();
            out.send(topic, "over tls", Map.of());
            assertEquals("over tls", got.poll(15, TimeUnit.SECONDS));
        }
        Map<String, String> pemTrustOnly = Map.of("serviceUrl", tls.get("serviceUrl"), "trustStore", dir + "/ca.pem");
        assertThrows(Exception.class, () -> check(pemTrustOnly), "no client certificate");
        Map<String, String> defaultTrust = new HashMap<>(tls);
        defaultTrust.remove("trustStore");
        defaultTrust.remove("trustStorePassword");
        assertThrows(Exception.class, () -> check(defaultTrust));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MSGFORGE_IT_PULSAR_TLS_WRONG_NAME", matches = ".+")
    void aCertificateForAnotherHostIsRefused() {
        String dir = System.getenv("MSGFORGE_IT_TLS_DIR");
        Map<String, String> tls = Map.of("serviceUrl", System.getenv("MSGFORGE_IT_PULSAR_TLS_WRONG_NAME"), "trustStore", dir + "/ca.p12",
                "trustStorePassword", "changeit", "keyStore", dir + "/client.p12", "keyStorePassword", "changeit");
        assertThrows(Exception.class, () -> check(tls));
    }

    private void check(Map<String, String> settings) throws Exception {
        try (TransportType.Sender out = type.open(settings)) {
            out.check();
            out.send("persistent://public/default/msgforge-it-check", "x", Map.of());
        }
    }
}
