package io.msgforge.nats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import io.msgforge.TransportType;
import io.msgforge.ext.Tls;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Dispatcher;
import io.nats.client.ErrorListener;
import io.nats.client.JetStream;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.impl.Headers;

/**
 * NATS. The destination is a subject. Sending is a core publish, or a JetStream publish that waits for the stream's
 * acknowledgement. Headers become NATS headers. Receiving is a core subscription: every subscriber gets its own copy,
 * and nothing is taken from a stream or from anyone else.
 */
public class NatsType implements TransportType {

    @Override
    public String id() {
        return "nats";
    }

    @Override
    public String label() {
        return "NATS";
    }

    @Override
    public List<Field> fields() {
        return Stream.concat(Stream.of(
                new Field("servers", "Servers", "nats://localhost:4222, tls://host:4222 for TLS", true, false),
                new Field("user", "User", "", false, false),
                new Field("password", "Password", "${MSGFORGE_NATS_PASSWORD}", false, true),
                new Field("token", "Token", "${MSGFORGE_NATS_TOKEN}", false, true),
                new Field("credentials", "Credentials file", "/secrets/user.creds", false, false),
                new Field("jetStream", "JetStream", "false; true to wait for the stream's acknowledgement", false, false)), Tls.fields().stream()).toList();
    }

    @Override
    public String summary(Map<String, String> s) {
        return s.getOrDefault("servers", "") + (Boolean.parseBoolean(s.getOrDefault("jetStream", "").trim()) ? " · JetStream" : "");
    }

    @Override
    public Sender open(Map<String, String> s) throws Exception {
        Problems problems = new Problems(e -> { });
        Connection nc = Nats.connect(options(s, problems));
        return sender(nc, Boolean.parseBoolean(s.getOrDefault("jetStream", "").trim()) ? nc.jetStream() : null, problems);
    }

    @Override
    public boolean canReceive() {
        return true;
    }

    @Override
    public Subscription subscribe(Map<String, String> s, String subject, Consumer<String> onMessage, Consumer<Exception> onError) throws Exception {
        Problems problems = new Problems(onError);
        return subscription(Nats.connect(options(s, problems)), subject, onMessage, problems);
    }

    static Options options(Map<String, String> s, Problems problems) throws Exception {
        String[] servers = Arrays.stream(s.getOrDefault("servers", "").split("[,\\s]+")).filter(x -> !x.isEmpty()).toArray(String[]::new);
        if (servers.length == 0) throw new IllegalArgumentException("Servers is required");
        boolean tls = false;
        for (String server : servers) {
            String scheme = Tls.uri("Servers", server, "nats", "tls", "ws", "wss").getScheme().toLowerCase();
            tls |= scheme.equals("tls") || scheme.equals("wss");
        }
        if (!tls && Tls.configured(s)) throw new IllegalArgumentException("A trust store or key store needs tls:// servers");
        Options.Builder b = new Options.Builder().servers(servers).noRandomize()
                // a gap in receiving must fail the run, not be bridged while messages go past
                .noReconnect()
                .connectionTimeout(Duration.ofSeconds(10)).connectionName("msgforge")
                .errorListener(problems).connectionListener(problems);
        if (tls) b.sslContext(Tls.contextOrDefault(s)).tlsVerifyHostname(true);
        if (!Tls.blank(s, "user")) b.userInfo(s.get("user").trim(), s.getOrDefault("password", ""));
        if (!Tls.blank(s, "token")) b.token(s.get("token").trim().toCharArray());
        if (!Tls.blank(s, "credentials")) {
            java.io.File file = new java.io.File(s.get("credentials").trim());
            if (!file.isFile()) throw new IllegalArgumentException("Credentials file not found: " + file);
            b.authHandler(Nats.credentials(file.getPath()));
        }
        return b.build();
    }

    /**
     * What the server reports on its own, outside any call: a permission it refused, a connection it closed.
     * The last one is kept for a sender to pick up after its next round trip; a receiver hands each to its run.
     */
    static final class Problems implements ErrorListener, ConnectionListener {

        final AtomicBoolean closing = new AtomicBoolean();
        final AtomicReference<String> last = new AtomicReference<>();
        private final Consumer<Exception> report;

        Problems(Consumer<Exception> report) {
            this.report = report;
        }

        void report(String problem) {
            last.set(problem);
            if (!closing.get()) report.accept(new IllegalStateException(problem));
        }

        @Override
        public void errorOccurred(Connection conn, String error) {
            report("The NATS server reported: " + error);
        }

        @Override
        public void exceptionOccurred(Connection conn, Exception exp) {
            report("NATS connection error: " + exp.getMessage());
        }

        @Override
        public void slowConsumerDetected(Connection conn, io.nats.client.Consumer consumer) {
            report("NATS dropped messages: msgforge did not read them fast enough");
        }

        @Override
        @SuppressWarnings("deprecation")   // the one the interface requires; its newer overload calls this one
        public void connectionEvent(Connection conn, Events type) {
            if (type == Events.DISCONNECTED || type == Events.CLOSED) report("The NATS connection was lost");
        }
    }

    /** Takes over the connection and closes it with the sender. */
    static Sender sender(Connection nc, JetStream js, Problems problems) {
        return new Sender() {
            // ponytail: each core publish is followed by a round trip, so a refusal the server reports is seen at
            // its message, and a dead connection at the first send. That caps throughput at a few thousand a second.
            @Override
            public void send(String subject, String body, Map<String, String> headers) throws Exception {
                Headers h = null;
                if (!headers.isEmpty()) {
                    h = new Headers();
                    for (Map.Entry<String, String> e : headers.entrySet()) h.add(e.getKey(), e.getValue());
                }
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                if (js != null) {
                    js.publish(subject, h, data);   // waits for the stream's acknowledgement; no stream on the subject is an error
                    return;
                }
                problems.last.set(null);
                nc.publish(subject, h, data);
                nc.flush(Duration.ofSeconds(10));
                String refused = problems.last.get();
                if (refused != null) throw new IOException(refused);
            }

            @Override
            public void check() throws Exception {
                nc.flush(Duration.ofSeconds(5));   // a PING the server has to answer
            }

            @Override
            public void close() {
                problems.closing.set(true);
                NatsType.close(nc);
            }
        };
    }

    /** Takes over the connection and closes it with the subscription. */
    static Subscription subscription(Connection nc, String subject, Consumer<String> onMessage, Problems problems) throws Exception {
        try {
            Dispatcher dispatcher = nc.createDispatcher(m -> onMessage.accept(new String(m.getData(), StandardCharsets.UTF_8)));
            dispatcher.subscribe(subject);
            // the server has the subscription before the run sends its first message
            nc.flush(Duration.ofSeconds(10));
        } catch (Exception e) {
            problems.closing.set(true);
            close(nc);
            throw e;
        }
        return new Subscription() {
            @Override
            public void check() throws Exception {
                nc.flush(Duration.ofSeconds(5));
            }

            @Override
            public void close() {
                problems.closing.set(true);
                NatsType.close(nc);
            }
        };
    }

    private static void close(Connection nc) {
        try {
            nc.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
