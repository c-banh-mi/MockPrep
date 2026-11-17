package edu.sjsu.cmpe172.scheduler.security;

import edu.sjsu.cmpe172.scheduler.model.AppUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * The logged-in user (built from the JWT by JwtUserConverter). Wraps the users row;
 * controllers get the row back with {@code @AuthenticationPrincipal(expression = "user")}.
 */
public record UserPrincipal(AppUser user) implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Role comes straight from users.role, e.g. CUSTOMER -> ROLE_CUSTOMER for hasRole("CUSTOMER").
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
    }

    @Override
    public String getPassword() {
        return user.passwordHash();
    }

    @Override
    public String getUsername() {
        return user.username();
    }
}
