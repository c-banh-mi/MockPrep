package edu.sjsu.cmpe172.scheduler.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Read side of the transactional outbox: sends notification_outbox rows to the notification
 * service and records the result.
 *
 * <ul>
 *   <li><b>No lost notifications.</b> The row was committed with the booking. Whatever happens
 *       to this JVM afterwards, the row stays PENDING until a send succeeds; after a crash and
 *       restart, {@link #poll()} finds it.</li>
 *   <li><b>In order.</b> Rows are sent in id (= write) order by one relay at a time. If a row
 *       cannot be sent yet, later rows for the same appointment wait behind it, so a
 *       "cancelled" never arrives before its "confirmed".</li>
 *   <li><b>No duplicates for the customer.</b> A retry reuses the row's idempotency key; if an
 *       earlier attempt did arrive (only the answer was lost), the service answers DUPLICATE.</li>
 * </ul>
 */
@Component
public class NotificationRelay {

    private static final Logger log = LoggerFactory.getLogger(NotificationRelay.class);
    private static final int BATCH = 100;

    private final OutboxRepository outbox;
    private final NotificationSender sender;
    private final NotificationProperties props;

    /** One relay at a time, so two threads never send the same rows or reorder them. */
    private final ReentrantLock running = new ReentrantLock();
    private final AtomicBoolean runAgain = new AtomicBoolean();

    public NotificationRelay(OutboxRepository outbox, NotificationSender sender, NotificationProperties props) {
        this.outbox = outbox;
        this.sender = sender;
        this.props = props;
    }

    /** Fast path: a booking/cancel just committed an outbox row. Runs on a notify-N thread. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOutboxWritten(OutboxWrittenEvent event) {
        drain();
    }

    /** Safety net: picks up anything the fast path missed (crash, restart, service was down). */
    @Scheduled(fixedDelayString = "${notification.relay-interval:PT5S}",
            initialDelayString = "${notification.relay-initial-delay:PT5S}")
    public void poll() {
        drain();
    }

    /** Sends every due PENDING row. If a relay is already running, it makes that one loop again. */
    public void drain() {
        if (!props.enabled()) {
            return;
        }
        if (!running.tryLock()) {
            runAgain.set(true);
            return;
        }
        try {
            do {
                runAgain.set(false);
                drainOnce();
            } while (runAgain.get());
        } catch (RuntimeException e) {
            // e.g. the database is down. Rows stay PENDING; the next poll tries again.
            log.error("Notification relay failed: {}", e.getMessage());
        } finally {
            running.unlock();
        }
    }

    private void drainOnce() {
        List<OutboxMessage> rows = outbox.findPending(BATCH);
        Set<Long> waiting = new HashSet<>();   // appointments with an earlier row that is not sent yet
        for (OutboxMessage row : rows) {
            NotificationRequest req = row.request();
            if (waiting.contains(req.appointmentId())) {
                continue;   // keep per-appointment order: an earlier row must go first
            }
            if (!row.due()) {
                waiting.add(req.appointmentId());
                continue;
            }
            NotificationSender.Result result = sender.trySend(req);
            int total = row.attempts() + result.attempts();
            switch (result.outcome()) {
                case SENT -> {
                    outbox.markSent(row.id(), result.attempts());
                    if (row.attempts() > 0) {
                        sender.count("redelivered", req);
                        log.info("Redelivered {} to {} after {} failed attempt(s) (service said {})",
                                req.idempotencyKey(), req.recipient(), row.attempts(), result.receipt().status());
                    } else {
                        sender.count("sent", req);
                    }
                }
                case REJECTED -> {
                    outbox.markFailed(row.id(), result.attempts(), result.error());
                    sender.count("failed", req);
                }
                case RETRY_LATER -> {
                    if (total >= props.maxRedeliveries()) {
                        outbox.markFailed(row.id(), result.attempts(), result.error());
                        sender.count("failed", req);
                        log.error("Giving up on notification {} after {} attempts: {}",
                                req.idempotencyKey(), total, result.error());
                    } else {
                        outbox.scheduleRetry(row.id(), result.attempts(), props.redeliveryInterval(), result.error());
                        if (row.attempts() == 0) {
                            sender.count("parked", req);
                        }
                        log.warn("Notification service unavailable after {} attempt(s); {} stays in the outbox, "
                                        + "next try in {} s ({})", total, req.idempotencyKey(),
                                props.redeliveryInterval().toSeconds(), result.error());
                    }
                    // The service is down: stop here. Everything after this row waits, in order.
                    return;
                }
            }
        }
    }
}
