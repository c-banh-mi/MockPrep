package edu.sjsu.cmpe172.scheduler.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings under "notification." in application.yml. The base URL is read separately
 * (see {@link RestNotificationGateway}) because it can depend on the server's port.
 *
 * @param enabled         false turns notifications off entirely (bookings are unaffected)
 * @param connectTimeout  give up if the TCP connection is not open by then
 * @param readTimeout     give up if the service has not answered by then
 * @param maxAttempts     attempts per burst (one relay pass) before the row is rescheduled
 * @param backoff         wait before the 2nd attempt; doubles for each attempt after that
 * @param maxRedeliveries total attempts for an outbox row before it is marked FAILED
 * @param redeliveryInterval wait before the next burst once a burst has failed
 */
@ConfigurationProperties("notification")
public record NotificationProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("2s") Duration readTimeout,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("200ms") Duration backoff,
        @DefaultValue("10") int maxRedeliveries,
        @DefaultValue("30s") Duration redeliveryInterval) {

    public NotificationProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("notification.max-attempts must be at least 1");
        }
    }
}
