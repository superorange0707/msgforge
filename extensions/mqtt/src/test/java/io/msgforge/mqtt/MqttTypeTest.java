package io.msgforge.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.msgforge.TransportType;

/** Against an embedded ActiveMQ Artemis broker with its MQTT protocol module, which speaks 3.1.1 and 5. */
class MqttTypeTest {

    private final MqttType type = new MqttType();
    private EmbeddedActiveMQ broker;
    private String uri;

    @BeforeEach
    void start() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        uri = "tcp://127.0.0.1:" + port;
        broker = new EmbeddedActiveMQ().setConfiguration(new ConfigurationImpl().setPersistenceEnabled(false).setSecurityEnabled(false)
                .addAcceptorConfiguration("mqtt", uri + "?protocols=MQTT"));
        broker.start();
    }

    @AfterEach
    void stop() throws Exception {
        if (broker != null) broker.stop();
    }

    @Test
    void isDiscoverableAsAPluginAndCanReceive() {
        assertTrue(ServiceLoader.load(TransportType.class).stream().anyMatch(p -> p.type() == MqttType.class));
        assertTrue(type.canReceive());
        assertTrue(type.fields().stream().filter(f -> f.key().toLowerCase().contains("password")).allMatch(TransportType.Field::secret));
    }

    @Test
    void settingsAreCheckedBeforeConnecting() {
        assertTrue(MqttType.v5(Map.of()));
        assertFalse(MqttType.v5(Map.of("version", "3.1.1")));
        assertThrows(IllegalArgumentException.class, () -> MqttType.v5(Map.of("version", "6")));
        assertEquals(1, MqttType.qos(Map.of()));
        assertEquals(2, MqttType.qos(Map.of("qos", " 2 ")));
        assertThrows(IllegalArgumentException.class, () -> MqttType.qos(Map.of("qos", "3")));
        MqttType.refused("subscription", new int[] {1});   // granted QoS 1
        assertThrows(IllegalStateException.class, () -> MqttType.refused("subscription", new int[] {0x80}));   // 3.1.1 failure
        assertThrows(IllegalStateException.class, () -> MqttType.refused("message", new int[] {0x87}));   // 5: not authorised
        assertTrue(assertThrows(IllegalArgumentException.class, () -> type.open(Map.of("uri", "tcp://app:S3CRET@h:1883"))).getMessage().contains("own fields"));
        assertThrows(IllegalArgumentException.class, () -> type.open(Map.of("uri", "http://h")));
        assertThrows(IllegalArgumentException.class, () -> type.open(Map.of("uri", "tcp://h:1883", "trustStore", "/ca.pem")));
    }

    @Test
    void version5SendsHeadersAsUserPropertiesAndReceives() throws Exception {
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        BlockingQueue<Exception> errors = new LinkedBlockingQueue<>();
        BlockingQueue<MqttMessage> raw = new LinkedBlockingQueue<>();
        MqttClient watcher = new MqttClient(uri, "watcher", new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence());
        watcher.connect();
        watcher.subscribe(new org.eclipse.paho.mqttv5.common.MqttSubscription[] {new org.eclipse.paho.mqttv5.common.MqttSubscription("orders/eu", 1)},
                new org.eclipse.paho.mqttv5.client.IMqttMessageListener[] {(t, m) -> raw.add(m)});

        try (TransportType.Subscription in = type.subscribe(Map.of("uri", uri), "orders/#", got::add, errors::add);
             TransportType.Sender out = type.open(Map.of("uri", uri, "qos", "1"))) {
            out.check();
            out.send("orders/eu", "{\"id\":1}", Map.of("region", "EU"));
            assertEquals("{\"id\":1}", got.poll(10, TimeUnit.SECONDS));
            in.check();
        }
        MqttMessage seen = raw.poll(10, TimeUnit.SECONDS);
        assertNotNull(seen);
        assertEquals(List.of(new UserProperty("region", "EU")), seen.getProperties().getUserProperties());
        watcher.disconnect();
        watcher.close();
        assertTrue(errors.isEmpty(), "closing is not an error: " + errors);
    }

    @Test
    void version311SendsAndReceivesAndRefusesHeaders() throws Exception {
        Map<String, String> v3 = Map.of("uri", uri, "version", "3.1.1", "qos", "2", "clientId", "fixed-id");
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        BlockingQueue<Exception> errors = new LinkedBlockingQueue<>();
        try (TransportType.Subscription in = type.subscribe(v3, "readings/+", got::add, errors::add);
             TransportType.Sender out = type.open(v3)) {   // the same client id twice: the receiver's own suffix keeps them apart
            out.check();
            out.send("readings/7", "21.5", Map.of());
            assertEquals("21.5", got.poll(10, TimeUnit.SECONDS));
            assertTrue(assertThrows(IllegalArgumentException.class, () -> out.send("readings/7", "x", Map.of("a", "b"))).getMessage().contains("version to 5"));
            in.check();
        }
        assertTrue(errors.isEmpty(), "closing is not an error: " + errors);
    }

    @Test
    void aServerThatGoesAwayFailsTheReceiverInEitherVersion() throws Exception {
        BlockingQueue<Exception> errors5 = new LinkedBlockingQueue<>(), errors3 = new LinkedBlockingQueue<>();
        TransportType.Subscription five = type.subscribe(Map.of("uri", uri), "a/b", body -> { }, errors5::add);
        TransportType.Subscription three = type.subscribe(Map.of("uri", uri, "version", "3.1.1"), "a/b", body -> { }, errors3::add);
        broker.stop();
        broker = null;
        assertNotNull(errors5.poll(10, TimeUnit.SECONDS), "MQTT 5 receiver reported the lost connection");
        assertNotNull(errors3.poll(10, TimeUnit.SECONDS), "MQTT 3.1.1 receiver reported the lost connection");
        assertThrows(Exception.class, five::check);
        assertThrows(Exception.class, three::check);
        five.close();
        three.close();
        assertThrows(Exception.class, () -> type.open(Map.of("uri", uri)), "and nothing listens there now");
    }
}
