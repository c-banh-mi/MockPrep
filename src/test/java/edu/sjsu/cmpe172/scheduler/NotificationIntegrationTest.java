package edu.sjsu.cmpe172.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.sjsu.cmpe172.scheduler.mocknotify.MockNotificationStore;
import edu.sjsu.cmpe172.scheduler.notification.NotificationRelay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real distribution boundary: the app runs on a random port and its gateway makes real HTTP
 * calls (with the API key) to the mock notification service, with real timeouts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "notification.enabled=true",
        "notification.api-key=test-notify-key",
        "notification.read-timeout=300ms",
        "notification.backoff=10ms",
        "notification.relay-interval=PT1H",
        "notification.relay-initial-delay=PT1H"   // tests call the relay themselves
})
class NotificationIntegrationTest extends IntegrationTestBase {

    private static final String KEY = "test-notify-key";

    @Autowired
    ObjectMapper json;

    @Autowired
    MockNotificationStore mockService;

    @Autowired
    NotificationRelay relay;

    @BeforeEach
    void resetMockService() {
        // A SLOW-mode request from the previous test may still be sleeping; ids restart with the
        // database, so letting it finish later would store a key this test also uses.
        await().atMost(Duration.ofSeconds(10)).until(() -> mockService.inFlight().get() == 0);
        mockService.clear();
    }

    private long book(String username, long slotId) throws Exception {
        String body = mvc.perform(post("/api/customer/appointments").with(as(username))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotId\": %d, \"serviceId\": %d}".formatted(slotId, serviceIdOfSlot(slotId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    private void cancel(String username, long apptId) throws Exception {
        mvc.perform(post("/api/customer/appointments/{id}/cancel", apptId).with(as(username)))
                .andExpect(status().isOk());
    }

    private long storedWithKey(String key) {
        return mockService.all().stream().filter(s -> s.message().idempotencyKey().equals(key)).count();
    }

    private String outboxStatus(String key) {
        return jdbc.sql("SELECT status FROM notification_outbox WHERE idempotency_key = :k")
                .param("k", key).query(String.class).optional().orElse(null);
    }

    private int outboxAttempts(String key) {
        return jdbc.sql("SELECT attempts FROM notification_outbox WHERE idempotency_key = :k")
                .param("k", key).query(Integer.class).single();
    }

    /** Skip the wait before the next retry (instead of sleeping 30 s in a test). */
    private void makeAllDue() {
        jdbc.sql("UPDATE notification_outbox SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'").update();
    }

    @Test
    void bookAndCancelEachSendOneNotificationInOrder() throws Exception {
        long apptId = book("jordan.dev", openSlotId("alice.mentor", "Mock Coding Interview"));

        await().atMost(Duration.ofSeconds(10)).until(() -> mockService.all().size() == 1);
        var confirmed = mockService.all().get(0).message();
        assertThat(confirmed.idempotencyKey()).isEqualTo("appointment-" + apptId + "-BOOKING_CONFIRMED");
        assertThat(confirmed.recipient()).isEqualTo("jordan@example.com");
        assertThat(confirmed.message()).contains("Mock Coding Interview", "Alice Nguyen", "confirmed");

        cancel("jordan.dev", apptId);

        await().atMost(Duration.ofSeconds(10)).until(() -> mockService.all().size() == 2);
        assertThat(mockService.all().get(1).message().type()).isEqualTo("BOOKING_CANCELLED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notification_outbox WHERE status = 'SENT'")
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void outageKeepsNotificationsInOrderUntilTheServiceIsBack() throws Exception {
        mockService.setMode(MockNotificationStore.Mode.DOWN, null);
        long apptId = book("jordan.dev", openSlotId("maria.mentor", "Resume Review"));
        String confirmedKey = "appointment-" + apptId + "-BOOKING_CONFIRMED";
        String cancelledKey = "appointment-" + apptId + "-BOOKING_CANCELLED";
        await().atMost(Duration.ofSeconds(10)).until(() -> outboxAttempts(confirmedKey) == 3);

        cancel("jordan.dev", apptId);
        relay.drain();

        // The cancellation waits behind the undelivered confirmation: it was not even attempted.
        assertThat(outboxStatus(cancelledKey)).isEqualTo("PENDING");
        assertThat(outboxAttempts(cancelledKey)).isZero();
        assertThat(mockService.all()).isEmpty();

        mockService.setMode(MockNotificationStore.Mode.UP, null);
        makeAllDue();
        relay.drain();

        List<String> delivered = mockService.all().stream().map(s -> s.message().type()).toList();
        assertThat(delivered).containsExactly("BOOKING_CONFIRMED", "BOOKING_CANCELLED");
        assertThat(outboxStatus(confirmedKey)).isEqualTo("SENT");
        assertThat(outboxAttempts(confirmedKey)).isEqualTo(4);   // 3 failed + 1 delivered
        assertThat(outboxStatus(cancelledKey)).isEqualTo("SENT");
    }

    @Test
    void committedNotificationSurvivesACrashBeforeSending() {
        // Simulate a crash right after a booking committed: the outbox row exists, but no thread
        // ever got the in-memory "send it now" event. Seed data: Sam's appointment #1.
        jdbc.sql("""
                INSERT INTO notification_outbox (idempotency_key, appointment_id, recipient, type, message)
                VALUES ('appointment-1-BOOKING_CONFIRMED', 1, 'sam@example.com', 'BOOKING_CONFIRMED',
                        'Hi Sam Lee, your Resume Review is confirmed (appointment #1).')
                """).update();
        assertThat(mockService.all()).isEmpty();

        relay.poll();   // what the scheduled poll does after a restart

        assertThat(storedWithKey("appointment-1-BOOKING_CONFIRMED")).isEqualTo(1);
        assertThat(outboxStatus("appointment-1-BOOKING_CONFIRMED")).isEqualTo("SENT");
    }

    @Test
    void slowServiceTimesOutButBookingIsFastAndRetryCausesNoDuplicate() throws Exception {
        // The service takes 1 s to answer; the gateway gives up after 300 ms.
        mockService.setMode(MockNotificationStore.Mode.SLOW, Duration.ofSeconds(1));

        long started = System.nanoTime();
        long apptId = book("sam.dev", openSlotId("raj.mentor", "Mock System Design"));
        long bookingMs = (System.nanoTime() - started) / 1_000_000;
        // The customer does not wait for the notification service.
        assertThat(bookingMs).isLessThan(1000);

        String key = "appointment-" + apptId + "-BOOKING_CONFIRMED";
        await().atMost(Duration.ofSeconds(10)).until(() -> outboxAttempts(key) == 3);
        assertThat(outboxStatus(key)).isEqualTo("PENDING");

        // The slow service did process the timed-out requests eventually...
        await().atMost(Duration.ofSeconds(10)).until(() -> storedWithKey(key) == 1);

        // ...so the retry is answered DUPLICATE and the customer is not notified twice.
        mockService.setMode(MockNotificationStore.Mode.UP, null);
        makeAllDue();
        relay.drain();

        assertThat(outboxStatus(key)).isEqualTo("SENT");
        assertThat(storedWithKey(key)).isEqualTo(1);
        assertThat(mockService.requestCount()).isEqualTo(4);   // 3 timed-out attempts + 1 retry

        mvc.perform(get("/health")).andExpect(jsonPath("$.notifications.status").value("UP"));
    }

    @Test
    void mockServiceRejectsCallersWithoutItsApiKey() throws Exception {
        // Reading customers' messages, switching the service off, or sending: all 401 without the key.
        mvc.perform(get("/mock-notify/api/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/mock-notify/api/notifications").header("X-Api-Key", "guess"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/mock-notify/admin/mode").param("mode", "DOWN")).andExpect(status().isUnauthorized());
        mvc.perform(post("/mock-notify/api/notifications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"x\",\"recipient\":\"a@b.c\",\"type\":\"T\",\"message\":\"spam\"}"))
                .andExpect(status().isUnauthorized());
        // A logged-in customer of the scheduler is not a caller of the notification service either.
        mvc.perform(get("/mock-notify/api/notifications").with(as("sam.dev"))).andExpect(status().isUnauthorized());

        assertThat(mockService.mode()).isEqualTo(MockNotificationStore.Mode.UP);
        assertThat(mockService.all()).isEmpty();

        mvc.perform(get("/mock-notify/api/notifications").header("X-Api-Key", KEY)).andExpect(status().isOk());
        mvc.perform(get("/mock-notify/admin/mode").header("X-Api-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("UP"));
    }

    @Test
    void healthEndpointReportsDatabase() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database.status").value("UP"))
                .andExpect(jsonPath("$.database.responseTimeMs").isNumber());
    }

    @Test
    void bookingMetricIsExposed() throws Exception {
        book("jordan.dev", openSlotId("alice.mentor", "Resume Review"));

        mvc.perform(get("/actuator/metrics/scheduler.booking").param("tag", "outcome:booked").with(as("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[?(@.statistic == 'COUNT')].value").isNotEmpty());
    }
}
