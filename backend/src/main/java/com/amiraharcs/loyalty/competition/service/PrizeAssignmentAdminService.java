package com.amiraharcs.loyalty.competition.service;
import com.amiraharcs.loyalty.audit.service.AuditService;
import com.amiraharcs.loyalty.auth.security.RoleAuthorities;
import com.amiraharcs.loyalty.common.api.*;
import com.amiraharcs.loyalty.competition.api.*;
import com.amiraharcs.loyalty.competition.model.*;
import com.amiraharcs.loyalty.competition.repository.*;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
@Service
@PreAuthorize(RoleAuthorities.REQUIRES_ADMIN)
@Transactional(readOnly=true)
public class PrizeAssignmentAdminService {
    private final PrizeAdminIdentity admins;
    private final CustomerPrizeRepository prizes;
    private final AdminAssignmentQuery query;
    private final EntityManager entities;
    private final Clock clock;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    public PrizeAssignmentAdminService(PrizeAdminIdentity admins,CustomerPrizeRepository prizes,AdminAssignmentQuery query,
            EntityManager entities,Clock clock,AuditService audit,JdbcTemplate jdbc,JsonMapper json) {
        this.admins=admins;this.prizes=prizes;this.query=query;this.entities=entities;this.clock=clock;this.audit=audit;this.jdbc=jdbc;this.json=json;
    }
    public AdminAssignmentResponse get(UUID id) {admins.require();return AdminAssignmentResponse.from(prizes.findById(id).orElseThrow(ResourceNotFoundException::new),clock.instant());}
    public AdminPrizePage<AdminAssignmentResponse> list(PrizeAssignmentFilter filters,int size,String cursor) {
        admins.require();if(size<1 || size>100) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST);}
        var after=PrizeAdminCursor.decode(cursor,filters.scope());
        var rows=query.list(filters,clock.instant(),after.after(),after.time(),after.id(),size+1);
        boolean next=rows.size()>size;var page=rows.subList(0,Math.min(size,rows.size()));
        return new AdminPrizePage<>(page,next?PrizeAdminCursor.encode(page.getLast().awardedAt(),page.getLast().customerPrizeId(),filters.scope()):null,next);
    }
    @Transactional public AdminAssignmentResponse revoke(UUID id,PrizeRevocationRequest request,UUID requestId) {
        UUID actor=admins.require();
        if(request==null || request.reason()==null || request.reason().isBlank() || request.reason().length()>1000 || request.reason().indexOf('\0')>=0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        // Source state lock serialises revocations. No account/points mutation or new grant occurs here.
        var p=prizes.lockById(id).orElseThrow(ResourceNotFoundException::new);entities.refresh(p);admins.require();
        Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if(p.getStatus()==CustomerPrizeStatus.REVOKED) {
            var previous=jdbc.queryForObject("SELECT metadata->>'reason' FROM loyalty.audit_logs WHERE id=?",String.class,p.getRevocationAuditId());
            if(!request.reason().equals(previous)) {throw new ConflictException();}
            return AdminAssignmentResponse.from(p,now);
        }
        if(!p.isAvailableAt(now) || now.isBefore(p.getAwardedAt())) {throw new ConflictException();}
        UUID event=UUID.randomUUID();
        // Only the required correction reason is retained, never a complete request body or credentials.
        audit.appendWithId(event,actor,"CUSTOMER_PRIZE_REVOKED","CUSTOMER_PRIZE",id,now,requestId,json.writeValueAsString(Map.of("reason",request.reason())));
        p.revoke(event,now);entities.flush();return AdminAssignmentResponse.from(p,now);
    }
}
