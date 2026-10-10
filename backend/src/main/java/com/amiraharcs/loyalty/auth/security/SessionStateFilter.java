package com.amiraharcs.loyalty.auth.security;

import com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.*;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import com.amiraharcs.loyalty.common.api.ApiErrorWriter;
import com.amiraharcs.loyalty.common.api.ApiErrorCode;
import org.springframework.web.filter.OncePerRequestFilter;

/** Fresh account/grant checks before authorization, with an independent absolute lifetime. */
public class SessionStateFilter extends OncePerRequestFilter {
    private final AuthenticationIdentityService identities;
    private final Clock clock;
    private final SecurityContextRepository contexts;
    private final SessionLifecycle lifecycle;
    private final CookieCsrfTokenRepository tokens;
    private final ApiErrorWriter errors;

    public SessionStateFilter(AuthenticationIdentityService identities, Clock clock,
            SecurityContextRepository contexts, SessionLifecycle lifecycle, CookieCsrfTokenRepository tokens, ApiErrorWriter errors) {
        this.identities = identities; this.clock = clock; this.contexts = contexts; this.lifecycle = lifecycle; this.tokens = tokens; this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof SessionPrincipal principal) {
            var session = request.getSession(false);
            var authenticatedAt = session == null ? null : session.getAttribute(SessionLifecycle.AUTHENTICATED_AT);
            boolean expired = !(authenticatedAt instanceof Instant start)
                    || start.isAfter(clock.instant()) || !clock.instant().isBefore(start.plus(Duration.ofHours(12)));
            AuthenticationIdentityService.CurrentIdentity identity;
            try { identity = expired ? null : identities.current(principal.userId()).orElse(null); }
            catch (RuntimeException unavailable) {
                SecurityContextHolder.clearContext();
                errors.write(ApiErrorCode.INTERNAL_ERROR, request, response);
                return;
            }
            if (identity == null || !identity.passwordVersion().equals(session.getAttribute(SessionLifecycle.PASSWORD_VERSION))) {
                lifecycle.invalidate(request, response, auth);
            } else {
                var authorities = identity.response().roles().stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList();
                Set<String> previous = auth.getAuthorities().stream().map(authority -> authority.getAuthority()).collect(Collectors.toSet());
                Set<String> current = authorities.stream().map(authority -> authority.getAuthority()).collect(Collectors.toSet());
                var refreshed = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(refreshed);
                SecurityContextHolder.setContext(context);
                if (!previous.equals(current)) {
                    tokens.saveToken(null, request, response);
                    request.changeSessionId(); // Preserve original auth time; role changes do not extend lifetime.
                    contexts.saveContext(context, request, response);
                }
            }
        }
        chain.doFilter(request, response);
    }
}
