package edu.sjsu.cmpe172.scheduler;

import edu.sjsu.cmpe172.scheduler.notification.NotificationException;
import edu.sjsu.cmpe172.scheduler.notification.NotificationGateway;
import edu.sjsu.cmpe172.scheduler.notification.NotificationProperties;
import edu.sjsu.cmpe172.scheduler.notification.NotificationReceipt;
import edu.sjsu.cmpe172.scheduler.notification.NotificationRequest;
import edu.sjsu.cmpe172.scheduler.notification.NotificationSender;
import edu.sjsu.cmpe172.scheduler.notification.NotificationSender.Outcome;
import edu.sjsu.cmpe172.scheduler.notification.NotificationType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** One delivery burst: retry with backoff on transient failures, never on a 4xx. */
class NotificationSenderTest {

    static final NotificationRequest REQ = new NotificationRequest(
            "appointment-7-BOOKING_CONFIRMED", "sam@example.com", NotificationType.BOOKING_CONFIRMED,
            "Hi Sam, your session is confirmed.", 7);
    static final NotificationReceipt OK = new NotificationReceipt("n-1", "ACCEPTED", Instant.now());
    static final NotificationProperties PROPS =
            new NotificationProperties(true, Duration.ofSeconds(1), Duration.ofSeconds(1), 3, Duration.ofMillis(1), 10,
                    Duration.ofSeconds(30));

    private final NotificationGateway gateway = mock(NotificationGateway.class);
    private final NotificationSender sender = new NotificationSender(gateway, PROPS, new SimpleMeterRegistry());

    static NotificationException down() {
        return new NotificationException("Connection refused", true, null);
    }

    @Test
    void firstTrySucceeds() {
        when(gateway.send(REQ)).thenReturn(OK);

        NotificationSender.Result result = sender.trySend(REQ);

        assertThat(result.outcome()).isEqualTo(Outcome.SENT);
        assertThat(result.attempts()).isEqualTo(1);
    }

    @Test
    void retriesTransientFailureThenSucceeds() {
        when(gateway.send(REQ)).thenThrow(down()).thenThrow(down()).thenReturn(OK);

        NotificationSender.Result result = sender.trySend(REQ);

        verify(gateway, times(3)).send(REQ);
        assertThat(result.outcome()).isEqualTo(Outcome.SENT);
        assertThat(result.attempts()).isEqualTo(3);
    }

    @Test
    void serviceDownForTheWholeBurstMeansRetryLater() {
        when(gateway.send(REQ)).thenThrow(down());

        NotificationSender.Result result = sender.trySend(REQ);

        verify(gateway, times(3)).send(REQ);
        assertThat(result.outcome()).isEqualTo(Outcome.RETRY_LATER);
        assertThat(result.error()).contains("Connection refused");
    }

    @Test
    void rejectedRequestIsNotRetried() {
        when(gateway.send(REQ)).thenThrow(new NotificationException("400 BAD_REQUEST", false, null));

        NotificationSender.Result result = sender.trySend(REQ);

        verify(gateway, times(1)).send(REQ);
        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
    }
}
