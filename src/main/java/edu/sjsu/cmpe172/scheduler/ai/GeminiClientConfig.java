package edu.sjsu.cmpe172.scheduler.ai;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * The Gemini SDK client, with a timeout on every call. Spring AI's auto-configuration uses this
 * bean instead of its own (@ConditionalOnMissingBean). The SDK's built-in retries are turned off
 * (attempts = 1) because Spring AI already retries (spring.ai.retry), and stacked retries made a
 * request to an overloaded model hang for minutes. After those, FallbackChatModel switches model.
 */
@Configuration
@ConditionalOnExpression("'${spring.ai.google.genai.api-key:}' != ''")
public class GeminiClientConfig {

    @Bean
    Client googleGenAiClient(@Value("${spring.ai.google.genai.api-key}") String apiKey,
                             @Value("${app.ai.timeout:PT20S}") Duration timeout) {
        return Client.builder()
                .apiKey(apiKey)   // Gemini Developer API (AI Studio key), not Vertex AI
                .httpOptions(HttpOptions.builder()
                        .timeout((int) timeout.toMillis())
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                        .build())
                .build();
    }
}
