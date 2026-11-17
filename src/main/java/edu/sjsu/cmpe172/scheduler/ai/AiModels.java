package edu.sjsu.cmpe172.scheduler.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Access to the LLM. Gemini is an external service: the ChatModel bean exists only when
 * GEMINI_API_KEY is set (Spring AI's Google GenAI auto-configuration builds it). Without a key
 * the rest of the app works normally and AI features answer 503.
 */
@Component
public class AiModels {

    private final ObjectProvider<ChatModel> chatModels;
    private final String fallbackModel;
    private final Clock clock;
    private volatile ChatModel wrapped;

    public AiModels(ObjectProvider<ChatModel> chatModels,
                    @Value("${app.ai.fallback-model:}") String fallbackModel, Clock clock) {
        this.chatModels = chatModels;
        this.fallbackModel = fallbackModel;
        this.clock = clock;
    }

    public boolean available() {
        return chatModels.getIfAvailable() != null;
    }

    public ChatClient chatClient() {
        ChatModel model = chatModels.getIfAvailable();
        if (model == null) {
            throw new AiUnavailableException("AI is not configured on this server (set GEMINI_API_KEY)");
        }
        return ChatClient.create(withFallback(model));
    }

    /** The real Gemini model gets the fallback wrapper; test stand-ins are used as they are. */
    private ChatModel withFallback(ChatModel model) {
        if (!(model instanceof GoogleGenAiChatModel)) {
            return model;
        }
        ChatModel w = wrapped;
        if (w == null) {
            w = new FallbackChatModel(model, fallbackModel, Duration.ofSeconds(60), clock);
            wrapped = w;
        }
        return w;
    }

    /** Wraps an error from the LLM call (quota, network, timeout) into a 503 with a short message. */
    public static AiUnavailableException callFailed(RuntimeException e) {
        if (e instanceof AiUnavailableException ai) {
            return ai;
        }
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (detail.length() > 200) {
            detail = detail.substring(0, 200) + "...";
        }
        return new AiUnavailableException("The AI service did not answer: " + detail, e);
    }
}
