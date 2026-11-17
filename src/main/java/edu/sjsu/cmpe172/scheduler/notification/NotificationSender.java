package edu.sjsu.cmpe172.scheduler.notification;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One delivery attempt "burst" for one notification: try, and on a transient failure retry with
 * exponential backoff (200 ms, 400 ms, ...). Never throws; the caller ({@link NotificationRelay})
 * records the result in the outbox.
 */
@Service
public class NotificationSender {

    /** SENT: the service has it. RETRY_LATER: service unavailable. REJECTED: 4xx, resending cannot help. */
    public enum Outcome { SENT, RETRY_LATER, REJECTED }

    /**
     * @param attempts calls made in this burst
     * @param error    last error message, null when SENT
     */
    public record Result(Outcome outcome, int attempts, String error, NotificationReceipt receipt) {
    }

    private static final Logger log = LoggerFactory.getLogger(NotificationSender.class);

    private final NotificationGateway gateway;
    private final NotificationProperties props;
    private final MeterRegistry metrics;

    public NotificationSender(NotificationGateway gateway, NotificationProperties props, MeterRegistry metrics) {
        this.gateway = gateway;
        this.props = props;
        this.metrics = metrics;
    }

    public Result trySend(NotificationRequest req) {
        NotificationException last = null;
        int attempts = 0;
        while (attempts < props.maxAttempts()) {
            attempts++;
            try {
                NotificationReceipt receipt = gateway.send(req);
                log.info("Sent {} to {} (key {}, notification {}, attempt {})",
                        req.type(), req.recipient(), req.idempotencyKey(), receipt.notificationId(), attempts);
                return new Result(Outcome.SENT, attempts, null, receipt);
            } catch (NotificationException e) {
                last = e;
                if (!e.retryable()) {
                    log.error("Notification {} rejected, not retrying: {}", req.idempotencyKey(), e.getMessage());
                    return new Result(Outcome.REJECTED, attempts, e.getMessage(), null);
                }
                if (attempts == props.maxAttempts()) {
                    break;
                }
                long waitMs = props.backoff().toMillis() << (attempts - 1);   // 200 ms, 400 ms, ...
                log.warn("Notification {} attempt {}/{} failed: {}. Retrying in {} ms",
                        req.idempotencyKey(), attempts, props.maxAttempts(), e.getMessage(), waitMs);
                if (!sleep(waitMs)) {
                    break;   // shutting down: leave it in the outbox for the next start
                }
            }
        }
        return new Result(Outcome.RETRY_LATER, attempts, last == null ? "interrupted" : last.getMessage(), null);
    }

    void count(String result, NotificationRequest req) {
        metrics.counter("scheduler.notifications", "result", result, "type", req.type().name()).increment();
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
