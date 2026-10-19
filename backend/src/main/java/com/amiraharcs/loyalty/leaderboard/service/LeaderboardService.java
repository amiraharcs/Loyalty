package com.amiraharcs.loyalty.leaderboard.service;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.auth.security.*;
import com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService;
import com.amiraharcs.loyalty.common.api.ResourceNotFoundException;
import com.amiraharcs.loyalty.competition.service.WeeklyPeriodService;
import com.amiraharcs.loyalty.customer.repository.CustomerProfileRepository;
import com.amiraharcs.loyalty.leaderboard.api.*;
import com.amiraharcs.loyalty.leaderboard.repository.LeaderboardQuery;
import java.time.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@PreAuthorize(RoleAuthorities.REQUIRES_CUSTOMER)
public class LeaderboardService {
    private final AuthenticationIdentityService identities;
    private final CustomerProfileRepository profiles;
    private final WeeklyPeriodService periods;
    private final LeaderboardQuery queries;
    private final LeaderboardCursor cursors;
    private final Clock clock;
    public LeaderboardService(AuthenticationIdentityService identities,CustomerProfileRepository profiles,
            WeeklyPeriodService periods,LeaderboardQuery queries,LeaderboardCursor cursors,Clock clock) {
        this.identities=identities;this.profiles=profiles;this.periods=periods;this.queries=queries;this.cursors=cursors;this.clock=clock;
    }
    public LeaderboardPage weekly(LocalDate periodStart,int size,String cursor) { return page(true,periodStart,size,cursor); }
    public LeaderboardPage allTime(int size,String cursor) { return page(false,null,size,cursor); }
    private LeaderboardPage page(boolean weekly,LocalDate selected,int size,String cursor) {
        UUID customer=currentCustomer();
        if(size<1 || size>100) { throw invalid(); }
        Instant now=clock.instant();
        LocalDate monday=selected==null?WeeklyPeriodService.startDate(now):selected;
        // v1 historical filter is an ISO Monday, never a caller-defined current/future week.
        if(weekly && (monday.getDayOfWeek()!=DayOfWeek.MONDAY || monday.getYear()<1 || monday.getYear()>9999
                || monday.isAfter(WeeklyPeriodService.startDate(now)))) { throw invalid(); }
        String scope=weekly?"weekly:"+monday:"all-time";
        var after=cursors.decode(cursor,scope);
        var period=weekly?periods.containing(WeeklyPeriodService.startAt(monday)):null;
        var rows=queries.read(period==null?null:period.getId(),customer,size+1,after);
        var page=rows.stream().filter(row->!row.own()).toList();
        boolean hasNext=page.size()>size;
        var visible=page.subList(0,Math.min(size,page.size()));
        var own=rows.stream().filter(LeaderboardQuery.Row::own).findFirst()
                .map(row->entry(row,customer)).orElseGet(()->new LeaderboardEntry(null,"Member",null,true));
        return new LeaderboardPage(visible.stream().map(row->entry(row,customer)).toList(),
                hasNext?cursors.encode(visible.getLast().position(),scope):null,hasNext,own,
                period==null?null:WeeklyPeriodResponse.from(period),now);
    }
    private UUID currentCustomer() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof SessionPrincipal principal)) {
            throw new BadCredentialsException("Authentication failed");
        }
        var grants=identities.activeRoleGrants(principal.userId()).orElseThrow(()->new BadCredentialsException("Authentication failed"));
        if(!grants.contains(RoleCode.CUSTOMER)) { throw new AccessDeniedException("Access denied"); }
        return profiles.findByUserId(principal.userId()).orElseThrow(ResourceNotFoundException::new).getId();
    }
    private static LeaderboardEntry entry(LeaderboardQuery.Row row,UUID customer) {
        return new LeaderboardEntry(row.rank(),row.alias(),row.score(),row.customerId().equals(customer));
    }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST); }
}
