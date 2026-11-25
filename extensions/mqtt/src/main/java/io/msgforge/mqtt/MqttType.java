package io.msgforge.mqtt;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import io.msgforge.TransportType;
import io.msgforge.ext.Tls;

/**
 * MQTT 3.1.1 or 5, through the Eclipse Paho clients. The destination is a topic. Headers become MQTT 5 user properties;
 * 3.1.1 has nowhere to put them, so a header there is an error. Receiving subscribes with a clean session:
 * every subscriber gets its own copy, so nothing is taken from anyone. An MQTT 5 arrival carries its user properties
 * and its correlation data; a 3.1.1 message has neither.
 */
public class MqttType implements TransportType {

    static final long WAIT_MS = 30_000;

    @Override
    public String id() {
        return "mqtt";
    }

    @Override
    public String label() {
        return "MQTT";
    }

    @Override
    public List<Field> fields() {
        return Stream.concat(Stream.of(
                new Field("uri", "Server URI", "tcp://localhost:1883, ssl://host:8883 for TLS", true, false),
                new Field("version", "MQTT version", "5 (or 3.1.1)", false, false),
                new Field("clientId", "Client ID", "generated when empty", false, false),
                new Field("user", "User", "", false, false),
                new Field("password", "Password", "${MSGFORGE_MQTT_PASSWORD}", false, true),
                new Field("qos", "QoS", "1 (0, 1 or 2)", false, false),
                new Field("retain", "Retain", "false", false, false)), Tls.fields().stream()).toList();
    }

    @Override
    public String summary(Map<String, String> s) {
        return s.getOrDefault("uri", "") + " · MQTT " + (v5(s) ? "5" : "3.1.1");
    }

    @Override
    public Sender open(Map<String, String> s) throws Exception {
        Client client = connect(s, "", e -> { });
        int qos = qos(s);
        boolean retain = Boolean.parseBoolean(s.getOrDefault("retain", "").trim());
        return new Sender() {
            @Override
            public void send(String topic, String body, Map<String, String> headers) throws Exception {
                client.publish(topic, body.getBytes(StandardCharsets.UTF_8), qos, retain, headers);
            }

            @Override
            public void check() throws Exception {
                client.ping();
            }

            @Override
            public void close() {
                client.close();
            }
        };
    }

    @Override
    public boolean canReceive() {
        return true;
    }

    @Override
    public Subscription receive(Map<String, String> s, String topic, Consumer<Arrival> onArrival, Consumer<Exception> onError) throws Exception {
        Client client = connect(s, "-r", onError);
        try {
            client.subscribe(topic, qos(s), onArrival);
        } catch (Exception e) {
            client.close();
            throw e;
        }
        return new Subscription() {
            @Override
            public void check() throws Exception {
                client.ping();
            }

            @Override
            public void close() {
                client.close();
            }
        };
    }

    /** Unsubscribing is answered even when there was no such subscription, needs no permission, and delivers nothing. */
    static final String CHECK_TOPIC = "msgforge/check";

    /** The two Paho clients behind one face. A connection that breaks after connect() is handed to lost. */
    interface Client {

        void publish(String topic, byte[] body, int qos, boolean retain, Map<String, String> headers) throws Exception;

        void subscribe(String topic, int qos, Consumer<Arrival> onArrival) throws Exception;

        void ping() throws Exception;

        void close();
    }

    static Client connect(Map<String, String> s, String suffix, Consumer<Exception> lost) throws Exception {
        URI uri = Tls.uri("Server URI", s.getOrDefault("uri", ""), "tcp", "ssl", "ws", "wss");
        boolean tls = uri.getScheme().equalsIgnoreCase("ssl") || uri.getScheme().equalsIgnoreCase("wss");
        if (!tls && Tls.configured(s)) throw new IllegalArgumentException("A trust store or key store needs an ssl:// or wss:// Server URI");
        // the receiver connects separately and needs an id of its own: a second client with the same id would push the first off
        String clientId = (Tls.blank(s, "clientId") ? "msgforge-" + UUID.randomUUID().toString().substring(0, 8) : s.get("clientId").trim()) + suffix;
        String user = s.getOrDefault("user", "").trim();
        byte[] password = s.getOrDefault("password", "").getBytes(StandardCharsets.UTF_8);
        return v5(s) ? V5.connect(uri.toString(), clientId, user, password, tls ? Tls.contextOrDefault(s) : null, lost)
                : V3.connect(uri.toString(), clientId, user, password, tls ? Tls.contextOrDefault(s) : null, lost);
    }

    static boolean v5(Map<String, String> s) {
        String version = s.getOrDefault("version", "").trim();
        return switch (version) {
            case "", "5", "5.0" -> true;
            case "3.1.1", "3", "4" -> false;
            default -> throw new IllegalArgumentException("MQTT version must be 5 or 3.1.1");
        };
    }

    static int qos(Map<String, String> s) {
        String qos = s.getOrDefault("qos", "").trim();
        return switch (qos) {
            case "" -> 1;
            case "0", "1", "2" -> Integer.parseInt(qos);
            default -> throw new IllegalArgumentException("QoS must be 0, 1 or 2");
        };
    }

    /** A subscription or a publish the server answered with a failure code (0x80 and up: not authorised, bad topic…). */
    static void refused(String what, int[] codes) {
        if (codes == null) return;
        for (int code : codes) {
            if (code >= 0x80) throw new IllegalStateException("The MQTT server refused the " + what + " (reason code 0x" + Integer.toHexString(code) + ")");
        }
    }

    /** An MQTT 5 message with its user properties as headers and its correlation data, which the protocol defines as bytes, read as UTF-8. */
    static Arrival arrival(MqttMessage message) {
        Map<String, String> headers = new LinkedHashMap<>();
        MqttProperties properties = message.getProperties();
        String correlation = null;
        if (properties != null) {
            // ponytail: user properties may repeat a name; the last value is kept.
            if (properties.getUserProperties() != null) {
                for (UserProperty p : properties.getUserProperties()) {
                    if (p.getKey() != null && p.getValue() != null) headers.put(p.getKey(), p.getValue());
                }
            }
            if (properties.getCorrelationData() != null) correlation = new String(properties.getCorrelationData(), StandardCharsets.UTF_8);
        }
        return new Arrival(new String(message.getPayload(), StandardCharsets.UTF_8), headers, correlation);
    }

    static final class V5 implements Client {

        private final MqttAsyncClient client;
        private volatile boolean closing;
        private volatile Consumer<Arrival> sink = arrival -> { };

        private V5(MqttAsyncClient client) {
            this.client = client;
        }

        static Client connect(String uri, String clientId, String user, byte[] password, javax.net.ssl.SSLContext tls, Consumer<Exception> lost) throws MqttException {
            MqttAsyncClient client = new MqttAsyncClient(uri, clientId, new MemoryPersistence());
            MqttConnectionOptions o = new MqttConnectionOptions();
            o.setCleanStart(true);
            o.setSessionExpiryInterval(0L);   // nothing stays on the server after the run
            o.setAutomaticReconnect(false);   // a gap in receiving must fail the run, not pass unnoticed
            o.setConnectionTimeout(10);
            if (!user.isEmpty()) {
                o.setUserName(user);
                o.setPassword(password);
            }
            if (tls != null) {
                o.setSocketFactory(tls.getSocketFactory());
                o.setHttpsHostnameVerificationEnabled(true);
            }
            V5 v5 = new V5(client);
            client.setCallback(new MqttCallback() {
                @Override
                public void disconnected(MqttDisconnectResponse response) {
                    if (!v5.closing) lost.accept(new IllegalStateException("The MQTT server closed the connection"
                            + (response.getReasonString() == null ? "" : ": " + response.getReasonString())
                            + " (reason code " + response.getReturnCode() + ")"));
                }

                @Override
                public void mqttErrorOccurred(MqttException e) {
                    if (!v5.closing) lost.accept(e);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    v5.sink.accept(arrival(message));
                }

                @Override
                public void deliveryComplete(IMqttToken token) {
                }

                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                }

                @Override
                public void authPacketArrived(int reasonCode, MqttProperties properties) {
                }
            });
            try {
                client.connect(o).waitForCompletion(WAIT_MS);
            } catch (MqttException e) {
                v5.close();
                throw e;
            }
            return v5;
        }

        @Override
        public void publish(String topic, byte[] body, int qos, boolean retain, Map<String, String> headers) throws Exception {
            MqttMessage message = new MqttMessage(body, qos, retain, null);
            if (!headers.isEmpty()) {
                MqttProperties properties = new MqttProperties();
                properties.setUserProperties(headers.entrySet().stream().map(h -> new UserProperty(h.getKey(), h.getValue())).toList());
                message.setProperties(properties);
            }
            IMqttToken token = client.publish(topic, message);
            token.waitForCompletion(WAIT_MS);
            refused("message", token.getReasonCodes());
        }

        @Override
        public void subscribe(String topic, int qos, Consumer<Arrival> onArrival) throws Exception {
            sink = onArrival;   // one subscription per client, so the client's callback is its listener
            IMqttToken token = client.subscribe(new MqttSubscription(topic, qos));
            token.waitForCompletion(WAIT_MS);
            refused("subscription to " + topic, token.getReasonCodes());
        }

        @Override
        public void ping() throws Exception {
            client.unsubscribe(CHECK_TOPIC).waitForCompletion(10_000);
        }

        @Override
        public void close() {
            closing = true;
            try {
                if (client.isConnected()) client.disconnect(2_000).waitForCompletion(3_000);
            } catch (Exception ignored) {
                // going anyway
            }
            try {
                client.close(true);
            } catch (Exception ignored) {
                // nothing left to release
            }
        }
    }

    static final class V3 implements Client {

        private final org.eclipse.paho.client.mqttv3.MqttAsyncClient client;
        private volatile boolean closing;
        private volatile Consumer<Arrival> sink = arrival -> { };

        private V3(org.eclipse.paho.client.mqttv3.MqttAsyncClient client) {
            this.client = client;
        }

        static Client connect(String uri, String clientId, String user, byte[] password, javax.net.ssl.SSLContext tls, Consumer<Exception> lost)
                throws org.eclipse.paho.client.mqttv3.MqttException {
            org.eclipse.paho.client.mqttv3.MqttAsyncClient client = new org.eclipse.paho.client.mqttv3.MqttAsyncClient(uri, clientId,
                    new org.eclipse.paho.client.mqttv3.persist.MemoryPersistence());
            MqttConnectOptions o = new MqttConnectOptions();
            o.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
            o.setCleanSession(true);
            o.setAutomaticReconnect(false);
            o.setConnectionTimeout(10);
            if (!user.isEmpty()) {
                o.setUserName(user);
                o.setPassword(new String(password, StandardCharsets.UTF_8).toCharArray());
            }
            if (tls != null) {
                o.setSocketFactory(tls.getSocketFactory());
                o.setHttpsHostnameVerificationEnabled(true);
            }
            V3 v3 = new V3(client);
            client.setCallback(new org.eclipse.paho.client.mqttv3.MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    if (!v3.closing) lost.accept(new IllegalStateException("The MQTT connection was lost: " + cause.getMessage(), cause));
                }

                @Override
                public void messageArrived(String topic, org.eclipse.paho.client.mqttv3.MqttMessage message) {
                    // 3.1.1 has a topic and a payload, nothing else: no headers, no correlation
                    v3.sink.accept(new Arrival(new String(message.getPayload(), StandardCharsets.UTF_8), Map.of(), null));
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
            try {
                client.connect(o).waitForCompletion(WAIT_MS);
            } catch (org.eclipse.paho.client.mqttv3.MqttException e) {
                v3.close();
                throw e;
            }
            return v3;
        }

        @Override
        public void publish(String topic, byte[] body, int qos, boolean retain, Map<String, String> headers) throws Exception {
            if (!headers.isEmpty()) {
                throw new IllegalArgumentException("MQTT 3.1.1 messages have no properties to put headers in: set MQTT version to 5, or remove the headers");
            }
            client.publish(topic, body, qos, retain).waitForCompletion(WAIT_MS);
        }

        @Override
        public void subscribe(String topic, int qos, Consumer<Arrival> onArrival) throws Exception {
            sink = onArrival;
            org.eclipse.paho.client.mqttv3.IMqttToken token = client.subscribe(topic, qos);
            token.waitForCompletion(WAIT_MS);
            refused("subscription to " + topic, token.getGrantedQos());
        }

        @Override
        public void ping() throws Exception {
            client.unsubscribe(CHECK_TOPIC).waitForCompletion(10_000);
        }

        @Override
        public void close() {
            closing = true;
            try {
                if (client.isConnected()) client.disconnect(2_000).waitForCompletion(3_000);
            } catch (Exception ignored) {
                // going anyway
            }
            try {
                client.close(true);
            } catch (Exception ignored) {
                // nothing left to release
            }
        }
    }

}
