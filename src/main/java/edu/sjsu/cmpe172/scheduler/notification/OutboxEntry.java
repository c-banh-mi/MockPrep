package edu.sjsu.cmpe172.scheduler.notification;

import java.time.LocalDateTime;

/** One notification_outbox row as the admin page shows it. */
public record OutboxEntry(
        long id,
        String idempotencyKey,
        long appointmentId,
        String recipient,
        String type,
        String message,
        String status,
        int attempts,
        String lastError,
        LocalDateTime createdAt,
        LocalDateTime sentAt) {
}
