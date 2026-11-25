# Connection types from plugins

The release zip carries these in `plugins/`. Each is one jar with its client library inside, loaded on its own like any other plugin (see [Extending msgforge](../README.md#extending-msgforge)). Running from source, `./gradlew :extensions:<name>:installPlugin` puts one in `plugins/`.

What they share:

- **Addresses** are URIs. A user name or password written into one (`amqp://user:pw@host`) is refused: they go in the User and Password fields, which keep the password out of the console and out of error messages. So is a URI option that turns certificate checks off.
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
