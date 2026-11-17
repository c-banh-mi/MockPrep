package edu.sjsu.cmpe172.scheduler.mocknotify;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The mock notification service's own access control (fixes the Milestone 3 finding that its
 * endpoints were public). Every /mock-notify request must carry the service's API key in the
 * X-Api-Key header, or it gets 401. That covers sending, reading the stored customer messages,
 * and the admin switch that makes the service slow or down.
 *
 * <p>It is a separate system from the scheduler, so it does not use the scheduler's user logins;
 * it trusts only callers that were given its key (here: the scheduler's notification gateway).
 */
@Component
@ConditionalOnProperty(name = "mock-notify.enabled", havingValue = "true", matchIfMissing = true)
public class MockNotifyApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Api-Key";
    private static final Logger log = LoggerFactory.getLogger(MockNotifyApiKeyFilter.class);

    private final byte[] expected;

    public MockNotifyApiKeyFilter(@Value("${mock-notify.api-key}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("mock-notify.api-key must be set");
        }
        this.expected = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/mock-notify/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String given = req.getHeader(HEADER);
        // Constant-time comparison, so response timing does not reveal how much of a guess was right.
        if (given == null || !MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8))) {
            log.warn("[mock-notify] Rejected {} {} from {}: missing or wrong API key",
                    req.getMethod(), req.getRequestURI(), req.getRemoteAddr());
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Missing or invalid API key\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
