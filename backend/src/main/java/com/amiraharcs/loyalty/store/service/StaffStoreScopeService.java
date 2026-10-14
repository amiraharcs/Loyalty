package com.amiraharcs.loyalty.store.service;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.auth.security.RoleAuthorities;
import com.amiraharcs.loyalty.auth.security.SessionPrincipal;
import com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService;
import com.amiraharcs.loyalty.common.api.ResourceNotFoundException;
import com.amiraharcs.loyalty.store.model.StoreStatus;
import com.amiraharcs.loyalty.store.repository.StaffStoreRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reusable staff scope for future scanning, visits, fulfilment and service lookup. */
@Service
@Transactional(readOnly = true)
@PreAuthorize(RoleAuthorities.REQUIRES_STAFF)
public class StaffStoreScopeService {
    private final StaffStoreRepository assignments;
    private final AuthenticationIdentityService identities;
    public StaffStoreScopeService(StaffStoreRepository assignments, AuthenticationIdentityService identities) {
        this.assignments = assignments;
        this.identities = identities;
    }

    public StoreSummary requireAssignedStore(UUID storeId) {
        UUID staffId = authenticatedStaffId();
        return assignments.findAssignedStore(staffId, storeId, StoreStatus.ACTIVE).map(StoreSummary::from)
                .orElseThrow(ResourceNotFoundException::new);
    }

    public List<StoreSummary> assignedActiveStores() {
        UUID staffId = authenticatedStaffId();
        return assignments.findAssignedStores(staffId, StoreStatus.ACTIVE).stream().map(StoreSummary::from).toList();
    }

    /** Current database-verified actor for trusted module operations, never supplied by a client. */
    public UUID currentStaffId() { return authenticatedStaffId(); }

    private UUID authenticatedStaffId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof SessionPrincipal principal)) {
            throw new BadCredentialsException("Authentication failed");
        }
        var grants = identities.activeRoleGrants(principal.userId()).orElseThrow(() -> new BadCredentialsException("Authentication failed"));
        if (!grants.contains(RoleCode.STAFF)) { throw new AccessDeniedException("Access denied"); }
        return principal.userId();
    }
}
