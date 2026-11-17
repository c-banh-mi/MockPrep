package edu.sjsu.cmpe172.scheduler.controller;

import edu.sjsu.cmpe172.scheduler.dto.ErrorResponse;
import edu.sjsu.cmpe172.scheduler.dto.LoginRequest;
import edu.sjsu.cmpe172.scheduler.dto.LoginResponse;
import edu.sjsu.cmpe172.scheduler.dto.UserDto;
import edu.sjsu.cmpe172.scheduler.security.JwtService;
import edu.sjsu.cmpe172.scheduler.security.UserPrincipal;
import edu.sjsu.cmpe172.scheduler.service.DtoMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** JWT login. The token is the only credential; the server keeps no session. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authManager;
    private final JwtService jwt;

    public AuthController(AuthenticationManager authManager, JwtService jwt) {
        this.authManager = authManager;
        this.jwt = jwt;
    }

    /** 200 {token, tokenType: "Bearer", expiresAt, user}, or 401 for a wrong username/password. */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        try {
            UserPrincipal p = (UserPrincipal) authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.username(), req.password())).getPrincipal();
            JwtService.IssuedToken token = jwt.issue(p.user());
            log.info("User {} logged in ({})", p.user().username(), p.user().role());
            return ResponseEntity.ok(new LoginResponse(token.token(), "Bearer", token.expiresAt(),
                    DtoMapper.toDto(p.user())));
        } catch (AuthenticationException e) {
            // Same answer for unknown user and wrong password, so it does not reveal which usernames exist.
            log.warn("Failed login for {}", req.username());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(Instant.now(), 401,
                    "Unauthorized", "Invalid username or password", http.getRequestURI()));
        }
    }

    /**
     * Stateless logout: the client deletes its token. (A token stays valid until it expires;
     * the 2-hour lifetime limits that. A server-side deny-list would be the next step.)
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }

    /** The user the bearer token belongs to, or 204 No Content without a (valid) token. */
    @GetMapping("/me")
    public ResponseEntity<UserDto> me(@AuthenticationPrincipal UserPrincipal me) {
        return me == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(DtoMapper.toDto(me.user()));
    }
}
