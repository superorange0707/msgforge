package io.msgforge.pulsar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Reader;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.TypedMessageBuilder;
import org.junit.jupiter.api.Test;

import io.msgforge.TransportType;

/** The client's PulsarClient, Producer and Reader are interfaces; Mockito stands in for the broker. */
class PulsarTypeTest {

    @Test
    void isDiscoverableAsAPluginAndCanReceive() {
        assertTrue(ServiceLoader.load(TransportType.class).stream().anyMatch(p -> p.type() == PulsarType.class));
        assertTrue(new PulsarType().canReceive());
        assertTrue(new PulsarType().fields().stream().filter(f -> f.key().matches("(?i).*(password|token).*")).allMatch(TransportType.Field::secret));
    }

    @Test
    void theServiceUrlIsChecked() {
        assertThrows(IllegalArgumentException.class, () -> PulsarType.client(Map.of("serviceUrl", "http://u:S3CRET@h:8080")));
        assertThrows(IllegalArgumentException.class, () -> PulsarType.client(Map.of("serviceUrl", "kafka://h")));
        assertThrows(IllegalArgumentException.class, () -> PulsarType.client(Map.of("serviceUrl", "pulsar://h:6650", "trustStore", "/ca.pem")));
        assertThrows(IllegalArgumentException.class, () -> PulsarType.client(Map.of("serviceUrl", "pulsar+ssl://h:6651", "trustStore", "/no/such/ca.pem")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theKeyHeaderBecomesTheMessageKeyAndTheRestProperties() throws Exception {
        PulsarClient client = mock(PulsarClient.class);
        ProducerBuilder<String> builder = mock(ProducerBuilder.class, RETURNS_SELF);
        Producer<String> producer = mock(Producer.class);
        TypedMessageBuilder<String> message = mock(TypedMessageBuilder.class, RETURNS_SELF);
        when(client.newProducer(Schema.STRING)).thenReturn(builder);
        when(builder.create()).thenReturn(producer);
        when(producer.newMessage()).thenReturn(message);
        when(producer.closeAsync()).thenReturn(CompletableFuture.completedFuture(null));

        TransportType.Sender sender = PulsarType.sender(client);
        sender.send("orders", "body", Map.of("key", "K-1", "region", "EU"));
        sender.send("orders", "again", Map.of());
        verify(builder).topic("orders");
        verify(builder).create();   // one producer per topic
        verify(message).value("body");
        verify(message).key("K-1");
        verify(message).property("region", "EU");
        verify(message, never()).property(org.mockito.ArgumentMatchers.eq("key"), anyString());

        when(client.getPartitionsForTopic(anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(List.of()))
                .thenReturn(CompletableFuture.failedFuture(new PulsarClientException.TopicDoesNotExistException("no such topic")))
                .thenReturn(CompletableFuture.failedFuture(new PulsarClientException.AuthorizationException("not authorized")))
                .thenReturn(CompletableFuture.failedFuture(new PulsarClientException.AuthenticationException("bad token")));
        sender.check();
        sender.check();   // the usual answer: the check's topic is not there, and is not created
        sender.check();   // a broker that knows the client but keeps it out of one topic has accepted its credentials
        assertThrows(PulsarClientException.AuthenticationException.class, sender::check);

        sender.close();
        verify(producer).closeAsync();
        verify(client).close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSendReturnsTheMessageIdAndAnArrivalCarriesItsPropertiesAndKey() throws Exception {
        PulsarClient client = mock(PulsarClient.class);
        ProducerBuilder<String> builder = mock(ProducerBuilder.class, RETURNS_SELF);
        Producer<String> producer = mock(Producer.class);
        TypedMessageBuilder<String> message = mock(TypedMessageBuilder.class, RETURNS_SELF);
        when(client.newProducer(Schema.STRING)).thenReturn(builder);
        when(builder.create()).thenReturn(producer);
        when(producer.newMessage()).thenReturn(message);
        org.apache.pulsar.client.api.MessageId stored = mock(org.apache.pulsar.client.api.MessageId.class);
        when(stored.toString()).thenReturn("12:7:-1");
        when(message.send()).thenReturn(stored).thenReturn(null);
        TransportType.Sender sender = PulsarType.sender(client);
        assertEquals("12:7:-1", sender.sendForId("shipments", "shipped", Map.of()), "the id the broker stored it under");
        assertNull(sender.sendForId("shipments", "again", Map.of()), "and no id is made up when the client gives none");
        sender.send("shipments", "plain", Map.of());
        verify(message, org.mockito.Mockito.times(3)).send();   // a plain send sends once

        Reader<String> reader = mock(Reader.class);
        Message<String> keyed = mock(Message.class), bare = mock(Message.class);
        when(keyed.getValue()).thenReturn("shipped");
        when(keyed.hasKey()).thenReturn(true);
        when(keyed.getKey()).thenReturn("K-1");
        when(keyed.getProperties()).thenReturn(Map.of("region", "EU"));
        when(bare.getValue()).thenReturn("bare");
        when(reader.readNext(anyInt(), any(TimeUnit.class))).thenReturn(keyed).thenReturn(bare).thenReturn(null);
        BlockingQueue<TransportType.Arrival> got = new LinkedBlockingQueue<>();
        TransportType.Subscription subscription = PulsarType.subscription(mock(PulsarClient.class), reader, got::add, e -> { });

        TransportType.Arrival first = got.poll(5, TimeUnit.SECONDS), second = got.poll(5, TimeUnit.SECONDS);
        subscription.close();
        assertEquals("shipped", first.body());
        assertEquals(Map.of("key", "K-1", "region", "EU"), first.headers(), "what the sender takes as headers");
        assertNull(first.correlationId(), "Pulsar has no correlation field");
        assertEquals(Map.of(), second.headers());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theReaderHandsOnEachValueAndAFailureIsReported() throws Exception {
        PulsarClient client = mock(PulsarClient.class);
        Reader<String> reader = mock(Reader.class);
        Message<String> m = mock(Message.class);
        when(m.getValue()).thenReturn("hello");
        when(reader.readNext(anyInt(), any(TimeUnit.class))).thenReturn(m).thenReturn(null);
        when(reader.getLastMessageIdsAsync()).thenReturn(CompletableFuture.completedFuture(List.of()));
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        BlockingQueue<Exception> errors = new LinkedBlockingQueue<>();

        TransportType.Subscription subscription = PulsarType.subscription(client, reader, a -> got.add(a.body()), errors::add);
        assertEquals("hello", got.poll(5, TimeUnit.SECONDS));
        subscription.check();
        subscription.close();
        verify(reader).close();
        verify(client).close();
        assertTrue(errors.isEmpty(), "closing is not an error");

        Reader<String> broken = mock(Reader.class);
        when(broken.readNext(anyInt(), any(TimeUnit.class))).thenThrow(new PulsarClientException.AlreadyClosedException("Consumer already closed"));
        TransportType.Subscription failing = PulsarType.subscription(mock(PulsarClient.class), broken, a -> got.add(a.body()), errors::add);
        assertEquals("Consumer already closed", errors.poll(5, TimeUnit.SECONDS).getMessage());
        assertThrows(PulsarClientException.class, failing::check);
        failing.close();
    }
}
