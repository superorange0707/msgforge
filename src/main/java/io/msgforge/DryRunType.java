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
 *
 * The Route setting is such a stand-in, for trying verification with nothing else running:
 *
 *   ORDERS.IN > ORDERS.OUT, invalid > ORDERS.REJECTED
 *
 * What is sent to ORDERS.IN is delivered on ORDERS.OUT when it is well-formed JSON or XML, and otherwise a small
 * reject message ({"reason":"empty"}, "not-json", "not-xml" or "not-a-format") is delivered on ORDERS.REJECTED, with
 * the original's headers and one more named reason. Several routes are separated by semicolons.
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
        return List.of(new Field("route", "Route", "ORDERS.IN > ORDERS.OUT, invalid > ORDERS.REJECTED", false, false));
    }

    @Override
    public String summary(Map<String, String> settings) {
        return settings.getOrDefault("route", "");
    }

    /** Where a route delivers what is sent to its source: well-formed messages to one place, the rest (when named) to another. */
    private record Route(String to, String invalidTo) {}

    // ponytail: "valid" here is well-formed JSON or XML and nothing more. A message with a field taken out is still
    // well-formed, so the stand-in lets it through, as a system without a schema would.
    static Map<String, Route> routes(String setting) {
        Map<String, Route> routes = new LinkedHashMap<>();
        for (String one : setting.split(";")) {
            if (one.isBlank()) continue;
            String[] parts = one.split(","), main = parts[0].split(">"), rest = parts.length > 1 ? parts[1].split(">") : null;
            if (parts.length > 2 || main.length != 2 || main[0].isBlank() || main[1].isBlank()
                    || rest != null && (rest.length != 2 || !rest[0].trim().equals("invalid") || rest[1].isBlank())) {
                throw new IllegalArgumentException("Write a route as FROM > TO, optionally followed by , invalid > REJECTS");
            }
            routes.put(main[0].trim(), new Route(main[1].trim(), rest == null ? null : rest[1].trim()));
        }
        return routes;
    }

    /** Why a body is not a message a system would take, or null when it is well-formed JSON or XML. */
    static String flaw(String body) {
        String text = body.strip();
        if (text.isEmpty()) return "empty";
        try {
            if (text.startsWith("{") || text.startsWith("[")) {
                try (var parser = JSON.createParser(text)) {
                    while (parser.nextToken() != null) {
                        // read to the end: a cut-off document fails on the way
                    }
                }
                return null;
            }
            if (text.startsWith("<")) {
                var factory = javax.xml.parsers.SAXParserFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.newSAXParser().parse(new org.xml.sax.InputSource(new java.io.StringReader(text)), new org.xml.sax.helpers.DefaultHandler());
                return null;
            }
        } catch (Exception e) {
            return text.startsWith("<") ? "not-xml" : "not-json";
        }
        return "not-a-format";
    }

    private static final com.fasterxml.jackson.core.JsonFactory JSON = new com.fasterxml.jackson.core.JsonFactory();

    @Override
    public Sender open(Map<String, String> settings) {
        Map<String, Route> routes = routes(settings.getOrDefault("route", ""));
        return new Sender() {
            @Override
            public void send(String destination, String body, Map<String, String> headers) {
                route(destination, body, headers, null);
            }

            /** An id only when asked for one, so a plain send stays as cheap as it was. The arrival carries it as JMSMessageID, as a JMS message would. */
            @Override
            public String sendForId(String destination, String body, Map<String, String> headers) {
                String id = "ID:" + UUID.randomUUID();
                route(destination, body, headers, id);
                return id;
            }

            private void route(String destination, String body, Map<String, String> headers, String id) {
                Route route = routes.get(destination);
                String flaw = route == null ? null : flaw(body);
                if (route == null) {
                    deliver(destination, body, headers, id);
                } else if (flaw == null) {
                    deliver(route.to(), body, headers, id);
                } else if (route.invalidTo() != null) {
                    Map<String, String> rejected = new LinkedHashMap<>(headers);
                    rejected.put("reason", flaw);
                    deliver(route.invalidTo(), "{\"reason\":\"" + flaw + "\"}", rejected, id);
                }   // with nowhere named for them, what is not well-formed is dropped
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
