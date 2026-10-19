-- Only the architecture's five catalogue definitions. No historical entitlements are invented.
CREATE TABLE loyalty.prizes (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL CHECK (name ~ '[^[:space:]]'),
    active BOOLEAN NOT NULL,
    benefit_type VARCHAR(24) NOT NULL CHECK (benefit_type IN ('REWARD','PERCENT_DISCOUNT')),
    benefit_description VARCHAR(2000) NOT NULL CHECK (benefit_description ~ '[^[:space:]]'),
    benefit_value INTEGER,
    reward_id UUID REFERENCES loyalty.rewards(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    trigger_type VARCHAR(24) NOT NULL CHECK (trigger_type IN ('WEEKLY_RANK','STREAK_MILESTONE')),
    weekly_rank INTEGER UNIQUE,
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(updated_at) AND updated_at >= created_at),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_prize_trigger UNIQUE(id,trigger_type),
    CONSTRAINT ck_prize_benefit CHECK (
        (benefit_type='REWARD' AND reward_id IS NOT NULL AND benefit_value IS NULL)
        OR (benefit_type='PERCENT_DISCOUNT' AND reward_id IS NULL AND benefit_value BETWEEN 1 AND 100 AND benefit_value IS NOT NULL)),
    CONSTRAINT ck_prize_trigger CHECK (
        (trigger_type='WEEKLY_RANK' AND weekly_rank BETWEEN 1 AND 4 AND weekly_rank IS NOT NULL)
        OR (trigger_type='STREAK_MILESTONE' AND weekly_rank IS NULL AND benefit_type='REWARD'))
);
CREATE UNIQUE INDEX uq_prize_streak_definition ON loyalty.prizes(trigger_type) WHERE trigger_type='STREAK_MILESTONE';

INSERT INTO loyalty.prizes(id,name,active,benefit_type,benefit_description,benefit_value,reward_id,trigger_type,weekly_rank,created_at,updated_at) VALUES
('f0330000-0000-4000-8000-000000000001','Weekly first place',true,'REWARD','One free coffee.',NULL,'e83bb76e-b4bf-470b-a3cc-5fc67495f162','WEEKLY_RANK',1,now(),now()),
('f0330000-0000-4000-8000-000000000002','Weekly second place',true,'PERCENT_DISCOUNT','50% discount on a coffee.',50,NULL,'WEEKLY_RANK',2,now(),now()),
('f0330000-0000-4000-8000-000000000003','Weekly third place',true,'PERCENT_DISCOUNT','25% discount on a coffee.',25,NULL,'WEEKLY_RANK',3,now(),now()),
('f0330000-0000-4000-8000-000000000004','Weekly fourth place',true,'PERCENT_DISCOUNT','10% discount on a coffee.',10,NULL,'WEEKLY_RANK',4,now(),now()),
('f0330000-0000-4000-8000-000000000010','Ten-day streak',true,'REWARD','One free coffee.',NULL,'e83bb76e-b4bf-470b-a3cc-5fc67495f162','STREAK_MILESTONE',NULL,now(),now());

ALTER TABLE loyalty.visits ADD CONSTRAINT uq_visit_owner UNIQUE(id,customer_id);
CREATE TABLE loyalty.customer_prizes (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES loyalty.customer_profiles(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    prize_id UUID NOT NULL,
    trigger_type VARCHAR(24) NOT NULL,
    trigger_visit_id UUID,
    prize_name_snapshot VARCHAR(200) NOT NULL CHECK (prize_name_snapshot ~ '[^[:space:]]'),
    benefit_type VARCHAR(24) NOT NULL CHECK (benefit_type IN ('REWARD','PERCENT_DISCOUNT')),
    benefit_description_snapshot VARCHAR(2000) NOT NULL CHECK (benefit_description_snapshot ~ '[^[:space:]]'),
    benefit_value INTEGER,
    reward_id UUID REFERENCES loyalty.rewards(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    reward_name_snapshot VARCHAR(200),
    reward_description_snapshot VARCHAR(2000),
    status VARCHAR(16) NOT NULL CHECK (status IN ('AVAILABLE','RESERVED','REDEEMED','EXPIRED','REVOKED')),
    awarded_at TIMESTAMPTZ NOT NULL CHECK (isfinite(awarded_at)),
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at) AND expires_at > awarded_at),
    CONSTRAINT fk_customer_prize_definition FOREIGN KEY(prize_id,trigger_type)
        REFERENCES loyalty.prizes(id,trigger_type) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_customer_prize_visit_owner FOREIGN KEY(trigger_visit_id,customer_id)
        REFERENCES loyalty.visits(id,customer_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT uq_customer_prize_streak UNIQUE(customer_id,trigger_visit_id,prize_id),
    -- Weekly materialisation is deliberately impossible until a real WeeklyPrizeAssignment FK exists.
    CONSTRAINT ck_customer_prize_source CHECK (trigger_type='STREAK_MILESTONE' AND trigger_visit_id IS NOT NULL AND benefit_type='REWARD'),
    CONSTRAINT ck_customer_prize_benefit CHECK (
        (benefit_type='REWARD' AND reward_id IS NOT NULL AND benefit_value IS NULL
            AND reward_name_snapshot IS NOT NULL AND reward_name_snapshot ~ '[^[:space:]]' AND reward_description_snapshot IS NOT NULL)
        OR (benefit_type='PERCENT_DISCOUNT' AND reward_id IS NULL AND benefit_value IS NOT NULL AND benefit_value BETWEEN 1 AND 100
            AND reward_name_snapshot IS NULL AND reward_description_snapshot IS NULL))
);
CREATE INDEX ix_customer_prize_history ON loyalty.customer_prizes(customer_id,awarded_at DESC,id DESC);
CREATE INDEX ix_customer_prize_available_expiry ON loyalty.customer_prizes(customer_id,status,expires_at);

CREATE FUNCTION loyalty.guard_new_customer_prize() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status <> 'AVAILABLE' THEN RAISE EXCEPTION 'New entitlement must be available' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER customer_prize_initial_status BEFORE INSERT ON loyalty.customer_prizes
    FOR EACH ROW EXECUTE FUNCTION loyalty.guard_new_customer_prize();
-- Only issuance exists in this issue. A later migration must introduce explicit operational transitions.
CREATE TRIGGER customer_prize_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON loyalty.customer_prizes
    FOR EACH STATEMENT EXECUTE FUNCTION loyalty.reject_audit_change();
