package io.msgforge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * An in-memory loopback: a message sent to a destination is handed to everyone subscribed to that destination name,
 * on the sender's thread. With no subscriber it goes nowhere. So sending and verifying both work with no broker at all.
 * Headers arrive as they were sent, and a JMSCorrelationID or correlation_id header is the arrival's correlation id,
 * so a stand-in for a system under test can answer the way a real one does.
 */
@Component
@Order(0)
public class DryRunType implements TransportType {

    private final Map<String, List<Consumer<Arrival>>> subscribers = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "dry-run";
    }

    @Override
    public String label() {
        return "Dry run";
    }

    @Override
    public List<Field> fields() {
        return List.of();
    }

    @Override
    public Sender open(Map<String, String> settings) {
        return new Sender() {
            @Override
            public void send(String destination, String body, Map<String, String> headers) {
                deliver(destination, body, headers, null);
            }

            /** An id only when asked for one, so a plain send stays as cheap as it was. The arrival carries it as JMSMessageID, as a JMS message would. */
            @Override
            public String sendForId(String destination, String body, Map<String, String> headers) {
                String id = "ID:" + UUID.randomUUID();
                deliver(destination, body, headers, id);
                return id;
            }
        };
    }

    private void deliver(String destination, String body, Map<String, String> headers, String id) {
        List<Consumer<Arrival>> listening = subscribers.get(destination);
        if (listening == null || listening.isEmpty()) return;
        String correlation = headers.containsKey("JMSCorrelationID") ? headers.get("JMSCorrelationID") : headers.get("correlation_id");
        Map<String, String> arriving = headers;
        if (id != null) {
            arriving = new LinkedHashMap<>(headers);
            arriving.put("JMSMessageID", id);
        }
        Arrival arrival = new Arrival(body, arriving, correlation);
        listening.forEach(s -> s.accept(arrival));
    }

    @Override
    public boolean canReceive() {
        return true;
    }

    @Override
    public Subscription receive(Map<String, String> settings, String destination, Consumer<Arrival> onArrival, Consumer<Exception> onError) {
        List<Consumer<Arrival>> list = subscribers.computeIfAbsent(destination, d -> new CopyOnWriteArrayList<>());
        list.add(onArrival);
        return () -> list.remove(onArrival);
    }

    /** Declared here as well, so code that holds a DryRunType and wants bodies only need not catch what this type never throws. */
    @Override
    public Subscription subscribe(Map<String, String> settings, String destination, Consumer<String> onMessage, Consumer<Exception> onError) {
        return receive(settings, destination, arrival -> onMessage.accept(arrival.body()), onError);
    }
}
