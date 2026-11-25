package io.msgforge;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.core.JmsTemplate;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;

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
        sendForId(destination, body, headers);
    }

    /** The id is the JMSMessageID the provider set while sending: on IBM MQ the message's MsgId, which a reply carries as its correlation id. */
    @Override
    public String sendForId(String destination, String body, Map<String, String> headers) {
        AtomicReference<Message> sent = new AtomicReference<>();
        jms.convertAndSend(destination, body, message -> {
            sent.set(message);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                switch (h.getKey()) {
                    case "JMSCorrelationID" -> message.setJMSCorrelationID(h.getValue());
                    case "JMSType" -> message.setJMSType(h.getValue());
                    default -> message.setStringProperty(h.getKey(), h.getValue());
                }
            }
            return message;
        });
        try {
            return sent.get() == null ? null : sent.get().getJMSMessageID();
        } catch (JMSException e) {
            return null;   // the message went; only its id is unknown
        }
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
