package io.msgforge.pulsar;

import java.io.File;
import java.net.URI;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.pulsar.client.api.AuthenticationFactory;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Reader;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.TypedMessageBuilder;

import io.msgforge.TransportType;
import io.msgforge.ext.Tls;

/**
 * Apache Pulsar. The destination is a topic. A header named "key" becomes the message key; every other header becomes
 * a message property. Receiving reads the topic from its end with a reader, which has no durable subscription,
 * so it takes nothing from anyone. An arrival carries the message's properties, and its key under the same name "key".
 * A send returns the message id the broker stored the message under.
 */
public class PulsarType implements TransportType {

    @Override
    public String id() {
        return "pulsar";
    }

    @Override
    public String label() {
        return "Apache Pulsar";
    }

    @Override
    public List<Field> fields() {
        return Stream.concat(Stream.of(
                new Field("serviceUrl", "Service URL", "pulsar://localhost:6650, pulsar+ssl://host:6651 for TLS", true, false),
                new Field("token", "Token", "${MSGFORGE_PULSAR_TOKEN}", false, true)), Tls.fields().stream()).toList();
    }

    @Override
    public String summary(Map<String, String> s) {
        return s.getOrDefault("serviceUrl", "");
    }

    @Override
    public Sender open(Map<String, String> s) throws Exception {
        return sender(client(s));
    }

    @Override
    public boolean canReceive() {
        return true;
    }

    @Override
    public Subscription receive(Map<String, String> s, String topic, Consumer<Arrival> onArrival, Consumer<Exception> onError) throws Exception {
        PulsarClient client = client(s);
        Reader<String> reader;
        try {
            // A reader is a non-durable subscription: nothing is kept for it, and it does not share out messages with anyone.
            reader = client.newReader(Schema.STRING).topic(topic).startMessageId(MessageId.latest).create();
        } catch (Exception e) {
            close(client);
            throw unanswered(e);
        }
        return subscription(client, reader, onArrival, onError);
    }

    static PulsarClient client(Map<String, String> s) throws Exception {
        String url = s.getOrDefault("serviceUrl", "").trim();
        // a service URL may list several hosts after the scheme (pulsar://a:6650,b:6650): the first one is checked as a URI
        URI first = Tls.uri("Service URL", url.split(",")[0], "pulsar", "pulsar+ssl", "http", "https");
        boolean tls = first.getScheme().equalsIgnoreCase("pulsar+ssl") || first.getScheme().equalsIgnoreCase("https");
        if (!tls && Tls.configured(s)) throw new IllegalArgumentException("A trust store or key store needs a pulsar+ssl:// Service URL");
        if (url.toLowerCase().contains("@")) throw new IllegalArgumentException("Put the token in its own field, not in Service URL");
        ClientBuilder b = PulsarClient.builder().serviceUrl(url)
                .connectionTimeout(10, TimeUnit.SECONDS).operationTimeout(15, TimeUnit.SECONDS);
        if (!Tls.blank(s, "token")) b.authentication(AuthenticationFactory.token(s.get("token").trim()));
        if (tls) {
            b.enableTlsHostnameVerification(true).allowTlsInsecureConnection(false);
            if (!Tls.blank(s, "keyStore")) {
                // ponytail: with a client certificate the trust store has to be a JKS or PKCS12 store as well, not PEM
                String keyStore = s.get("keyStore").trim(), keyPassword = s.getOrDefault("keyStorePassword", "");
                b.useKeyStoreTls(true).tlsKeyStorePath(keyStore).tlsKeyStorePassword(keyPassword).tlsKeyStoreType(type("Key store", keyStore, keyPassword));
                if (!Tls.blank(s, "trustStore")) {
                    String trustStore = s.get("trustStore").trim(), trustPassword = s.getOrDefault("trustStorePassword", "");
                    String type = type("Trust store", trustStore, trustPassword);
                    b.tlsTrustStorePath(trustStore).tlsTrustStorePassword(trustPassword).tlsTrustStoreType(type);
                }
            } else if (!Tls.blank(s, "trustStore")) {
                String trustStore = s.get("trustStore").trim(), trustPassword = s.getOrDefault("trustStorePassword", "");
                Tls.trustManagers(s);   // the same checks and messages as the other plugins: missing file, wrong password
                if (keyStoreType(trustStore, trustPassword) == null) {
                    b.tlsTrustCertsFilePath(trustStore);   // PEM
                } else {
                    b.useKeyStoreTls(true).tlsTrustStorePath(trustStore).tlsTrustStorePassword(trustPassword).tlsTrustStoreType(keyStoreType(trustStore, trustPassword));
                }
            }
        }
        return b.build();
    }

    private static String type(String what, String path, String password) {
        if (!new File(path).isFile()) throw new IllegalArgumentException(what + " file not found: " + path);
        String type = keyStoreType(path, password);
        if (type == null) throw new IllegalArgumentException(what + " could not be opened as a JKS or PKCS12 store (wrong password or format): " + path);
        return type;
    }

    private static String keyStoreType(String path, String password) {
        try {
            return KeyStore.getInstance(new File(path), password.toCharArray()).getType();
        } catch (Exception notAKeyStore) {
            return null;
        }
    }

    /** Takes over the client and closes it with the sender. One producer per topic, created at its first message. */
    static Sender sender(PulsarClient client) {
        Map<String, Producer<String>> producers = new ConcurrentHashMap<>();
        return new Sender() {
            @Override
            public void send(String topic, String body, Map<String, String> headers) throws Exception {
                // The MessageId the broker answers with is not reported: Pulsar has no correlation field an arrival could
                // carry it in, so a run could never match on it. A reply is recognised by a token in its body or in a property.
                Producer<String> producer = producers.get(topic);
                if (producer == null) {
                    try {
                        producer = client.newProducer(Schema.STRING).topic(topic).sendTimeout(30, TimeUnit.SECONDS).blockIfQueueFull(true).create();
                    } catch (Exception e) {
                        throw unanswered(e);
                    }
                    producers.put(topic, producer);
                }
                TypedMessageBuilder<String> message = producer.newMessage().value(body);
                headers.forEach((name, value) -> {
                    if (name.equals("key")) message.key(value);
                    else message.property(name, value);
                });
                message.send();   // waits for the broker to store it
            }

            /**
             * Pulsar opens connections on demand, so this asks for a topic's metadata: a lookup the broker has to answer
             * after authenticating the client. The topic is not created, so the usual answer is "no such topic"; a broker
             * that keeps this client out of that one topic has still accepted its credentials. Both pass.
             */
            @Override
            public void check() throws Exception {
                try {
                    client.getPartitionsForTopic("msgforge-connection-check", false).get(15, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    if (!(e.getCause() instanceof PulsarClientException.TopicDoesNotExistException
                            || e.getCause() instanceof PulsarClientException.AuthorizationException)) throw unanswered((Exception) e.getCause());
                } catch (java.util.concurrent.TimeoutException e) {
                    throw unanswered(e);
                }
            }

            @Override
            public void close() {
                producers.values().forEach(p -> p.closeAsync());
                PulsarType.close(client);
            }
        };
    }

    /** What the sender turns into a message, read back the same way: the key under "key", the properties as they are. */
    static Map<String, String> headers(Message<String> m) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (m.hasKey() && m.getKey() != null) headers.put("key", m.getKey());
        // ponytail: a property named "key" replaces the message key.
        if (m.getProperties() != null) {
            m.getProperties().forEach((name, value) -> {
                if (value != null) headers.put(name, value);
            });
        }
        return headers;
    }

    /** Takes over the client and the reader: the reader is read on its own thread, and both are closed with the subscription. */
    static Subscription subscription(PulsarClient client, Reader<String> reader, Consumer<Arrival> onArrival, Consumer<Exception> onError) {
        AtomicBoolean closing = new AtomicBoolean();
        AtomicReference<Exception> ended = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                while (!closing.get()) {
                    Message<String> m = reader.readNext(500, TimeUnit.MILLISECONDS);
                    if (m != null && m.getValue() != null) onArrival.accept(new Arrival(m.getValue(), headers(m), null));
                }
            } catch (Throwable e) {
                Exception reason = e instanceof Exception x ? x : new IllegalStateException(e.toString());
                ended.set(reason);
                if (!closing.get()) onError.accept(reason);
            }
        }, "msgforge-pulsar-receive");
        thread.setDaemon(true);
        thread.start();
        return new Subscription() {
            /** Asking for the last message id is a round trip to the broker that owns the topic. */
            @Override
            public void check() throws Exception {
                if (ended.get() != null) throw ended.get();
                reader.getLastMessageIdsAsync().get(10, TimeUnit.SECONDS);
            }

            @Override
            public void close() {
                closing.set(true);
                try {
                    thread.join(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                try {
                    reader.close();
                } catch (Exception ignored) {
                    // the client goes next anyway
                }
                PulsarType.close(client);
            }
        };
    }

    /** The client's timeouts carry no message; the address may come from the environment, so it is not quoted. */
    static Exception unanswered(Exception e) {
        return e instanceof java.util.concurrent.TimeoutException || e instanceof PulsarClientException.TimeoutException
                ? new java.io.IOException("No Pulsar broker answered within 15 seconds") : e;
    }

    private static void close(PulsarClient client) {
        try {
            client.close();
        } catch (PulsarClientException ignored) {
            // nothing left to release
        }
    }
}
