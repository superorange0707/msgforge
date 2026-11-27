package io.msgforge.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void aVersion5ArrivalCarriesItsUserPropertiesAndCorrelationDataAndA311ArrivalHasNeither() throws Exception {
        // without a broker: what the client hands over, turned into an arrival
        MqttMessage reply = new MqttMessage("shipped".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        org.eclipse.paho.mqttv5.common.packet.MqttProperties properties = new org.eclipse.paho.mqttv5.common.packet.MqttProperties();
        properties.setUserProperties(List.of(new UserProperty("region", "EU"), new UserProperty("trace", "t-7")));
        properties.setCorrelationData("order-41".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        reply.setProperties(properties);
        TransportType.Arrival made = MqttType.arrival(reply);
        assertEquals("shipped", made.body());
        assertEquals(List.of("region", "trace"), List.copyOf(made.headers().keySet()));
        assertEquals("order-41", made.correlationId());
        TransportType.Arrival bare = MqttType.arrival(new MqttMessage(new byte[0]));
        assertEquals(Map.of(), bare.headers());
        assertNull(bare.correlationId());

        // and through the broker, in both versions
        BlockingQueue<TransportType.Arrival> got5 = new LinkedBlockingQueue<>(), got3 = new LinkedBlockingQueue<>();
        BlockingQueue<Exception> errors = new LinkedBlockingQueue<>();
        MqttClient replier = new MqttClient(uri, "replier", new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence());
        replier.connect();
        try (TransportType.Subscription five = type.receive(Map.of("uri", uri), "replies/#", got5::add, errors::add);
             TransportType.Subscription three = type.receive(Map.of("uri", uri, "version", "3.1.1"), "replies/#", got3::add, errors::add);
             TransportType.Sender out = type.open(Map.of("uri", uri))) {
            reply.setQos(1);
            replier.publish("replies/eu", reply);
            TransportType.Arrival arrived = got5.poll(10, TimeUnit.SECONDS);
            assertNotNull(arrived);
            assertEquals("shipped", arrived.body());
            assertEquals(Map.of("region", "EU", "trace", "t-7"), arrived.headers());
            assertEquals("order-41", arrived.correlationId());

            TransportType.Arrival old = got3.poll(10, TimeUnit.SECONDS);
            assertNotNull(old);
            assertEquals("shipped", old.body());
            assertEquals(Map.of(), old.headers(), "3.1.1 has nowhere to carry them");
            assertNull(old.correlationId());

            // what this sender puts in as headers comes back as headers; MQTT gives a message no id a receiver sees
            assertNull(out.sendForId("replies/eu", "again", Map.of("region", "EU")));
            assertEquals(Map.of("region", "EU"), got5.poll(10, TimeUnit.SECONDS).headers());
        }
        replier.disconnect();
        replier.close();
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

    @Test
    void aRefusalOfCredentialsIsToldFromOtherFailures() {
        // what this client throws: a monitor stops on the first, and goes on trying after the second
        assertTrue(io.msgforge.Refusals.refused(new org.eclipse.paho.client.mqttv3.MqttSecurityException(org.eclipse.paho.client.mqttv3.MqttException.REASON_CODE_FAILED_AUTHENTICATION)));
        assertTrue(io.msgforge.Refusals.refused(new org.eclipse.paho.client.mqttv3.MqttSecurityException(org.eclipse.paho.client.mqttv3.MqttException.REASON_CODE_NOT_AUTHORIZED)));
        assertTrue(io.msgforge.Refusals.refused(new org.eclipse.paho.mqttv5.common.MqttSecurityException(134)));
        assertTrue(io.msgforge.Refusals.refused(new IllegalStateException(new org.eclipse.paho.mqttv5.common.MqttException(135).getMessage())), "by its text: " + new org.eclipse.paho.mqttv5.common.MqttException(135).getMessage());
        assertFalse(io.msgforge.Refusals.refused(new org.eclipse.paho.client.mqttv3.MqttException(org.eclipse.paho.client.mqttv3.MqttException.REASON_CODE_SERVER_CONNECT_ERROR)));
    }
}
