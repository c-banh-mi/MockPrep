package edu.sjsu.cmpe172.scheduler.dto;

import java.time.Instant;
import java.util.List;

/**
 * Result of one run of the autonomous reminder agent.
 *
 * @param trigger "scheduled" or "manual (admin)"
 */
public record ReminderRunReport(String trigger, Instant startedAt, int scanned, int queued, int skipped, int failed,
                                List<Item> items) {

    /** status: QUEUED (message written by the LLM and put in the outbox), SKIPPED, FAILED. */
    public record Item(long appointmentId, String customer, String service, String startTime, String status,
                       String message) {
    }
}
