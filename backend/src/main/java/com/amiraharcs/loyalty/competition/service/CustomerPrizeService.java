package com.amiraharcs.loyalty.competition.service;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.auth.security.*;
import com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService;
import com.amiraharcs.loyalty.common.api.ResourceNotFoundException;
import com.amiraharcs.loyalty.competition.api.*;
import com.amiraharcs.loyalty.competition.repository.CustomerPrizeRepository;
import com.amiraharcs.loyalty.customer.repository.CustomerProfileRepository;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@PreAuthorize(RoleAuthorities.REQUIRES_CUSTOMER)
@Transactional(readOnly=true)
public class CustomerPrizeService {
    private final AuthenticationIdentityService identities;
    private final CustomerProfileRepository profiles;
    private final CustomerPrizeRepository prizes;
    private final Clock clock;
    public CustomerPrizeService(AuthenticationIdentityService identities,CustomerProfileRepository profiles,CustomerPrizeRepository prizes,Clock clock) {
        this.identities=identities;this.profiles=profiles;this.prizes=prizes;this.clock=clock;
    }
    public CustomerPrizePage history(int size,String cursor) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof SessionPrincipal principal)) {throw new BadCredentialsException("Authentication failed");}
        var grants=identities.activeRoleGrants(principal.userId()).orElseThrow(()->new BadCredentialsException("Authentication failed"));
        if(!grants.contains(RoleCode.CUSTOMER)) {throw new AccessDeniedException("Access denied");}
        var customer=profiles.findByUserId(principal.userId()).orElseThrow(ResourceNotFoundException::new);
        if(size<1 || size>100) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST);}
        var after=PrizeHistoryCursor.decode(cursor,customer.getId());
        var rows=prizes.history(customer.getId(),after.after(),after.time(),after.id(),org.springframework.data.domain.PageRequest.of(0,size+1));
        boolean hasNext=rows.size()>size;var visible=rows.subList(0,Math.min(size,rows.size()));
        var now=clock.instant();
        return new CustomerPrizePage(visible.stream().map(p->CustomerPrizeResponse.from(p,now)).toList(),
                hasNext?PrizeHistoryCursor.encode(visible.getLast(),customer.getId()):null,hasNext);
    }
}
