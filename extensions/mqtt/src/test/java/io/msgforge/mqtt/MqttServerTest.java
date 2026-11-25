package io.msgforge.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.msgforge.TransportType;

/**
 * Against a real server. Runs only when MSGFORGE_IT_MQTT is set, for example
 *   MSGFORGE_IT_MQTT=tcp://127.0.0.1:1883 MSGFORGE_IT_MQTT_USER=… MSGFORGE_IT_MQTT_PASSWORD=…
 * The user may use msgforge/# and nothing else under secret/. With MSGFORGE_IT_MQTT_TLS=ssl://localhost:8883 and
 * MSGFORGE_IT_TLS_DIR (ca.pem, client.p12 with password changeit) it also connects over TLS with a client certificate.
 */
@EnabledIfEnvironmentVariable(named = "MSGFORGE_IT_MQTT", matches = ".+")
class MqttServerTest {

    private final MqttType type = new MqttType();

    private Map<String, String> settings(String version) {
        Map<String, String> s = new HashMap<>(Map.of("uri", System.getenv("MSGFORGE_IT_MQTT"), "version", version,
                "user", System.getenv("MSGFORGE_IT_MQTT_USER"), "password", System.getenv("MSGFORGE_IT_MQTT_PASSWORD")));
        String tls = System.getenv("MSGFORGE_IT_MQTT_TLS");
        if (tls != null) {
            String dir = System.getenv("MSGFORGE_IT_TLS_DIR");
            s.putAll(Map.of("tlsUri", tls, "trustStore", dir + "/ca.pem", "keyStore", dir + "/client.p12", "keyStorePassword", "changeit"));
        }
        return s;
    }

    @ParameterizedTest
    @ValueSource(strings = {"5", "3.1.1"})
    void sendsAndReceivesAndAWrongPasswordOrAForbiddenTopicIsAnError(String version) throws Exception {
        Map<String, String> s = settings(version);
        s.remove("trustStore");
        s.remove("keyStore");
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        BlockingQueue<Exception> errors = new LinkedBlockingQueue<>();
        String topic = "msgforge/it/" + System.nanoTime();
        try (TransportType.Subscription in = type.subscribe(s, topic + "/#", got::add, errors::add); TransportType.Sender out = type.open(s)) {
            out.check();
            in.check();
            out.send(topic + "/a", "hello " + version, version.equals("5") ? Map.of("trace", "t-1") : Map.of());
            assertEquals("hello " + version, got.poll(10, TimeUnit.SECONDS));
            if (version.equals("5")) {
                // MQTT 5 says so for a publish too; 3.1.1 has no way to, the server drops it
                assertTrue(assertThrows(Exception.class, () -> out.send("secret/x", "x", Map.of())).getMessage().contains("refused the message"));
            }
        }
        assertTrue(errors.isEmpty(), errors.toString());

        Map<String, String> wrong = new HashMap<>(s);
        wrong.put("password", "not-the-pw-1234");
        String message = assertThrows(Exception.class, () -> type.open(wrong)).getMessage();
        assertFalse(message.contains("not-the-pw-1234"), message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5", "3.1.1"})
    @EnabledIfEnvironmentVariable(named = "MSGFORGE_IT_MQTT_TLS", matches = ".+")
    void connectsOverTlsWithAClientCertificateAndChecksTheServerName(String version) throws Exception {
        Map<String, String> tls = settings(version);
        tls.put("uri", tls.remove("tlsUri"));
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        try (TransportType.Subscription in = type.subscribe(tls, "msgforge/tls", got::add, e -> { }); TransportType.Sender out = type.open(tls)) {
            out.send("msgforge/tls", "over tls", Map.of());
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
        assertNotNull(assertThrows(Exception.class, () -> type.open(otherName)));
    }
}
