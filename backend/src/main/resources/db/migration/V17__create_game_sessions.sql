CREATE TABLE loyalty.game_sessions (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES loyalty.customer_profiles(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    started_at TIMESTAMPTZ NOT NULL CHECK (isfinite(started_at)),
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at) AND expires_at > started_at),
    completed_at TIMESTAMPTZ,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','COMPLETED','EXPIRED')),
    engine_version VARCHAR(40) NOT NULL CHECK (engine_version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$'),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_game_session_source UNIQUE (id,customer_id,started_at),
    CONSTRAINT ck_game_session_completion CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL AND isfinite(completed_at)
            AND completed_at >= started_at AND completed_at < expires_at)
        OR (status IN ('ACTIVE','EXPIRED') AND completed_at IS NULL))
);
CREATE UNIQUE INDEX uq_game_session_active_customer ON loyalty.game_sessions(customer_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_game_session_customer_started ON loyalty.game_sessions(customer_id,started_at DESC,id DESC);

-- Preserve pre-V17 evidence without inventing sessions. NOT VALID deliberately grandfathers
-- existing null links but enforces a real link on EVERY new INSERT. Historical rows are immutable.
ALTER TABLE loyalty.game_life_consumptions ADD COLUMN session_id UUID;
ALTER TABLE loyalty.game_life_consumptions ADD CONSTRAINT uq_game_life_session UNIQUE (session_id);
ALTER TABLE loyalty.game_life_consumptions ADD CONSTRAINT fk_game_life_session
    FOREIGN KEY (session_id,customer_id,consumed_at) REFERENCES loyalty.game_sessions(id,customer_id,started_at)
    ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE loyalty.game_life_consumptions ADD CONSTRAINT ck_game_life_session_required
    CHECK (session_id IS NOT NULL) NOT VALID;

-- Paired source evidence is mandatory at commit; session INSERT precedes its consumption.
CREATE FUNCTION loyalty.require_game_life_consumption() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM loyalty.game_life_consumptions WHERE session_id = NEW.id) THEN
        RAISE EXCEPTION 'Game session requires its life consumption'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_game_session_requires_life';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER game_session_requires_life AFTER INSERT ON loyalty.game_sessions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION loyalty.require_game_life_consumption();

CREATE FUNCTION loyalty.guard_game_session_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id,NEW.customer_id,NEW.started_at,NEW.expires_at,NEW.engine_version)
        IS DISTINCT FROM ROW(OLD.id,OLD.customer_id,OLD.started_at,OLD.expires_at,OLD.engine_version)
        OR (OLD.status <> 'ACTIVE' AND ROW(NEW.status,NEW.completed_at) IS DISTINCT FROM ROW(OLD.status,OLD.completed_at)) THEN
        RAISE EXCEPTION 'Game session source and terminal state are immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_game_session_immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER game_session_lifecycle BEFORE UPDATE ON loyalty.game_sessions
    FOR EACH ROW EXECUTE FUNCTION loyalty.guard_game_session_change();
CREATE TRIGGER game_session_no_delete BEFORE DELETE ON loyalty.game_sessions
    FOR EACH STATEMENT EXECUTE FUNCTION loyalty.reject_audit_change();
CREATE TRIGGER game_session_no_truncate BEFORE TRUNCATE ON loyalty.game_sessions
    FOR EACH STATEMENT EXECUTE FUNCTION loyalty.reject_audit_change();

-- No sessions, scores, weekly periods or synthetic consumption links are backfilled.
