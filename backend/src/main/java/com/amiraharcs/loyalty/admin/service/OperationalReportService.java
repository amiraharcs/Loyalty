package com.amiraharcs.loyalty.admin.service;

import com.amiraharcs.loyalty.admin.api.OperationalReportDtos.*;
import com.amiraharcs.loyalty.admin.repository.OperationalReportQuery;
import com.amiraharcs.loyalty.auth.security.RoleAuthorities;
import com.amiraharcs.loyalty.auth.service.AdminAuthorityService;
import java.time.Clock;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@PreAuthorize(RoleAuthorities.REQUIRES_ADMIN)
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class OperationalReportService {
    private final OperationalReportQuery query;
    private final AdminAuthorityService admins;
    private final Clock clock;
    public OperationalReportService(OperationalReportQuery query,AdminAuthorityService admins,Clock clock) {this.query=query;this.admins=admins;this.clock=clock;}
    public Overview overview() {admins.require();return query.overview(clock.instant());}
    public Visits visits(String from,String to,UUID store) {admins.require();var range=OperationalReportRange.dates(from,to,clock.instant());query.requireStore(store);return query.visits(range,store);}
    public Points points(String from,String to,UUID store) {admins.require();var now=clock.instant();var range=OperationalReportRange.instants(from,to,now);query.requireStore(store);return query.points(range,store,now);}
    public Redemptions redemptions(String from,String to,UUID store) {admins.require();var now=clock.instant();var range=OperationalReportRange.instants(from,to,now);query.requireStore(store);return query.redemptions(range,store,now);}
    public Game game(String from,String to) {admins.require();var now=clock.instant();return query.game(OperationalReportRange.instants(from,to,now),now);}
    public Competition competition(String from,String to) {admins.require();var now=clock.instant();return query.competition(OperationalReportRange.instants(from,to,now),now);}
}
