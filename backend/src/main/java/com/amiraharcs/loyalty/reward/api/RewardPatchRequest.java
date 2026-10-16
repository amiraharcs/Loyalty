package com.amiraharcs.loyalty.reward.api;

import com.amiraharcs.loyalty.reward.model.Reward;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

/** Presence tracking distinguishes omitted bounds from an explicit JSON null (clear). */
public class RewardPatchRequest {
    private final Set<String> fields = new TreeSet<>();
    private String name;
    private String description;
    private BigInteger pointCost;
    private Boolean active;
    private Instant availableFrom;
    private Instant availableUntil;
    @JsonSetter public void setName(String value) { fields.add("name"); name = value; }
    @JsonSetter public void setDescription(String value) { fields.add("description"); description = value; }
    @JsonSetter public void setPointCost(BigInteger value) { fields.add("pointCost"); pointCost = value; }
    @JsonSetter public void setActive(Boolean value) { fields.add("active"); active = value; }
    @JsonSetter public void setAvailableFrom(Instant value) { fields.add("availableFrom"); availableFrom = value; }
    @JsonSetter public void setAvailableUntil(Instant value) { fields.add("availableUntil"); availableUntil = value; }
    public List<String> fields() { return List.copyOf(fields); }
    public RewardRequest merge(Reward reward) {
        return new RewardRequest(fields.contains("name") ? name : reward.getName(),
                fields.contains("description") ? description : reward.getDescription(),
                fields.contains("pointCost") ? pointCost : BigInteger.valueOf(reward.getPointCost()),
                fields.contains("active") ? active : Boolean.valueOf(reward.isActive()),
                fields.contains("availableFrom") ? availableFrom : reward.getAvailableFrom(),
                fields.contains("availableUntil") ? availableUntil : reward.getAvailableUntil());
    }
}
