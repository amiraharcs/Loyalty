-- V5/V7 already enforce adjustment actor/reason/amount/no-visit and ledger immutability.
-- Successful privileged mutations now have durable audit and retry records in the same transaction.
CREATE TABLE loyalty.audit_logs (
    id UUID PRIMARY KEY,
    actor_user_id UUID,
    actor_type VARCHAR(16) NOT NULL CHECK (actor_type IN ('USER', 'SYSTEM')),
    action VARCHAR(100) NOT NULL CHECK (action ~ '[^[:space:]]'),
    target_type VARCHAR(100) NOT NULL CHECK (target_type ~ '[^[:space:]]'),
    target_id UUID NOT NULL,
    store_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL,
    request_id UUID NOT NULL,
    metadata JSONB NOT NULL CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT ck_audit_actor CHECK ((actor_type = 'USER' AND actor_user_id IS NOT NULL)
        OR (actor_type = 'SYSTEM' AND actor_user_id IS NULL)),
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_user_id) REFERENCES loyalty.users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_audit_store FOREIGN KEY (store_id) REFERENCES loyalty.stores(id) ON DELETE RESTRICT
);
CREATE INDEX ix_audit_actor_time ON loyalty.audit_logs(actor_user_id, occurred_at DESC, id DESC);
CREATE INDEX ix_audit_target_time ON loyalty.audit_logs(target_type, target_id, occurred_at DESC, id DESC);
CREATE FUNCTION loyalty.reject_audit_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Audit history is append-only' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER audit_immutable BEFORE UPDATE OR DELETE ON loyalty.audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION loyalty.reject_audit_change();
CREATE TRIGGER audit_no_truncate BEFORE TRUNCATE ON loyalty.audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION loyalty.reject_audit_change();

CREATE TABLE loyalty.idempotency_records (
    actor_id UUID NOT NULL REFERENCES loyalty.users(id) ON DELETE RESTRICT,
    operation VARCHAR(100) NOT NULL,
    key UUID NOT NULL,
    request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    response_body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at >= created_at + INTERVAL '24 hours'),
    PRIMARY KEY (actor_id, operation, key)
);
CREATE INDEX ix_idempotency_expiry ON loyalty.idempotency_records(expires_at);
