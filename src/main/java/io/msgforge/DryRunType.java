package io.msgforge;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * An in-memory loopback: a message sent to a destination is handed to everyone subscribed to that destination name,
 * on the sender's thread. With no subscriber it goes nowhere. So sending and verifying both work with no broker at all.
 */
@Component
@Order(0)
public class DryRunType implements TransportType {

    private final Map<String, List<Consumer<String>>> subscribers = new ConcurrentHashMap<>();

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
        return (destination, body, headers) -> subscribers.getOrDefault(destination, List.of()).forEach(s -> s.accept(body));
    }

    @Override
    public boolean canReceive() {
        return true;
    }

    @Override
    public Subscription subscribe(Map<String, String> settings, String destination, Consumer<String> onMessage, Consumer<Exception> onError) {
        List<Consumer<String>> list = subscribers.computeIfAbsent(destination, d -> new CopyOnWriteArrayList<>());
        list.add(onMessage);
        return () -> list.remove(onMessage);
    }
}
