-- London civil weeks are not fixed 168-hour UTC intervals across DST.
CREATE TABLE loyalty.weekly_periods (
    id UUID PRIMARY KEY,
    start_date DATE NOT NULL UNIQUE CHECK (isfinite(start_date) AND extract(isodow FROM start_date) = 1),
    start_at TIMESTAMPTZ NOT NULL UNIQUE CHECK (isfinite(start_at)),
    end_at TIMESTAMPTZ NOT NULL CHECK (isfinite(end_at) AND end_at > start_at),
    settlement_cutoff_at TIMESTAMPTZ NOT NULL CHECK (isfinite(settlement_cutoff_at) AND settlement_cutoff_at >= end_at),
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','SETTLED')),
    CONSTRAINT ck_weekly_period_boundaries CHECK (
        start_at = start_date::timestamp AT TIME ZONE 'Europe/London'
        AND end_at = (start_date + 7)::timestamp AT TIME ZONE 'Europe/London'
        AND settlement_cutoff_at = end_at + interval '15 minutes')
);

-- The same get-or-create path serves new runs, historical backfill and read setup.
-- UUID is deterministic from the local Monday; ON CONFLICT serialises competing creators.
CREATE FUNCTION loyalty.ensure_weekly_period(at_time TIMESTAMPTZ) RETURNS UUID LANGUAGE plpgsql AS $$
DECLARE monday DATE; period_id UUID;
BEGIN
    IF at_time IS NULL OR NOT isfinite(at_time) THEN RAISE EXCEPTION 'Finite server timestamp required' USING ERRCODE='23514'; END IF;
    monday := date_trunc('week', at_time AT TIME ZONE 'Europe/London')::date;
    INSERT INTO loyalty.weekly_periods(id,start_date,start_at,end_at,settlement_cutoff_at)
        VALUES (md5('loyalty.weekly-period:' || to_char(monday,'YYYY-MM-DD'))::uuid, monday,
            monday::timestamp AT TIME ZONE 'Europe/London',
            (monday + 7)::timestamp AT TIME ZONE 'Europe/London',
            ((monday + 7)::timestamp AT TIME ZONE 'Europe/London') + interval '15 minutes')
        ON CONFLICT (start_date) DO NOTHING;
    SELECT id INTO STRICT period_id FROM loyalty.weekly_periods WHERE start_date=monday;
    RETURN period_id;
END;
$$;

ALTER TABLE loyalty.game_sessions ADD COLUMN weekly_period_id UUID;
UPDATE loyalty.game_sessions SET weekly_period_id=loyalty.ensure_weekly_period(started_at);
-- Drain V18 deferred completion checks before further DDL on the backfilled table.
SET CONSTRAINTS ALL IMMEDIATE;
ALTER TABLE loyalty.game_sessions ALTER COLUMN weekly_period_id SET NOT NULL;
ALTER TABLE loyalty.game_sessions ADD CONSTRAINT fk_game_session_weekly_period
    FOREIGN KEY (weekly_period_id) REFERENCES loyalty.weekly_periods(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

-- Database-owned membership cannot be forged, even by a future writer bypassing JPA.
CREATE FUNCTION loyalty.assign_game_weekly_period() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected UUID;
BEGIN
    expected := loyalty.ensure_weekly_period(NEW.started_at);
    IF NEW.weekly_period_id IS NOT NULL AND NEW.weekly_period_id <> expected THEN
        RAISE EXCEPTION 'Session period must match server start time' USING ERRCODE='23514';
    END IF;
    NEW.weekly_period_id := expected;
    RETURN NEW;
END;
$$;
CREATE TRIGGER game_session_assign_week BEFORE INSERT ON loyalty.game_sessions
    FOR EACH ROW EXECUTE FUNCTION loyalty.assign_game_weekly_period();
CREATE FUNCTION loyalty.guard_game_weekly_period() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.weekly_period_id IS DISTINCT FROM OLD.weekly_period_id THEN
        RAISE EXCEPTION 'Session period is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER game_session_week_immutable BEFORE UPDATE ON loyalty.game_sessions
    FOR EACH ROW EXECUTE FUNCTION loyalty.guard_game_weekly_period();
CREATE FUNCTION loyalty.guard_weekly_period() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id,NEW.start_date,NEW.start_at,NEW.end_at,NEW.settlement_cutoff_at)
        IS DISTINCT FROM ROW(OLD.id,OLD.start_date,OLD.start_at,OLD.end_at,OLD.settlement_cutoff_at)
        OR (OLD.status='SETTLED' AND NEW.status <> OLD.status) THEN
        RAISE EXCEPTION 'Period boundaries and settled state are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER weekly_period_immutable BEFORE UPDATE ON loyalty.weekly_periods
    FOR EACH ROW EXECUTE FUNCTION loyalty.guard_weekly_period();
CREATE TRIGGER weekly_period_no_delete BEFORE DELETE OR TRUNCATE ON loyalty.weekly_periods
    FOR EACH STATEMENT EXECUTE FUNCTION loyalty.reject_audit_change();

-- Session PK and unique game_scores.session_id already support the score join.
-- One additional index narrows weekly scans; no redundant score-wide indexes.
CREATE INDEX ix_game_session_week_customer ON loyalty.game_sessions(weekly_period_id,customer_id,id)
    WHERE status='COMPLETED';
