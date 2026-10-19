package com.amiraharcs.loyalty.competition.api;

import com.amiraharcs.loyalty.competition.model.*;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.util.*;

/** Explicit presence and expected version: omitted fields retain state, nullable benefit fields may clear. */
public class PrizePatchRequest {
    private final Set<String> fields=new TreeSet<>();
    private Long expectedVersion;
    private String name,benefitDescription;
    private Boolean active;
    private PrizeBenefitType benefitType;
    private Integer benefitValue,weeklyRank;
    private UUID rewardId;
    private PrizeTriggerType triggerType;
    @JsonSetter public void setExpectedVersion(Long value) {expectedVersion=value;}
    @JsonSetter public void setName(String value) {fields.add("name");name=value;}
    @JsonSetter public void setActive(Boolean value) {fields.add("active");active=value;}
    @JsonSetter public void setBenefitType(PrizeBenefitType value) {fields.add("benefitType");benefitType=value;}
    @JsonSetter public void setBenefitDescription(String value) {fields.add("benefitDescription");benefitDescription=value;}
    @JsonSetter public void setBenefitValue(Integer value) {fields.add("benefitValue");benefitValue=value;}
    @JsonSetter public void setRewardId(UUID value) {fields.add("rewardId");rewardId=value;}
    @JsonSetter public void setTriggerType(PrizeTriggerType value) {fields.add("triggerType");triggerType=value;}
    @JsonSetter public void setWeeklyRank(Integer value) {fields.add("weeklyRank");weeklyRank=value;}
    public Long expectedVersion() {return expectedVersion;}
    public List<String> fields() {return List.copyOf(fields);}
    public PrizeRequest merge(Prize p) {
        return new PrizeRequest(fields.contains("name")?name:p.getName(),fields.contains("active")?active:Boolean.valueOf(p.isActive()),
            fields.contains("benefitType")?benefitType:p.getBenefitType(),fields.contains("benefitDescription")?benefitDescription:p.getBenefitDescription(),
            fields.contains("benefitValue")?benefitValue:p.getBenefitValue(),fields.contains("rewardId")?rewardId:p.getRewardId(),
            fields.contains("triggerType")?triggerType:p.getTriggerType(),fields.contains("weeklyRank")?weeklyRank:p.getWeeklyRank());
    }
}
