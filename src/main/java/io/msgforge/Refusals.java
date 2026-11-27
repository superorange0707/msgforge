package io.msgforge;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tells a broker that refused the credentials from one that could not be reached. The difference matters to
 * whatever tries again by itself: a broker that is down is worth another try, a wrong password is the same wrong
 * password each time, and enough failed logins lock the account.
 *
 * By the exception's type where the client has one for it, by the reason it gives otherwise. The types are named,
 * not referenced: they live in the plugins' class loaders.
 */
public final class Refusals {

    private Refusals() {}

    /** An error that was a refusal of credentials, after the connection's guard has reduced it to its text. */
    public static final class Refused extends IllegalStateException {
        Refused(String message) {
            super(message);
        }
    }

    private static final Set<String> TYPES = Set.of(
            "jakarta.jms.JMSSecurityException", "javax.jms.JMSSecurityException",
            "jakarta.jms.JMSSecurityRuntimeException", "javax.jms.JMSSecurityRuntimeException",
            // not its parent, PossibleAuthenticationFailureException: that one is a connection lost during the handshake
            "com.rabbitmq.client.AuthenticationFailureException",
            "io.nats.client.AuthenticationException",
            "org.apache.kafka.common.errors.AuthenticationException", "org.apache.kafka.common.errors.AuthorizationException",
            "org.eclipse.paho.client.mqttv3.MqttSecurityException", "org.eclipse.paho.mqttv5.common.MqttSecurityException",
            "org.apache.pulsar.client.api.PulsarClientException$AuthenticationException",
            "org.apache.pulsar.client.api.PulsarClientException$AuthorizationException",
            "javax.security.sasl.AuthenticationException");

    // What the clients say when they have no type for it, or when only the text is left.
    private static final Pattern TEXT = Pattern.compile(
            "MQRC_NOT_AUTHORIZED|reason(?: code)? '?2035\\b|JMSWMQ2013"                        // IBM MQ
            + "|JMSSecurityException|amqp:unauthorized-access"                                 // JMS, AMQP 1.0
            + "|ACCESS_REFUSED|reply-code=403"                                                 // RabbitMQ
            + "|authori[sz]ation violation"                                                    // NATS
            + "|SaslAuthenticationException|authentication failed"                             // Kafka, and SASL anywhere ("possibly caused by authentication failure" is not one)
            + "|bad user ?name or password|BAD_USER_NAME_OR_PASSWORD|not[ _]authori[sz]ed"     // MQTT
            + "|AccessDenied|InvalidClientTokenId|SignatureDoesNotMatch",                      // SQS
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> AWS_CODES = Set.of("AccessDenied", "AccessDeniedException", "InvalidClientTokenId", "SignatureDoesNotMatch", "UnrecognizedClientException");

    /** Whether this error, or anything it was caused by, is a broker refusing the credentials or the right to the destination. */
    public static boolean refused(Throwable error) {
        int depth = 0;
        for (Throwable e = error; e != null && depth++ < 20; e = e.getCause() == e ? null : e.getCause()) {
            if (e instanceof Refused) return true;
            for (Class<?> c = e.getClass(); c != null; c = c.getSuperclass()) {
                if (TYPES.contains(c.getName())) return true;
                if (c.getName().equals("software.amazon.awssdk.awscore.exception.AwsServiceException") && AWS_CODES.contains(awsCode(e))) return true;
            }
            if (refused(e.getMessage())) return true;
        }
        return false;
    }

    /** The same, of an error of which only the text is left. */
    public static boolean refused(String text) {
        return text != null && TEXT.matcher(text).find();
    }

    /** The AWS error code, which the SDK keeps beside the message and not in it. */
    private static String awsCode(Throwable e) {
        try {
            Object details = e.getClass().getMethod("awsErrorDetails").invoke(e);
            return details == null ? null : String.valueOf(details.getClass().getMethod("errorCode").invoke(details));
        } catch (ReflectiveOperationException | RuntimeException unknown) {
            return null;
        }
    }
}
