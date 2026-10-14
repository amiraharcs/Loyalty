package com.amiraharcs.loyalty.qr.service;

import com.amiraharcs.loyalty.auth.security.RoleAuthorities;
import com.amiraharcs.loyalty.common.api.*;
import com.amiraharcs.loyalty.qr.api.*;
import com.amiraharcs.loyalty.store.service.StaffStoreScopeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

/** No ambient transaction: rejected attempts are recorded only after the business transaction rolls back. */
@Service
@Validated
@PreAuthorize(RoleAuthorities.REQUIRES_STAFF)
@Transactional(propagation = Propagation.NEVER)
public class StaffQrService {
    private static final Logger LOG=LoggerFactory.getLogger(StaffQrService.class);
    private final StaffQrWorkflow workflow;
    private final StaffStoreScopeService scope;
    private final ScanEventService events;
    private final StaffScanRateLimiter limiter;
    private final TransactionTemplate transactions;
    public StaffQrService(StaffQrWorkflow workflow,StaffStoreScopeService scope,ScanEventService events,
            StaffScanRateLimiter limiter,PlatformTransactionManager manager) {
        this.workflow=workflow;this.scope=scope;this.events=events;this.limiter=limiter;this.transactions=new TransactionTemplate(manager);
    }
    public StaffScanResponse scan(@NotNull @Valid StaffScanRequest request,@NotNull UUID requestId) {
        UUID staffId=scope.currentStaffId();
        try {
            limiter.reserveStaff(staffId);
            return transactions.execute(status->workflow.scan(request,requestId));
        } catch (InvalidQrTokenException invalid) {
            ApiErrorCode code=switch(invalid.reason()) {
                case EXPIRED -> ApiErrorCode.QR_EXPIRED;
                case REVOKED -> ApiErrorCode.QR_REVOKED;
                default -> ApiErrorCode.QR_INVALID;
            };
            failure(staffId,request.storeId(),code,requestId);throw new QrFlowException(code);
        } catch (RuntimeException failure) {
            failure(staffId,request.storeId(),code(failure),requestId);throw failure;
        }
    }
    public StaffVisitResponse confirm(@NotNull @Valid VisitConfirmationRequest request,@NotNull UUID key,@NotNull UUID requestId) {
        UUID staffId=scope.currentStaffId();
        try { return transactions.execute(status->workflow.confirm(request.scanResolutionId(),key,requestId)); }
        catch (RuntimeException failure) {
            try { events.confirmationFailure(staffId,request.scanResolutionId(),code(failure).name(),requestId); }
            catch (RuntimeException unavailable) { LOG.error("Scan event persistence failed actorId={} requestId={} outcome={}",staffId,requestId,code(failure)); }
            throw failure;
        }
    }
    private void failure(UUID staffId,UUID storeId,ApiErrorCode code,UUID requestId) {
        try { events.failure(staffId,storeId,code.name(),requestId); }
        catch (RuntimeException unavailable) {
            // Preserve the original safe response; no request/exception text is logged.
            LOG.error("Scan event persistence failed actorId={} requestId={} outcome={}",staffId,requestId,code);
        }
    }
    private static ApiErrorCode code(RuntimeException failure) {
        if (failure instanceof ConflictException conflict) { return conflict.code(); }
        if (failure instanceof ResourceNotFoundException) { return ApiErrorCode.RESOURCE_NOT_FOUND; }
        if (failure instanceof AccessDeniedException) { return ApiErrorCode.FORBIDDEN; }
        if (failure instanceof AuthenticationException) { return ApiErrorCode.UNAUTHENTICATED; }
        if (failure instanceof RateLimitException) { return ApiErrorCode.RATE_LIMITED; }
        return ApiErrorCode.INTERNAL_ERROR;
    }
}
