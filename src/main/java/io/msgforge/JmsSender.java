package io.msgforge;

import java.util.Map;

import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.core.JmsTemplate;

import jakarta.jms.ConnectionFactory;

/** Sends text messages through any JMS ConnectionFactory, keeping one connection open between sends. */
public final class JmsSender implements TransportType.Sender {

    private final CachingConnectionFactory cached;
    private final JmsTemplate jms;

    public JmsSender(ConnectionFactory target) {
        this.cached = new CachingConnectionFactory(target);
        this.jms = new JmsTemplate(cached);
    }

    /** Headers become string properties, except JMSCorrelationID and JMSType, which set the JMS header of that name. */
    @Override
    public void send(String destination, String body, Map<String, String> headers) {
        jms.convertAndSend(destination, body, message -> {
            for (Map.Entry<String, String> h : headers.entrySet()) {
                switch (h.getKey()) {
                    case "JMSCorrelationID" -> message.setJMSCorrelationID(h.getValue());
                    case "JMSType" -> message.setJMSType(h.getValue());
                    default -> message.setStringProperty(h.getKey(), h.getValue());
                }
            }
            return message;
        });
    }

    @Override
    public void check() throws Exception {
        cached.createConnection().close();
    }

    @Override
    public void close() {
        cached.destroy();
    }
}
