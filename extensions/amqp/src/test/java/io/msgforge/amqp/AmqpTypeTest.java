package io.msgforge.amqp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.spi.core.security.ActiveMQJAASSecurityManager;
import org.apache.activemq.artemis.spi.core.security.jaas.InVMLoginModule;
import org.apache.activemq.artemis.core.security.Role;
import org.apache.activemq.artemis.core.config.impl.SecurityConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.msgforge.TransportType;
import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;

/** Against an embedded ActiveMQ Artemis broker with its AMQP protocol module and one user, app / app-pw. */
class AmqpTypeTest {

    private final AmqpType type = new AmqpType();
    private EmbeddedActiveMQ broker;
    private String uri;

    @BeforeEach
    void start() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        uri = "amqp://127.0.0.1:" + port;
        SecurityConfiguration users = new SecurityConfiguration();
        users.addUser("app", "app-pw");
        users.addRole("app", "app");
        ConfigurationImpl config = new ConfigurationImpl();
        config.setPersistenceEnabled(false);
        config.setSecurityEnabled(true);
        config.putSecurityRoles("#", java.util.Set.of(new Role("app", true, true, true, true, true, true, true, true, true, true, false, false)));
        config.addAcceptorConfiguration("amqp", "tcp://127.0.0.1:" + port + "?protocols=AMQP");
        broker = new EmbeddedActiveMQ().setConfiguration(config)
                .setSecurityManager(new ActiveMQJAASSecurityManager(InVMLoginModule.class.getName(), users));
        broker.start();
    }

    @AfterEach
    void stop() throws Exception {
        if (broker != null) broker.stop();
    }

    private Map<String, String> settings() {
        return Map.of("uri", uri, "user", "app", "password", "app-pw");
    }

    @Test
    void isDiscoverableAsAPluginAndCanReceive() {
        assertTrue(ServiceLoader.load(TransportType.class).stream().anyMatch(p -> p.type() == AmqpType.class));
        assertTrue(type.canReceive());
        assertTrue(type.fields().stream().filter(f -> f.key().toLowerCase().contains("password")).allMatch(TransportType.Field::secret));
    }

    @Test
    void theUriMayNotCarryALoginOrTurnCertificateChecksOff() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AmqpType.factory(Map.of("uri", "amqp://u:S3CRET@h"))).getMessage().contains("own fields"));
        assertThrows(IllegalArgumentException.class, () -> AmqpType.factory(Map.of("uri", "amqp://h?jms.password=S3CRET")));
        assertThrows(IllegalArgumentException.class, () -> AmqpType.factory(Map.of("uri", "amqps://h?transport.trustAll=true")));
        assertThrows(IllegalArgumentException.class, () -> AmqpType.factory(Map.of("uri", "failover:(amqps://a,amqps://b)?failover.nested.transport.verifyHost=false")));
        assertThrows(IllegalArgumentException.class, () -> AmqpType.factory(Map.of("uri", "amqp://h", "trustStore", "/ca.pem")));
        assertThrows(IllegalArgumentException.class, () -> AmqpType.factory(Map.of("uri", "http://h")));
    }

    @Test
    void sendsWithPropertiesAndReceivesByConsuming() throws Exception {
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        BlockingQueue<Exception> errors = new LinkedBlockingQueue<>();
        try (TransportType.Sender out = type.open(settings())) {
            out.check();
            out.send("orders", "first", Map.of("JMSCorrelationID", "c-1", "region", "EU"));
            // read back with a plain JMS consumer to see what the message carries
            Connection c = AmqpType.factory(settings()).createConnection();
            c.start();
            MessageConsumer raw = c.createSession(false, Session.AUTO_ACKNOWLEDGE).createConsumer(c.createSession(false, Session.AUTO_ACKNOWLEDGE).createQueue("orders"));
            Message m = raw.receive(10_000);
            assertEquals("first", m.getBody(String.class));
            assertEquals("c-1", m.getJMSCorrelationID());
            assertEquals("EU", m.getStringProperty("region"));
            c.close();

            try (TransportType.Subscription in = type.subscribe(settings(), "routed", got::add, errors::add)) {
                out.send("routed", "second", Map.of());
                assertEquals("second", got.poll(10, TimeUnit.SECONDS));
                in.check();
            }
        }
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void aWrongPasswordFailsAtOpenWithoutTheNameOfThePassword() {
        String message = assertThrows(Exception.class, () -> type.open(Map.of("uri", uri, "user", "app", "password", "not-the-pw-1234"))).getMessage();
        assertFalse(message.contains("not-the-pw-1234"), message);
        assertThrows(Exception.class, () -> type.subscribe(Map.of("uri", uri, "user", "app", "password", "nope"), "q", b -> { }, e -> { }));
    }

    @Test
    void aBrokerThatGoesAwayFailsTheReceiver() throws Exception {
        BlockingQueue<Exception> errors = new LinkedBlockingQueue<>();
        TransportType.Subscription in = type.subscribe(settings(), "q", body -> { }, errors::add);
        broker.stop();
        broker = null;
        assertNotNull(errors.poll(15, TimeUnit.SECONDS), "the receiver reported the lost connection");
        assertThrows(Exception.class, in::check);
        in.close();
    }
}
