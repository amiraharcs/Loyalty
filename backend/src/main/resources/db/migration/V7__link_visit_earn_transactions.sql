-- Earlier phases exposed no production visit/award endpoint. Do not invent awards or rewrite history.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM loyalty.visits)
        OR EXISTS (SELECT 1 FROM loyalty.point_transactions WHERE type = 'EARN') THEN
        RAISE EXCEPTION 'V7 requires no legacy unawarded visits or unlinked EARN records'
            USING ERRCODE = '23514';
    END IF;
END;
$$;

ALTER TABLE loyalty.visits ADD COLUMN earned_points BIGINT NOT NULL;
ALTER TABLE loyalty.visits ADD CONSTRAINT ck_visits_earned_points
    CHECK (earned_points BETWEEN 1 AND 9007199254740991);
-- A valid earning source must also match the account, immutable award snapshot and confirming actor.
ALTER TABLE loyalty.visits ADD CONSTRAINT uq_visits_earn_source
    UNIQUE (id, account_id, earned_points, confirmed_by);
ALTER TABLE loyalty.point_transactions ADD COLUMN visit_id UUID;
ALTER TABLE loyalty.point_transactions ADD CONSTRAINT fk_point_transactions_visit_source
    FOREIGN KEY (visit_id, account_id, signed_amount, actor_id)
    REFERENCES loyalty.visits(id, account_id, earned_points, confirmed_by)
    ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE loyalty.point_transactions ADD CONSTRAINT uq_point_transactions_visit_earn UNIQUE (visit_id);
ALTER TABLE loyalty.point_transactions ADD CONSTRAINT ck_point_transactions_visit_type CHECK (
    (type = 'EARN' AND visit_id IS NOT NULL AND actor_id IS NOT NULL)
    OR (type <> 'EARN' AND visit_id IS NULL));

-- INSERTs occur Visit first, EARN second. Check the complete aggregate at commit, never midway through it.
CREATE FUNCTION loyalty.require_visit_earn() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM loyalty.point_transactions WHERE visit_id = NEW.id AND type = 'EARN') THEN
        RAISE EXCEPTION 'A qualifying visit requires its EARN transaction'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_visits_required_earn';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER visit_requires_earn AFTER INSERT ON loyalty.visits
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION loyalty.require_visit_earn();
-- Existing history guards, account-version serialization and safe-total trigger remain in force.
