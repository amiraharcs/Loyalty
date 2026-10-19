-- Retain inactive definitions/history; only one definition per slot may be active.
ALTER TABLE loyalty.prizes DROP CONSTRAINT prizes_weekly_rank_key;
DROP INDEX loyalty.uq_prize_streak_definition;
CREATE UNIQUE INDEX uq_prize_active_weekly_rank ON loyalty.prizes(weekly_rank) WHERE active AND trigger_type='WEEKLY_RANK';
CREATE UNIQUE INDEX uq_prize_active_streak ON loyalty.prizes(trigger_type) WHERE active AND trigger_type='STREAK_MILESTONE';
CREATE INDEX ix_prize_admin_history ON loyalty.prizes(created_at DESC,id DESC);
CREATE FUNCTION loyalty.guard_prize_catalogue() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id,NEW.trigger_type,NEW.weekly_rank,NEW.created_at) IS DISTINCT FROM ROW(OLD.id,OLD.trigger_type,OLD.weekly_rank,OLD.created_at) THEN
        RAISE EXCEPTION 'Prize identity and configuration slot are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER prize_catalogue_identity BEFORE UPDATE ON loyalty.prizes FOR EACH ROW EXECUTE FUNCTION loyalty.guard_prize_catalogue();
CREATE TRIGGER prize_catalogue_no_delete BEFORE DELETE OR TRUNCATE ON loyalty.prizes FOR EACH STATEMENT EXECUTE FUNCTION loyalty.reject_audit_change();

CREATE INDEX ix_customer_prize_admin_history ON loyalty.customer_prizes(awarded_at DESC,id DESC);
ALTER TABLE loyalty.customer_prizes ADD COLUMN revocation_audit_id UUID UNIQUE
    REFERENCES loyalty.audit_logs(id) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE loyalty.customer_prizes ADD CONSTRAINT ck_customer_prize_revocation CHECK (
    (status='REVOKED' AND revocation_audit_id IS NOT NULL) OR (status<>'REVOKED' AND revocation_audit_id IS NULL));
-- A milestone remains one entitlement even if an admin later replaces the active definition.
ALTER TABLE loyalty.customer_prizes ADD CONSTRAINT uq_customer_prize_visit UNIQUE(customer_id,trigger_visit_id);
DROP TRIGGER customer_prize_immutable ON loyalty.customer_prizes;
CREATE TRIGGER customer_prize_no_delete BEFORE DELETE OR TRUNCATE ON loyalty.customer_prizes
    FOR EACH STATEMENT EXECUTE FUNCTION loyalty.reject_audit_change();
CREATE FUNCTION loyalty.guard_customer_prize_revocation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE event loyalty.audit_logs%ROWTYPE;
BEGIN
    IF (to_jsonb(NEW)-'status'-'revocation_audit_id') IS DISTINCT FROM (to_jsonb(OLD)-'status'-'revocation_audit_id')
        OR OLD.status<>'AVAILABLE' OR NEW.status<>'REVOKED' OR OLD.revocation_audit_id IS NOT NULL OR NEW.revocation_audit_id IS NULL THEN
        RAISE EXCEPTION 'Entitlement snapshots and terminal state are immutable' USING ERRCODE='23514';
    END IF;
    SELECT * INTO event FROM loyalty.audit_logs WHERE id=NEW.revocation_audit_id;
    IF NOT FOUND OR event.action IS DISTINCT FROM 'CUSTOMER_PRIZE_REVOKED' OR event.target_type IS DISTINCT FROM 'CUSTOMER_PRIZE' OR event.target_id IS DISTINCT FROM NEW.id
        OR event.actor_type IS DISTINCT FROM 'USER' OR event.actor_user_id IS NULL OR event.occurred_at<OLD.awarded_at OR event.occurred_at>=OLD.expires_at
        OR jsonb_typeof(event.metadata->'reason') IS DISTINCT FROM 'string'
        OR length(btrim(event.metadata->>'reason')) NOT BETWEEN 1 AND 1000
        OR NOT ((event.metadata->>'reason') ~ '[^[:space:]]') THEN
        RAISE EXCEPTION 'Revocation requires matching unexpired audit evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER customer_prize_revocation_only BEFORE UPDATE ON loyalty.customer_prizes
    FOR EACH ROW EXECUTE FUNCTION loyalty.guard_customer_prize_revocation();
