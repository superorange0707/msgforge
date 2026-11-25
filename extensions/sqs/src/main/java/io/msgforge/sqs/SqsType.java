package io.msgforge.sqs;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;

import io.msgforge.TransportType;
import io.msgforge.ext.Tls;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Amazon SQS, or anything that answers its API (ElasticMQ, LocalStack). The destination is a queue name or a queue URL.
 * Headers become string message attributes. Receiving polls the queue and deletes what it receives.
 */
public class SqsType implements TransportType {

    /** Headers that set a parameter of the send instead of becoming an attribute: they matter for FIFO queues. */
    static final List<String> PARAMETERS = List.of("MessageGroupId", "MessageDeduplicationId");

    @Override
    public String id() {
        return "sqs";
    }

    @Override
    public String label() {
        return "Amazon SQS";
    }

    @Override
    public List<Field> fields() {
        return Stream.concat(Stream.of(
                new Field("region", "Region", "eu-west-1", true, false),
                new Field("endpoint", "Endpoint", "empty for AWS; http://localhost:9324 for ElasticMQ", false, false),
                new Field("accessKeyId", "Access key ID", "${MSGFORGE_AWS_ACCESS_KEY_ID}", true, false),
                new Field("secretAccessKey", "Secret access key", "${MSGFORGE_AWS_SECRET_ACCESS_KEY}", true, true),
                new Field("sessionToken", "Session token", "for temporary credentials", false, true)), Tls.fields().stream()).toList();
    }

    @Override
    public String summary(Map<String, String> s) {
        String endpoint = s.getOrDefault("endpoint", "");
        return endpoint.isBlank() ? "SQS " + s.getOrDefault("region", "") : endpoint;
    }

    @Override
    public Sender open(Map<String, String> s) {
        return sender(client(s));
    }

    @Override
    public boolean canReceive() {
        return true;
    }

    @Override
    public Subscription subscribe(Map<String, String> s, String destination, Consumer<String> onMessage, Consumer<Exception> onError) {
        return subscription(client(s), destination, onMessage, onError, 2);
    }

    /**
     * Credentials come from the connection only. The SDK's default chain (environment, ~/.aws, instance roles) is not
     * used: whoever can edit a connection could otherwise point it at their own endpoint and have it sign requests
     * with this machine's credentials.
     */
    static SqsClient client(Map<String, String> s) {
        SqsClientBuilder builder = SqsClient.builder();
        String region = s.getOrDefault("region", "").trim();
        if (region.isEmpty()) throw new IllegalArgumentException("Region is required");
        builder.region(Region.of(region));
        if (!Tls.blank(s, "endpoint")) {
            URI endpoint = Tls.uri("Endpoint", s.get("endpoint"), "http", "https");
            if (!endpoint.getScheme().equalsIgnoreCase("https") && Tls.configured(s)) {
                throw new IllegalArgumentException("A trust store or key store needs an https:// Endpoint");
            }
            builder.endpointOverride(endpoint);
        }
        if (Tls.blank(s, "accessKeyId") || Tls.blank(s, "secretAccessKey")) {
            throw new IllegalArgumentException("Access key ID and Secret access key are required");
        }
        String id = s.get("accessKeyId").trim(), secret = s.get("secretAccessKey").trim();
        builder.credentialsProvider(StaticCredentialsProvider.create(Tls.blank(s, "sessionToken")
                ? AwsBasicCredentials.create(id, secret) : AwsSessionCredentials.create(id, secret, s.get("sessionToken").trim())));

        UrlConnectionHttpClient.Builder http = UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(10)).socketTimeout(Duration.ofSeconds(30));
        try {
            // the SDK takes the stores as trust and key managers; either one left out is the JVM's default
            TrustManager[] trust = Tls.trustManagers(s);
            KeyManager[] keys = Tls.keyManagers(s);
            if (trust != null) http.tlsTrustManagersProvider(() -> trust);
            if (keys != null) http.tlsKeyManagersProvider(() -> keys);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("TLS could not be set up: " + e.getMessage());
        }
        return builder.httpClient(http.build())
                .overrideConfiguration(o -> o.apiCallTimeout(Duration.ofSeconds(30)))
                .build();
    }

    /** A queue URL is used as it is; a name is looked up once per sender. */
    static String queueUrl(SqsClient sqs, String destination, Map<String, String> known) {
        if (destination.startsWith("https://") || destination.startsWith("http://")) return destination;
        return known.computeIfAbsent(destination, name -> sqs.getQueueUrl(r -> r.queueName(name)).queueUrl());
    }

    static SendMessageRequest request(String queueUrl, String body, Map<String, String> headers) {
        SendMessageRequest.Builder r = SendMessageRequest.builder().queueUrl(queueUrl).messageBody(body);
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        headers.forEach((name, value) -> {
            if (!PARAMETERS.contains(name)) attributes.put(name, MessageAttributeValue.builder().dataType("String").stringValue(value).build());
        });
        if (!attributes.isEmpty()) r.messageAttributes(attributes);
        if (queueUrl.endsWith(".fifo")) {
            // A FIFO queue needs a group, and a deduplication id unless the queue derives one from the body.
            // ponytail: one group for the whole run unless a header says otherwise, so the queue keeps the send order.
            r.messageGroupId(headers.getOrDefault("MessageGroupId", "msgforge"));
            r.messageDeduplicationId(headers.getOrDefault("MessageDeduplicationId", UUID.randomUUID().toString()));
        }
        return r.build();
    }

    /** Takes over the client and closes it with the sender. */
    static Sender sender(SqsClient sqs) {
        Map<String, String> urls = new ConcurrentHashMap<>();
        return new Sender() {
            @Override
            public void send(String destination, String body, Map<String, String> headers) {
                sqs.sendMessage(request(queueUrl(sqs, destination, urls), body, headers));
            }

            /**
             * A signed request the service has to answer. "Access denied" also proves the credentials are good:
             * a user allowed only to send may not list queues.
             */
            @Override
            public void check() {
                try {
                    sqs.listQueues(r -> r.maxResults(1));
                } catch (AwsServiceException e) {
                    if (e.awsErrorDetails() == null || !String.valueOf(e.awsErrorDetails().errorCode()).startsWith("AccessDenied")) throw e;
                }
            }

            @Override
            public void close() {
                sqs.close();
            }
        };
    }

    /** Takes over the client: it is polled on its own thread, and closed when the subscription closes. */
    static Subscription subscription(SqsClient sqs, String destination, Consumer<String> onMessage, Consumer<Exception> onError, int waitSeconds) {
        String url;
        try {
            url = queueUrl(sqs, destination, new HashMap<>());
        } catch (RuntimeException e) {
            sqs.close();
            throw e;
        }
        AtomicReference<Exception> ended = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean closing = new java.util.concurrent.atomic.AtomicBoolean();
        Thread thread = new Thread(() -> {
            try {
                while (!closing.get()) {
                    List<Message> messages = sqs.receiveMessage(r -> r.queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(waitSeconds)).messages();
                    if (messages.isEmpty()) continue;
                    messages.forEach(m -> onMessage.accept(m.body()));
                    int[] n = {0};
                    sqs.deleteMessageBatch(r -> r.queueUrl(url).entries(messages.stream()
                            .map(m -> DeleteMessageBatchRequestEntry.builder().id(String.valueOf(n[0]++)).receiptHandle(m.receiptHandle()).build()).toList()));
                }
                ended.set(new IllegalStateException("The SQS receiver was closed"));
            } catch (Throwable e) {
                Exception reason = e instanceof Exception x ? x : new IllegalStateException(e.toString());
                if (closing.get()) {   // the SDK reports an interrupted call as an error of its own
                    ended.set(new IllegalStateException("The SQS receiver was closed"));
                } else {
                    ended.set(reason);
                    onError.accept(reason);
                }
            }
        }, "msgforge-sqs-receive");
        thread.setDaemon(true);
        thread.start();
        return new Subscription() {
            @Override
            public void check() throws Exception {
                if (ended.get() != null) throw ended.get();
                sqs.getQueueAttributes(r -> r.queueUrl(url).attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
            }

            @Override
            public void close() {
                closing.set(true);
                thread.interrupt();   // a receive in progress returns within its wait time
                try {
                    thread.join((waitSeconds + 5) * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                sqs.close();
            }
        };
    }
}
