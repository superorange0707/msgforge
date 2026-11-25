package io.msgforge.rabbitmq;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.Return;
import com.rabbitmq.client.ReturnCallback;
import com.rabbitmq.client.ShutdownSignalException;

import io.msgforge.TransportType;

class RabbitMqTypeTest {

    @Test
    void isDiscoverableAsAPluginAndCanReceive() {
        assertTrue(ServiceLoader.load(TransportType.class).stream().anyMatch(p -> p.type() == RabbitMqType.class));
        assertTrue(new RabbitMqType().canReceive());
        assertTrue(new RabbitMqType().fields().stream().filter(f -> f.key().toLowerCase().contains("password")).allMatch(TransportType.Field::secret));
    }

    @Test
    void theUriGivesHostPortAndVirtualHostAndRefusesALoginOrCertificateBypass() throws Exception {
        ConnectionFactory plain = RabbitMqType.factory(Map.of("uri", "amqp://broker.example", "user", "app", "password", "pw"));
        assertEquals("broker.example", plain.getHost());
        assertEquals(5672, plain.getPort());
        assertEquals("/", plain.getVirtualHost());
        assertEquals("app", plain.getUsername());
        assertTrue(!plain.isSSL());
        assertEquals("orders/eu", RabbitMqType.factory(Map.of("uri", "amqp://h:5000/orders%2Feu")).getVirtualHost());

        ConnectionFactory tls = RabbitMqType.factory(Map.of("uri", "amqps://broker.example"));
        assertEquals(5671, tls.getPort());
        assertTrue(tls.isSSL());

        assertTrue(assertThrows(IllegalArgumentException.class, () -> RabbitMqType.factory(Map.of("uri", "amqp://app:S3CRET@h"))).getMessage().contains("own fields"));
        assertThrows(IllegalArgumentException.class, () -> RabbitMqType.factory(Map.of("uri", "http://h")));
        assertThrows(IllegalArgumentException.class, () -> RabbitMqType.factory(Map.of("uri", "amqp://h", "trustStore", "/ca.pem")));
    }

    @Test
    void aDestinationIsAQueueOrAnExchangeWithARoutingKey() {
        assertArrayEquals(new String[] {"", "orders"}, RabbitMqType.address("orders"));
        assertArrayEquals(new String[] {"events", "order.created"}, RabbitMqType.address("events/order.created"));
        assertArrayEquals(new String[] {"fanout", ""}, RabbitMqType.address("fanout/"));
        assertArrayEquals(new String[] {"", "a/b"}, RabbitMqType.address("/a/b"));
    }

    @Test
    void headersBecomePropertiesOrTheHeadersTable() {
        AMQP.BasicProperties p = RabbitMqType.properties(Map.of("correlation_id", "c-1", "content_type", "application/json", "priority", "5", "region", "EU"));
        assertEquals("c-1", p.getCorrelationId());
        assertEquals("application/json", p.getContentType());
        assertEquals(5, p.getPriority());
        assertEquals(2, p.getDeliveryMode());
        assertEquals(Map.of("region", "EU"), p.getHeaders());
        assertNull(RabbitMqType.properties(Map.of()).getHeaders());
        assertThrows(IllegalArgumentException.class, () -> RabbitMqType.properties(Map.of("priority", "high")));
    }

    @Test
    void sendingWaitsForTheConfirmAndAMessageNoQueueTookIsAnError() throws Exception {
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        when(connection.createChannel()).thenReturn(channel);
        ArgumentCaptor<ReturnCallback> returns = ArgumentCaptor.forClass(ReturnCallback.class);
        when(channel.waitForConfirms(anyLong())).thenReturn(true);

        TransportType.Sender sender = RabbitMqType.sender(connection);
        verify(channel).confirmSelect();
        verify(channel).addReturnListener(returns.capture());

        sender.send("orders", "body", Map.of("message_id", "m-1"));
        ArgumentCaptor<AMQP.BasicProperties> props = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
        verify(channel).basicPublish(eq(""), eq("orders"), eq(true), props.capture(), eq("body".getBytes(StandardCharsets.UTF_8)));
        assertEquals("m-1", props.getValue().getMessageId());

        // the broker hands the message back before confirming it
        doAnswer(call -> {
            returns.getValue().handle(new Return(312, "NO_ROUTE", "events", "nowhere", null, new byte[0]));
            return null;
        }).when(channel).basicPublish(eq("events"), eq("nowhere"), anyBoolean(), any(), any());
        String error = assertThrows(java.io.IOException.class, () -> sender.send("events/nowhere", "x", Map.of())).getMessage();
        assertTrue(error.contains("NO_ROUTE") && error.contains("exchange events"), error);
        sender.send("orders", "next", Map.of());   // one return does not stick to the next message

        when(channel.waitForConfirms(anyLong())).thenReturn(false);
        assertTrue(assertThrows(java.io.IOException.class, () -> sender.send("orders", "x", Map.of())).getMessage().contains("nack"));

        sender.close();
        verify(connection).close(5_000);
    }

    @Test
    void receivingFromAQueueConsumesItAndABrokenConsumerIsReported() throws Exception {
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        when(connection.createChannel()).thenReturn(channel);
        AtomicReference<DefaultConsumer> consumer = new AtomicReference<>();
        when(channel.basicConsume(eq("out"), eq(true), any(com.rabbitmq.client.Consumer.class))).thenAnswer(call -> {
            consumer.set(call.getArgument(2));
            return "tag";
        });
        List<String> got = new CopyOnWriteArrayList<>();
        List<Exception> errors = new CopyOnWriteArrayList<>();

        TransportType.Subscription subscription = RabbitMqType.subscription(connection, "out", got::add, errors::add);
        verify(channel).queueDeclarePassive("out");
        verify(channel, never()).queueBind(anyString(), anyString(), anyString());
        consumer.get().handleDelivery("tag", new Envelope(1, false, "", "out"), null, "hello".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("hello"), got);

        subscription.check();
        verify(channel, org.mockito.Mockito.times(2)).queueDeclarePassive("out");

        consumer.get().handleShutdownSignal("tag", new ShutdownSignalException(false, true, null, connection));   // our own close
        assertTrue(errors.isEmpty());
        consumer.get().handleShutdownSignal("tag", new ShutdownSignalException(true, false, null, connection, "connection lost", null));
        consumer.get().handleCancel("tag");
        assertEquals(2, errors.size());

        subscription.close();
        verify(connection).close(5_000);
    }

    @Test
    void receivingFromAnExchangeBindsAQueueOfItsOwn() throws Exception {
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        when(connection.createChannel()).thenReturn(channel);
        when(channel.queueDeclare()).thenReturn(new AMQP.Queue.DeclareOk.Builder().queue("amq.gen-1").build());

        RabbitMqType.subscription(connection, "events/order.*", body -> { }, e -> { }).close();
        verify(channel).queueBind("amq.gen-1", "events", "order.*");
        verify(channel).basicConsume(eq("amq.gen-1"), eq(true), any(com.rabbitmq.client.Consumer.class));

        // a queue that is not there is an error now, and the connection is not left open
        Connection other = mock(Connection.class);
        Channel failing = mock(Channel.class);
        when(other.createChannel()).thenReturn(failing);
        when(failing.queueDeclarePassive("missing")).thenThrow(new java.io.IOException("NOT_FOUND - no queue 'missing'"));
        assertThrows(java.io.IOException.class, () -> RabbitMqType.subscription(other, "missing", body -> { }, e -> { }));
        verify(other).close(5_000);
    }
}
