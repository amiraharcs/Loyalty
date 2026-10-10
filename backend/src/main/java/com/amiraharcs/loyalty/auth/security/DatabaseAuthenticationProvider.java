package com.amiraharcs.loyalty.auth.security;

import com.amiraharcs.loyalty.auth.model.AccountStatus;
import com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService;
import com.amiraharcs.loyalty.auth.service.LoginRateLimiter;
import java.util.UUID;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DatabaseAuthenticationProvider implements AuthenticationProvider {
    private final AuthenticationIdentityService identities;
    private final PasswordEncoder encoder;
    private final LoginRateLimiter limiter;
    private final String dummyHash;

    public DatabaseAuthenticationProvider(AuthenticationIdentityService identities, PasswordEncoder encoder, LoginRateLimiter limiter) {
        this.identities = identities; this.encoder = encoder; this.limiter = limiter;
        dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    @Override
    public Authentication authenticate(Authentication input) {
        String email = input.getName();
        String address = input.getDetails() instanceof String ip ? ip : "internal";
        var attempt = limiter.reserveAccount(email, address);
        if (attempt.retryAfter() > 0) { throw denied(); }
        var credentials = identities.credentials(email).orElse(null);
        String encoded = credentials == null ? dummyHash : credentials.hash();
        boolean matches;
        try { matches = encoder.matches((String) input.getCredentials(), encoded); }
        catch (IllegalArgumentException invalidEncoding) { throw denied(); }
        if (!matches || credentials == null || credentials.status() != AccountStatus.ACTIVE) { throw denied(); }
        if (encoder.upgradeEncoding(encoded)) {
            String next = encoder.encode((String) input.getCredentials());
            if (!identities.upgradeHash(credentials.id(), encoded, next)) { throw denied(); }
            encoded = next;
        }
        var current = identities.current(credentials.id()).orElseThrow(DatabaseAuthenticationProvider::denied);
        String version = AuthenticationIdentityService.passwordVersion(encoded);
        if (!version.equals(current.passwordVersion())) { throw denied(); }
        limiter.succeeded(attempt);
        var authenticated = UsernamePasswordAuthenticationToken.authenticated(new SessionPrincipal(credentials.id()), null,
                RoleAuthorities.forRoles(current.response().roles()));
        authenticated.setDetails(new VerifiedPasswordVersion(version));
        return authenticated;
    }

    public record VerifiedPasswordVersion(String value) implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        @Override public String toString() { return "VerifiedPasswordVersion[redacted]"; }
    }

    private static BadCredentialsException denied() { return new BadCredentialsException("Authentication failed"); }
    @Override public boolean supports(Class<?> type) { return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type); }
}
