package io.msgforge;

import java.io.File;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import org.springframework.core.annotation.Order;
import org.springframework.jms.connection.UserCredentialsConnectionFactoryAdapter;
import org.springframework.stereotype.Component;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;

import jakarta.jms.ConnectionFactory;

@Component
@Order(1)
public class IbmMqType implements TransportType {

    private static final Pattern HOST = Pattern.compile("[^\\s(),:]+\\(\\d{1,5}\\)");

    static {
        // On a non-IBM JVM the client must be told that cipher suites use the standard (JSSE) names.
        if (System.getProperty("com.ibm.mq.cfg.useIBMCipherMappings") == null) {
            System.setProperty("com.ibm.mq.cfg.useIBMCipherMappings", "false");
        }
    }

    @Override
    public String id() {
        return "ibm-mq";
    }

    @Override
    public String label() {
        return "IBM MQ";
    }

    @Override
    public List<Field> fields() {
        return List.of(
                new Field("hosts", "Hosts", "mq1(1414), mq2(1414)", true, false),
                new Field("queueManager", "Queue manager", "QM1", false, false),
                new Field("channel", "Channel", "DEV.APP.SVRCONN", true, false),
                new Field("user", "User", "", false, false),
                new Field("password", "Password", "${MSGFORGE_MQ_PASSWORD}", false, true),
                new Field("cipherSuite", "TLS cipher suite", "TLS_AES_256_GCM_SHA384", false, false),
                new Field("trustStore", "Trust store file", "/certs/truststore.p12", false, false),
                new Field("trustStorePassword", "Trust store password", "${MSGFORGE_TRUSTSTORE_PASSWORD}", false, true),
                new Field("keyStore", "Key store file", "/certs/keystore.p12", false, false),
                new Field("keyStorePassword", "Key store password", "${MSGFORGE_KEYSTORE_PASSWORD}", false, true));
    }

    /** Connections saved before host lists existed had separate host and port settings. */
    @Override
    public Map<String, String> migrate(Map<String, String> settings) {
        if (settings.containsKey("hosts") || !settings.containsKey("host")) return settings;
        Map<String, String> out = new LinkedHashMap<>(settings);
        out.put("hosts", out.remove("host") + "(" + out.getOrDefault("port", "1414") + ")");
        out.remove("port");
        return out;
    }

    @Override
    public String summary(Map<String, String> s) {
        String qm = s.getOrDefault("queueManager", "");
        return (qm.isBlank() ? "" : qm + " @ ") + s.getOrDefault("hosts", "") + (s.containsKey("cipherSuite") ? " · TLS" : "");
    }

    @Override
    public Sender open(Map<String, String> s) throws Exception {
        JmsSender sender = new JmsSender(factory(s));
        return new Sender() {
            @Override
            public void send(String destination, String body, Map<String, String> headers) {
                sender.send(destination, body, headers);
            }

            @Override
            public String sendForId(String destination, String body, Map<String, String> headers) {
                return sender.sendForId(destination, body, headers);
            }

            @Override
            public void check() throws Exception {
                try {
                    sender.check();
                } catch (Exception e) {
                    throw advised(e, s);
                }
            }

            @Override
            public void close() {
                sender.close();
            }
        };
    }

    /**
     * A TLS mismatch is reported by Java or by the queue manager in terms of their own ("certification path",
     * "Remote CipherSpec error", "Channel negotiation failed"). This adds which setting of the connection to change.
     * Anything else is returned as it is.
     */
    static Exception advised(Exception e, Map<String, String> s) {
        String text = Runs.describe(e);
        String advice = text.contains("unable to find valid certification path")
                ? "The queue manager's certificate is not trusted: set Trust store file to a store that holds it or its issuer."
                : text.contains("MQRC_UNSUPPORTED_CIPHER_SUITE")
                ? "TLS cipher suite must be the Java name of a suite, for example TLS_AES_256_GCM_SHA384, not an MQ CipherSpec name."
                : text.contains("AMQ9641")
                ? (blank(s, "cipherSuite") ? "The channel requires TLS: set TLS cipher suite." : "The channel does not accept this cipher suite: use one its SSLCIPH setting allows or, if the channel does not use TLS, clear TLS cipher suite.")
                // what a channel without TLS does to a TLS handshake: it hangs up, and Java reports only that
                : text.contains("SSL peer shut down incorrectly") || text.contains("Remote host terminated the handshake")
                ? "The queue manager ended the TLS handshake. If the channel does not use TLS, clear TLS cipher suite; otherwise use a suite its SSLCIPH setting allows."
                : text.contains("AMQ9503") && !blank(s, "cipherSuite") && blank(s, "keyStore")
                ? "If the channel requires a client certificate, set Key store file."
                : null;
        return advice == null ? e : new IllegalStateException(text + " " + advice);
    }

    @Override
    public boolean canReceive() {
        return true;
    }

    /** Consumes: every message it receives is taken off the queue. */
    @Override
    public Subscription receive(Map<String, String> s, String destination, Consumer<Arrival> onArrival, Consumer<Exception> onError) throws Exception {
        try {
            return JmsReceiver.arrivals(factory(s), destination, onArrival, onError);
        } catch (jakarta.jms.JMSException e) {
            throw advised(e, s);
        }
    }

    private static ConnectionFactory factory(Map<String, String> s) throws Exception {
        MQConnectionFactory mq = new MQConnectionFactory();
        mq.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        mq.setConnectionNameList(hosts(s.getOrDefault("hosts", "")));   // tried in order until one accepts
        if (!blank(s, "queueManager")) mq.setQueueManager(s.get("queueManager"));
        mq.setChannel(s.get("channel"));

        boolean stores = !blank(s, "trustStore") || !blank(s, "keyStore");
        if (!blank(s, "cipherSuite")) {
            mq.setSSLCipherSuite(s.get("cipherSuite"));
            if (stores) mq.setSSLSocketFactory(sslSocketFactory(s));   // otherwise the JVM's default trust store applies
        } else if (stores) {
            throw new IllegalArgumentException("TLS cipher suite is required when a trust store or key store is set");
        }

        if (blank(s, "user")) return mq;
        mq.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        UserCredentialsConnectionFactoryAdapter withLogin = new UserCredentialsConnectionFactoryAdapter();
        withLogin.setTargetConnectionFactory(mq);
        withLogin.setUsername(s.get("user"));
        withLogin.setPassword(s.getOrDefault("password", ""));
        return withLogin;
    }

    /** "mq1, mq2(1415)" becomes "mq1(1414),mq2(1415)", the form the MQ client expects. */
    static String hosts(String raw) {
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String host = part.trim();
            if (host.isEmpty()) continue;
            if (!host.contains("(")) host += "(1414)";
            if (!HOST.matcher(host).matches()) throw new IllegalArgumentException("Write hosts as name(port), separated by commas");   // the value may come from ${ENV}: not quoted
            out.add(host);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("Hosts is required");
        return String.join(",", out);
    }

    /** A trust store alone gives server authentication; adding a key store gives mutual TLS. */
    static SSLSocketFactory sslSocketFactory(Map<String, String> s) throws Exception {
        TrustManagerFactory trust = null;
        if (!blank(s, "trustStore")) {
            trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(load("Trust store", s.get("trustStore"), s.getOrDefault("trustStorePassword", "")));
        }
        KeyManagerFactory keys = null;
        if (!blank(s, "keyStore")) {
            String password = s.getOrDefault("keyStorePassword", "");
            keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(load("Key store", s.get("keyStore"), password), password.toCharArray());
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys == null ? null : keys.getKeyManagers(), trust == null ? null : trust.getTrustManagers(), null);
        return context.getSocketFactory();
    }

    private static KeyStore load(String what, String path, String password) {
        File file = new File(path);
        if (!file.isFile()) throw new IllegalArgumentException(what + " file not found: " + path);
        try {
            return KeyStore.getInstance(file, password.toCharArray());   // detects JKS or PKCS12
        } catch (Exception e) {
            throw new IllegalArgumentException(what + " could not be opened (wrong password or format): " + path);
        }
    }

    private static boolean blank(Map<String, String> s, String key) {
        return s.getOrDefault(key, "").isBlank();
    }
}
