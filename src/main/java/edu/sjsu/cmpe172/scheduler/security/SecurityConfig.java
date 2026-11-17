package edu.sjsu.cmpe172.scheduler.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.sjsu.cmpe172.scheduler.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Stateless JWT authentication and role-based access control.
 *
 * <ul>
 *   <li>POST /api/auth/login checks the BCrypt hash and returns a signed JWT (see AuthController).</li>
 *   <li>Every other request sends it as {@code Authorization: Bearer <token>}. Spring's OAuth2
 *       resource server verifies the signature and expiry; JwtUserConverter loads the user.</li>
 *   <li>No server-side session and no cookies, so there is nothing for CSRF to abuse: a
 *       cross-site request cannot attach the token. CSRF protection is therefore off.</li>
 *   <li>/api/customer/** needs CUSTOMER, /api/provider/** needs PROVIDER, /api/admin/** needs
 *       ADMIN. No/invalid token: 401. Valid token, wrong role: 403.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    private final ObjectMapper json;

    public SecurityConfig(ObjectMapper json) {
        this.json = json;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Username + password check used by the login endpoint (BCrypt hash from the users table). */
    @Bean
    AuthenticationManager authenticationManager(JdbcUserDetailsService userDetails, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetails);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtService jwt, JwtUserConverter toUser) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()   // CORS preflight
                        .requestMatchers("/api/customer/**").hasRole("CUSTOMER")
                        .requestMatchers("/api/provider/**").hasRole("PROVIDER")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Agent 1 books and cancels as the logged-in customer.
                        .requestMatchers("/api/assistant/chat/**").hasRole("CUSTOMER")
                        // Grounded policy Q&A: any logged-in user (each question costs an LLM call).
                        .requestMatchers("/api/assistant/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/home", "/api/slots", "/api/slots/*", "/api/services", "/api/providers")
                                .permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/health", "/actuator/health", "/actuator/info").permitAll()
                        // Metrics reveal internals (traffic, failures): operators only. /health stays public.
                        .requestMatchers(HttpMethod.GET, "/actuator/metrics", "/actuator/metrics/**").hasRole("ADMIN")
                        // The mock notification service checks its own API key (MockNotifyApiKeyFilter).
                        .requestMatchers("/mock-notify/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .cors(cors -> { })
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(j -> j.decoder(jwt.decoder()).jwtAuthenticationConverter(toUser))
                        .authenticationEntryPoint((req, res, e) ->
                                writeError(req, res, HttpStatus.UNAUTHORIZED, "Please log in")))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) ->
                                writeError(req, res, HttpStatus.UNAUTHORIZED, "Please log in"))
                        .accessDeniedHandler((req, res, e) ->
                                writeError(req, res, HttpStatus.FORBIDDEN, "Your role is not allowed to do this")));
        return http.build();
    }

    /** Lets a frontend on another origin (Next.js dev server, Vercel) call the API with a bearer token. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors-origins:}") String origins) {
        CorsConfiguration cors = new CorsConfiguration();
        List<String> allowed = Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        cors.setAllowedOrigins(allowed);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    private void writeError(HttpServletRequest req, HttpServletResponse res, HttpStatus status, String message)
            throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(res.getOutputStream(), new ErrorResponse(
                Instant.now(), status.value(), status.getReasonPhrase(), message, req.getRequestURI()));
    }
}
