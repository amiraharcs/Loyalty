-- Earlier phases exposed no redemption writer. Never invent source records or rewrite immutable history.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM loyalty.point_transactions WHERE type = 'REDEMPTION') THEN
        RAISE EXCEPTION 'V14 requires no legacy unlinked REDEMPTION records'
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE TABLE loyalty.reward_redemptions (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES loyalty.customer_profiles(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    account_id UUID NOT NULL,
    store_id UUID NOT NULL REFERENCES loyalty.stores(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    reward_id UUID NOT NULL REFERENCES loyalty.rewards(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    source VARCHAR(16) NOT NULL CHECK (source = 'POINTS'),
    reward_name_snapshot VARCHAR(200) NOT NULL CHECK (reward_name_snapshot ~ '[^[:space:]]'),
    reward_description_snapshot VARCHAR(2000) NOT NULL,
    point_cost_snapshot BIGINT NOT NULL CHECK (point_cost_snapshot BETWEEN 1 AND 9007199254740991),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','FULFILLED','CANCELLED','EXPIRED')),
    requested_at TIMESTAMPTZ NOT NULL CHECK (isfinite(requested_at)),
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at) AND expires_at > requested_at),
    fulfilled_by UUID REFERENCES loyalty.users(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    fulfilled_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT fk_redemptions_account_customer FOREIGN KEY (account_id,customer_id)
        REFERENCES loyalty.loyalty_accounts(id,customer_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_redemptions_fulfilment CHECK (
        (status = 'FULFILLED' AND fulfilled_by IS NOT NULL AND fulfilled_at IS NOT NULL
            AND isfinite(fulfilled_at) AND fulfilled_at >= requested_at AND fulfilled_at < expires_at)
        OR (status <> 'FULFILLED' AND fulfilled_by IS NULL AND fulfilled_at IS NULL))
);
CREATE INDEX ix_redemptions_customer_history ON loyalty.reward_redemptions(customer_id,requested_at DESC,id DESC);
CREATE INDEX ix_redemptions_store_pending ON loyalty.reward_redemptions(store_id,expires_at,id) WHERE status = 'PENDING';
CREATE INDEX ix_redemptions_reward ON loyalty.reward_redemptions(reward_id);

ALTER TABLE loyalty.point_transactions ADD COLUMN redemption_id UUID
    REFERENCES loyalty.reward_redemptions(id) ON DELETE RESTRICT ON UPDATE RESTRICT;
CREATE UNIQUE INDEX uq_point_transactions_redemption_debit ON loyalty.point_transactions(redemption_id)
    WHERE type = 'REDEMPTION';
ALTER TABLE loyalty.point_transactions ADD CONSTRAINT ck_point_transactions_redemption_type CHECK (
    (type = 'REDEMPTION' AND redemption_id IS NOT NULL AND actor_id IS NOT NULL)
    OR (type <> 'REDEMPTION' AND redemption_id IS NULL));

-- Check ownership, immutable cost, actor and server request time, not today's mutable catalogue price.
-- Serialize raw SQL writers on the same account as application services; the existing total guard
-- still advances its version and enforces the exact JavaScript-safe range for every ledger INSERT.
CREATE FUNCTION loyalty.check_redemption_debit_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE redemption loyalty.reward_redemptions%ROWTYPE;
        owner_user UUID;
        total NUMERIC;
BEGIN
    SELECT * INTO redemption FROM loyalty.reward_redemptions WHERE id = NEW.redemption_id;
    IF NOT FOUND OR redemption.account_id <> NEW.account_id
        OR NEW.signed_amount <> -redemption.point_cost_snapshot
        OR NEW.created_at <> redemption.requested_at THEN
        RAISE EXCEPTION 'Redemption debit must match its immutable source'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_redemption_debit_source';
    END IF;
    SELECT user_id INTO owner_user FROM loyalty.customer_profiles WHERE id = redemption.customer_id;
    IF NEW.actor_id IS DISTINCT FROM owner_user THEN
        RAISE EXCEPTION 'Redemption debit actor must own the customer profile'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_redemption_debit_actor';
    END IF;
    PERFORM 1 FROM loyalty.loyalty_accounts WHERE id = NEW.account_id FOR UPDATE;
    SELECT COALESCE(SUM(signed_amount),0) + NEW.signed_amount INTO total
        FROM loyalty.point_transactions WHERE account_id = NEW.account_id;
    IF total < 0 THEN
        RAISE EXCEPTION 'Redemption cannot make the point total negative'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_redemption_nonnegative_total';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER point_redemption_source BEFORE INSERT ON loyalty.point_transactions
    FOR EACH ROW WHEN (NEW.type = 'REDEMPTION') EXECUTE FUNCTION loyalty.check_redemption_debit_source();

-- Redemption INSERT precedes debit INSERT. Require the complete pair at commit, never midway through it.
CREATE FUNCTION loyalty.require_redemption_debit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM loyalty.point_transactions WHERE redemption_id = NEW.id AND type = 'REDEMPTION') THEN
        RAISE EXCEPTION 'A points redemption requires its immutable debit'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_redemptions_required_debit';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER redemption_requires_debit AFTER INSERT ON loyalty.reward_redemptions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION loyalty.require_redemption_debit();

-- Retain source evidence; later lifecycle work may change only status/fulfilment/version.
CREATE FUNCTION loyalty.guard_redemption_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' THEN
            RAISE EXCEPTION 'New redemptions must be pending' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.id,NEW.customer_id,NEW.account_id,NEW.store_id,NEW.reward_id,NEW.source,
           NEW.reward_name_snapshot,NEW.reward_description_snapshot,NEW.point_cost_snapshot,NEW.requested_at,NEW.expires_at)
        IS DISTINCT FROM ROW(OLD.id,OLD.customer_id,OLD.account_id,OLD.store_id,OLD.reward_id,OLD.source,
           OLD.reward_name_snapshot,OLD.reward_description_snapshot,OLD.point_cost_snapshot,OLD.requested_at,OLD.expires_at) THEN
        RAISE EXCEPTION 'Redemption source history is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER redemption_history_guard BEFORE INSERT OR UPDATE ON loyalty.reward_redemptions
    FOR EACH ROW EXECUTE FUNCTION loyalty.guard_redemption_history();
CREATE FUNCTION loyalty.reject_redemption_deletion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Redemption history cannot be deleted' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER redemption_no_delete BEFORE DELETE ON loyalty.reward_redemptions
    FOR EACH STATEMENT EXECUTE FUNCTION loyalty.reject_redemption_deletion();
CREATE TRIGGER redemption_no_truncate BEFORE TRUNCATE ON loyalty.reward_redemptions
    FOR EACH STATEMENT EXECUTE FUNCTION loyalty.reject_redemption_deletion();
