package edu.sjsu.cmpe172.scheduler.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Safe defaults for secrets that are not configured, decided once at startup. Nothing secret is
 * ever written in application.yml or committed to Git.
 *
 * <ul>
 *   <li>No {@code JWT_SECRET}: a random signing key for this run (tokens stop working after a
 *       restart, so set it in .env for anything longer-lived).</li>
 *   <li>No {@code NOTIFICATION_API_KEY}: a random key for this run, shared by the gateway and the
 *       mock service because they run in the same JVM. A separately deployed notification service
 *       needs the key set on both sides.</li>
 *   <li>No {@code GEMINI_API_KEY}: the Gemini auto-configuration is switched off
 *       ({@code spring.ai.model.chat=none}) instead of failing startup. The app runs; the AI
 *       endpoints answer 503 "AI is not configured". This is also how CI runs the tests.</li>
 * </ul>
 */
public class RuntimeDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        Map<String, Object> defaults = new HashMap<>();
        if (!StringUtils.hasText(env.getProperty("app.jwt.secret"))) {
            defaults.put("app.jwt.secret", randomKey());
        }
        if (!StringUtils.hasText(env.getProperty("notification.api-key"))) {
            defaults.put("notification.api-key", randomKey());
        }
        if (!StringUtils.hasText(env.getProperty("spring.ai.google.genai.api-key"))) {
            defaults.put("spring.ai.model.chat", "none");
            defaults.put("spring.ai.model.embedding.text", "none");
            // The embedding connection auto-configuration ignores the switch above and would fail
            // ("project-id must be set"), so leave it out entirely.
            String exclude = env.getProperty("spring.autoconfigure.exclude", "");
            defaults.put("spring.autoconfigure.exclude", (exclude.isBlank() ? "" : exclude + ",")
                    + "org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration");
        }
        if (!defaults.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource("runtimeDefaults", defaults));
        }
    }

    private static String randomKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
