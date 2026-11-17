package edu.sjsu.cmpe172.scheduler.ai.reminder;

import edu.sjsu.cmpe172.scheduler.ai.AiModels;
import edu.sjsu.cmpe172.scheduler.ai.AiUnavailableException;
import edu.sjsu.cmpe172.scheduler.ai.knowledge.KnowledgeBase;
import edu.sjsu.cmpe172.scheduler.dto.ReminderRunReport;
import edu.sjsu.cmpe172.scheduler.model.ReminderCandidate;
import edu.sjsu.cmpe172.scheduler.notification.NotificationOutbox;
import edu.sjsu.cmpe172.scheduler.notification.NotificationType;
import edu.sjsu.cmpe172.scheduler.repository.AppointmentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Agent 2, the autonomous (proactive) agent. Nobody asks it to do anything: every hour
 * (@Scheduled cron) it
 * <ol>
 *   <li>scans for BOOKED appointments starting within the next 24 hours that have no reminder yet,</li>
 *   <li>retrieves preparation tips for that session type from the handbook (RAG),</li>
 *   <li>asks Gemini to write a short personalized reminder (customer, mentor, time, their notes),</li>
 *   <li>and, in one transaction, marks the appointment reminded and puts the message in the
 *       notification outbox, from where NotificationRelay delivers it to the notification service.</li>
 * </ol>
 * POST /api/admin/agents/reminders/run runs the same code on demand (for the demo).
 */
@Component
public class ReminderAgent {

    private static final Logger log = LoggerFactory.getLogger(ReminderAgent.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEEE, MMMM d 'at' h:mm a", Locale.US);
    private static final int MAX_MESSAGE_CHARS = 1500;

    static final String SYSTEM = """
            You write appointment reminder messages for MockPrep, a platform where software engineers
            book mock interviews and resume reviews with mentors.
            Write one friendly, personalized reminder addressed to the customer by first name.
            Include the session type, the mentor's name, and the date and time exactly as given (Pacific Time).
            Add 2 or 3 concrete preparation tips that fit the session type and the customer's notes, using
            the handbook tips provided. Mention that they can cancel or reschedule from My appointments.
            At most 120 words. Plain text only: no markdown, no subject line, no placeholders like [link].
            """;

    private final AppointmentRepository appointments;
    private final NotificationOutbox outbox;
    private final AiModels ai;
    private final KnowledgeBase knowledge;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final Duration lookahead;
    private final int maxPerRun;
    private final ReentrantLock running = new ReentrantLock();

    public ReminderAgent(AppointmentRepository appointments, NotificationOutbox outbox, AiModels ai,
                         KnowledgeBase knowledge, TransactionTemplate tx, Clock clock, MeterRegistry metrics,
                         @Value("${app.ai.reminders.enabled:true}") boolean enabled,
                         @Value("${app.ai.reminders.lookahead:PT24H}") Duration lookahead,
                         @Value("${app.ai.reminders.max-per-run:20}") int maxPerRun) {
        this.appointments = appointments;
        this.outbox = outbox;
        this.ai = ai;
        this.knowledge = knowledge;
        this.tx = tx;
        this.clock = clock;
        this.metrics = metrics;
        this.enabled = enabled;
        this.lookahead = lookahead;
        this.maxPerRun = maxPerRun;
    }

    @Scheduled(cron = "${app.ai.reminders.cron:0 0 * * * *}")
    public void scheduledRun() {
        if (!enabled) {
            return;
        }
        if (!ai.available()) {
            log.debug("Reminder agent skipped: AI not configured");
            return;
        }
        run("scheduled");
    }

    /** One pass over upcoming appointments. Safe to call concurrently: a second caller waits. */
    public ReminderRunReport run(String trigger) {
        if (!ai.available()) {
            throw new AiUnavailableException("AI is not configured on this server (set GEMINI_API_KEY)");
        }
        running.lock();
        try {
            return scan(trigger);
        } finally {
            running.unlock();
        }
    }

    private ReminderRunReport scan(String trigger) {
        Instant started = clock.instant();
        LocalDateTime now = LocalDateTime.now(clock);
        List<ReminderCandidate> due = appointments.findNeedingReminder(now, now.plus(lookahead), maxPerRun);
        log.info("Reminder agent ({}): {} appointment(s) starting in the next {} h need a reminder",
                trigger, due.size(), lookahead.toHours());

        List<ReminderRunReport.Item> items = new ArrayList<>();
        int queued = 0;
        int skipped = 0;
        int failed = 0;
        for (ReminderCandidate c : due) {
            String when = WHEN.format(c.startTime());
            try {
                String message = writeReminder(c);
                // Claim + enqueue atomically: a cancelled appointment, or one another run already
                // claimed, is skipped, and a claimed one always has its message in the outbox.
                Boolean added = tx.execute(status -> appointments.markReminderSent(c.appointmentId()) == 1
                        && outbox.enqueue(c.appointmentId(), NotificationType.APPOINTMENT_REMINDER, message));
                if (Boolean.TRUE.equals(added)) {
                    queued++;
                    count("queued");
                    log.info("Reminder agent queued a reminder for appointment {} ({}, {})",
                            c.appointmentId(), c.customerName(), when);
                    items.add(new ReminderRunReport.Item(c.appointmentId(), c.customerName(), c.serviceName(), when,
                            "QUEUED", message));
                } else {
                    skipped++;
                    count("skipped");
                    items.add(new ReminderRunReport.Item(c.appointmentId(), c.customerName(), c.serviceName(), when,
                            "SKIPPED", "Cancelled or already reminded meanwhile"));
                }
            } catch (RuntimeException e) {
                // Not claimed, so the next run tries again.
                failed++;
                count("failed");
                log.warn("Reminder agent could not write a reminder for appointment {}: {}",
                        c.appointmentId(), e.getMessage());
                items.add(new ReminderRunReport.Item(c.appointmentId(), c.customerName(), c.serviceName(), when,
                        "FAILED", AiModels.callFailed(e).getMessage()));
            }
        }
        return new ReminderRunReport(trigger, started, due.size(), queued, skipped, failed, items);
    }

    /** The LLM step: context about this appointment + retrieved handbook tips -> reminder text. */
    String writeReminder(ReminderCandidate c) {
        String tips = prepTips(c.serviceName());
        String facts = """
                Customer: %s
                Session: %s (%d minutes)
                Mentor: %s, %s
                When: %s (Pacific Time)
                Customer's booking notes: %s

                Handbook preparation tips for this session type:
                %s
                """.formatted(c.customerName(), c.serviceName(), c.durationMinutes(), c.mentorName(),
                c.mentorHeadline(), WHEN.format(c.startTime()),
                c.notes() == null || c.notes().isBlank() ? "(none)" : c.notes(), tips);
        String text = ai.chatClient().prompt().system(SYSTEM).user(facts).call().content();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("The model returned an empty reminder");
        }
        text = text.trim();
        return text.length() > MAX_MESSAGE_CHARS ? text.substring(0, MAX_MESSAGE_CHARS) : text;
    }

    private String prepTips(String serviceName) {
        if (!knowledge.available()) {
            return "(none available)";
        }
        try {
            List<Document> docs = knowledge.search("How to prepare for a " + serviceName, 2);
            return docs.isEmpty() ? "(none found)"
                    : docs.stream().map(Document::getText).collect(Collectors.joining("\n\n"));
        } catch (RuntimeException e) {
            log.warn("Reminder agent: handbook search failed, writing without tips: {}", e.getMessage());
            return "(none available)";
        }
    }

    private void count(String result) {
        metrics.counter("scheduler.ai.reminders", "result", result).increment();
    }
}
