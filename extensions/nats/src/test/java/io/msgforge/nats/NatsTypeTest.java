package io.msgforge.nats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.msgforge.TransportType;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Dispatcher;
import io.nats.client.JetStream;
import io.nats.client.Message;
import io.nats.client.MessageHandler;
import io.nats.client.Options;
import io.nats.client.impl.Headers;

/** The client's Connection, Dispatcher and JetStream are interfaces; Mockito stands in for the server. */
class NatsTypeTest {

    @Test
    void isDiscoverableAsAPluginAndCanReceive() {
        assertTrue(ServiceLoader.load(TransportType.class).stream().anyMatch(p -> p.type() == NatsType.class));
        assertTrue(new NatsType().canReceive());
        assertTrue(new NatsType().fields().stream().filter(f -> f.key().matches("(?i).*(password|token).*")).allMatch(TransportType.Field::secret));
    }

    @Test
    void serversAreCheckedAndTlsChecksTheHostName() throws Exception {
        NatsType.Problems none = new NatsType.Problems(e -> { });
        Options plain = NatsType.options(Map.of("servers", "nats://a:4222, nats://b:4222"), none);
        assertEquals(2, plain.getServers().size());
        assertEquals(0, plain.getMaxReconnect());
        Options tls = NatsType.options(Map.of("servers", "tls://a:4222"), none);
        assertTrue(tls.isTLSRequired());
        assertTrue(tls.isTlsVerifyHostname());
        assertThrows(IllegalArgumentException.class, () -> NatsType.options(Map.of("servers", "nats://u:S3CRET@a:4222"), none));
        assertThrows(IllegalArgumentException.class, () -> NatsType.options(Map.of("servers", "http://a"), none));
        assertThrows(IllegalArgumentException.class, () -> NatsType.options(Map.of("servers", "nats://a:4222", "trustStore", "/ca.pem"), none));
        assertThrows(IllegalArgumentException.class, () -> NatsType.options(Map.of("servers", "nats://a:4222", "credentials", "/no/such.creds"), none));
    }

    @Test
    void aCorePublishCarriesHeadersAndWaitsForARoundTripThatSurfacesARefusal() throws Exception {
        Connection nc = mock(Connection.class);
        NatsType.Problems problems = new NatsType.Problems(e -> { });
        TransportType.Sender sender = NatsType.sender(nc, null, problems);
        sender.send("orders.eu", "body", Map.of("trace", "t-1"));
        ArgumentCaptor<Headers> headers = ArgumentCaptor.forClass(Headers.class);
        verify(nc).publish(eq("orders.eu"), headers.capture(), eq("body".getBytes(StandardCharsets.UTF_8)));
        assertEquals("t-1", headers.getValue().getFirst("trace"));
        verify(nc).flush(Duration.ofSeconds(10));

        sender.send("orders.eu", "plain", Map.of());
        verify(nc).publish(eq("orders.eu"), isNull(Headers.class), any(byte[].class));

        // the server answers a publish it does not allow with -ERR, which the client has recorded when the flush returns
        when(nc.getLastError()).thenReturn("Permissions Violation for Publish to \"secret.x\"").thenReturn(null);
        assertTrue(assertThrows(java.io.IOException.class, () -> sender.send("secret.x", "x", Map.of())).getMessage().contains("Permissions Violation"));
        sender.send("orders.eu", "next", Map.of());
        verify(nc, org.mockito.Mockito.times(4)).clearLastError();   // before every publish, so a refusal does not stick to the next message

        sender.check();
        sender.close();
        verify(nc).close();
    }

    @Test
    void jetStreamPublishesWaitForTheAcknowledgementInstead() throws Exception {
        Connection nc = mock(Connection.class);
        JetStream js = mock(JetStream.class);
        TransportType.Sender sender = NatsType.sender(nc, js, new NatsType.Problems(e -> { }));
        sender.send("orders.eu", "body", Map.of());
        verify(js).publish(eq("orders.eu"), isNull(Headers.class), eq("body".getBytes(StandardCharsets.UTF_8)));
        verify(nc, never()).publish(any(String.class), nullable(Headers.class), any(byte[].class));

        when(js.publish(eq("no.stream"), nullable(Headers.class), any(byte[].class))).thenThrow(new java.io.IOException("503 No Responders Available For Request"));
        assertThrows(java.io.IOException.class, () -> sender.send("no.stream", "x", Map.of()));
    }

    @Test
    void receivingSubscribesAndALostConnectionOrARefusalIsReported() throws Exception {
        Connection nc = mock(Connection.class);
        Dispatcher dispatcher = mock(Dispatcher.class);
        ArgumentCaptor<MessageHandler> handler = ArgumentCaptor.forClass(MessageHandler.class);
        when(nc.createDispatcher(handler.capture())).thenReturn(dispatcher);
        List<String> got = new CopyOnWriteArrayList<>();
        List<Exception> errors = new CopyOnWriteArrayList<>();
        NatsType.Problems problems = new NatsType.Problems(errors::add);

        TransportType.Subscription subscription = NatsType.subscription(nc, "orders.>", got::add, problems);
        verify(dispatcher).subscribe("orders.>");
        verify(nc).flush(Duration.ofSeconds(10));
        Message m = mock(Message.class);
        when(m.getData()).thenReturn("hello".getBytes(StandardCharsets.UTF_8));
        handler.getValue().onMessage(m);
        assertEquals(List.of("hello"), got);

        problems.errorOccurred(nc, "Permissions Violation for Subscription to \"orders.>\"");
        problems.connectionEvent(nc, ConnectionListener.Events.DISCONNECTED, 0L, null);
        assertEquals(2, errors.size());
        subscription.check();

        subscription.close();
        problems.connectionEvent(nc, ConnectionListener.Events.CLOSED, 0L, null);   // our own close
        assertEquals(2, errors.size(), "closing is not an error");
        verify(nc).close();
    }
}
