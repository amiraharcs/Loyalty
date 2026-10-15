package com.amiraharcs.loyalty.loyalty.service;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.auth.security.*;
import com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService;
import com.amiraharcs.loyalty.common.api.ResourceNotFoundException;
import com.amiraharcs.loyalty.customer.repository.CustomerProfileRepository;
import com.amiraharcs.loyalty.loyalty.api.StreakResponse;
import com.amiraharcs.loyalty.visit.model.Visit;
import java.time.Clock;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize(RoleAuthorities.REQUIRES_CUSTOMER)
@Transactional(readOnly = true)
public class CustomerStreakService {
    private final AuthenticationIdentityService identities;
    private final CustomerProfileRepository profiles;
    private final StreakService streaks;
    private final Clock clock;
    public CustomerStreakService(AuthenticationIdentityService identities,CustomerProfileRepository profiles,StreakService streaks,Clock clock) {
        this.identities=identities;this.profiles=profiles;this.streaks=streaks;this.clock=clock;
    }
    public StreakResponse current() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if (authentication==null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof SessionPrincipal principal)) {
            throw new BadCredentialsException("Authentication failed");
        }
        var grants=identities.activeRoleGrants(principal.userId()).orElseThrow(()->new BadCredentialsException("Authentication failed"));
        if (!grants.contains(RoleCode.CUSTOMER)) { throw new AccessDeniedException("Access denied"); }
        var profile=profiles.findByUserId(principal.userId()).orElseThrow(ResourceNotFoundException::new);
        return streaks.snapshot(profile.getId(),Visit.businessDateAt(clock.instant()));
    }
}
