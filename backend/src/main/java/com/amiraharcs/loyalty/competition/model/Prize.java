package com.amiraharcs.loyalty.competition.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Seeded v1 benefit catalogue; no arbitrary cash benefit or assignment source strings. */
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
