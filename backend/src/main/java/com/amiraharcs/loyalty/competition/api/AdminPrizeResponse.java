package com.amiraharcs.loyalty.competition.api;

import com.amiraharcs.loyalty.competition.model.*;
import java.time.Instant;
import java.util.UUID;

public record AdminPrizeResponse(UUID id,String name,boolean active,PrizeBenefitType benefitType,String benefitDescription,
        Integer benefitValue,UUID rewardId,PrizeTriggerType triggerType,Integer weeklyRank,Instant createdAt,Instant updatedAt,long version) {
    public static AdminPrizeResponse from(Prize p) {
        return new AdminPrizeResponse(p.getId(),p.getName(),p.isActive(),p.getBenefitType(),p.getBenefitDescription(),p.getBenefitValue(),
                p.getRewardId(),p.getTriggerType(),p.getWeeklyRank(),p.getCreatedAt(),p.getUpdatedAt(),p.getVersion());
    }
}
