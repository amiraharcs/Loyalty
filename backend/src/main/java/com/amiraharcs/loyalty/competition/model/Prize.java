package com.amiraharcs.loyalty.competition.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Admin-managed v1 benefit catalogue; no arbitrary cash benefit or assignment source strings. */
@Entity
@Table(name="prizes",schema="loyalty")
public class Prize {
    @Id private UUID id;
    @Column(nullable=false,length=200) private String name;
    @Column(nullable=false) private boolean active;
    @Enumerated(EnumType.STRING) @Column(name="benefit_type",nullable=false,length=24) private PrizeBenefitType benefitType;
    @Column(name="benefit_description",nullable=false,length=2000) private String benefitDescription;
    @Column(name="benefit_value") private Integer benefitValue;
    @Column(name="reward_id") private UUID rewardId;
    @Enumerated(EnumType.STRING) @Column(name="trigger_type",nullable=false,length=24,updatable=false) private PrizeTriggerType triggerType;
    @Column(name="weekly_rank",updatable=false) private Integer weeklyRank;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @Version @Column(nullable=false) private long version;
    protected Prize() { }
    public Prize(String name,boolean active,PrizeBenefitType type,String description,Integer value,UUID rewardId,
            PrizeTriggerType trigger,Integer rank,Instant now) {
        id=UUID.randomUUID();triggerType=java.util.Objects.requireNonNull(trigger);weeklyRank=rank;createdAt=now;
        revise(name,active,type,description,value,rewardId,now);
    }
    public void revise(String name,boolean active,PrizeBenefitType type,String description,Integer value,UUID rewardId,Instant now) {
        if(name==null || name.isBlank() || name.length()>200 || name.indexOf('\0')>=0
                || description==null || description.isBlank() || description.length()>2000 || description.indexOf('\0')>=0
                || type==null || (type==PrizeBenefitType.REWARD && (rewardId==null || value!=null))
                || (type==PrizeBenefitType.PERCENT_DISCOUNT && (rewardId!=null || value==null || value<1 || value>100))
                || (triggerType==PrizeTriggerType.STREAK_MILESTONE && (type!=PrizeBenefitType.REWARD || weeklyRank!=null))
                || (triggerType==PrizeTriggerType.WEEKLY_RANK && (weeklyRank==null || weeklyRank<1 || weeklyRank>4))) {
            throw new IllegalArgumentException("Invalid prize definition");
        }
        java.util.Objects.requireNonNull(now);
        if(createdAt==null || now.isBefore(createdAt) || (updatedAt!=null && now.isBefore(updatedAt))) {throw new IllegalArgumentException("Update clock precedes catalogue time");}
        this.name=name;this.active=active;benefitType=type;benefitDescription=description;benefitValue=value;this.rewardId=rewardId;updatedAt=now;
    }
    public UUID getId() {return id;}
    public String getName() {return name;}
    public boolean isActive() {return active;}
    public PrizeBenefitType getBenefitType() {return benefitType;}
    public String getBenefitDescription() {return benefitDescription;}
    public Integer getBenefitValue() {return benefitValue;}
    public UUID getRewardId() {return rewardId;}
    public PrizeTriggerType getTriggerType() {return triggerType;}
    public Integer getWeeklyRank() {return weeklyRank;}
    public Instant getCreatedAt() {return createdAt;}
    public Instant getUpdatedAt() {return updatedAt;}
    public long getVersion() {return version;}
}
