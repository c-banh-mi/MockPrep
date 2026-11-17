package edu.sjsu.cmpe172.scheduler.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Gemini is an external service and can be overloaded ("503 high demand") or rate-limited (429).
 * Same pattern as the Milestone 3 notification boundary: bounded retries (spring.ai.retry), then
 * fallbacks. Here the fallbacks are other Gemini models, tried in order, and a small circuit
 * breaker skips the primary model for a while after it failed, so users do not wait for it on
 * every request.
 */
public class FallbackChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(FallbackChatModel.class);

    private final ChatModel delegate;
    private final List<String> fallbackModels;
    private final Duration openFor;
    private final Clock clock;
    private volatile Instant primaryBlockedUntil = Instant.MIN;

    /** @param fallbackModels comma-separated model names, e.g. "gemini-3.1-flash-lite,gemini-3.5-flash" */
    public FallbackChatModel(ChatModel delegate, String fallbackModels, Duration openFor, Clock clock) {
        this.delegate = delegate;
        this.fallbackModels = fallbackModels == null ? List.of() : Arrays.stream(fallbackModels.split(","))
                .map(String::trim).filter(m -> !m.isEmpty()).toList();
        this.openFor = openFor;
        this.clock = clock;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (fallbackModels.isEmpty()) {
            return delegate.call(prompt);
        }
        RuntimeException last = null;
        boolean circuitOpen = clock.instant().isBefore(primaryBlockedUntil);
        if (!circuitOpen) {
            try {
                return delegate.call(prompt);
            } catch (RuntimeException e) {
                last = e;
                primaryBlockedUntil = clock.instant().plus(openFor);
                log.warn("Primary Gemini model failed ({}); using fallbacks {} for the next {} s",
                        shorten(e.getMessage()), fallbackModels, openFor.toSeconds());
            }
        }
        for (String model : fallbackModels) {   // circuit open, or the primary just failed
            try {
                return delegate.call(withModel(prompt, model));
            } catch (RuntimeException e) {
                last = e;
                log.warn("Fallback model {} failed: {}", model, shorten(e.getMessage()));
            }
        }
        if (circuitOpen) {
            // Every fallback failed while the primary was being skipped: it may have recovered.
            try {
                ChatResponse response = delegate.call(prompt);
                primaryBlockedUntil = Instant.MIN;
                return response;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw last;
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    static Prompt withModel(Prompt prompt, String model) {
        ChatOptions options = prompt.getOptions();
        GoogleGenAiChatOptions copy = options instanceof GoogleGenAiChatOptions g ? g.copy()
                : GoogleGenAiChatOptions.builder().build();
        copy.setModel(model);
        return new Prompt(prompt.getInstructions(), copy);
    }

    private static String shorten(String s) {
        return s == null ? "" : s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
