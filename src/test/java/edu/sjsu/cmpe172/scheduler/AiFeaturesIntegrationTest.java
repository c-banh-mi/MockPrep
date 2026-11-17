package edu.sjsu.cmpe172.scheduler;

import edu.sjsu.cmpe172.scheduler.ai.agent.BookingAgent;
import edu.sjsu.cmpe172.scheduler.ai.reminder.ReminderAgent;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import edu.sjsu.cmpe172.scheduler.dto.ReminderRunReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Agent 2 (autonomous reminders), RAG Q&A and the chat endpoint, with the Gemini stand-ins from
 * AiTestConfig. Checks the wiring and guarantees around the LLM, not the LLM's writing.
 */
@Import(AiTestConfig.class)
@TestPropertySource(properties = {
        "app.ai.reminders.enabled=false",   // no surprise scheduled run during a test
        "app.ai.reminders.lookahead=PT72H",  // seed appointment is "tomorrow 9:00", within 72 h at any time of day
        "app.ai.rag.min-similarity=0.05",
        "app.ai.requests-per-minute=3"
})
class AiFeaturesIntegrationTest extends IntegrationTestBase {

    @Autowired
    ReminderAgent reminderAgent;

    @Autowired
    AiTestConfig.StubChatModel llm;

    @Autowired
    BookingAgent bookingAgent;

    @BeforeEach
    void resetLlm() {
        llm.reset();
    }

    private String reminderMessage(long appointmentId) {
        return jdbc.sql("""
                        SELECT message FROM notification_outbox
                         WHERE appointment_id = :id AND type = 'APPOINTMENT_REMINDER'
                        """)
                .param("id", appointmentId).query(String.class).optional().orElse(null);
    }

    // ------------------------------------------------------------ Agent 2

    @Test
    void reminderAgentWritesPersonalizedReminderWithTheLlmAndQueuesItOnce() {
        llm.answer = p -> "Hi Sam, your Resume Review with Maria is tomorrow. Bring your resume!";

        ReminderRunReport first = reminderAgent.run("test");

        assertThat(first.queued()).isEqualTo(1);
        assertThat(reminderMessage(1)).isEqualTo("Hi Sam, your Resume Review with Maria is tomorrow. Bring your resume!");
        // The LLM got the appointment's facts and the retrieved handbook tips (RAG).
        String prompt = llm.lastUserText();
        assertThat(prompt).contains("Sam Lee", "Resume Review", "Maria Gonzalez", "Targeting new-grad backend roles",
                "How to prepare: Resume Review");
        // Recorded as sent; a second run finds nothing to do (one reminder per appointment).
        assertThat(jdbc.sql("SELECT reminder_sent_at IS NOT NULL FROM appointments WHERE id = 1")
                .query(Boolean.class).single()).isTrue();
        ReminderRunReport second = reminderAgent.run("test");
        assertThat(second.scanned()).isZero();
    }

    @Test
    void reminderAgentSkipsCancelledAppointments() {
        jdbc.sql("UPDATE appointments SET status = 'CANCELLED' WHERE id = 1").update();

        assertThat(reminderAgent.run("test").scanned()).isZero();
        assertThat(reminderMessage(1)).isNull();
    }

    @Test
    void llmFailureLeavesTheAppointmentForTheNextRun() {
        llm.answer = p -> {
            throw new IllegalStateException("429 quota exceeded");
        };

        ReminderRunReport failed = reminderAgent.run("test");

        assertThat(failed.failed()).isEqualTo(1);
        assertThat(reminderMessage(1)).isNull();
        assertThat(jdbc.sql("SELECT reminder_sent_at IS NULL FROM appointments WHERE id = 1")
                .query(Boolean.class).single()).isTrue();

        llm.answer = p -> "Hi Sam, see you tomorrow.";
        assertThat(reminderAgent.run("test").queued()).isEqualTo(1);
    }

    @Test
    void scheduledAndManualRunAtTheSameTimeSendOneReminder() throws Exception {
        llm.answer = p -> {
            sleep(300);   // slow LLM, so both runs overlap
            return "Hi Sam, see you tomorrow.";
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ReminderRunReport> a = pool.submit(() -> reminderAgent.run("scheduled"));
            Future<ReminderRunReport> b = pool.submit(() -> reminderAgent.run("manual (admin)"));
            int queued = a.get(20, TimeUnit.SECONDS).queued() + b.get(20, TimeUnit.SECONDS).queued();

            assertThat(queued).isEqualTo(1);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM notification_outbox WHERE type = 'APPOINTMENT_REMINDER'")
                    .query(Integer.class).single()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void appointmentCancelledWhileTheLlmIsWritingGetsNoReminder() {
        // The customer cancels during the (slow) LLM call: the claim must fail, nothing is sent.
        llm.answer = p -> {
            jdbc.sql("UPDATE appointments SET status = 'CANCELLED' WHERE id = 1").update();
            return "Hi Sam, see you tomorrow.";
        };

        ReminderRunReport report = reminderAgent.run("test");

        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.queued()).isZero();
        assertThat(reminderMessage(1)).isNull();
    }

    @Test
    void reminderIsQueuedInTheOutboxSoItSurvivesANotificationOutage() {
        // Notifications are switched off in this test context, like a notification service that is
        // down: the reminder must still be safely stored, waiting for delivery (not lost).
        llm.answer = p -> "Hi Sam, see you tomorrow.";

        reminderAgent.run("test");

        assertThat(jdbc.sql("""
                        SELECT status FROM notification_outbox
                         WHERE idempotency_key = 'appointment-1-APPOINTMENT_REMINDER'
                        """).query(String.class).single()).isEqualTo("PENDING");
    }

    @Test
    void aiEndpointsAreRateLimitedPerUser() throws Exception {
        // One account must not be able to burn the shared Gemini quota for everyone.
        llm.answer = p -> "ok";
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/assistant/ask").with(as("maria.mentor"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"question\": \"hours?\"}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/assistant/ask").with(as("maria.mentor"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\": \"hours?\"}"))
                .andExpect(status().isTooManyRequests());
        // Other users are not affected.
        mvc.perform(post("/api/assistant/ask").with(as("raj.mentor"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\": \"hours?\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void bookingThatSucceededIsReportedEvenIfGeminiFailsAfterwards() throws Exception {
        // Turn 1 (driven directly): Jordan's agent proposed a slot.
        long slotId = openSlotId("raj.mentor", "Mock System Design");
        String convo = "c0ffee00-late-fail";
        bookingAgent.startTurn(account("jordan.dev"), convo).proposeBooking(slotId, null);

        // Turn 2: the model calls confirmPendingAction (the booking commits), then the Gemini call
        // that should phrase the answer times out.
        llm.answer = p -> {
            ToolCallingChatOptions options = (ToolCallingChatOptions) p.getOptions();
            options.getToolCallbacks().stream()
                    .filter(t -> t.getToolDefinition().name().equals("confirmPendingAction"))
                    .findFirst().orElseThrow()
                    .call("{}");
            throw new IllegalStateException("Failed to generate content: timeout");
        };

        mvc.perform(post("/api/assistant/chat").with(as("jordan.dev"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\": \"" + convo + "\", \"message\": \"yes\"}"))
                .andExpect(status().isOk())   // not a 503 that would make the user book again
                .andExpect(jsonPath("$.actionsTaken[0]").value(org.hamcrest.Matchers.startsWith("Booked appointment #")));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM appointments WHERE slot_id = :id AND status = 'BOOKED'")
                .param("id", slotId).query(Integer.class).single()).isEqualTo(1);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void adminTriggersTheAgentOverHttpOthersCannot() throws Exception {
        mvc.perform(post("/api/admin/agents/reminders/run").with(as("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trigger").value("manual (admin)"))
                .andExpect(jsonPath("$.queued").value(1))
                .andExpect(jsonPath("$.items[0].status").value("QUEUED"));
        mvc.perform(post("/api/admin/agents/reminders/run").with(as("alice.mentor")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/notifications").with(as("admin")))
                .andExpect(jsonPath("$[0].type").value("APPOINTMENT_REMINDER"));
    }

    // ------------------------------------------------------------ RAG Q&A

    @Test
    void policyQuestionIsAnsweredFromRetrievedHandbookSections() throws Exception {
        llm.answer = p -> "You can cancel for free up to 24 hours before the session.";

        mvc.perform(post("/api/assistant/ask").with(as("jordan.dev"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"What is the cancellation policy?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("You can cancel for free up to 24 hours before the session."))
                .andExpect(jsonPath("$.sources[0]").value("Cancellation policy"));

        // The handbook text was put into the prompt the model saw (grounding).
        assertThat(llm.lastUserText()).contains("Cancelling 24 hours or more before the start is free");
    }

    // ------------------------------------------------------------ Agent 1 endpoint + memory

    @Test
    void chatIsRememberedPerUserAndConversation() throws Exception {
        llm.answer = p -> "Hello! Which session would you like?";
        String convo = "c0ffee00-aaaa-bbbb";

        mvc.perform(post("/api/assistant/chat").with(as("sam.dev"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\": \"" + convo + "\", \"message\": \"I need a mock interview\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Hello! Which session would you like?"))
                .andExpect(jsonPath("$.pendingAction").doesNotExist());

        // The system prompt carries the rules and the logged-in user; the tools were offered.
        String system = llm.prompts.get(0).getSystemMessage().getText();
        assertThat(system).contains("Sam Lee", "Never book on the first request");

        // Persisted in PostgreSQL (bonus: memory survives restarts); scoped to Sam.
        assertThat(jdbc.sql("SELECT COUNT(*) FROM spring_ai_chat_memory WHERE conversation_id LIKE :id")
                .param("id", "%:" + convo).query(Integer.class).single()).isEqualTo(2);
        mvc.perform(get("/api/assistant/chat/" + convo).with(as("sam.dev")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[1].role").value("assistant"));
        mvc.perform(get("/api/assistant/chat/" + convo).with(as("jordan.dev")))
                .andExpect(jsonPath("$.length()").value(0));

        // Second turn: the model sees the earlier messages.
        mvc.perform(post("/api/assistant/chat").with(as("sam.dev"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\": \"" + convo + "\", \"message\": \"System design please\"}"))
                .andExpect(status().isOk());
        assertThat(llm.prompts.get(1).getInstructions()).anySatisfy(m ->
                assertThat(m.getText()).isEqualTo("I need a mock interview"));
    }
}
