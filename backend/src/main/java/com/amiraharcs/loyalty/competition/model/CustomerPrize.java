package com.amiraharcs.loyalty.competition.model;

import com.amiraharcs.loyalty.reward.model.Reward;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;

/** Issuance evidence and frozen benefit. Reservation/redemption are deliberately unavailable. */
@Entity
@Table(name="customer_prizes",schema="loyalty")
public class CustomerPrize {
    public static final Duration VALIDITY=Duration.ofDays(7);
    @Id private UUID id;
    @Column(name="customer_id",nullable=false,updatable=false) private UUID customerId;
    @Column(name="prize_id",nullable=false,updatable=false) private UUID prizeId;
    @Enumerated(EnumType.STRING) @Column(name="trigger_type",nullable=false,length=24,updatable=false) private PrizeTriggerType triggerType;
    @Column(name="trigger_visit_id",updatable=false) private UUID triggerVisitId;
    @Column(name="prize_name_snapshot",nullable=false,length=200,updatable=false) private String prizeNameSnapshot;
    @Enumerated(EnumType.STRING) @Column(name="benefit_type",nullable=false,length=24,updatable=false) private PrizeBenefitType benefitType;
    @Column(name="benefit_description_snapshot",nullable=false,length=2000,updatable=false) private String benefitDescriptionSnapshot;
    @Column(name="benefit_value",updatable=false) private Integer benefitValue;
    @Column(name="reward_id",updatable=false) private UUID rewardId;
    @Column(name="reward_name_snapshot",length=200,updatable=false) private String rewardNameSnapshot;
    @Column(name="reward_description_snapshot",length=2000,updatable=false) private String rewardDescriptionSnapshot;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private CustomerPrizeStatus status;
    @Column(name="awarded_at",nullable=false,updatable=false) private Instant awardedAt;
    @Column(name="expires_at",nullable=false,updatable=false) private Instant expiresAt;
    @Column(name="revocation_audit_id") private UUID revocationAuditId;
    protected CustomerPrize() { }
    public static CustomerPrize streak(UUID customer,UUID visit,Prize prize,Reward reward,Instant now) {
        if(!prize.isActive() || prize.getTriggerType()!=PrizeTriggerType.STREAK_MILESTONE
                || prize.getBenefitType()!=PrizeBenefitType.REWARD || reward==null || !reward.getId().equals(prize.getRewardId())) {
            throw new IllegalArgumentException("Invalid streak prize definition");
        }
        var entitlement=new CustomerPrize();entitlement.id=UUID.randomUUID();
        entitlement.customerId=Objects.requireNonNull(customer);entitlement.triggerVisitId=Objects.requireNonNull(visit);
        entitlement.prizeId=prize.getId();entitlement.triggerType=prize.getTriggerType();
        entitlement.prizeNameSnapshot=prize.getName();entitlement.benefitType=prize.getBenefitType();
        entitlement.benefitDescriptionSnapshot=prize.getBenefitDescription();entitlement.benefitValue=prize.getBenefitValue();
        entitlement.rewardId=reward.getId();entitlement.rewardNameSnapshot=reward.getName();entitlement.rewardDescriptionSnapshot=reward.getDescription();
        entitlement.status=CustomerPrizeStatus.AVAILABLE;
        entitlement.awardedAt=Objects.requireNonNull(now).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        entitlement.expiresAt=entitlement.awardedAt.plus(VALIDITY);return entitlement;
    }
    public void revoke(UUID auditId,Instant now) {
        if(!isAvailableAt(now) || now.isBefore(awardedAt)) {throw new com.amiraharcs.loyalty.common.api.ConflictException();}
        status=CustomerPrizeStatus.REVOKED;revocationAuditId=Objects.requireNonNull(auditId);
    }
    public UUID getRevocationAuditId() {return revocationAuditId;}
    public CustomerPrizeStatus effectiveStatus(Instant now) {
        return status==CustomerPrizeStatus.AVAILABLE && !now.isBefore(expiresAt)?CustomerPrizeStatus.EXPIRED:status;
    }
    public boolean isAvailableAt(Instant now) {return effectiveStatus(now)==CustomerPrizeStatus.AVAILABLE;}
    public UUID getId() {return id;}
    public UUID getCustomerId() {return customerId;}
    public UUID getPrizeId() {return prizeId;}
    public UUID getTriggerVisitId() {return triggerVisitId;}
    public PrizeTriggerType getTriggerType() {return triggerType;}
    public String getPrizeNameSnapshot() {return prizeNameSnapshot;}
    public PrizeBenefitType getBenefitType() {return benefitType;}
    public String getBenefitDescriptionSnapshot() {return benefitDescriptionSnapshot;}
    public Integer getBenefitValue() {return benefitValue;}
    public UUID getRewardId() {return rewardId;}
    public String getRewardNameSnapshot() {return rewardNameSnapshot;}
    public String getRewardDescriptionSnapshot() {return rewardDescriptionSnapshot;}
    public CustomerPrizeStatus getStatus() {return status;}
    public Instant getAwardedAt() {return awardedAt;}
    public Instant getExpiresAt() {return expiresAt;}
}
