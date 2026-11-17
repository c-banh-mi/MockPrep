package edu.sjsu.cmpe172.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A booking still succeeds when the notification service is down. "Down" here is real: the
 * gateway points at a port where nothing listens, so every call gets "connection refused".
 */
@TestPropertySource(properties = {
        "notification.enabled=true",
        "notification.base-url=http://127.0.0.1:1/mock-notify",
        "notification.backoff=10ms",
        "notification.relay-interval=PT1H",
        "notification.relay-initial-delay=PT1H"
})
class NotificationDownIntegrationTest extends IntegrationTestBase {

    @Autowired
    ObjectMapper json;

    @Autowired
    MeterRegistry metrics;

    private Integer attempts(long appointmentId, String type) {
        return jdbc.sql("""
                        SELECT attempts FROM notification_outbox
                         WHERE appointment_id = :id AND type = :type AND status = 'PENDING'
                        """)
                .param("id", appointmentId).param("type", type)
                .query(Integer.class).optional().orElse(null);
    }

    @Test
    void bookingAndCancelSucceedWhileNotificationServiceIsDown() throws Exception {
        long slotId = openSlotId("alice.mentor", "Mock Coding Interview");
        double parkedBefore = metrics.counter("scheduler.notifications",
                "result", "parked", "type", "BOOKING_CONFIRMED").count();

        // Book: 201 and the appointment is really in the database.
        String body = mvc.perform(post("/api/customer/appointments").with(as("jordan.dev"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotId\": %d, \"serviceId\": %d}".formatted(slotId, serviceIdOfSlot(slotId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("BOOKED"))
                .andReturn().getResponse().getContentAsString();
        long apptId = json.readTree(body).get("id").asLong();
        mvc.perform(get("/api/customer/appointments").with(as("jordan.dev")))
                .andExpect(jsonPath("$[0].id").value(apptId));

        // The confirmation was committed with the booking; its first burst of 3 tries failed,
        // so it stays PENDING in the outbox for a later retry.
        await().atMost(Duration.ofSeconds(10)).until(() -> Integer.valueOf(3).equals(attempts(apptId, "BOOKING_CONFIRMED")));
        assertThat(metrics.counter("scheduler.notifications",
                "result", "parked", "type", "BOOKING_CONFIRMED").count()).isEqualTo(parkedBefore + 1);

        // Cancel works too; its notification is queued behind the confirmation.
        mvc.perform(post("/api/customer/appointments/{id}/cancel", apptId).with(as("jordan.dev")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(attempts(apptId, "BOOKING_CANCELLED")).isZero();

        // The app reports itself UP (bookings work) with notifications DEGRADED.
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database.status").value("UP"))
                .andExpect(jsonPath("$.notifications.status").value("DEGRADED"))
                .andExpect(jsonPath("$.notifications.pending").value(2))
                .andExpect(jsonPath("$.notifications.retrying").value(1));
    }
}
