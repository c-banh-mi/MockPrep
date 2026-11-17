package edu.sjsu.cmpe172.scheduler.ai.agent;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-conversation agent state that must NOT come from the model: the turn counter and the
 * pending (unconfirmed) action. The chat messages themselves are in Spring AI's chat memory.
 * In memory: after a restart a pending action is gone and the user simply asks again.
 */
@Component
public class AgentSessions {

    /** An unconfirmed booking older than this is dropped (the slot may have changed meanwhile). */
    static final Duration PENDING_TTL = Duration.ofMinutes(15);
    private static final Duration IDLE_TTL = Duration.ofHours(2);

    public static final class Session {
        private int turn;
        private PendingAction pending;
        private volatile Instant lastUsed = Instant.now();

        /** Called once at the start of every user message. */
        synchronized int nextTurn() {
            lastUsed = Instant.now();
            return ++turn;
        }

        synchronized PendingAction pending() {
            if (pending != null && pending.createdAt().plus(PENDING_TTL).isBefore(Instant.now())) {
                pending = null;
            }
            return pending;
        }

        synchronized void setPending(PendingAction action) {
            this.pending = action;
        }

        /**
         * Atomically hands out the pending action for execution, or explains why not. Two confirms
         * racing each other (a double-clicked button) get it at most once.
         */
        synchronized Take take(int currentTurn) {
            PendingAction p = pending();
            if (p == null) {
                return new Take(null, false);
            }
            if (p.proposedInTurn() >= currentTurn) {
                return new Take(p, false);   // not confirmed by the user yet; keep it pending
            }
            pending = null;
            return new Take(p, true);
        }
    }

    /** Result of {@link Session#take}: run = true means the caller now owns the action and must run it. */
    record Take(PendingAction action, boolean run) {
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public Session get(String conversationId) {
        return sessions.computeIfAbsent(conversationId, id -> new Session());
    }

    public void remove(String conversationId) {
        sessions.remove(conversationId);
    }

    @Scheduled(fixedDelay = 600_000)
    void evictIdle() {
        Instant cutoff = Instant.now().minus(IDLE_TTL);
        sessions.values().removeIf(s -> s.lastUsed.isBefore(cutoff));
    }
}
