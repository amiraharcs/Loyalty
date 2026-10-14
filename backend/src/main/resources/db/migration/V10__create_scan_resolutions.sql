-- Bind a trusted resolution to the exact token/customer pair, retaining restrictive history.
ALTER TABLE loyalty.qr_identity_tokens ADD CONSTRAINT uq_qr_token_customer UNIQUE (jti, customer_id);
CREATE TABLE loyalty.scan_resolutions (
    id UUID PRIMARY KEY,
    token_jti VARCHAR(43) NOT NULL,
    customer_id UUID NOT NULL REFERENCES loyalty.customer_profiles(id) ON DELETE RESTRICT,
    staff_user_id UUID NOT NULL REFERENCES loyalty.users(id) ON DELETE RESTRICT,
    store_id UUID NOT NULL REFERENCES loyalty.stores(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT fk_resolution_token_customer FOREIGN KEY (token_jti, customer_id)
        REFERENCES loyalty.qr_identity_tokens(jti, customer_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_resolution_expiry CHECK (isfinite(created_at) AND isfinite(expires_at)
        AND expires_at = created_at + INTERVAL '2 minutes'),
    CONSTRAINT ck_resolution_consumption CHECK (consumed_at IS NULL OR
        (isfinite(consumed_at) AND consumed_at >= created_at AND consumed_at < expires_at))
);
CREATE INDEX ix_resolution_staff_store_expiry ON loyalty.scan_resolutions(staff_user_id,store_id,expires_at);
CREATE INDEX ix_resolution_token ON loyalty.scan_resolutions(token_jti);
CREATE INDEX ix_resolution_expiry ON loyalty.scan_resolutions(expires_at);
CREATE FUNCTION loyalty.guard_resolution_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.token_jti IS DISTINCT FROM OLD.token_jti
        OR NEW.customer_id IS DISTINCT FROM OLD.customer_id OR NEW.staff_user_id IS DISTINCT FROM OLD.staff_user_id
        OR NEW.store_id IS DISTINCT FROM OLD.store_id OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.expires_at IS DISTINCT FROM OLD.expires_at OR OLD.consumed_at IS NOT NULL
        OR NEW.consumed_at IS NULL THEN
        RAISE EXCEPTION 'Invalid scan resolution transition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER resolution_lifecycle BEFORE UPDATE ON loyalty.scan_resolutions
    FOR EACH ROW EXECUTE FUNCTION loyalty.guard_resolution_change();

-- Failures can lack a trusted token or an existing store/actor. Never preserve the encoded input.
CREATE TABLE loyalty.qr_scan_events (
    id UUID PRIMARY KEY,
    token_jti VARCHAR(43) REFERENCES loyalty.qr_identity_tokens(jti) ON DELETE RESTRICT,
    staff_user_id UUID REFERENCES loyalty.users(id) ON DELETE RESTRICT,
    store_id UUID REFERENCES loyalty.stores(id) ON DELETE RESTRICT,
    occurred_at TIMESTAMPTZ NOT NULL CHECK (isfinite(occurred_at)),
    outcome VARCHAR(32) NOT NULL CHECK (outcome IN ('RESOLUTION_CREATED','VISIT_CONFIRMED','REJECTED')),
    reason_code VARCHAR(64) CHECK (reason_code ~ '^[A-Z_]{1,64}$'),
    request_id UUID NOT NULL,
    CONSTRAINT ck_scan_event_outcome CHECK (
        (outcome = 'REJECTED' AND reason_code IS NOT NULL)
        OR (outcome <> 'REJECTED' AND reason_code IS NULL AND token_jti IS NOT NULL
            AND staff_user_id IS NOT NULL AND store_id IS NOT NULL))
);
CREATE INDEX ix_scan_event_staff_store_time ON loyalty.qr_scan_events(staff_user_id,store_id,occurred_at DESC);
CREATE INDEX ix_scan_event_token_time ON loyalty.qr_scan_events(token_jti,occurred_at DESC);
CREATE INDEX ix_scan_event_request ON loyalty.qr_scan_events(request_id);
CREATE TRIGGER scan_event_immutable BEFORE UPDATE OR DELETE ON loyalty.qr_scan_events
    FOR EACH STATEMENT EXECUTE FUNCTION loyalty.reject_audit_change();
CREATE TRIGGER scan_event_no_truncate BEFORE TRUNCATE ON loyalty.qr_scan_events
    FOR EACH STATEMENT EXECUTE FUNCTION loyalty.reject_audit_change();
