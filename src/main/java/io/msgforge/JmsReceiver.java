package io.msgforge;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

/**
 * Receives from one queue over its own JMS connection until closed. It consumes: each message is taken off the queue.
 * A connection that breaks, or a message that cannot be read, is reported to onError.
 */
public final class JmsReceiver implements TransportType.Subscription {

    private final Connection connection;

    /** Bodies only, as plugins written before arrivals had headers use it. */
    public JmsReceiver(ConnectionFactory factory, String queue, Consumer<String> onMessage, Consumer<Exception> onError) throws JMSException {
        this(factory, queue, arrival -> onMessage.accept(arrival.body()), onError, true);
    }

    /** Each message with its string-readable properties, JMSType and JMSMessageID as headers, and its JMSCorrelationID. */
    public static JmsReceiver arrivals(ConnectionFactory factory, String queue, Consumer<TransportType.Arrival> onArrival,
                                       Consumer<Exception> onError) throws JMSException {
        return new JmsReceiver(factory, queue, onArrival, onError, true);
    }

    private JmsReceiver(ConnectionFactory factory, String queue, Consumer<TransportType.Arrival> onArrival, Consumer<Exception> onError, boolean arrivals) throws JMSException {
        connection = factory.createConnection();
        try {
            connection.setExceptionListener(onError::accept);
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            session.createConsumer(session.createQueue(queue)).setMessageListener(message -> {
                try {
                    // Applications that are not JMS (most things behind IBM MQ) often forward bytes, not text.
                    String body = message instanceof TextMessage text ? text.getText()
                            : message instanceof BytesMessage bytes ? new String(bytes.getBody(byte[].class), StandardCharsets.UTF_8) : null;
                    // A message with no body, or of a kind with no text in it, is still an arrival: a system that answers a bad request with nothing, say.
                    onArrival.accept(new TransportType.Arrival(body, headers(message), message.getJMSCorrelationID()));
                } catch (JMSException unreadable) {
                    onError.accept(unreadable);
                }
            });
            connection.start();
        } catch (JMSException e) {
            close();
            throw e;
        }
    }

    private static Map<String, String> headers(Message message) throws JMSException {
        Map<String, String> headers = new LinkedHashMap<>();
        for (java.util.Enumeration<?> names = message.getPropertyNames(); names.hasMoreElements();) {
            String name = String.valueOf(names.nextElement());
            Object value = message.getObjectProperty(name);
            if (value != null) headers.put(name, String.valueOf(value));
        }
        if (message.getJMSCorrelationID() != null) headers.put("JMSCorrelationID", message.getJMSCorrelationID());
        if (message.getJMSType() != null) headers.put("JMSType", message.getJMSType());
        if (message.getJMSMessageID() != null) headers.put("JMSMessageID", message.getJMSMessageID());
        return headers;
    }

    /** Opening a session is a round trip to the broker. The exception listener reports a broken connection too, but only once the client notices, which can be later than the run's timeout. */
    @Override
    public void check() throws JMSException {
        connection.createSession(false, Session.AUTO_ACKNOWLEDGE).close();
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (JMSException ignored) {
            // nothing left to release
        }
    }
}
