package io.msgforge;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

/**
 * Receives from one queue over its own JMS connection until closed. It consumes: each message is taken off the queue.
 * A connection that breaks, or a message that cannot be read, is reported to onError.
 */
public final class JmsReceiver implements TransportType.Subscription {

    private final Connection connection;

    public JmsReceiver(ConnectionFactory factory, String queue, Consumer<String> onMessage, Consumer<Exception> onError) throws JMSException {
        connection = factory.createConnection();
        try {
            connection.setExceptionListener(onError::accept);
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            session.createConsumer(session.createQueue(queue)).setMessageListener(message -> {
                try {
                    // Applications that are not JMS (most things behind IBM MQ) often forward bytes, not text.
                    if (message instanceof TextMessage text) onMessage.accept(text.getText());
                    else if (message instanceof BytesMessage bytes) onMessage.accept(new String(bytes.getBody(byte[].class), StandardCharsets.UTF_8));
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
