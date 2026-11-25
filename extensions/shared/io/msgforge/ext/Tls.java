package io.msgforge.ext;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * The TLS settings every plugin in extensions/ offers, compiled into each plugin jar (a plugin cannot use another's classes).
 * There is no setting that turns certificate checks off: without a trust store the JVM's default one applies.
 */
public final class Tls {

    private Tls() {
    }

    /** The fields, in the order the console shows them, for a type to add to its own. */
    public static java.util.List<io.msgforge.TransportType.Field> fields() {
        return java.util.List.of(
                new io.msgforge.TransportType.Field("trustStore", "Trust store file", "/certs/truststore.p12 or ca.pem", false, false),
                new io.msgforge.TransportType.Field("trustStorePassword", "Trust store password", "${MSGFORGE_TRUSTSTORE_PASSWORD}", false, true),
                new io.msgforge.TransportType.Field("keyStore", "Key store file", "/certs/keystore.p12", false, false),
                new io.msgforge.TransportType.Field("keyStorePassword", "Key store password", "${MSGFORGE_KEYSTORE_PASSWORD}", false, true));
    }

    /** Whether a trust store or a key store is set. */
    public static boolean configured(Map<String, String> s) {
        return !blank(s, "trustStore") || !blank(s, "keyStore");
    }

    /**
     * A context from the trust store and key store settings; each one left out falls back to the JVM's default.
     * A trust store is a JKS or PKCS12 file, or PEM/DER certificates (a CA file, as brokers usually hand out).
     */
    // ponytail: a client certificate has to be in a key store (PKCS12 or JKS); a PEM key and certificate pair is not read.
    public static SSLContext context(Map<String, String> s) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers(s), trustManagers(s), null);
        return context;
    }

    /** From the trust store setting, or null when it is not set. */
    public static TrustManager[] trustManagers(Map<String, String> s) throws Exception {
        if (blank(s, "trustStore")) return null;
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trustStore(s.get("trustStore").trim(), s.getOrDefault("trustStorePassword", "")));
        return trust.getTrustManagers();
    }

    /** From the key store setting, or null when it is not set. */
    public static KeyManager[] keyManagers(Map<String, String> s) throws Exception {
        if (blank(s, "keyStore")) return null;
        String password = s.getOrDefault("keyStorePassword", "");
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(keyStore("Key store", s.get("keyStore").trim(), password), password.toCharArray());
        return keys.getKeyManagers();
    }

    /** The default context when nothing is set, so a caller can always hand one to its client. */
    public static SSLContext contextOrDefault(Map<String, String> s) throws Exception {
        return configured(s) ? context(s) : SSLContext.getDefault();
    }

    static KeyStore trustStore(String path, String password) {
        File file = file("Trust store", path);
        try {
            return KeyStore.getInstance(file, password.toCharArray());   // detects JKS or PKCS12
        } catch (Exception notAKeyStore) {
            try (InputStream in = new FileInputStream(file)) {
                Collection<? extends Certificate> certificates = CertificateFactory.getInstance("X.509").generateCertificates(in);
                if (certificates.isEmpty()) throw new IllegalArgumentException("no certificate");
                KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
                store.load(null, null);
                int i = 0;
                for (Certificate c : certificates) store.setCertificateEntry("ca-" + i++, c);
                return store;
            } catch (Exception e) {
                throw new IllegalArgumentException("Trust store could not be opened (wrong password, or not a JKS, PKCS12 or PEM file): " + path);
            }
        }
    }

    static KeyStore keyStore(String what, String path, String password) {
        File file = file(what, path);
        try {
            return KeyStore.getInstance(file, password.toCharArray());
        } catch (Exception e) {
            throw new IllegalArgumentException(what + " could not be opened (wrong password or format): " + path);
        }
    }

    private static File file(String what, String path) {
        File file = new File(path);
        if (!file.isFile()) throw new IllegalArgumentException(what + " file not found: " + path);
        return file;
    }

    /**
     * Parses an address setting. A login written into it is refused: the password would then sit in a field that is
     * not marked secret, shown in the console and quoted in errors. So is a URI option that turns certificate checks off.
     */
    public static URI uri(String label, String value, String... schemes) {
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(label + " is not a valid URI");   // the value may come from ${ENV}: not quoted
        }
        if (uri.getScheme() == null || java.util.Arrays.stream(schemes).noneMatch(uri.getScheme().toLowerCase(Locale.ROOT)::equals)) {
            throw new IllegalArgumentException(label + " must start with " + String.join("://, ", schemes) + "://");
        }
        // the whole text, not the parsed parts: a failover:(…) URI is opaque and its options are not a query
        String text = value.toLowerCase(Locale.ROOT);
        if (uri.getRawUserInfo() != null || text.contains("password=")) {
            throw new IllegalArgumentException("Put the user and password in their own fields, not in " + label);
        }
        if (text.contains("trustall") || text.contains("verifyhost") || text.contains("insecure")) {
            throw new IllegalArgumentException(label + " may not change certificate checks; set Trust store file instead");
        }
        return uri;
    }

    public static boolean blank(Map<String, String> s, String key) {
        return s.getOrDefault(key, "").isBlank();
    }
}
