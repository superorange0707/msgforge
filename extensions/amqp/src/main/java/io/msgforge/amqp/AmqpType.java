package io.msgforge.amqp;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.qpid.jms.JmsConnectionFactory;

import io.msgforge.JmsReceiver;
import io.msgforge.JmsSender;
import io.msgforge.TransportType;
import io.msgforge.ext.Tls;

/**
 * AMQP 1.0 through Apache Qpid JMS, so any broker that speaks it: ActiveMQ Artemis, ActiveMQ, Azure Service Bus,
 * Solace, RabbitMQ 4 and others. The destination is the broker's address for a queue. Sending and receiving are
 * msgforge's own JMS sender and receiver, as for IBM MQ.
 */
public class AmqpType implements TransportType {

    @Override
    public String id() {
        return "amqp";
    }

    @Override
    public String label() {
        return "AMQP 1.0";
    }

    @Override
    public List<Field> fields() {
        return Stream.concat(Stream.of(
                new Field("uri", "Broker URI", "amqp://localhost:5672, amqps://host:5671 for TLS", true, false),
                new Field("user", "User", "", false, false),
                new Field("password", "Password", "${MSGFORGE_AMQP_PASSWORD}", false, true)), Tls.fields().stream()).toList();
    }

    @Override
    public String summary(Map<String, String> s) {
        return s.getOrDefault("uri", "");
    }

    @Override
    public Sender open(Map<String, String> s) throws Exception {
        JmsSender sender = new JmsSender(factory(s));
        try {
            sender.check();   // Qpid connects when asked for a connection: this is the login, and a wrong one fails here
        } catch (Exception e) {
            sender.close();
            throw e;
        }
        return sender;
    }

    @Override
    public boolean canReceive() {
        return true;
    }

    /** Consumes: every message it receives is taken off the queue. */
    @Override
    public Subscription subscribe(Map<String, String> s, String destination, Consumer<String> onMessage, Consumer<Exception> onError) throws Exception {
        return new JmsReceiver(factory(s), destination, onMessage, onError);
    }

    static JmsConnectionFactory factory(Map<String, String> s) throws Exception {
        String value = s.getOrDefault("uri", "").trim();
        URI uri = Tls.uri("Broker URI", value, "amqp", "amqps", "amqpws", "amqpwss", "failover");
        boolean tls = value.toLowerCase().matches("(failover:\\(?)?amqps:.*|(failover:\\(?)?amqpwss:.*");
        if (!tls && Tls.configured(s)) throw new IllegalArgumentException("A trust store or key store needs an amqps:// Broker URI");
        JmsConnectionFactory f = new JmsConnectionFactory(uri.toString());
        if (!Tls.blank(s, "user")) {
            f.setUsername(s.get("user").trim());
            f.setPassword(s.getOrDefault("password", ""));
        }
        // Qpid checks the server's certificate and host name unless the URI says otherwise, which Tls.uri does not allow
        if (Tls.configured(s)) f.setSslContext(Tls.context(s));
        // Fail within seconds, not the client's default of waiting for ever, when a broker does not answer.
        f.setConnectTimeout(10_000);
        f.setRequestTimeout(10_000);
        f.setSendTimeout(30_000);
        f.setCloseTimeout(5_000);
        f.setForceSyncSend(true);   // each send waits for the broker to accept the message, so a refusal stops the run at it
        return f;
    }
}
