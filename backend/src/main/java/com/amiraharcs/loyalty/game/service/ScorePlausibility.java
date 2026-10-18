package com.amiraharcs.loyalty.game.service;

import com.amiraharcs.loyalty.game.model.GameSession;
import java.time.*;
import org.springframework.stereotype.Component;

/** Screening only: a client-reported score cannot prove honest gameplay. */
@Component
public class ScorePlausibility {
    public long ceiling(GameSession session,Instant now) {
        if(!"v1".equals(session.getEngineVersion())) {throw new IllegalStateException("Unsupported game engine version");}
        if(now.isBefore(session.getStartedAt())) {throw new IllegalStateException("Server clock precedes session start");}
        long elapsed=Math.min(900,Duration.between(session.getStartedAt(),now).getSeconds());
        return 350*elapsed+100;
    }
}
