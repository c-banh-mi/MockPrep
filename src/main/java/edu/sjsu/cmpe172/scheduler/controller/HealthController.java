package edu.sjsu.cmpe172.scheduler.controller;

import edu.sjsu.cmpe172.scheduler.notification.OutboxMessage;
import edu.sjsu.cmpe172.scheduler.notification.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /health: 200 {"status":"UP", ...} when the database answers, 503 {"status":"DOWN", ...}
 * when it does not (a load balancer or Docker would stop sending traffic / restart the app).
 * Notification problems show as "DEGRADED" but keep the app UP, because bookings still work
 * without the notification service.
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final JdbcClient jdbc;
    private final OutboxRepository outbox;

    public HealthController(JdbcClient jdbc, OutboxRepository outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> database = new LinkedHashMap<>();
        Map<String, Object> notifications = new LinkedHashMap<>();
        boolean dbUp;

        long start = System.nanoTime();
        try {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            dbUp = true;
            database.put("status", "UP");
            database.put("responseTimeMs", (System.nanoTime() - start) / 1_000_000);

            // pending = not delivered yet; retrying = already failed at least once; failed = given up.
            // A row queued a moment ago and not tried yet is normal, so only retrying/failed degrade.
            int pending = outbox.countByStatus(OutboxMessage.PENDING);
            int retrying = outbox.countWaitingForRetry();
            int failed = outbox.countByStatus(OutboxMessage.FAILED);
            notifications.put("status", retrying + failed == 0 ? "UP" : "DEGRADED");
            notifications.put("pending", pending);
            notifications.put("retrying", retrying);
            notifications.put("failed", failed);
        } catch (DataAccessException e) {
            dbUp = false;
            log.error("Health check: database unreachable: {}", e.getMostSpecificCause().getMessage());
            database.put("status", "DOWN");
            database.put("error", e.getMostSpecificCause().getClass().getSimpleName());
            notifications.put("status", "UNKNOWN");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", dbUp ? "UP" : "DOWN");
        body.put("timestamp", Instant.now());
        body.put("database", database);
        body.put("notifications", notifications);
        return ResponseEntity.status(dbUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
