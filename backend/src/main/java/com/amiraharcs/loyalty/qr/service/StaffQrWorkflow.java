package com.amiraharcs.loyalty.qr.service;

import com.amiraharcs.loyalty.audit.service.AuditService;
import com.amiraharcs.loyalty.auth.model.*;
import com.amiraharcs.loyalty.auth.security.RoleAuthorities;
import com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService;
import com.amiraharcs.loyalty.common.api.*;
import com.amiraharcs.loyalty.common.idempotency.IdempotencyService;
import com.amiraharcs.loyalty.loyalty.service.LoyaltyAccountService;
import com.amiraharcs.loyalty.qr.api.*;
import com.amiraharcs.loyalty.qr.model.*;
import com.amiraharcs.loyalty.qr.repository.*;
import com.amiraharcs.loyalty.store.model.Store;
import com.amiraharcs.loyalty.store.service.StaffStoreScopeService;
import com.amiraharcs.loyalty.visit.service.*;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/** Transactional composition. Lock order: idempotency (confirmation), account, token, resolution. */
@Service
@PreAuthorize(RoleAuthorities.REQUIRES_STAFF)
@Transactional(propagation = Propagation.MANDATORY)
public class StaffQrWorkflow {
    private static final String OPERATION = "STAFF_VISIT_CONFIRMATION";
    private final StaffStoreScopeService scope;
    private final QrTokenValidationService validator;
    private final QrTokenRepository tokens;
    private final ScanResolutionRepository resolutions;
    private final LoyaltyAccountService accounts;
    private final AuthenticationIdentityService identities;
    private final VisitService visits;
    private final ScanEventService events;
    private final AuditService audit;
    private final IdempotencyService idempotency;
    private final EntityManager entities;
    private final Clock clock;
    private final JsonMapper json;
    public StaffQrWorkflow(StaffStoreScopeService scope, QrTokenValidationService validator, QrTokenRepository tokens,
            ScanResolutionRepository resolutions, LoyaltyAccountService accounts, AuthenticationIdentityService identities,
            VisitService visits, ScanEventService events, AuditService audit, IdempotencyService idempotency,
            EntityManager entities, Clock clock, JsonMapper json) {
        this.scope=scope; this.validator=validator; this.tokens=tokens; this.resolutions=resolutions; this.accounts=accounts;
        this.identities=identities; this.visits=visits; this.events=events; this.audit=audit; this.idempotency=idempotency;
        this.entities=entities; this.clock=clock; this.json=json;
    }
    public StaffScanResponse scan(StaffScanRequest request, UUID requestId) {
        UUID staffId=scope.currentStaffId(); scope.requireAssignedStore(request.storeId());
        var identity=validator.validate(request.token());
        accounts.lockForMutation(identity.customerId());
        var token=lockToken(identity.jti());
        scope.requireAssignedStore(request.storeId());
        validator.validate(request.token()); // Reuse all crypto/state checks after waiting, using refreshed token state.
        if (staffId.equals(token.getCustomer().getUser().getId())) { throw new AccessDeniedException("Access denied"); }
        var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!now.isBefore(token.getExpiresAt())) { throw new QrFlowException(ApiErrorCode.QR_EXPIRED); }
        if (now.isBefore(token.getIssuedAt())) { throw new QrFlowException(ApiErrorCode.QR_INVALID); }
        var resolution=new ScanResolution(token,entities.getReference(User.class,staffId),entities.getReference(Store.class,request.storeId()),now);
        entities.persist(resolution); entities.flush();
        events.success(token.getJti(),staffId,request.storeId(),ScanEventOutcome.RESOLUTION_CREATED,requestId,resolution.getId(),null);
        return new StaffScanResponse(resolution.getId(),resolution.getExpiresAt(),token.getCustomer().getDisplayName(),accounts.currentBalance(identity.customerId()));
    }
    public StaffVisitResponse confirm(UUID resolutionId, UUID key, UUID requestId) {
        UUID staffId=scope.currentStaffId();
        String hash=IdempotencyService.hash(resolutionId.toString());
        var previous=idempotency.previous(staffId,OPERATION,key,hash);
        var hint=resolutions.findByIdAndStaffId(resolutionId,staffId).orElseThrow(ResourceNotFoundException::new);
        UUID customerId=hint.getCustomer().getId(); UUID storeId=hint.getStore().getId();
        scope.requireAssignedStore(storeId);
        accounts.lockForMutation(customerId);
        var token=lockToken(hint.getToken().getJti());
        var resolution=resolutions.lockOwned(resolutionId,staffId).orElseThrow(ResourceNotFoundException::new);
        entities.refresh(resolution); // Discard pre-lock lifecycle snapshots from the first-level cache.
        scope.requireAssignedStore(storeId);
        var grants=identities.activeRoleGrants(resolution.getCustomer().getUser().getId()).orElseThrow(ResourceNotFoundException::new);
        if (!grants.contains(RoleCode.CUSTOMER)) { throw new ResourceNotFoundException(); }
        if (staffId.equals(resolution.getCustomer().getUser().getId())) { throw new AccessDeniedException("Access denied"); }
        if (previous.isPresent()) { return json.readValue(previous.get(),StaffVisitResponse.class); }
        var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (resolution.getConsumedAt()!=null) { throw new QrFlowException(ApiErrorCode.SCAN_RESOLUTION_CONSUMED); }
        if (now.isBefore(resolution.getCreatedAt()) || !now.isBefore(resolution.getExpiresAt())) {
            throw new QrFlowException(ApiErrorCode.SCAN_RESOLUTION_EXPIRED);
        }
        if (token.getStatus()!=QrTokenStatus.ACTIVE) {
            if (token.getStatus()==QrTokenStatus.CONSUMED && visits.alreadyQualifiedToday(customerId)) { throw new VisitAlreadyRecordedException(); }
            throw new QrFlowException(ApiErrorCode.QR_REVOKED);
        }
        // Natural QR expiry is deliberately not checked here: resolution had to be created while QR was valid.
        var result=StaffVisitResponse.from(visits.confirmResolvedCustomer(customerId,storeId));
        if (result.confirmedAt().isBefore(resolution.getCreatedAt()) || !result.confirmedAt().isBefore(resolution.getExpiresAt())) {
            throw new QrFlowException(ApiErrorCode.SCAN_RESOLUTION_EXPIRED);
        }
        resolution.consume(result.confirmedAt()); token.consume(); entities.flush();
        events.success(token.getJti(),staffId,storeId,ScanEventOutcome.VISIT_CONFIRMED,requestId,resolutionId,result.visitId());
        audit.append(staffId,"VISIT_CONFIRMED","VISIT",result.visitId(),result.confirmedAt(),requestId,
                json.writeValueAsString(Map.of("scanResolutionId",resolutionId,"storeId",storeId,"earnedPoints",result.earnedPoints())));
        idempotency.complete(staffId,OPERATION,key,hash,json.writeValueAsString(result),result.confirmedAt());
        return result;
    }
    private QrIdentityToken lockToken(String jti) {
        var token=tokens.lockByJti(jti).orElseThrow(()->new QrFlowException(ApiErrorCode.QR_INVALID));
        entities.refresh(token);
        return token;
    }
}
