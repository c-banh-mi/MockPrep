package edu.sjsu.cmpe172.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** JWT login, BCrypt storage, and role-based access (401 vs 403), with real tokens. */
class AuthIntegrationTest extends IntegrationTestBase {

    @Autowired
    ObjectMapper json;

    private JsonNode loginResponse(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"%s\", \"password\": \"%s\"}".formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private String bearer(String username) throws Exception {
        return "Bearer " + loginResponse(username, "password123").get("token").asText();
    }

    @Test
    void passwordsAreStoredAsBcryptHashes() {
        String hash = jdbc.sql("SELECT password_hash FROM users WHERE username = 'sam.dev'")
                .query(String.class).single();
        assertThat(hash).startsWith("$2a$10$").isNotEqualTo("password123");
    }

    @Test
    void loginReturnsSignedJwtWithRoleFromDatabase() throws Exception {
        JsonNode login = loginResponse("alice.mentor", "password123");
        assertThat(login.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(login.get("user").get("role").asText()).isEqualTo("PROVIDER");
        assertThat(login.get("user").has("passwordHash")).isFalse();
        // header.payload.signature
        String token = login.get("token").asText();
        assertThat(token.split("\\.")).hasSize(3);

        String sam = bearer("sam.dev");
        mvc.perform(get("/api/auth/me").header("Authorization", sam))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("sam.dev"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
        mvc.perform(get("/api/customer/appointments").header("Authorization", sam))
                .andExpect(status().isOk());
    }

    @Test
    void serverKeepsNoSession() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"sam.dev\", \"password\": \"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSame401() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"sam.dev\", \"password\": \"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"nobody\", \"password\": \"password123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    @Test
    void tamperedOrMissingTokenIs401() throws Exception {
        // Forge the payload: keep Sam's signature but claim to be the admin. The signature no longer
        // matches the content, so the server rejects it.
        String[] parts = bearer("sam.dev").substring("Bearer ".length()).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"sam.dev\"", "\"admin\"").replace("CUSTOMER", "ADMIN");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        mvc.perform(get("/api/admin/notifications").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(get("/api/customer/appointments").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousUserIs401OnProtectedEndpoints() throws Exception {
        mvc.perform(get("/api/customer/appointments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(get("/api/provider/slots")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/agents/reminders/run")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/assistant/ask").contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"hours?\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isNoContent());
    }

    @Test
    void customerOnProviderEndpointIs403() throws Exception {
        String sam = bearer("sam.dev");
        mvc.perform(get("/api/provider/slots").header("Authorization", sam))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        mvc.perform(post("/api/provider/slots").header("Authorization", sam)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceId\": 1, \"startTime\": \"2030-01-01T09:00:00\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/agents/reminders/run").header("Authorization", sam))
                .andExpect(status().isForbidden());
    }

    @Test
    void providerOnCustomerEndpointsIs403() throws Exception {
        long slotId = openSlotId("raj.mentor", "Mock System Design");
        String alice = bearer("alice.mentor");
        mvc.perform(post("/api/customer/appointments").header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotId\": " + slotId + ", \"serviceId\": " + serviceIdOfSlot(slotId) + "}"))
                .andExpect(status().isForbidden());
        // The booking agent acts as a customer, so a provider cannot use it either.
        mvc.perform(post("/api/assistant/chat").header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\": \"c0ffee00-1234\", \"message\": \"book something\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanSeeTheOutbox() throws Exception {
        mvc.perform(get("/api/admin/notifications").header("Authorization", bearer("admin")))
                .andExpect(status().isOk());
    }

    @Test
    void aiEndpointsAnswer503WhenGeminiIsNotConfigured() throws Exception {
        // No GEMINI_API_KEY in tests: the app runs, AI features say so instead of failing.
        mvc.perform(post("/api/assistant/chat").header("Authorization", bearer("sam.dev"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\": \"c0ffee00-1234\", \"message\": \"hi\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("AI is not configured on this server (set GEMINI_API_KEY)"));
        mvc.perform(post("/api/admin/agents/reminders/run").header("Authorization", bearer("admin")))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void publicEndpointsNeedNoLogin() throws Exception {
        mvc.perform(get("/api/slots")).andExpect(status().isOk());
        mvc.perform(get("/api/home")).andExpect(status().isOk());
        mvc.perform(get("/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void corsAllowsTheFrontendOrigin() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/customer/appointments")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/customer/appointments")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }
}
