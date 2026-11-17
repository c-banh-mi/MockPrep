package edu.sjsu.cmpe172.scheduler.controller;

import edu.sjsu.cmpe172.scheduler.ai.reminder.ReminderAgent;
import edu.sjsu.cmpe172.scheduler.dto.ReminderRunReport;
import edu.sjsu.cmpe172.scheduler.model.AppUser;
import edu.sjsu.cmpe172.scheduler.notification.OutboxEntry;
import edu.sjsu.cmpe172.scheduler.notification.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Admin-only operations (SecurityConfig requires ROLE_ADMIN for /api/admin/**). */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final ReminderAgent reminders;
    private final OutboxRepository outbox;

    public AdminController(ReminderAgent reminders, OutboxRepository outbox) {
        this.reminders = reminders;
        this.outbox = outbox;
    }

    /** Manual trigger for the autonomous reminder agent (same code as the hourly @Scheduled run). */
    @PostMapping("/agents/reminders/run")
    public ReminderRunReport runReminderAgent(@AuthenticationPrincipal(expression = "user") AppUser me) {
        log.info("Reminder agent run requested by {}", me.username());
        return reminders.run("manual (" + me.username() + ")");
    }

    /** The latest notifications in the outbox: confirmations, cancellations, AI reminders. */
    @GetMapping("/notifications")
    public List<OutboxEntry> recentNotifications(@RequestParam(defaultValue = "30") int limit) {
        return outbox.findRecent(Math.max(1, Math.min(limit, 200)));
    }
}
