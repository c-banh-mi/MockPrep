package edu.sjsu.cmpe172.scheduler.notification;

/**
 * A PENDING notification_outbox row.
 *
 * @param attempts delivery attempts already made (0 = never tried yet)
 * @param due      true if next_attempt_at has passed, i.e. the relay may try it now
 */
public record OutboxMessage(long id, NotificationRequest request, int attempts, boolean due) {

    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
}
