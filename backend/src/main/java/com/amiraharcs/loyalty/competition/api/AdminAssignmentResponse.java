package com.amiraharcs.loyalty.competition.api;
import com.amiraharcs.loyalty.competition.model.*;
import java.time.Instant;
import java.util.UUID;
public record AdminAssignmentResponse(UUID customerPrizeId,UUID customerId,UUID prizeId,UUID triggerVisitId,String prizeName,
        PrizeBenefitType benefitType,String benefitDescription,Integer benefitValue,UUID rewardId,String rewardName,String rewardDescription,
        CustomerPrizeStatus status,Instant awardedAt,Instant expiresAt,PrizeTriggerType triggerType) {
    public static AdminAssignmentResponse from(CustomerPrize p,Instant now) {
        return new AdminAssignmentResponse(p.getId(),p.getCustomerId(),p.getPrizeId(),p.getTriggerVisitId(),p.getPrizeNameSnapshot(),
                p.getBenefitType(),p.getBenefitDescriptionSnapshot(),p.getBenefitValue(),p.getRewardId(),p.getRewardNameSnapshot(),
                p.getRewardDescriptionSnapshot(),p.effectiveStatus(now),p.getAwardedAt(),p.getExpiresAt(),p.getTriggerType());
    }
}
