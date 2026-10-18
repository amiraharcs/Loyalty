package com.amiraharcs.loyalty.game.api;

import com.amiraharcs.loyalty.game.model.GameSession;
import com.amiraharcs.loyalty.game.model.GameSessionStatus;
import java.time.Instant;
import java.util.UUID;

/** Start retries retain their original snapshot; recovery returns current status/lives/time. */
public record GameSessionResponse(UUID sessionId, GameSessionStatus status, Instant startedAt, Instant expiresAt,
        String engineVersion, int remainingLives, Instant nextRechargeAt, Instant serverTime) {
    public static GameSessionResponse from(GameSession session, GameLivesResponse lives) {
        return new GameSessionResponse(session.getId(), session.getStatus(), session.getStartedAt(), session.getExpiresAt(),
                session.getEngineVersion(), lives.availableLives(), lives.nextRechargeAt(), lives.serverTime());
    }
}
