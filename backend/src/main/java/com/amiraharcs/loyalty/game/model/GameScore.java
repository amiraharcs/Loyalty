package com.amiraharcs.loyalty.game.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import java.util.Objects;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name="game_scores",schema="loyalty")
public class GameScore {
    @Id @Column(nullable=false,updatable=false) private UUID id;
    @Column(name="session_id",nullable=false,updatable=false) private UUID sessionId;
    @Column(nullable=false,updatable=false) private long score;
    @Column(name="accepted_at",nullable=false,updatable=false) private Instant acceptedAt;
    protected GameScore() { }
    public GameScore(UUID sessionId,long score,Instant acceptedAt) {
        if(score<0 || score>9007199254740991L) {throw new IllegalArgumentException("Unsupported score");}
        this.id=UUID.randomUUID();this.sessionId=Objects.requireNonNull(sessionId);
        this.score=score;this.acceptedAt=Objects.requireNonNull(acceptedAt);
    }
    public UUID getId() {return id;}
    public UUID getSessionId() {return sessionId;}
    public long getScore() {return score;}
    public Instant getAcceptedAt() {return acceptedAt;}
}
