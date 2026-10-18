-- No production completion writer existed before this issue. Do not invent legacy scores.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM loyalty.game_sessions WHERE status='COMPLETED') THEN
        RAISE EXCEPTION 'V18 requires no legacy unscored COMPLETED sessions';
    END IF;
END $$;

CREATE TABLE loyalty.game_scores (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL UNIQUE REFERENCES loyalty.game_sessions(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    score BIGINT NOT NULL CHECK (score BETWEEN 0 AND 9007199254740991),
    accepted_at TIMESTAMPTZ NOT NULL CHECK (isfinite(accepted_at))
);
CREATE INDEX ix_game_score_best ON loyalty.game_scores(score DESC,session_id);
CREATE TRIGGER game_score_immutable BEFORE UPDATE OR DELETE ON loyalty.game_scores
    FOR EACH STATEMENT EXECUTE FUNCTION loyalty.reject_audit_change();
CREATE TRIGGER game_score_no_truncate BEFORE TRUNCATE ON loyalty.game_scores
    FOR EACH STATEMENT EXECUTE FUNCTION loyalty.reject_audit_change();

-- A deferred cross-table invariant permits either flush order within one transaction.
-- A row CHECK cannot safely express COMPLETED <-> exactly one immutable score.
CREATE FUNCTION loyalty.check_game_score_completion(target UUID) RETURNS void LANGUAGE plpgsql AS $$
DECLARE state loyalty.game_sessions%ROWTYPE;
BEGIN
    SELECT * INTO STRICT state FROM loyalty.game_sessions WHERE id=target;
    IF state.status='COMPLETED' THEN
        IF NOT EXISTS (SELECT 1 FROM loyalty.game_scores WHERE session_id=target AND accepted_at=state.completed_at) THEN
            RAISE EXCEPTION 'Completed session requires matching accepted score' USING ERRCODE='23514',CONSTRAINT='ck_game_score_completion';
        END IF;
    ELSIF EXISTS (SELECT 1 FROM loyalty.game_scores WHERE session_id=target) THEN
        RAISE EXCEPTION 'Accepted score requires completed session' USING ERRCODE='23514',CONSTRAINT='ck_game_score_completion';
    END IF;
END;
$$;
CREATE FUNCTION loyalty.require_game_score_completion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME='game_scores' THEN PERFORM loyalty.check_game_score_completion(NEW.session_id);
    ELSE PERFORM loyalty.check_game_score_completion(NEW.id); END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER game_score_requires_completion AFTER INSERT ON loyalty.game_scores
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION loyalty.require_game_score_completion();
CREATE CONSTRAINT TRIGGER completed_session_requires_score AFTER INSERT OR UPDATE ON loyalty.game_sessions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION loyalty.require_game_score_completion();

CREATE TABLE loyalty.game_submission_events (
    id UUID PRIMARY KEY,
    actor_user_id UUID NOT NULL REFERENCES loyalty.users(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    session_id UUID REFERENCES loyalty.game_sessions(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    submitted_score BIGINT NOT NULL CHECK (submitted_score BETWEEN 0 AND 9007199254740991),
    reason_code VARCHAR(40) NOT NULL CHECK (reason_code IN ('RESOURCE_NOT_FOUND','SESSION_EXPIRED','SCORE_ALREADY_SUBMITTED',
        'SCORE_IMPLAUSIBLE','IDEMPOTENCY_KEY_REUSED','RATE_LIMITED','UNAUTHENTICATED','FORBIDDEN','INTERNAL_ERROR')),
    occurred_at TIMESTAMPTZ NOT NULL CHECK (isfinite(occurred_at)),
    request_id UUID NOT NULL
);
CREATE INDEX ix_game_submission_actor_time ON loyalty.game_submission_events(actor_user_id,occurred_at DESC);
CREATE TRIGGER game_submission_immutable BEFORE UPDATE OR DELETE ON loyalty.game_submission_events
    FOR EACH STATEMENT EXECUTE FUNCTION loyalty.reject_audit_change();
CREATE TRIGGER game_submission_no_truncate BEFORE TRUNCATE ON loyalty.game_submission_events
    FOR EACH STATEMENT EXECUTE FUNCTION loyalty.reject_audit_change();
