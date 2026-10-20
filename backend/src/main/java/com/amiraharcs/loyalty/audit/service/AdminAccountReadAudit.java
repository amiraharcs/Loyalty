package com.amiraharcs.loyalty.audit.service;

import com.amiraharcs.loyalty.auth.service.AdminAuthorityService;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Support reads are audited after their read transaction ends; no raw search/filter values. */
@Service
public class AdminAccountReadAudit {
    public enum Action { ADMIN_CUSTOMERS_LIST, ADMIN_CUSTOMER_VIEW, ADMIN_STAFF_LIST, ADMIN_STAFF_VIEW }
    private final AdminAuthorityService admins;
    private final AuditService audit;
    private final Clock clock;
    public AdminAccountReadAudit(AdminAuthorityService admins, AuditService audit, Clock clock) {
        this.admins=admins;this.audit=audit;this.clock=clock;
    }
    @Transactional
    public void record(Action action, UUID target, UUID requestId) {
        audit.append(admins.require(),action.name(),target==null?"ADMIN_SEARCH_REQUEST":"USER",
                target==null?requestId:target,clock.instant(),requestId,"{}");
    }
}
