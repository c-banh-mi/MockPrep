package edu.sjsu.cmpe172.scheduler.ai.agent;

import java.time.Instant;

/**
 * A write the agent has prepared but not performed: it waits for the user's confirmation.
 * Kept on the server, so the model cannot alter what will be booked between proposal and confirm.
 *
 * @param proposedInTurn the conversation turn in which it was proposed; it may only be confirmed
 *                       in a LATER turn, i.e. after the user has replied to the summary
 */
public record PendingAction(
        Type type,
        long slotId,
        long serviceId,
        String notes,
        long appointmentId,
        String summary,
        int proposedInTurn,
        Instant createdAt) {

    public enum Type { BOOK, CANCEL }

    static PendingAction book(long slotId, long serviceId, String notes, String summary, int turn) {
        return new PendingAction(Type.BOOK, slotId, serviceId, notes, 0, summary, turn, Instant.now());
    }

    static PendingAction cancel(long appointmentId, String summary, int turn) {
        return new PendingAction(Type.CANCEL, 0, 0, null, appointmentId, summary, turn, Instant.now());
    }
}
