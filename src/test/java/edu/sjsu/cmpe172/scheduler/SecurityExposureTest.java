package edu.sjsu.cmpe172.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.sjsu.cmpe172.scheduler.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Same class of problem as the Milestone 3 review finding ("public endpoints expose customer
 * messages and allow unauthorized service disruption"), checked across the WHOLE API: every
 * endpoint that reads personal data or changes something is closed to anonymous callers and to
 * the wrong roles, and tokens cannot be stretched past what the database says.
 */
class SecurityExposureTest extends IntegrationTestBase {

    @Autowired
    ObjectMapper json;

    @Value("${app.jwt.secret}")
    String jwtSecret;

    @Autowired
    JwtService jwt;

    private String bearer(String username) {
        return "Bearer " + jwt.issue(account(username)).token();
    }

    /** Every non-public endpoint, called with no token: 401, and no data in the body. */
    @ParameterizedTest(name = "{0} {1} -> 401")
    @CsvSource({
            "GET,    /api/customer/appointments",
            "POST,   /api/customer/appointments",
            "POST,   /api/customer/appointments/1/cancel",
            "GET,    /api/provider/slots",
            "POST,   /api/provider/slots",
            "DELETE, /api/provider/slots/1",
            "GET,    /api/provider/appointments",
            "POST,   /api/assistant/chat",
            "GET,    /api/assistant/chat/c0ffee00-1234",
            "DELETE, /api/assistant/chat/c0ffee00-1234",
            "POST,   /api/assistant/ask",
            "POST,   /api/admin/agents/reminders/run",
            "GET,    /api/admin/notifications",
            "GET,    /actuator/metrics",
            "GET,    /actuator/metrics/scheduler.booking",
            "GET,    /mock-notify/api/notifications",
            "PUT,    /mock-notify/admin/mode",
    })
    void anonymousCallerIsRejected(String method, String path) throws Exception {
        String body = mvc.perform(request(HttpMethod.valueOf(method), path)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("@example.com", "password", "Exception");
    }

    /** Role matrix: a valid token with the wrong role gets 403. */
    @ParameterizedTest(name = "{0} {1} {2} -> 403")
    @CsvSource({
            "sam.dev,      GET,  /api/provider/slots",
            "sam.dev,      GET,  /api/admin/notifications",
            "sam.dev,      POST, /api/admin/agents/reminders/run",
            "sam.dev,      GET,  /actuator/metrics",
            "alice.mentor, GET,  /api/customer/appointments",
            "alice.mentor, POST, /api/assistant/chat",
            "alice.mentor, GET,  /api/admin/notifications",
            "admin,        POST, /api/customer/appointments",
            "admin,        GET,  /api/provider/slots",
    })
    void wrongRoleIsForbidden(String user, String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path).header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\": \"c0ffee00-1234\", \"message\": \"hi\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyHealthAndBasicActuatorAreOpen() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        // Not exposed at all (would leak configuration and secrets).
        mvc.perform(get("/actuator/env").header("Authorization", bearer("admin"))).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/configprops").header("Authorization", bearer("admin"))).andExpect(status().isNotFound());
        // Metrics are for the operator.
        mvc.perform(get("/actuator/metrics").header("Authorization", bearer("admin"))).andExpect(status().isOk());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        // Same secret, but issued 3 hours ago with the 2-hour lifetime.
        Clock threeHoursAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(3)), ZoneOffset.UTC);
        String old = new JwtService(jwtSecret, Duration.ofHours(2), threeHoursAgo).issue(account("sam.dev")).token();

        mvc.perform(get("/api/customer/appointments").header("Authorization", "Bearer " + old))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        String foreign = new JwtService("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", Duration.ofHours(2), Clock.systemUTC())
                .issue(account("admin")).token();

        mvc.perform(get("/api/admin/notifications").header("Authorization", "Bearer " + foreign))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void roleComesFromTheDatabaseNotFromAnOldToken() throws Exception {
        String samToken = bearer("sam.dev");
        mvc.perform(get("/api/customer/appointments").header("Authorization", samToken)).andExpect(status().isOk());

        // An operator demotes/changes the account; the still-valid token must not keep the old rights.
        jdbc.sql("UPDATE users SET role = 'ADMIN' WHERE username = 'sam.dev'").update();
        mvc.perform(get("/api/customer/appointments").header("Authorization", samToken)).andExpect(status().isForbidden());

        // A deleted account's token stops working immediately.
        jdbc.sql("DELETE FROM appointments WHERE customer_id = (SELECT id FROM users WHERE username = 'jordan.dev')").update();
        String jordanToken = bearer("jordan.dev");
        jdbc.sql("DELETE FROM users WHERE username = 'jordan.dev'").update();
        mvc.perform(get("/api/customer/appointments").header("Authorization", jordanToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void oneCustomerCannotReadAnothersDataThroughAnyEndpoint() throws Exception {
        // Seed: appointment 1 (with notes) belongs to Sam.
        String jordan = bearer("jordan.dev");
        mvc.perform(get("/api/customer/appointments").param("scope", "upcoming").header("Authorization", jordan))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/customer/appointments").param("scope", "history").header("Authorization", jordan))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/customer/appointments/1/cancel").header("Authorization", jordan))
                .andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT status FROM appointments WHERE id = 1").query(String.class).single())
                .isEqualTo("BOOKED");
    }

    @Test
    void errorsNeverLeakStackTracesOrSql() throws Exception {
        String body = mvc.perform(post("/api/customer/appointments").header("Authorization", bearer("sam.dev"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slotId\": \"not-a-number\"}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("at edu.", "SELECT", "Exception", "trace");
    }
}
