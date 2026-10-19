package com.amiraharcs.loyalty.competition.service;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.auth.service.AuthenticationIdentityService;
import com.amiraharcs.loyalty.competition.model.*;
import com.amiraharcs.loyalty.competition.repository.PrizeRepository;
import com.amiraharcs.loyalty.customer.repository.CustomerProfileRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Preparation for settlement: eligibility and definition selection only; creates no entitlement. */
@Service
@Transactional(readOnly=true)
public class WeeklyPrizeRules {
    private final PrizeRepository prizes;
    private final CustomerProfileRepository profiles;
    private final AuthenticationIdentityService identities;
    public WeeklyPrizeRules(PrizeRepository prizes,CustomerProfileRepository profiles,AuthenticationIdentityService identities) {
        this.prizes=prizes;this.profiles=profiles;this.identities=identities;
    }
    public Optional<Definition> forOutcome(UUID customerId,int rank) {
        if(rank<1 || rank>4) {return Optional.empty();}
        var profile=profiles.findById(customerId);
        if(profile.isEmpty()) {return Optional.empty();}
        var grants=identities.activeRoleGrants(profile.get().getUser().getId());
        if(grants.isEmpty() || !grants.get().contains(RoleCode.CUSTOMER)
                || grants.get().contains(RoleCode.STAFF) || grants.get().contains(RoleCode.ADMIN)) {return Optional.empty();}
        return prizes.findByTriggerTypeAndWeeklyRankAndActiveTrue(PrizeTriggerType.WEEKLY_RANK,rank)
                .map(p->new Definition(p.getId(),p.getName(),p.getBenefitType(),p.getBenefitDescription(),p.getBenefitValue(),p.getRewardId()));
    }
    public record Definition(UUID prizeId,String name,PrizeBenefitType benefitType,String description,Integer percentage,UUID rewardId) { }
}
