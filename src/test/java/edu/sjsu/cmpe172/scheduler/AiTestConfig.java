package edu.sjsu.cmpe172.scheduler;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Stand-ins for Gemini in tests (CI has no API key, and tests must not depend on a paid, slow,
 * non-deterministic service). StubChatModel answers with a programmable function and records
 * every prompt; WordEmbeddingModel is a tiny bag-of-words embedding, enough to make retrieval
 * deterministic.
 */
@TestConfiguration(proxyBeanMethods = false)
public class AiTestConfig {

    @Bean
    StubChatModel stubChatModel() {
        return new StubChatModel();
    }

    @Bean
    WordEmbeddingModel wordEmbeddingModel() {
        return new WordEmbeddingModel();
    }

    public static class StubChatModel implements ChatModel {

        public final List<Prompt> prompts = new CopyOnWriteArrayList<>();
        public volatile Function<Prompt, String> answer = p -> "stub answer";

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer.apply(prompt)))));
        }

        public String lastUserText() {
            return prompts.get(prompts.size() - 1).getUserMessage().getText();
        }

        public void reset() {
            prompts.clear();
            answer = p -> "stub answer";
        }
    }

    /** Hashes each word's first 5 letters into one of 512 buckets ("cancel", "cancellation" match). */
    public static class WordEmbeddingModel implements EmbeddingModel {

        private static final int DIMS = 512;

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> out = new ArrayList<>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                out.add(new Embedding(vector(request.getInstructions().get(i)), i));
            }
            return new EmbeddingResponse(out);
        }

        @Override
        public float[] embed(Document document) {
            return vector(document.getText());
        }

        @Override
        public int dimensions() {
            return DIMS;
        }

        static float[] vector(String text) {
            float[] v = new float[DIMS];
            for (String word : text.toLowerCase().split("[^a-z]+")) {
                if (word.length() < 3) {
                    continue;
                }
                String stem = word.substring(0, Math.min(5, word.length()));
                v[Math.floorMod(stem.hashCode(), DIMS)] += 1;
            }
            double norm = 0;
            for (float f : v) {
                norm += f * f;
            }
            norm = Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < DIMS; i++) {
                    v[i] /= (float) norm;
                }
            }
            return v;
        }
    }
}
