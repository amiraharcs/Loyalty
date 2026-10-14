-- Opaque references only: never store the encoded JWS or customer data in claims.
CREATE TABLE loyalty.qr_identity_tokens (
    jti VARCHAR(43) PRIMARY KEY CHECK (jti ~ '^[A-Za-z0-9_-]{43}$'),
    customer_id UUID NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    key_id VARCHAR(64) NOT NULL CHECK (key_id ~ '^[A-Za-z0-9._-]{1,64}$'),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED', 'CONSUMED')),
    revoked_at TIMESTAMPTZ,
    CONSTRAINT fk_qr_tokens_customer FOREIGN KEY (customer_id)
        REFERENCES loyalty.customer_profiles(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_qr_tokens_lifetime CHECK (isfinite(issued_at) AND isfinite(expires_at)
        AND expires_at = issued_at + INTERVAL '60 seconds'
        AND issued_at = date_trunc('second', issued_at)),
    CONSTRAINT ck_qr_tokens_revocation CHECK (
        (status = 'REVOKED' AND revoked_at IS NOT NULL AND isfinite(revoked_at) AND revoked_at >= issued_at)
        OR (status <> 'REVOKED' AND revoked_at IS NULL))
);
-- Expiry is evaluated at read time; replacement revokes even expired ACTIVE rows.
CREATE UNIQUE INDEX uq_qr_tokens_current_customer ON loyalty.qr_identity_tokens(customer_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_qr_tokens_customer_time ON loyalty.qr_identity_tokens(customer_id, issued_at DESC);
CREATE INDEX ix_qr_tokens_expiry ON loyalty.qr_identity_tokens(expires_at);

CREATE FUNCTION loyalty.guard_qr_token_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.jti IS DISTINCT FROM OLD.jti OR NEW.customer_id IS DISTINCT FROM OLD.customer_id
        OR NEW.issued_at IS DISTINCT FROM OLD.issued_at OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
        OR NEW.key_id IS DISTINCT FROM OLD.key_id OR OLD.status <> 'ACTIVE'
        OR NEW.status NOT IN ('REVOKED', 'CONSUMED') THEN
        RAISE EXCEPTION 'Invalid QR lifecycle transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER qr_token_lifecycle BEFORE UPDATE ON loyalty.qr_identity_tokens
    FOR EACH ROW EXECUTE FUNCTION loyalty.guard_qr_token_transition();
