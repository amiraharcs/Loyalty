-- Shared owner key is a lockable aggregate, never a mutable lives counter.
CREATE TABLE loyalty.game_life_state (
    customer_id UUID PRIMARY KEY REFERENCES loyalty.customer_profiles(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
);

CREATE TABLE loyalty.game_life_consumptions (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES loyalty.game_life_state(customer_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    consumed_at TIMESTAMPTZ NOT NULL CHECK (isfinite(consumed_at)),
    recharge_at TIMESTAMPTZ NOT NULL CHECK (isfinite(recharge_at)),
    CONSTRAINT ck_game_life_recharge CHECK (recharge_at > consumed_at)
);
CREATE INDEX ix_game_life_customer_recharge ON loyalty.game_life_consumptions(customer_id,recharge_at);

-- Keep evidence after recharge. A future retention policy requires an explicit migration.
CREATE TRIGGER game_life_consumption_immutable BEFORE UPDATE OR DELETE ON loyalty.game_life_consumptions
    FOR EACH STATEMENT EXECUTE FUNCTION loyalty.reject_audit_change();
CREATE TRIGGER game_life_consumption_no_truncate BEFORE TRUNCATE ON loyalty.game_life_consumptions
    FOR EACH STATEMENT EXECUTE FUNCTION loyalty.reject_audit_change();

-- Profiles are retained even if roles/status later change. Do not fabricate consumption history.
INSERT INTO loyalty.game_life_state(customer_id)
SELECT id FROM loyalty.customer_profiles ON CONFLICT (customer_id) DO NOTHING;

-- Session column, unique ownership and FK are deferred to Issue #29, when GameSession exists.
-- Capacity is enforced under the aggregate lock, not by a CHECK depending on database now().
