# Connection types from plugins

The release zip carries these in `plugins/`. Each is one jar with its client library inside, loaded on its own like any other plugin (see [Extending msgforge](../README.md#extending-msgforge)). Running from source, `./gradlew :extensions:<name>:installPlugin` puts one in `plugins/`.

What they share:

- **Addresses** are URIs. A user name or password written into one (`amqp://user:pw@host`) is refused: they go in the User and Password fields, which keep the password out of the console and out of error messages. So is a URI option that names a user or password, a trust store or key store, or turns certificate checks off, also when its name is percent-encoded.
- **TLS** is on when the URI says so (`amqps://`, `ssl://`, `tls://`, `https://`, `pulsar+ssl://`). The server certificate and its host name are always checked; there is no setting to turn that off. Without a trust store the JVM's default one is used. *Trust store file* is a JKS or PKCS12 store, or a PEM or DER file with the CA certificates. *Key store file* (JKS or PKCS12, not PEM) holds a client certificate, for servers that ask for one.
- **Passwords**, tokens and secret keys are fields marked secret: written once, never shown again, and replaced by `***` in errors. Any field can be `${MSGFORGE_…}`.
- **Test connection** logs in and makes one round trip to the server. It sends nothing.

## RabbitMQ

AMQP 0-9-1, through the RabbitMQ Java client.

| Setting | Notes |
|---|---|
| Broker URI | `amqp://host:5672/vhost`, or `amqps://host:5671/vhost` for TLS. No path means the virtual host `/`; a `/` inside a virtual host name is written `%2F`. |
| User, Password | the broker's login. Left empty, the client's default (`guest`) is used, which RabbitMQ accepts only from its own machine. |

The destination is a queue name, or `exchange/routing-key` to publish to an exchange (`events/order.created`; `logs/` for a fanout exchange with no key). A queue name that contains `/` is written with a leading `/` (`/a/b` is the queue `a/b`).

Messages are persistent and published with *mandatory* set, and each send waits for the broker's confirm. A message no queue takes is an error, not a silent drop: sending to a queue that does not exist fails the run at that message.

Headers named `content_type`, `content_encoding`, `correlation_id`, `message_id`, `reply_to`, `type`, `app_id`, `expiration` and `priority` (a whole number) set the AMQP property of that name. Every other header goes into the message's headers table as a string.

Receiving from a queue consumes it: every message is taken off the queue, including ones msgforge did not send. Receiving from `exchange/routing-key` declares a queue of its own (server-named, exclusive, deleted when the run ends) and binds it to the exchange with that key; it gets a copy of each message and takes nothing from anyone. To watch where a router delivers without disturbing its consumers, expect on the exchange and key it publishes with. A queue that is deleted, or a connection the broker closes, while a run verifies fails the run.

Verified against RabbitMQ 4.4.1 (the `rabbitmq:4` image): sending to queues and exchanges, receiving both ways, a refused login, a deleted queue during receiving, TLS with a PEM CA file and a client certificate, the host name check.

## MQTT

MQTT 5 or 3.1.1, through the Eclipse Paho clients.

| Setting | Notes |
|---|---|
| Server URI | `tcp://host:1883`, `ssl://host:8883` for TLS, or `ws://` / `wss://` for MQTT over WebSocket |
| MQTT version | `5` (the default) or `3.1.1` |
| Client ID | generated (`msgforge-` and 8 characters) when empty. A receiver connects separately with the same ID and `-r` added, so the two do not push each other off. With an ID of your own, do not run two runs on this connection at once: the second pair of clients would push the first off. |
| User, Password | the server's login |
| QoS | `0`, `1` (the default) or `2`, for sending and for subscribing |
| Retain | `true` to publish retained messages |

The destination is a topic; an expectation may use wildcards (`orders/+`, `orders/#`).

Each send waits for the server's acknowledgement at the chosen QoS (none at QoS 0). With MQTT 5 a publish the server refuses (a reason code of 0x80 or more, such as "not authorised") is an error. MQTT 3.1.1 has no way to say so: a server drops a message it does not allow, and the run reports it missing.

Headers become MQTT 5 user properties. MQTT 3.1.1 messages have no properties, so a run that sets headers on a 3.1.1 connection fails at the first message and says to switch to version 5.

Receiving subscribes with a clean session that ends with the connection. Every subscriber gets its own copy, so nothing is taken from other subscribers (a shared subscription, `$share/…`, is the exception: it is one of the group). A retained message on the topic is delivered when the subscription starts; it is not one msgforge is waiting for and does not count. A subscription the server refuses fails the run; but some servers, Mosquitto among them, accept a subscription their access rules deny and then deliver nothing, which shows as missing messages. A connection that is lost while a run verifies fails the run; the client does not reconnect.

Test connection logs in and makes one round trip (an UNSUBSCRIBE of `msgforge/check`, which every server answers and which changes nothing).

Verified against Eclipse Mosquitto 2.1.4 (the `eclipse-mosquitto:2` image) with both versions: sending and receiving with wildcards, a refused login, a refused MQTT 5 publish, TLS with a PEM CA file and a client certificate, the host name check. The unit tests use an embedded ActiveMQ Artemis 2.40 broker with both versions; they check that headers arrive as user properties and that a broker that stops fails a receiver. Retain is passed to the client as it is and not tested.

## AMQP 1.0

Any broker that speaks AMQP 1.0, through Apache Qpid JMS: ActiveMQ Artemis, ActiveMQ, Azure Service Bus, Solace, RabbitMQ 4 and others. Sending and receiving use the same JMS code as IBM MQ.

| Setting | Notes |
|---|---|
| Broker URI | `amqp://host:5672`, `amqps://host:5671` for TLS, `amqpws://` / `amqpwss://` over WebSocket, or a Qpid JMS `failover:(amqps://a:5671,amqps://b:5671)` list. Other Qpid JMS URI options may be added (`?amqp.idleTimeout=…`), except ones that carry a user or password, name a trust store or key store, or turn certificate checks off: those are set in their own fields. |
| User, Password | the SASL login. For Azure Service Bus: the shared access policy name and its key. |

The destination is the address of a queue, written as the broker names it: `orders` on ActiveMQ Artemis and ActiveMQ, the queue or topic name on Azure Service Bus (a subscription is received as `topic/Subscriptions/name`), `/queues/orders` on RabbitMQ 4. ActiveMQ Artemis sends to the address as an anycast (queue) address; topics as such (`session.createTopic`) are not offered.

Each send waits for the broker to accept the message, so an address the broker refuses fails the run at that message.

Headers become JMS string properties, which Qpid JMS sends as AMQP application properties. `JMSCorrelationID` sets the AMQP `correlation-id`, and `JMSType` the `subject`. A property name has to be a Java identifier (letters, digits, `_`, `$`); another name fails the send.

Receiving consumes, like IBM MQ: every message on the queue is taken off it, including ones msgforge did not send. Expect on queues that nothing else reads. A connection that breaks while a run verifies fails the run. With a `failover:` URI the client reconnects instead, and messages that arrive meanwhile can be missed.

Verified against RabbitMQ 4.4.1 (its built-in AMQP 1.0, the `rabbitmq:4` image): sending and receiving on `/queues/…` addresses, an unknown address refused at the send, a refused login, TLS with a PEM CA file and a client certificate, the host name check. The unit tests use an embedded ActiveMQ Artemis 2.40 broker with a login: properties and correlation id arrive, a wrong password is refused, a broker that stops fails a receiver. Not tried against ActiveMQ Classic, Azure Service Bus or Solace.

## Amazon SQS

Amazon SQS, or anything that answers its API (ElasticMQ, LocalStack), through the AWS SDK for Java 2: only its SQS module, with the JDK's own HTTP client.

| Setting | Notes |
|---|---|
| Region | for example `eu-west-1`. Required; ElasticMQ takes any name. |
| Endpoint | empty for AWS. `http://localhost:9324` for ElasticMQ, `http://localhost:4566` for LocalStack. A trust store or key store needs an `https://` endpoint. |
| Access key ID, Secret access key | required. Write them as `${MSGFORGE_AWS_ACCESS_KEY_ID}` and `${MSGFORGE_AWS_SECRET_ACCESS_KEY}` to keep them out of `msgforge.json`. |
| Session token | for temporary credentials |

Credentials come from the connection only. The SDK's usual sources (the `AWS_*` environment variables, `~/.aws`, an instance or task role) are not used, on purpose: anyone who can edit a connection could otherwise point it at an endpoint of their choosing and have it sign requests with this machine's identity. The SDK may still read `~/.aws/config` for settings that are not credentials, such as retry mode.

The destination is a queue name (looked up once per run) or a queue URL. Each send waits for SQS to accept the message.

Headers become message attributes of type `String`. SQS allows 10 per message and restricts their names (letters, digits, `-`, `_`, `.`; not starting with `AWS.` or `Amazon.`); a header it does not accept fails the send with SQS's own message. Two headers are not attributes: `MessageGroupId` and `MessageDeduplicationId` set those parameters. A queue whose name ends in `.fifo` gets the group `msgforge` and a new deduplication id per message when the headers do not say otherwise, so the queue keeps the send order and a repeated body is not dropped.

Receiving polls the queue (up to 10 messages, waiting up to 2 seconds per call), hands each body on and then deletes the batch. It consumes, like IBM MQ: other consumers of the queue compete for the same messages, and what msgforge receives is gone. A receive or delete that fails (the queue was deleted, the credentials stopped working) fails the run.

Test connection lists one queue. An "access denied" answer also passes, since only valid credentials get that far; a user allowed to send to one queue usually may not list them.

Verified against ElasticMQ 1.7.1 (the `softwaremill/elasticmq` image): sending by name and by URL, attributes, receiving and deleting, a FIFO queue, a missing queue. ElasticMQ accepts any credentials and has no TLS, so signing, refused credentials and TLS were not checked against a server, and nothing was tried against AWS itself or LocalStack.

## NATS

NATS core publish and subscribe, with optional JetStream publishing, through the NATS Java client.

| Setting | Notes |
|---|---|
| Servers | `nats://host:4222`, comma-separated for a cluster (tried in order); `tls://host:4222` for TLS; `ws://` / `wss://` over WebSocket |
| User, Password | a user and password login |
| Token | a token login |
| Credentials file | a `.creds` file (JWT and NKey seed), for decentralised auth. The file holds a secret; keep it readable only by the account msgforge runs as. |
| JetStream | `true` to publish through JetStream and wait for the stream's acknowledgement |

The destination is a subject; an expectation may use wildcards (`orders.*`, `orders.>`).

A core publish has no acknowledgement. Each one is followed by a round trip to the server (a PING), so a publish the server refuses for permissions is reported at that message and a dead connection at the first send. With JetStream on, each send waits for the stream's acknowledgement instead, and a subject that no stream takes fails the run (`503 No Responders`).

Headers become NATS headers (NATS Server 2.2 or later).

Receiving is a core subscription, so it reads nothing from a stream and takes nothing from anyone: every subscriber gets its own copy, and a queue group of the system under test still gets its one. It sees messages published with or without JetStream, from the moment it subscribes. A subscription the server refuses for permissions, a connection that is lost, or messages the client had to drop because it fell behind all fail the run. The client does not reconnect.

With TLS the server's certificate and host name are checked.

Test connection logs in and makes one PING round trip.

Verified against NATS Server 2.15.3 (the `nats:2` image): core sending and receiving with headers, another subscriber still getting its copy, refused publish and subscription, a refused login, JetStream publishing with and without a stream, TLS with a PEM CA file and a client certificate, the host name check. Token and credentials-file logins and WebSocket were not tried.

## Apache Pulsar

Through the Apache Pulsar Java client. **Not in the release zip**: the client is a 38 MB shaded jar that carries some twenty other libraries without their licence files, and they have not been reviewed. To use it, build it and put it in `plugins/`:

```bash
./gradlew :extensions:pulsar:installPlugin
```

| Setting | Notes |
|---|---|
| Service URL | `pulsar://host:6650`, `pulsar+ssl://host:6651` for TLS, or the HTTP(S) service URL. Several brokers: `pulsar://a:6650,b:6650`. |
| Token | a JWT for token authentication. Other Pulsar authentication methods (OAuth 2, Athenz, a client certificate as identity) are not offered. |

TLS: with a client key store, the trust store has to be a JKS or PKCS12 store as well; with only a trust store it may also be a PEM file.

The destination is a topic (`orders`, or in full `persistent://tenant/namespace/orders`). A broker that creates topics on demand creates it at the first message. Each send waits for the broker to store the message.

A header named `key` becomes the message key, as for Kafka; every other header becomes a message property.

Receiving reads the topic from its end with a reader: a non-durable subscription that keeps nothing for later and shares out nothing, so durable subscriptions of the system under test are not affected. A topic with partitions is read on all of them. A reader that fails while a run verifies fails the run.

Test connection asks the broker for the partitions of a topic named `msgforge-connection-check` without creating it; "no such topic" (the usual answer) and "not authorised" both prove the broker accepted the client.

Verified against Pulsar 4.0.11 standalone (the `apachepulsar/pulsar:4.0.11` image): key and properties, a durable subscription still holding what the reader saw, TLS with a client certificate, a missing client certificate, the JVM's default trust, a broker certificate for another host name refused, an unreachable broker. Token authentication and partitioned topics were not tried.
