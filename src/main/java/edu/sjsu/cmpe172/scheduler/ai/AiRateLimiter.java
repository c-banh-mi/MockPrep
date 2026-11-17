package edu.sjsu.cmpe172.scheduler.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user limit on AI requests (sliding one-minute window). Every AI call spends the shared
 * Gemini quota, so without a limit one account could exhaust it and switch the AI off for
 * everyone (a denial of service). Over the limit: HTTP 429.
 */
@Component
public class AiRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final int perMinute;
    private final Clock clock;
    private final Map<Long, Deque<Instant>> calls = new ConcurrentHashMap<>();

    public AiRateLimiter(@Value("${app.ai.requests-per-minute:10}") int perMinute, Clock clock) {
        this.perMinute = perMinute;
        this.clock = clock;
    }

    /** Records one request for this user, or throws if they are over the limit. */
    public void check(long userId) {
        Instant now = clock.instant();
        Deque<Instant> recent = calls.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minus(WINDOW))) {
                recent.pollFirst();
            }
            if (recent.size() >= perMinute) {
                throw new AiRateLimitException("Too many AI requests: limit is " + perMinute
                        + " per minute. Please wait a moment.");
            }
            recent.addLast(now);
        }
    }

    public static class AiRateLimitException extends RuntimeException {
        public AiRateLimitException(String message) {
            super(message);
        }
    }
}
