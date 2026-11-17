package edu.sjsu.cmpe172.scheduler.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import edu.sjsu.cmpe172.scheduler.model.AppUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Issues and verifies the JWT access tokens. HS256 (HMAC-SHA256) with one server-side secret:
 * only this server can create a valid token, and any change to a token breaks its signature.
 *
 * <p>Token claims: {@code sub} = username, {@code uid} = user id, {@code roles} = ["CUSTOMER"],
 * {@code iat}/{@code exp} = issued / expires (default 2 hours).
 */
@Service
public class JwtService {

    static final String ISSUER = "mockprep";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration ttl;
    private final Clock clock;

    public JwtService(@Value("${app.jwt.secret}") String base64Secret,
                      @Value("${app.jwt.ttl:PT2H}") Duration ttl,
                      Clock clock) {
        SecretKey key = secretKey(base64Secret);
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        this.decoder = nimbus;   // also checks exp/nbf (default validator, 60 s clock skew)
        this.ttl = ttl;
        this.clock = clock;
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }

    public IssuedToken issue(AppUser user) {
        Instant now = clock.instant();
        Instant expires = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(user.username())
                .issuedAt(now)
                .expiresAt(expires)
                .claim("uid", user.id())
                .claim("roles", List.of(user.role().name()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expires);
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    private static SecretKey secretKey(String base64Secret) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.jwt.secret (JWT_SECRET) must be base64, e.g. `openssl rand -base64 32`", e);
        }
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret (JWT_SECRET) must be at least 32 bytes (256 bits)");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
