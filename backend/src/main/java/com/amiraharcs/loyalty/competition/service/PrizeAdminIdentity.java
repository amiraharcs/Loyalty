package com.amiraharcs.loyalty.competition.service;
import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.auth.security.SessionPrincipal;
import com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
@Component
public class PrizeAdminIdentity {
    private final AuthenticationIdentityService identities;
    public PrizeAdminIdentity(AuthenticationIdentityService identities) {this.identities=identities;}
    public UUID require() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof SessionPrincipal principal)) {throw new BadCredentialsException("Authentication failed");}
        var roles=identities.activeRoleGrants(principal.userId()).orElseThrow(()->new BadCredentialsException("Authentication failed"));
        if(!roles.contains(RoleCode.ADMIN)) {throw new AccessDeniedException("Access denied");}
        return principal.userId();
    }
}
