package edu.sjsu.cmpe172.scheduler.ai.agent;

import edu.sjsu.cmpe172.scheduler.ai.knowledge.KnowledgeBase;
import edu.sjsu.cmpe172.scheduler.dto.AppointmentDto;
import edu.sjsu.cmpe172.scheduler.dto.BookingRequest;
import edu.sjsu.cmpe172.scheduler.dto.ProviderDto;
import edu.sjsu.cmpe172.scheduler.dto.ServiceDto;
import edu.sjsu.cmpe172.scheduler.dto.SlotDto;
import edu.sjsu.cmpe172.scheduler.dto.SlotFilter;
import edu.sjsu.cmpe172.scheduler.exception.ConflictException;
import edu.sjsu.cmpe172.scheduler.exception.ForbiddenException;
import edu.sjsu.cmpe172.scheduler.exception.NotFoundException;
import edu.sjsu.cmpe172.scheduler.exception.SlotUnavailableException;
import edu.sjsu.cmpe172.scheduler.model.AppUser;
import edu.sjsu.cmpe172.scheduler.service.AppointmentService;
import edu.sjsu.cmpe172.scheduler.service.BookingService;
import edu.sjsu.cmpe172.scheduler.service.CatalogService;
import edu.sjsu.cmpe172.scheduler.service.SlotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The tools Gemini may call. A NEW instance is created for every chat request, bound to the
 * logged-in user, so no tool takes a user id: the model cannot act as anyone else.
 *
 * <p>Every tool is a thin wrapper over the existing service layer. Writes go through
 * BookingService / AppointmentService, exactly like the REST endpoints, so the same rules
 * apply: optimistic-lock booking transaction + unique index (no double booking), customer-only
 * booking, owner-only cancel, no overlapping sessions.
 *
 * <p>Confirm-before-write is enforced in code, not only in the prompt: proposeBooking /
 * proposeCancellation just store a {@link PendingAction}; confirmPendingAction refuses to run
 * in the same turn as the proposal, so the user must have answered the summary first.
 */
public class BookingAgentTools {

    private static final Logger log = LoggerFactory.getLogger(BookingAgentTools.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final int MAX_RANGE_DAYS = 31;
    private static final int PAGE = 50;

    // What the model gets back. Times are pre-formatted strings, in Pacific Time.
    public record SlotOption(long slotId, long serviceId, String service, long mentorId, String mentor,
                             String when, String start, int minutes, String price) {
    }

    public record AppointmentInfo(long appointmentId, String service, String mentor, String when, String status,
                                  String notes) {
    }

    public record Catalog(List<ServiceDto> services, List<ProviderDto> mentors) {
    }

    public record PolicySnippet(String section, String text) {
    }

    /** status: PENDING_CONFIRMATION, DONE, NOT_CONFIRMED_YET, UNAVAILABLE, REFUSED, NOTHING_PENDING. */
    public record ToolResult(String status, String message, List<SlotOption> alternatives) {
        static ToolResult of(String status, String message) {
            return new ToolResult(status, message, List.of());
        }
    }

    private final AppUser user;
    private final AgentSessions.Session session;
    private final int turn;
    private final SlotService slots;
    private final CatalogService catalog;
    private final AppointmentService appointments;
    private final BookingService booking;
    private final KnowledgeBase knowledge;
    private final Clock clock;
    private final List<String> actionsTaken = new ArrayList<>();

    BookingAgentTools(AppUser user, AgentSessions.Session session, int turn, SlotService slots,
                      CatalogService catalog, AppointmentService appointments, BookingService booking,
                      KnowledgeBase knowledge, Clock clock) {
        this.user = user;
        this.session = session;
        this.turn = turn;
        this.slots = slots;
        this.catalog = catalog;
        this.appointments = appointments;
        this.booking = booking;
        this.knowledge = knowledge;
        this.clock = clock;
    }

    public List<String> actionsTaken() {
        return List.copyOf(actionsTaken);
    }

    int turn() {
        return turn;
    }

    // ---------------------------------------------------------------- read-only tools

    @Tool(description = "List MockPrep's services (id, name, duration, price) and mentors (id, name, headline). "
            + "Use it to map a service or mentor name the user mentions to its id.")
    public Catalog listServicesAndMentors() {
        log.info("Agent tool listServicesAndMentors (user {})", user.username());
        return new Catalog(catalog.listServices(), catalog.listProviders());
    }

    @Tool(description = "Check availability: OPEN slots that can be booked, earliest first, between fromDate and "
            + "toDate inclusive (Pacific Time). Optionally filter by serviceId and/or mentorId.")
    public List<SlotOption> checkAvailability(
            @ToolParam(description = "First day to search, yyyy-MM-dd. Use today's date for 'soonest'.") String fromDate,
            @ToolParam(description = "Last day to search, yyyy-MM-dd (at most 31 days after fromDate). "
                    + "Defaults to fromDate + 6 days.", required = false) String toDate,
            @ToolParam(description = "Only slots for this service id", required = false) Long serviceId,
            @ToolParam(description = "Only slots with this mentor (provider) id", required = false) Long mentorId,
            @ToolParam(description = "Maximum number of slots to return, default 10", required = false) Integer maxResults) {
        LocalDate today = LocalDate.now(clock);
        LocalDate from = parseDate(fromDate, today);
        if (from.isBefore(today)) {
            from = today;
        }
        LocalDate to = toDate == null || toDate.isBlank() ? from.plusDays(6) : parseDate(toDate, from.plusDays(6));
        if (to.isBefore(from)) {
            to = from;
        }
        if (to.isAfter(from.plusDays(MAX_RANGE_DAYS))) {
            to = from.plusDays(MAX_RANGE_DAYS);
        }
        int limit = maxResults == null || maxResults < 1 ? 10 : Math.min(maxResults, 25);
        log.info("Agent tool checkAvailability {}..{} service={} mentor={} (user {})",
                from, to, serviceId, mentorId, user.username());
        return findOpen(from, to, serviceId, mentorId, limit);
    }

    @Tool(description = "List the current user's own appointments. scope: 'upcoming' (default) or 'history'.")
    public List<AppointmentInfo> listMyAppointments(
            @ToolParam(description = "upcoming or history", required = false) String scope) {
        String s = "history".equalsIgnoreCase(scope) ? "history" : "upcoming";
        log.info("Agent tool listMyAppointments {} (user {})", s, user.username());
        return appointments.listForCustomer(user, s).stream().map(BookingAgentTools::info).toList();
    }

    @Tool(description = "Search the MockPrep policy and FAQ handbook (hours, cancellation and refund policy, prices, "
            + "how to prepare, rules). Answer policy questions only from what this returns.")
    public List<PolicySnippet> searchPolicies(@ToolParam(description = "The user's question") String question) {
        log.info("Agent tool searchPolicies \"{}\" (user {})", question, user.username());
        return knowledge.search(question, 3).stream()
                .map(d -> new PolicySnippet(KnowledgeBase.section(d), d.getText()))
                .toList();
    }

    // ---------------------------------------------------------------- write tools (two-step)

    @Tool(description = "Step 1 of booking. Checks that the slot is open and saves it as a booking WAITING FOR THE "
            + "USER'S CONFIRMATION. It does NOT book. Afterwards, show the returned summary to the user and ask "
            + "them to confirm. If the slot is unavailable, offer the returned alternatives.")
    public ToolResult proposeBooking(
            @ToolParam(description = "The slotId from checkAvailability") long slotId,
            @ToolParam(description = "Optional notes for the mentor, e.g. target role", required = false) String notes) {
        log.info("Agent tool proposeBooking slot {} (user {}, turn {})", slotId, user.username(), turn);
        SlotDto slot;
        try {
            slot = slots.findOpenSlot(slotId);
        } catch (NotFoundException e) {
            return new ToolResult("UNAVAILABLE", "Slot " + slotId + " is not available (booked, removed or past).",
                    alternativesFor(null));
        }
        String cleanNotes = notes == null || notes.isBlank() ? null
                : notes.substring(0, Math.min(notes.length(), 500));
        String summary = "Book " + slot.serviceName() + " with " + slot.providerName() + " on "
                + range(slot.startTime(), slot.endTime()) + " (" + slot.durationMinutes() + " min, $"
                + slot.price() + ")" + (cleanNotes == null ? "" : ", notes: \"" + cleanNotes + "\"");
        PendingAction before = session.pending();
        if (before != null && before.type() == PendingAction.Type.BOOK && before.slotId() == slot.id()) {
            // The model re-proposed the same booking (common when the user answers "yes"). Keep the
            // original turn: the user already saw this summary, so their "yes" still counts.
            return ToolResult.of("PENDING_CONFIRMATION", before.summary()
                    + ". Already waiting for confirmation: if the user just said yes, call confirmPendingAction.");
        }
        session.setPending(PendingAction.book(slot.id(), slot.serviceId(), cleanNotes, summary, turn));
        return ToolResult.of("PENDING_CONFIRMATION", summary + ". Not booked yet: ask the user to confirm.");
    }

    @Tool(description = "Step 1 of cancelling. Checks that the appointment is one of the user's upcoming appointments "
            + "and saves the cancellation WAITING FOR THE USER'S CONFIRMATION. It does NOT cancel.")
    public ToolResult proposeCancellation(
            @ToolParam(description = "The appointmentId from listMyAppointments") long appointmentId) {
        log.info("Agent tool proposeCancellation appointment {} (user {}, turn {})", appointmentId, user.username(), turn);
        AppointmentDto appt = appointments.listForCustomer(user, "upcoming").stream()
                .filter(a -> a.id() == appointmentId)
                .findFirst().orElse(null);
        if (appt == null) {
            return ToolResult.of("REFUSED", "Appointment " + appointmentId
                    + " is not one of your upcoming appointments, so it cannot be cancelled.");
        }
        String summary = "Cancel " + appt.serviceName() + " with " + appt.providerName() + " on "
                + range(appt.startTime(), appt.endTime()) + " (appointment #" + appt.id() + ")";
        PendingAction before = session.pending();
        if (before != null && before.type() == PendingAction.Type.CANCEL && before.appointmentId() == appt.id()) {
            return ToolResult.of("PENDING_CONFIRMATION", before.summary()
                    + ". Already waiting for confirmation: if the user just said yes, call confirmPendingAction.");
        }
        session.setPending(PendingAction.cancel(appt.id(), summary, turn));
        return ToolResult.of("PENDING_CONFIRMATION", summary + ". Not cancelled yet: ask the user to confirm.");
    }

    @Tool(description = "Step 2: carry out the pending booking or cancellation. Call it ONLY when the user's latest "
            + "message clearly confirms the summary you showed (e.g. 'yes', 'confirm', 'book it').")
    public ToolResult confirmPendingAction() {
        AgentSessions.Take take = session.take(turn);
        if (take.action() == null) {
            return ToolResult.of("NOTHING_PENDING", "There is nothing waiting for confirmation. Propose it first.");
        }
        if (!take.run()) {
            // The guard that makes "confirm before write" a rule of the code, not just of the prompt.
            log.warn("Agent tried to confirm in the same turn as the proposal (user {}, turn {}); refused",
                    user.username(), turn);
            return ToolResult.of("NOT_CONFIRMED_YET", "The user has not confirmed yet. Show this summary and ask: "
                    + take.action().summary());
        }
        PendingAction pending = take.action();
        return pending.type() == PendingAction.Type.BOOK ? book(pending) : cancel(pending);
    }

    @Tool(description = "Discard the pending booking or cancellation, when the user declines or changes their mind.")
    public ToolResult discardPendingAction() {
        PendingAction pending = session.pending();
        session.setPending(null);
        log.info("Agent tool discardPendingAction (user {})", user.username());
        return pending == null ? ToolResult.of("NOTHING_PENDING", "Nothing was pending.")
                : ToolResult.of("DONE", "Discarded: " + pending.summary());
    }

    // ---------------------------------------------------------------- the actual writes

    private ToolResult book(PendingAction p) {
        try {
            // The same transactional service method the REST endpoint uses.
            AppointmentDto appt = booking.book(user, new BookingRequest(p.slotId(), p.serviceId(), p.notes()));
            String done = "Booked appointment #" + appt.id() + ": " + appt.serviceName() + " with "
                    + appt.providerName() + " on " + range(appt.startTime(), appt.endTime());
            actionsTaken.add(done);
            log.info("Agent booked slot {} for {} (appointment {})", p.slotId(), user.username(), appt.id());
            return ToolResult.of("DONE", done + ". A confirmation message is on its way.");
        } catch (SlotUnavailableException | NotFoundException e) {
            log.info("Agent booking of slot {} for {} failed: {}", p.slotId(), user.username(), e.getMessage());
            return new ToolResult("UNAVAILABLE", "Sorry, that slot was just taken or is no longer available ("
                    + e.getMessage() + "). Offer these alternatives:", alternativesFor(p.serviceId()));
        } catch (ConflictException | ForbiddenException | IllegalArgumentException e) {
            return ToolResult.of("REFUSED", e.getMessage());
        }
    }

    private ToolResult cancel(PendingAction p) {
        try {
            AppointmentDto appt = appointments.cancel(user, p.appointmentId());
            String done = "Cancelled appointment #" + appt.id() + ": " + appt.serviceName() + " with "
                    + appt.providerName() + " on " + range(appt.startTime(), appt.endTime());
            actionsTaken.add(done);
            log.info("Agent cancelled appointment {} for {}", p.appointmentId(), user.username());
            return ToolResult.of("DONE", done + ". The slot is open again.");
        } catch (ConflictException | ForbiddenException | NotFoundException e) {
            return ToolResult.of("REFUSED", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    private List<SlotOption> alternativesFor(Long serviceId) {
        LocalDate today = LocalDate.now(clock);
        return findOpen(today, today.plusDays(14), serviceId, null, 3);
    }

    /** Pages through SlotService (SQL LIMIT/OFFSET) day by day, earliest first. */
    private List<SlotOption> findOpen(LocalDate from, LocalDate to, Long serviceId, Long mentorId, int limit) {
        List<SlotOption> found = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to) && found.size() < limit; day = day.plusDays(1)) {
            List<SlotDto> items = slots.findOpenSlots(new SlotFilter(mentorId, serviceId, day), 0, PAGE).items();
            for (SlotDto s : items) {
                if (found.size() == limit) {
                    break;
                }
                found.add(new SlotOption(s.id(), s.serviceId(), s.serviceName(), s.providerId(), s.providerName(),
                        range(s.startTime(), s.endTime()), s.startTime().toString(), s.durationMinutes(),
                        "$" + s.price()));
            }
        }
        return found;
    }

    private static AppointmentInfo info(AppointmentDto a) {
        return new AppointmentInfo(a.id(), a.serviceName(), a.providerName(), range(a.startTime(), a.endTime()),
                a.status(), a.notes());
    }

    static String range(LocalDateTime start, LocalDateTime end) {
        return WHEN.format(start) + " - " + TIME.format(end) + " PT";
    }

    private static LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value.trim().substring(0, Math.min(10, value.trim().length())));
        } catch (DateTimeParseException e) {
            return Objects.requireNonNull(fallback);
        }
    }
}
