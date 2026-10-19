package com.amiraharcs.loyalty.competition.api;

import com.amiraharcs.loyalty.competition.model.*;
import java.time.Instant;
import java.util.UUID;

/** Only the owner's frozen benefit and effective status; never source/audit/actor internals. */
public record CustomerPrizeResponse(UUID customerPrizeId,String prizeName,PrizeBenefitType benefitType,
        String benefitDescription,Integer percentage,UUID rewardId,String rewardName,String rewardDescription,
        CustomerPrizeStatus status,Instant awardedAt,Instant expiresAt,PrizeTriggerType triggerType) {
    public static CustomerPrizeResponse from(CustomerPrize p,Instant now) {
        return new CustomerPrizeResponse(p.getId(),p.getPrizeNameSnapshot(),p.getBenefitType(),p.getBenefitDescriptionSnapshot(),
                p.getBenefitValue(),p.getRewardId(),p.getRewardNameSnapshot(),p.getRewardDescriptionSnapshot(),
                p.effectiveStatus(now),p.getAwardedAt(),p.getExpiresAt(),p.getTriggerType());
    }
}
