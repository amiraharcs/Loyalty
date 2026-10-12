-- Ledger source references are added with their future owning domains, not unvalidated UUID placeholders.
CREATE TABLE loyalty.loyalty_accounts (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_loyalty_accounts_customer UNIQUE (customer_id),
    CONSTRAINT fk_loyalty_accounts_customer FOREIGN KEY (customer_id)
        REFERENCES loyalty.customer_profiles(id) ON DELETE RESTRICT ON UPDATE RESTRICT
);

-- Existing customers registered before this module receive an empty account, never starter points.
INSERT INTO loyalty.loyalty_accounts (id, customer_id, created_at)
SELECT gen_random_uuid(), id, CURRENT_TIMESTAMP FROM loyalty.customer_profiles;

CREATE TABLE loyalty.point_transactions (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    signed_amount BIGINT NOT NULL,
    type VARCHAR(16) NOT NULL,
    actor_id UUID,
    reason VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_point_transactions_account FOREIGN KEY (account_id)
        REFERENCES loyalty.loyalty_accounts(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_point_transactions_actor FOREIGN KEY (actor_id)
        REFERENCES loyalty.users(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_point_transactions_amount CHECK (signed_amount <> 0
        AND signed_amount BETWEEN -9007199254740991 AND 9007199254740991),
    CONSTRAINT ck_point_transactions_type CHECK (type IN ('EARN', 'REDEMPTION', 'REFUND', 'ADJUSTMENT')),
    CONSTRAINT ck_point_transactions_sign CHECK (
        (type IN ('EARN', 'REFUND') AND signed_amount > 0)
        OR (type = 'REDEMPTION' AND signed_amount < 0) OR type = 'ADJUSTMENT'),
    CONSTRAINT ck_point_transactions_reason CHECK (reason IS NULL OR reason ~ '[^[:space:]]'),
    CONSTRAINT ck_point_transactions_adjustment CHECK (
        type <> 'ADJUSTMENT' OR (actor_id IS NOT NULL AND reason IS NOT NULL))
);
CREATE INDEX ix_point_transactions_history ON loyalty.point_transactions (account_id, created_at DESC, id DESC);
CREATE INDEX ix_point_transactions_actor ON loyalty.point_transactions (actor_id) WHERE actor_id IS NOT NULL;

CREATE FUNCTION loyalty.reject_point_history_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Point history is append-only' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER point_history_immutable BEFORE UPDATE OR DELETE ON loyalty.point_transactions
    FOR EACH STATEMENT EXECUTE FUNCTION loyalty.reject_point_history_change();
CREATE TRIGGER point_history_no_truncate BEFORE TRUNCATE ON loyalty.point_transactions
    FOR EACH STATEMENT EXECUTE FUNCTION loyalty.reject_point_history_change();

-- All inserts serialize on the aggregate. Exact integer SUM is checked before commit, including raw SQL.
-- Nonnegative balance and workflow/source eligibility belong to later authorized mutation services.
CREATE FUNCTION loyalty.check_point_total() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE total BIGINT;
BEGIN
    -- Writing the version also forces stale REPEATABLE READ/SERIALIZABLE writers to retry.
    UPDATE loyalty.loyalty_accounts SET version = version + 1 WHERE id = NEW.account_id;
    SELECT COALESCE(SUM(signed_amount), 0) + NEW.signed_amount INTO total
        FROM loyalty.point_transactions WHERE account_id = NEW.account_id;
    IF total NOT BETWEEN -9007199254740991 AND 9007199254740991 THEN
        RAISE EXCEPTION 'Point total exceeds supported integer range' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER point_total_safe BEFORE INSERT ON loyalty.point_transactions
    FOR EACH ROW EXECUTE FUNCTION loyalty.check_point_total();
