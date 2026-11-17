package edu.sjsu.cmpe172.scheduler.dto;

import java.util.List;

/**
 * The booking agent's answer.
 *
 * @param pendingAction  a booking/cancellation waiting for the user's "yes" (null if none);
 *                       the UI shows Confirm / Cancel buttons for it
 * @param actionsTaken   writes the agent actually performed in this turn, e.g. "Booked appointment #12"
 */
public record ChatReply(String conversationId, String reply, String pendingAction, List<String> actionsTaken) {
}
