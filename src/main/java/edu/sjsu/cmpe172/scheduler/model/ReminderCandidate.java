package edu.sjsu.cmpe172.scheduler.model;

import java.time.LocalDateTime;

/** A BOOKED, upcoming appointment that has not had its reminder yet, with what the LLM needs. */
public record ReminderCandidate(
        long appointmentId,
        String customerName,
        String serviceName,
        int durationMinutes,
        String mentorName,
        String mentorHeadline,
        LocalDateTime startTime,
        String notes) {
}
