package com.amiraharcs.loyalty.auth.service;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.auth.security.SessionPrincipal;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Fresh database authority, including rechecks after waiting for management locks. */
@Service
public class AdminAuthorityService {
    private final AuthenticationIdentityService identities;
    public AdminAuthorityService(AuthenticationIdentityService identities) { this.identities = identities; }
    public UUID require() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof SessionPrincipal principal)) {
            throw new BadCredentialsException("Authentication failed");
        }
        var roles = identities.activeRoleGrants(principal.userId())
                .orElseThrow(() -> new BadCredentialsException("Authentication failed"));
        if (!roles.contains(RoleCode.ADMIN)) { throw new AccessDeniedException("Access denied"); }
        return principal.userId();
    }
}
