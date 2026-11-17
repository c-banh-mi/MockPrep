package edu.sjsu.cmpe172.scheduler.notification;

/**
 * "There is something new in the outbox." Published inside the writing transaction;
 * {@link NotificationRelay} reacts to it only after that transaction commits, so a new
 * notification normally goes out within milliseconds instead of at the next poll.
 * Losing this event (e.g. a crash right after the commit) loses nothing: the row is
 * already in the database and the relay's poll picks it up.
 */
public record OutboxWrittenEvent(long appointmentId) {
}
