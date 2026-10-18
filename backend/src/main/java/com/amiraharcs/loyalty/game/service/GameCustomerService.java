package com.amiraharcs.loyalty.game.service;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.auth.security.*;
import com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService;
import com.amiraharcs.loyalty.common.api.ResourceNotFoundException;
import com.amiraharcs.loyalty.customer.repository.CustomerProfileRepository;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shared game-module ownership lookup; always rechecks fresh account status and grants. */
@Service
@PreAuthorize(RoleAuthorities.REQUIRES_CUSTOMER)
@Transactional(readOnly = true)
public class GameCustomerService {
    private final AuthenticationIdentityService identities;
    private final CustomerProfileRepository profiles;
    public GameCustomerService(AuthenticationIdentityService identities, CustomerProfileRepository profiles) {
        this.identities = identities;
        this.profiles = profiles;
    }
    public Customer current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof SessionPrincipal principal)) {
            throw new BadCredentialsException("Authentication failed");
        }
        var grants = identities.activeRoleGrants(principal.userId())
                .orElseThrow(() -> new BadCredentialsException("Authentication failed"));
        if (!grants.contains(RoleCode.CUSTOMER)) { throw new AccessDeniedException("Access denied"); }
        return new Customer(principal.userId(), profiles.findByUserId(principal.userId())
                .orElseThrow(ResourceNotFoundException::new).getId());
    }
    public record Customer(UUID actorId, UUID customerId) { }
}
