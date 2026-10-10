package com.amiraharcs.loyalty.auth.api;

import com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService;
import com.amiraharcs.loyalty.auth.security.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.time.Clock;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class SessionController {
    private final AuthenticationManager authentication;
    private final AuthenticationIdentityService identities;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy strategy;
    private final SessionLifecycle lifecycle;
    private final Clock clock;
    public SessionController(AuthenticationManager authentication, AuthenticationIdentityService identities,
            SecurityContextRepository contexts, SessionAuthenticationStrategy strategy, SessionLifecycle lifecycle, Clock clock) {
        this.authentication = authentication; this.identities = identities; this.contexts = contexts;
        this.strategy = strategy; this.lifecycle = lifecycle; this.clock = clock;
    }

    @PostMapping("/sessions")
    public ResponseEntity<CurrentUserResponse> login(@Valid @RequestBody LoginRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        var credentials = UsernamePasswordAuthenticationToken.unauthenticated(body.email(), body.password());
        credentials.setDetails(request.getRemoteAddr()); // No untrusted forwarded headers or session IDs.
        var result = authentication.authenticate(credentials);
        var principal = (SessionPrincipal) result.getPrincipal();
        var identity = identities.current(principal.userId()).orElseThrow(() -> new BadCredentialsException("Authentication failed"));
        var version = (DatabaseAuthenticationProvider.VerifiedPasswordVersion) result.getDetails();
        if (!version.value().equals(identity.passwordVersion())) { throw new BadCredentialsException("Authentication failed"); }
        strategy.onAuthentication(result, request, response);
        var session = request.getSession(true);
        session.setAttribute(SessionLifecycle.AUTHENTICATED_AT, clock.instant());
        session.setAttribute(SessionLifecycle.PASSWORD_VERSION, version.value());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(result);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return ResponseEntity.status(201).header("Cache-Control", "no-store").header("Location", "/api/v1/auth/me")
                .body(identity.response());
    }

    @GetMapping("/me")
    @PreAuthorize(RoleAuthorities.REQUIRES_AUTHENTICATION)
    public ResponseEntity<CurrentUserResponse> me(Authentication authentication) {
        var principal = (SessionPrincipal) authentication.getPrincipal();
        var identity = identities.current(principal.userId()).orElseThrow(() -> new BadCredentialsException("Authentication failed"));
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(identity.response());
    }

    @DeleteMapping("/sessions/current")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        lifecycle.invalidate(request, response, authentication);
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }
}
