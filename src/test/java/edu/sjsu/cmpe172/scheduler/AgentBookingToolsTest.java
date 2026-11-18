package edu.sjsu.cmpe172.scheduler;

import edu.sjsu.cmpe172.scheduler.ai.agent.BookingAgent;
import edu.sjsu.cmpe172.scheduler.ai.agent.BookingAgentTools;
import edu.sjsu.cmpe172.scheduler.ai.agent.BookingAgentTools.ToolResult;
import edu.sjsu.cmpe172.scheduler.dto.BookingRequest;
import edu.sjsu.cmpe172.scheduler.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 4 requirement: the agent's write tools go through the transactional service layer,
 * so its bookings are protected by the same double-booking guard and RBAC as the REST API.
 * The tools are called directly, turn by turn, exactly as Spring AI calls them for Gemini
 * (no LLM needed: the guarantees must hold whatever the model decides to do).
 */
class AgentBookingToolsTest extends IntegrationTestBase {

    @Autowired
    BookingAgent agent;

    @Autowired
    BookingService bookingService;

    private int activeBookings(long slotId) {
        return jdbc.sql("SELECT COUNT(*) FROM appointments WHERE slot_id = :id AND status = 'BOOKED'")
                .param("id", slotId).query(Integer.class).single();
    }

    private static String newConversation() {
        return UUID.randomUUID().toString();
    }

    @Test
    void agentCannotBookInTheSameTurnItProposed() {
        long slotId = openSlotId("alice.mentor", "Mock Coding Interview");
        String convo = newConversation();

        // Turn 1: "book the earliest coding interview". The model proposes and immediately tries to confirm.
        BookingAgentTools turn1 = agent.startTurn(account("jordan.dev"), convo);
        assertThat(turn1.proposeBooking(slotId, "backend").status()).isEqualTo("PENDING_CONFIRMATION");
        ToolResult tooEarly = turn1.confirmPendingAction();

        assertThat(tooEarly.status()).isEqualTo("NOT_CONFIRMED_YET");
        assertThat(activeBookings(slotId)).isZero();

        // Turn 2: the user said "yes".
        BookingAgentTools turn2 = agent.startTurn(account("jordan.dev"), convo);
        ToolResult done = turn2.confirmPendingAction();

        assertThat(done.status()).isEqualTo("DONE");
        assertThat(turn2.actionsTaken()).singleElement().asString().startsWith("Booked appointment #");
        assertThat(activeBookings(slotId)).isEqualTo(1);
        // Same transactional path as the REST API: the confirmation is in the outbox.
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notification_outbox WHERE type = 'BOOKING_CONFIRMED'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void doubleBookingIsBlockedWhenTheAgentBooks() {
        long slotId = openSlotId("alice.mentor", "Mock Coding Interview");
        String convo = newConversation();
        BookingAgentTools turn1 = agent.startTurn(account("sam.dev"), convo);
        assertThat(turn1.proposeBooking(slotId, null).status()).isEqualTo("PENDING_CONFIRMATION");

        // While Sam is reading the summary, Jordan books the same slot through the normal API path.
        bookingService.book(account("jordan.dev"), new BookingRequest(slotId, serviceIdOfSlot(slotId), null));

        // Sam confirms: the transactional booking path refuses, and the agent offers alternatives.
        ToolResult result = agent.startTurn(account("sam.dev"), convo).confirmPendingAction();

        assertThat(result.status()).isEqualTo("UNAVAILABLE");
        assertThat(result.alternatives()).isNotEmpty().allSatisfy(a -> assertThat(a.slotId()).isNotEqualTo(slotId));
        assertThat(activeBookings(slotId)).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT u.username FROM appointments a JOIN users u ON u.id = a.customer_id
                         WHERE a.slot_id = :id AND a.status = 'BOOKED'
                        """).param("id", slotId).query(String.class).single()).isEqualTo("jordan.dev");
    }

    @Test
    void twoAgentsConfirmingTheSameSlotAtOnceBookItExactlyOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                resetDatabase();
                long slotId = openSlotId("raj.mentor", "Mock System Design");
                List<BookingAgentTools> confirmTurns = new ArrayList<>();
                for (String user : List.of("sam.dev", "jordan.dev")) {
                    String convo = newConversation();
                    agent.startTurn(account(user), convo).proposeBooking(slotId, null);
                    confirmTurns.add(agent.startTurn(account(user), convo));
                }

                CyclicBarrier start = new CyclicBarrier(2);
                List<Future<ToolResult>> results = new ArrayList<>();
                for (BookingAgentTools tools : confirmTurns) {
                    results.add(pool.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        return tools.confirmPendingAction();
                    }));
                }
                List<String> statuses = new ArrayList<>();
                for (Future<ToolResult> f : results) {
                    statuses.add(f.get(20, TimeUnit.SECONDS).status());
                }

                assertThat(statuses).containsExactlyInAnyOrder("DONE", "UNAVAILABLE");
                assertThat(activeBookings(slotId)).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void doubleClickedConfirmBooksOnce() throws Exception {
        // The user clicks "Confirm" twice: two confirms for the SAME conversation race each other.
        long slotId = openSlotId("alice.mentor", "Resume Review");
        String convo = newConversation();
        agent.startTurn(account("jordan.dev"), convo).proposeBooking(slotId, null);
        BookingAgentTools click1 = agent.startTurn(account("jordan.dev"), convo);
        BookingAgentTools click2 = agent.startTurn(account("jordan.dev"), convo);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier start = new CyclicBarrier(2);
            Future<ToolResult> a = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return click1.confirmPendingAction(); });
            Future<ToolResult> b = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return click2.confirmPendingAction(); });
            List<String> statuses = List.of(a.get(20, TimeUnit.SECONDS).status(), b.get(20, TimeUnit.SECONDS).status());

            // Exactly one of them runs the booking; the other finds nothing pending (not "slot taken").
            assertThat(statuses).containsExactlyInAnyOrder("DONE", "NOTHING_PENDING");
            assertThat(activeBookings(slotId)).isEqualTo(1);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM notification_outbox WHERE type = 'BOOKING_CONFIRMED'")
                    .query(Integer.class).single()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void reProposingTheSameSlotKeepsTheUsersConfirmationValid() {
        // Turn 1 proposes; in turn 2 ("yes") the model proposes the same slot again, then confirms.
        long slotId = openSlotId("maria.mentor", "Behavioral Interview Prep");
        String convo = newConversation();
        agent.startTurn(account("jordan.dev"), convo).proposeBooking(slotId, null);

        BookingAgentTools turn2 = agent.startTurn(account("jordan.dev"), convo);
        turn2.proposeBooking(slotId, null);
        assertThat(turn2.confirmPendingAction().status()).isEqualTo("DONE");

        // But proposing a DIFFERENT slot needs a fresh confirmation.
        long other = openSlotId("raj.mentor", "Mock Coding Interview");
        BookingAgentTools turn3 = agent.startTurn(account("jordan.dev"), convo);
        turn3.proposeBooking(other, null);
        assertThat(turn3.confirmPendingAction().status()).isEqualTo("NOT_CONFIRMED_YET");
        assertThat(activeBookings(other)).isZero();
    }

    @Test
    void agentActsOnlyAsTheLoggedInUser() {
        // Seed data: appointment #1 belongs to Sam. Jordan's agent cannot cancel it.
        BookingAgentTools jordan = agent.startTurn(account("jordan.dev"), newConversation());
        ToolResult result = jordan.proposeCancellation(1);

        assertThat(result.status()).isEqualTo("REFUSED");
        assertThat(jordan.confirmPendingAction().status()).isEqualTo("NOTHING_PENDING");
        assertThat(jdbc.sql("SELECT status FROM appointments WHERE id = 1").query(String.class).single())
                .isEqualTo("BOOKED");
        // Jordan's listing shows only Jordan's appointments (none), not Sam's.
        assertThat(jordan.listMyAppointments("upcoming")).isEmpty();
    }

    @Test
    void cancelThroughTheAgentNeedsConfirmationAndReopensTheSlot() {
        String convo = newConversation();
        BookingAgentTools turn1 = agent.startTurn(account("sam.dev"), convo);
        assertThat(turn1.listMyAppointments(null)).singleElement()
                .satisfies(a -> assertThat(a.appointmentId()).isEqualTo(1));
        assertThat(turn1.proposeCancellation(1).status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(turn1.confirmPendingAction().status()).isEqualTo("NOT_CONFIRMED_YET");

        ToolResult done = agent.startTurn(account("sam.dev"), convo).confirmPendingAction();

        assertThat(done.status()).isEqualTo("DONE");
        assertThat(jdbc.sql("""
                SELECT s.status FROM availability_slots s JOIN appointments a ON a.slot_id = s.id WHERE a.id = 1
                """).query(String.class).single()).isEqualTo("OPEN");
    }

    @Test
    void checkAvailabilityReturnsOnlyOpenSlotsEarliestFirst() {
        BookingAgentTools tools = agent.startTurn(account("sam.dev"), newConversation());
        String today = LocalDate.now().toString();

        var slots = tools.checkAvailability(today, LocalDate.now().plusDays(7).toString(), null, null, 25);

        // 10 seeded slots, 1 of them booked by Sam.
        assertThat(slots).hasSize(9);
        assertThat(slots).extracting(BookingAgentTools.SlotOption::start).isSorted();
        long bookedSlot = jdbc.sql("SELECT slot_id FROM appointments WHERE id = 1").query(Long.class).single();
        assertThat(slots).noneMatch(s -> s.slotId() == bookedSlot);

        var resumeOnly = tools.checkAvailability(today, null, 1L, null, null);
        assertThat(resumeOnly).isNotEmpty().allMatch(s -> s.service().equals("Resume Review"));
    }

    @Test
    void reProposingASlotTakenMeanwhileDropsThePendingBooking() {
        long slotId = openSlotId("alice.mentor", "Mock Coding Interview");
        String convo = newConversation();
        assertThat(agent.startTurn(account("sam.dev"), convo).proposeBooking(slotId, null).status())
                .isEqualTo("PENDING_CONFIRMATION");
        bookingService.book(account("jordan.dev"), new BookingRequest(slotId, serviceIdOfSlot(slotId), null));

        // Sam says yes, but the model re-proposes the slot instead of confirming: it is gone now.
        ToolResult again = agent.startTurn(account("sam.dev"), convo).proposeBooking(slotId, null);

        assertThat(again.status()).isEqualTo("UNAVAILABLE");
        assertThat(again.alternatives()).isNotEmpty();
        // No stale proposal is left for a Confirm button.
        assertThat(agent.startTurn(account("sam.dev"), convo).confirmPendingAction().status())
                .isEqualTo("NOTHING_PENDING");
        assertThat(activeBookings(slotId)).isEqualTo(1);
    }

    @Test
    void proposingATakenSlotOffersAlternatives() {
        long bookedSlot = jdbc.sql("SELECT slot_id FROM appointments WHERE id = 1").query(Long.class).single();

        ToolResult result = agent.startTurn(account("jordan.dev"), newConversation()).proposeBooking(bookedSlot, null);

        assertThat(result.status()).isEqualTo("UNAVAILABLE");
        assertThat(result.alternatives()).hasSizeBetween(1, 3);
    }
}
