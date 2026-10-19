package com.amiraharcs.loyalty.competition.api;

import com.amiraharcs.loyalty.competition.model.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;
import java.util.UUID;

public record PrizeRequest(@NotBlank @Size(max=200) @Pattern(regexp="[^\\x00]*") String name,
        @NotNull Boolean active,@NotNull PrizeBenefitType benefitType,
        @NotBlank @Size(max=2000) @Pattern(regexp="[^\\x00]*") String benefitDescription,
        @Min(1) @Max(100) Integer benefitValue,UUID rewardId,@NotNull PrizeTriggerType triggerType,
        @Min(1) @Max(4) Integer weeklyRank) {
    public PrizeRequest {if(name!=null) {name=name.strip();}}
    @JsonIgnore @AssertTrue(message="Benefit fields must match the selected benefit type")
    public boolean isBenefitValid() {
        return benefitType==null || (benefitType==PrizeBenefitType.REWARD?rewardId!=null && benefitValue==null:rewardId==null && benefitValue!=null);
    }
    @JsonIgnore @AssertTrue(message="Trigger fields must match the selected configuration slot")
    public boolean isTriggerValid() {
        return triggerType==null || (triggerType==PrizeTriggerType.WEEKLY_RANK?weeklyRank!=null:weeklyRank==null && benefitType==PrizeBenefitType.REWARD);
    }
}
