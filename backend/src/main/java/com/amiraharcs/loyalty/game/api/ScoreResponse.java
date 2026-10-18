package com.amiraharcs.loyalty.game.api;

import com.amiraharcs.loyalty.game.model.*;
import java.time.Instant;
import java.util.UUID;

public record ScoreResponse(UUID scoreId,UUID sessionId,long score,Instant acceptedAt,GameSessionStatus sessionStatus) {
    public static ScoreResponse from(GameScore score) {
        return new ScoreResponse(score.getId(),score.getSessionId(),score.getScore(),score.getAcceptedAt(),GameSessionStatus.COMPLETED);
    }
}
