package io.msgforge;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Sends each message as an HTTP request. The destination is the path added to the base URL. */
@Component
@Order(2)
public class HttpType implements TransportType {

    // One for every sender: each client owns a selector thread that only goes away with garbage collection, and a run opens a sender of its own.
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public String id() {
        return "http";
    }

    @Override
    public String label() {
        return "HTTP";
    }

    @Override
    public List<Field> fields() {
        return List.of(
                new Field("url", "Base URL", "https://api.example.com", true, false),
                new Field("method", "Method", "POST", false, false),
                new Field("contentType", "Content type", "application/json", false, false),
                new Field("authorization", "Authorization header", "Bearer ${MSGFORGE_API_TOKEN}", false, true));
    }

    @Override
    public String summary(Map<String, String> s) {
        return s.getOrDefault("method", "POST") + " " + s.getOrDefault("url", "");
    }

    @Override
    public Sender open(Map<String, String> s) {
        URI base;
        try {
            base = URI.create(s.getOrDefault("url", "").replaceAll("/+$", ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Base URL is not a valid URL");
        }
        if (base.getHost() == null || !("http".equals(base.getScheme()) || "https".equals(base.getScheme()))) {
            throw new IllegalArgumentException("Base URL must start with http:// or https://");
        }
        String method = s.getOrDefault("method", "POST").toUpperCase();
        String contentType = s.getOrDefault("contentType", "application/json");
        String authorization = s.getOrDefault("authorization", "");

        return new Sender() {
            @Override
            public void send(String destination, String body, Map<String, String> headers) throws Exception {
                String path = destination.startsWith("/") ? destination : "/" + destination;
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
                        .header("Content-Type", contentType).method(method, HttpRequest.BodyPublishers.ofString(body));
                if (!authorization.isBlank()) request.header("Authorization", authorization);
                headers.forEach(request::setHeader);
                HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 300) {
                    String text = response.body() == null ? "" : response.body().strip();
                    throw new IOException("HTTP " + response.statusCode() + (text.isEmpty() ? "" : ": " + text.substring(0, Math.min(200, text.length()))));
                }
            }

            /** Reachability only: a real request would deliver a message. */
            @Override
            public void check() throws Exception {
                int port = base.getPort() > 0 ? base.getPort() : "https".equals(base.getScheme()) ? 443 : 80;
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(base.getHost(), port), 5000);
                }
            }
        };
    }
}
