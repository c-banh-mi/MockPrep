package edu.sjsu.cmpe172.scheduler.security;

import edu.sjsu.cmpe172.scheduler.repository.UserRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/**
 * Turns a verified JWT into the logged-in user. The role is re-read from users.role on every
 * request (one indexed lookup), so a deleted account or a changed role takes effect at once
 * instead of when the token expires. Controllers keep receiving the same UserPrincipal as
 * with the old session login.
 */
@Component
public class JwtUserConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserRepository users;

    public JwtUserConverter(UserRepository users) {
        this.users = users;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UserPrincipal principal = users.findByUsername(jwt.getSubject())
                .map(UserPrincipal::new)
                .orElseThrow(() -> new InvalidBearerTokenException("Unknown user"));
        return new UsernamePasswordAuthenticationToken(principal, jwt, principal.getAuthorities());
    }
}
