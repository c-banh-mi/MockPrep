package edu.sjsu.cmpe172.scheduler.notification;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;

/** The notification_outbox table: notifications waiting to be sent, in the order they were written. */
@Repository
public class OutboxRepository {

    private static final RowMapper<OutboxMessage> MAPPER = (rs, i) -> new OutboxMessage(
            rs.getLong("id"),
            new NotificationRequest(
                    rs.getString("idempotency_key"),
                    rs.getString("recipient"),
                    NotificationType.valueOf(rs.getString("type")),
                    rs.getString("message"),
                    rs.getLong("appointment_id")),
            rs.getInt("attempts"),
            rs.getBoolean("due"));

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Adds a notification to the outbox. Runs inside the caller's transaction (booking, cancel,
     * reminder), so the row commits or rolls back together with that change.
     *
     * @return false if a row with this idempotency key already exists (nothing was added)
     */
    public boolean insert(NotificationRequest req) {
        return jdbc.sql("""
                        INSERT INTO notification_outbox (idempotency_key, appointment_id, recipient, type, message)
                        VALUES (:key, :appointmentId, :recipient, :type, :message)
                        ON CONFLICT (idempotency_key) DO NOTHING
                        """)
                .param("key", req.idempotencyKey())
                .param("appointmentId", req.appointmentId())
                .param("recipient", req.recipient())
                .param("type", req.type().name())
                .param("message", req.message())
                .update() == 1;
    }

    /** PENDING rows in the order they were written (id), whether due yet or not. */
    public List<OutboxMessage> findPending(int limit) {
        return jdbc.sql("""
                        SELECT id, idempotency_key, appointment_id, recipient, type, message, attempts,
                               next_attempt_at <= CURRENT_TIMESTAMP AS due
                          FROM notification_outbox
                         WHERE status = 'PENDING'
                         ORDER BY id
                         LIMIT :limit
                        """)
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }

    public void markSent(long id, int attemptsMade) {
        jdbc.sql("""
                        UPDATE notification_outbox
                           SET status = 'SENT', attempts = attempts + :n, last_error = NULL, sent_at = CURRENT_TIMESTAMP
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("n", attemptsMade)
                .update();
    }

    /** Still PENDING; the relay tries again once {@code delay} has passed. */
    public void scheduleRetry(long id, int attemptsMade, Duration delay, String error) {
        jdbc.sql("""
                        UPDATE notification_outbox
                           SET attempts = attempts + :n, last_error = :error,
                               next_attempt_at = CURRENT_TIMESTAMP + :delayMs * INTERVAL '1 millisecond'
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("n", attemptsMade)
                .param("error", truncate(error))
                .param("delayMs", delay.toMillis())
                .update();
    }

    /** No more attempts (rejected by the service, or out of retries). Kept for a person to look at. */
    public void markFailed(long id, int attemptsMade, String error) {
        jdbc.sql("""
                        UPDATE notification_outbox
                           SET status = 'FAILED', attempts = attempts + :n, last_error = :error
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("n", attemptsMade)
                .param("error", truncate(error))
                .update();
    }

    /** Latest outbox rows, newest first (admin view). */
    public List<OutboxEntry> findRecent(int limit) {
        return jdbc.sql("""
                        SELECT id, idempotency_key, appointment_id, recipient, type, message, status, attempts,
                               last_error, created_at, sent_at
                          FROM notification_outbox
                         ORDER BY id DESC
                         LIMIT :limit
                        """)
                .param("limit", limit)
                .query((rs, i) -> new OutboxEntry(
                        rs.getLong("id"),
                        rs.getString("idempotency_key"),
                        rs.getLong("appointment_id"),
                        rs.getString("recipient"),
                        rs.getString("type"),
                        rs.getString("message"),
                        rs.getString("status"),
                        rs.getInt("attempts"),
                        rs.getString("last_error"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toLocalDateTime()))
                .list();
    }

    /** PENDING rows that already failed at least once (the service is having trouble). */
    public int countWaitingForRetry() {
        return jdbc.sql("SELECT COUNT(*) FROM notification_outbox WHERE status = 'PENDING' AND attempts > 0")
                .query(Integer.class)
                .single();
    }

    public int countByStatus(String status) {
        return jdbc.sql("SELECT COUNT(*) FROM notification_outbox WHERE status = :status")
                .param("status", status)
                .query(Integer.class)
                .single();
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}
