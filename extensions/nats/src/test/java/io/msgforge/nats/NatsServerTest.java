package io.msgforge.nats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.msgforge.TransportType;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StorageType;

/**
 * Against a real server. Runs only when MSGFORGE_IT_NATS is set, for example
 *   MSGFORGE_IT_NATS=nats://127.0.0.1:4222 MSGFORGE_IT_NATS_USER=… MSGFORGE_IT_NATS_PASSWORD=…
 * The server runs JetStream, and the user may publish and subscribe on msgforge.> but not on secret.>.
 * With MSGFORGE_IT_NATS_TLS=tls://localhost:4443 (the same user, client certificates required) and MSGFORGE_IT_TLS_DIR
 * (ca.pem, client.p12 with password changeit) it also connects over TLS.
 */
@EnabledIfEnvironmentVariable(named = "MSGFORGE_IT_NATS", matches = ".+")
class NatsServerTest {

    private final NatsType type = new NatsType();

    private Map<String, String> settings() {
        return new HashMap<>(Map.of("servers", System.getenv("MSGFORGE_IT_NATS"), "user", System.getenv("MSGFORGE_IT_NATS_USER"),
                "password", System.getenv("MSGFORGE_IT_NATS_PASSWORD")));
    }

    @Test
    void corePublishWithHeadersReachesEverySubscriberAndARefusalIsAnError() throws Exception {
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        BlockingQueue<Exception> errors = new LinkedBlockingQueue<>();
        try (Connection raw = Nats.connect(NatsType.options(settings(), new NatsType.Problems(e -> { })));
             TransportType.Subscription in = type.subscribe(settings(), "msgforge.it.>", got::add, errors::add);
             TransportType.Sender out = type.open(settings())) {
            io.nats.client.Subscription other = raw.subscribe("msgforge.it.a");
            raw.flush(Duration.ofSeconds(5));
            out.check();
            out.send("msgforge.it.a", "hello", Map.of("trace", "t-1"));
            assertEquals("hello", got.poll(10, TimeUnit.SECONDS));
            Message seen = other.nextMessage(Duration.ofSeconds(10));
            assertEquals("t-1", seen.getHeaders().getFirst("trace"), "and the other subscriber got it too, headers and all");
            in.check();

            String refused = assertThrows(Exception.class, () -> out.send("secret.x", "x", Map.of())).getMessage();
            assertTrue(refused.contains("Permissions Violation"), refused);
        }
        assertTrue(errors.isEmpty(), errors.toString());

        // a subscription the server does not allow is reported to the run
        BlockingQueue<Exception> refusedErrors = new LinkedBlockingQueue<>();
        try (TransportType.Subscription denied = type.subscribe(settings(), "secret.>", body -> { }, refusedErrors::add)) {
            Exception e = refusedErrors.poll(10, TimeUnit.SECONDS);
            assertNotNull(e);
            assertTrue(e.getMessage().contains("Permissions Violation"), e.getMessage());
        }

        Map<String, String> wrong = settings();
        wrong.put("password", "not-the-pw-1234");
        String message = assertThrows(Exception.class, () -> type.open(wrong)).getMessage();
        assertFalse(message.contains("not-the-pw-1234"), message);
    }

    @Test
    void jetStreamPublishWaitsForTheStreamAndFailsWithoutOne() throws Exception {
        String stream = "MSGFORGE_IT_" + System.nanoTime();
        try (Connection admin = Nats.connect(NatsType.options(settings(), new NatsType.Problems(e -> { })))) {
            admin.jetStreamManagement().addStream(StreamConfiguration.builder().name(stream).subjects("msgforge.js.>").storageType(StorageType.Memory).build());
            try {
                Map<String, String> js = settings();
                js.put("jetStream", "true");
                BlockingQueue<String> got = new LinkedBlockingQueue<>();
                try (TransportType.Subscription in = type.subscribe(js, "msgforge.js.>", got::add, e -> { }); TransportType.Sender out = type.open(js)) {
                    out.send("msgforge.js.a", "stored", Map.of("trace", "t-2"));
                    assertEquals("stored", got.poll(10, TimeUnit.SECONDS), "a core subscriber sees what JetStream stores");
                    assertThrows(Exception.class, () -> out.send("msgforge.nostream.a", "x", Map.of()), "no stream takes this subject");
                }
                assertEquals(1, admin.jetStreamManagement().getStreamInfo(stream).getStreamState().getMsgCount(), "and the stream still has it");
            } finally {
                admin.jetStreamManagement().deleteStream(stream);
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MSGFORGE_IT_NATS_TLS", matches = ".+")
    void connectsOverTlsWithAClientCertificateAndChecksTheServerName() throws Exception {
        String dir = System.getenv("MSGFORGE_IT_TLS_DIR");
        Map<String, String> tls = settings();
        tls.putAll(Map.of("servers", System.getenv("MSGFORGE_IT_NATS_TLS"), "trustStore", dir + "/ca.pem", "keyStore", dir + "/client.p12",
                "keyStorePassword", "changeit"));
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        try (TransportType.Subscription in = type.subscribe(tls, "msgforge.tls", got::add, e -> { }); TransportType.Sender out = type.open(tls)) {
            out.send("msgforge.tls", "over tls", Map.of());
            assertEquals("over tls", got.poll(10, TimeUnit.SECONDS));
        }
        Map<String, String> noClientCertificate = new HashMap<>(tls);
        noClientCertificate.remove("keyStore");
        assertThrows(Exception.class, () -> type.open(noClientCertificate));
        Map<String, String> defaultTrust = new HashMap<>(tls);
        defaultTrust.remove("trustStore");
        assertThrows(Exception.class, () -> type.open(defaultTrust));
        Map<String, String> otherName = new HashMap<>(tls);
        otherName.put("servers", tls.get("servers").replace("localhost", "127.0.0.1"));
        assertThrows(Exception.class, () -> type.open(otherName));
    }
}
