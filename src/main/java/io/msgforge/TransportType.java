package io.msgforge;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A kind of connection (IBM MQ, Kafka, HTTP…). To add one, implement this as a Spring bean:
 * the console builds the connection form from {@link #fields()}, so no UI change is needed.
 */
public interface TransportType {

    String id();

    String label();

    List<Field> fields();

    /** One line shown under the connection, e.g. the broker address. */
    default String summary(Map<String, String> settings) {
        return "";
    }

    /** Upgrades settings saved by an older version. Called when the saved connections are loaded. */
    default Map<String, String> migrate(Map<String, String> settings) {
        return settings;
    }

    /**
     * Called for each run and each connection test; the caller closes the sender when it is done.
     * Errors may say what went wrong but should not quote settings: msgforge replaces the values it knows of
     * in every error message, and cannot recognise one that was encoded or cut up first.
     */
    Sender open(Map<String, String> settings) throws Exception;

    /** Whether connections of this type can receive, so the console only offers those for expectations. */
    default boolean canReceive() {
        return false;
    }

    /**
     * Starts receiving from a destination. Each message body is handed to onMessage until the subscription is closed.
     * If receiving breaks after this has returned, tell onError: the run then ends as failed with that error.
     * Without it a broken receiver looks exactly like a system under test that lost every message.
     */
    default Subscription subscribe(Map<String, String> settings, String destination, Consumer<String> onMessage,
                                   Consumer<Exception> onError) throws Exception {
        throw new UnsupportedOperationException(label() + " connections cannot receive");
    }

    /** secret fields are write-only: the API never returns their value. */
    record Field(String key, String label, String placeholder, boolean required, boolean secret) {}

    interface Sender extends AutoCloseable {

        /** headers are message properties in the transport's own sense (JMS properties, HTTP headers…); may be empty. */
        void send(String destination, String body, Map<String, String> headers) throws Exception;

        /** Proves the connection works without sending anything. */
        default void check() throws Exception {
        }

        @Override
        default void close() {
        }
    }

    interface Subscription extends AutoCloseable {

        /**
         * Asks the source whether it still answers, and fails if it does not. A run calls this once, when its
         * timeout has passed and messages are still awaited: silence from a healthy source means they are
         * missing, silence from a dead one means nobody can tell. It must return or fail within seconds.
         */
        default void check() throws Exception {
        }

        @Override
        void close();
    }
}
