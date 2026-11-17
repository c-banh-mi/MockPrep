package edu.sjsu.cmpe172.scheduler;

import edu.sjsu.cmpe172.scheduler.ai.FallbackChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Gemini overloaded (503) -> fallback models in order; circuit skips the primary for a while. */
class FallbackChatModelTest {

    /** Fails for the models in "down", answers with the model name otherwise; records what was called. */
    static class FakeGemini implements ChatModel {
        final Set<String> down;
        final List<String> called = new ArrayList<>();

        FakeGemini(Set<String> down) {
            this.down = down;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            String model = ((GoogleGenAiChatOptions) prompt.getOptions()).getModel();
            called.add(model);
            if (down.contains(model)) {
                throw new IllegalStateException("503 This model is currently experiencing high demand");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("answer from " + model))));
        }
    }

    private static Prompt prompt() {
        return new Prompt("hi", GoogleGenAiChatOptions.builder().model("primary").build());
    }

    private static String text(ChatResponse r) {
        return r.getResult().getOutput().getText();
    }

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void healthyPrimaryIsUsed() {
        FakeGemini gemini = new FakeGemini(Set.of());
        FallbackChatModel model = new FallbackChatModel(gemini, "lite-a,lite-b", Duration.ofSeconds(60), clock);

        assertThat(text(model.call(prompt()))).isEqualTo("answer from primary");
    }

    @Test
    void overloadedPrimaryFallsBackInOrderAndCircuitSkipsItNextTime() {
        FakeGemini gemini = new FakeGemini(Set.of("primary", "lite-a"));
        FallbackChatModel model = new FallbackChatModel(gemini, "lite-a,lite-b", Duration.ofSeconds(60), clock);

        assertThat(text(model.call(prompt()))).isEqualTo("answer from lite-b");
        assertThat(gemini.called).containsExactly("primary", "lite-a", "lite-b");

        // Within 60 s the primary is not tried again (no extra wait for the user).
        gemini.called.clear();
        model.call(prompt());
        assertThat(gemini.called).containsExactly("lite-a", "lite-b");
    }

    @Test
    void everythingDownSurfacesTheError() {
        FakeGemini gemini = new FakeGemini(Set.of("primary", "lite-a", "lite-b"));
        FallbackChatModel model = new FallbackChatModel(gemini, "lite-a,lite-b", Duration.ofSeconds(60), clock);

        assertThrows(IllegalStateException.class, () -> model.call(prompt()));
    }
}
