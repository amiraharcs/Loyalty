package com.amiraharcs.loyalty.reward.repository;

import com.amiraharcs.loyalty.reward.model.RewardRedemption;
import java.util.*;
import org.springframework.data.repository.Repository;

public interface RewardRedemptionRepository extends Repository<RewardRedemption,UUID>, RewardRedemptionAppend {
    Optional<RewardRedemption> findByIdAndCustomerId(UUID id, UUID customerId);
}
