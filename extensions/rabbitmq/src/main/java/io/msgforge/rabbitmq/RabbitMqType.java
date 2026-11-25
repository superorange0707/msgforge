package io.msgforge.rabbitmq;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.ShutdownSignalException;

import io.msgforge.TransportType;
import io.msgforge.ext.Tls;

/**
 * RabbitMQ over AMQP 0-9-1. The destination is a queue name, or exchange/routing-key to publish to an exchange.
 * Receiving from a queue consumes it; receiving from exchange/routing-key binds a temporary queue of its own.
 * An arrival carries the headers table and the properties below under the names the sender takes them by.
 */
public class RabbitMqType implements TransportType {

    /** Headers with these names set the AMQP property of that name; every other header goes into the headers table. */
    static final List<String> PROPERTIES = List.of("content_type", "content_encoding", "correlation_id", "message_id",
            "reply_to", "type", "app_id", "expiration", "priority");

    @Override
    public String id() {
        return "rabbitmq";
    }

    @Override
    public String label() {
        return "RabbitMQ";
    }

    @Override
    public List<Field> fields() {
        return Stream.concat(Stream.of(
                new Field("uri", "Broker URI", "amqp://localhost:5672/vhost, amqps:// for TLS", true, false),
                new Field("user", "User", "guest", false, false),
                new Field("password", "Password", "${MSGFORGE_RABBITMQ_PASSWORD}", false, true)), Tls.fields().stream()).toList();
    }

    @Override
    public String summary(Map<String, String> s) {
        return s.getOrDefault("uri", "");
    }

    @Override
    public Sender open(Map<String, String> s) throws Exception {
        return sender(factory(s).newConnection("msgforge"));
    }

    @Override
    public boolean canReceive() {
        return true;
    }

    @Override
    public Subscription receive(Map<String, String> s, String destination, Consumer<Arrival> onArrival, Consumer<Exception> onError) throws Exception {
        return subscription(factory(s).newConnection("msgforge-receive"), destination, onArrival, onError);
    }

    static ConnectionFactory factory(Map<String, String> s) throws Exception {
        URI uri = Tls.uri("Broker URI", s.getOrDefault("uri", ""), "amqp", "amqps");
        if (uri.getHost() == null) throw new IllegalArgumentException("Broker URI needs a host, for example amqp://localhost:5672");
        boolean tls = uri.getScheme().equalsIgnoreCase("amqps");
        if (!tls && Tls.configured(s)) throw new IllegalArgumentException("A trust store or key store needs an amqps:// Broker URI");
        ConnectionFactory f = new ConnectionFactory();
        // Not setUri: for amqps:// it installs a trust manager that accepts any certificate.
        f.setHost(uri.getHost());
        f.setPort(uri.getPort() > 0 ? uri.getPort() : tls ? 5671 : 5672);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        f.setVirtualHost(path.length() <= 1 ? "/" : URLDecoder.decode(path.substring(1), StandardCharsets.UTF_8));
        if (!Tls.blank(s, "user")) {
            f.setUsername(s.get("user").trim());
            f.setPassword(s.getOrDefault("password", ""));
        }
        if (tls) {
            f.useSslProtocol(Tls.contextOrDefault(s));
            f.enableHostnameVerification();
        }
        // A broken connection must fail the run, not be mended quietly while messages go past.
        f.setAutomaticRecoveryEnabled(false);
        f.setConnectionTimeout(10_000);
        f.setHandshakeTimeout(10_000);
        f.setChannelRpcTimeout(10_000);
        return f;
    }

    /** "orders" is the queue orders; "events/order.created" is the exchange events with that routing key; "/orders" is the queue too. */
    static String[] address(String destination) {
        int slash = destination.indexOf('/');
        return slash < 0 ? new String[] {"", destination} : new String[] {destination.substring(0, slash), destination.substring(slash + 1)};
    }

    static AMQP.BasicProperties properties(Map<String, String> headers) {
        AMQP.BasicProperties.Builder p = new AMQP.BasicProperties.Builder().deliveryMode(2);   // persistent
        Map<String, Object> table = new HashMap<>();
        headers.forEach((name, value) -> {
            switch (name) {
                case "content_type" -> p.contentType(value);
                case "content_encoding" -> p.contentEncoding(value);
                case "correlation_id" -> p.correlationId(value);
                case "message_id" -> p.messageId(value);
                case "reply_to" -> p.replyTo(value);
                case "type" -> p.type(value);
                case "app_id" -> p.appId(value);
                case "expiration" -> p.expiration(value);
                case "priority" -> {
                    try {
                        p.priority(Integer.parseInt(value.trim()));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("The priority header must be a whole number, not \"" + value + "\"");
                    }
                }
                default -> table.put(name, value);
            }
        });
        return p.headers(table.isEmpty() ? null : table).build();
    }

    /**
     * The reverse of {@link #properties(Map)} for the text properties: the headers table, then each property that is set
     * under the name a header sets it by. expiration and priority say how the broker treats the message, not what it
     * is, and delivery mode is always set by the sender, so those are left out.
     */
    static Map<String, String> headers(AMQP.BasicProperties p) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (p == null) return headers;
        if (p.getHeaders() != null) {
            // The client hands a string in the table over as a LongString, whose toString() is its text.
            // ponytail: a nested table or list arrives as Java's toString() of it, not as JSON.
            p.getHeaders().forEach((name, value) -> {
                if (value != null) headers.put(name, value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString());
            });
        }
        put(headers, "content_type", p.getContentType());
        put(headers, "content_encoding", p.getContentEncoding());
        put(headers, "correlation_id", p.getCorrelationId());
        put(headers, "message_id", p.getMessageId());
        put(headers, "reply_to", p.getReplyTo());
        put(headers, "type", p.getType());
        put(headers, "app_id", p.getAppId());
        return headers;
    }

    private static void put(Map<String, String> headers, String name, String value) {
        if (value != null) headers.put(name, value);
    }

    /** Takes over the connection and closes it with the sender. */
    static Sender sender(Connection connection) throws IOException {
        Channel channel;
        AtomicReference<String> returned = new AtomicReference<>();
        try {
            channel = connection.createChannel();
            channel.confirmSelect();
            // mandatory publishing: a message no queue takes comes back here, before the broker confirms it
            channel.addReturnListener(r -> returned.set(r.getReplyText()));
        } catch (IOException | RuntimeException e) {
            close(connection);
            throw e;
        }
        return new Sender() {
            // ponytail: waits for the broker to confirm each message, so a failure stops the run at the right one.
            // That caps throughput at a few thousand a second.
            @Override
            public void send(String destination, String body, Map<String, String> headers) throws Exception {
                String[] at = address(destination);
                returned.set(null);
                channel.basicPublish(at[0], at[1], true, properties(headers), body.getBytes(StandardCharsets.UTF_8));
                if (!channel.waitForConfirms(30_000)) throw new IOException("RabbitMQ did not accept the message (nack)");
                if (returned.get() != null) {
                    throw new IOException("RabbitMQ could not route the message to any queue (" + returned.get() + ")"
                            + (at[0].isEmpty() ? ": there is no queue named " + at[1] : ": no queue is bound to exchange " + at[0] + " with that routing key"));
                }
            }

            /** Opening the connection logged in already; a channel is one more round trip, to be sure it is still there. */
            @Override
            public void check() throws Exception {
                connection.createChannel().close();
            }

            @Override
            public void close() {
                RabbitMqType.close(connection);
            }
        };
    }

    /** Takes over the connection and closes it with the subscription. */
    static Subscription subscription(Connection connection, String destination, Consumer<Arrival> onArrival, Consumer<Exception> onError) throws IOException {
        String queue;
        Channel channel;
        try {
            channel = connection.createChannel();
            String[] at = address(destination);
            if (at[0].isEmpty()) {
                queue = at[1];
                channel.queueDeclarePassive(queue);   // a clear "NOT_FOUND - no queue" now, not silence later
            } else {
                // A queue of its own, bound like the ones the system under test reads: copies, so nobody else loses a message.
                queue = channel.queueDeclare().getQueue();   // server-named, exclusive, deleted with the connection
                channel.queueBind(queue, at[0], at[1]);
            }
            channel.basicConsume(queue, true, new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String tag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) {
                    onArrival.accept(new Arrival(new String(body, StandardCharsets.UTF_8), headers(properties), properties == null ? null : properties.getCorrelationId()));
                }

                @Override
                public void handleCancel(String tag) {
                    onError.accept(new IllegalStateException("RabbitMQ cancelled the consumer of " + destination + " (the queue was deleted?)"));
                }

                @Override
                public void handleShutdownSignal(String tag, ShutdownSignalException signal) {
                    if (!signal.isInitiatedByApplication()) onError.accept(signal);
                }
            });
        } catch (IOException | RuntimeException e) {
            close(connection);
            throw e;
        }
        return new Subscription() {
            @Override
            public void check() throws Exception {
                channel.queueDeclarePassive(queue);
            }

            @Override
            public void close() {
                RabbitMqType.close(connection);
            }
        };
    }

    private static void close(Connection connection) {
        try {
            connection.close(5_000);
        } catch (Exception ignored) {
            // closed already, or the broker is gone
        }
    }
}
