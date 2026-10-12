-- Composite ownership FK prevents combining a valid customer with somebody else's account.
ALTER TABLE loyalty.loyalty_accounts ADD CONSTRAINT uq_loyalty_accounts_id_customer UNIQUE (id, customer_id);
CREATE TABLE loyalty.visits (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    account_id UUID NOT NULL,
    store_id UUID NOT NULL,
    confirmed_by UUID NOT NULL,
    business_date DATE NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_visits_customer FOREIGN KEY (customer_id)
        REFERENCES loyalty.customer_profiles(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_visits_account_customer FOREIGN KEY (account_id, customer_id)
        REFERENCES loyalty.loyalty_accounts(id, customer_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_visits_store FOREIGN KEY (store_id)
        REFERENCES loyalty.stores(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_visits_staff FOREIGN KEY (confirmed_by)
        REFERENCES loyalty.users(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT uq_visits_customer_business_date UNIQUE (customer_id, business_date),
    CONSTRAINT ck_visits_business_date CHECK (business_date = (confirmed_at AT TIME ZONE 'Europe/London')::date)
);
CREATE INDEX ix_visits_customer_history ON loyalty.visits(customer_id, confirmed_at DESC, id DESC);
CREATE INDEX ix_visits_store_history ON loyalty.visits(store_id, confirmed_at DESC, id DESC);
CREATE INDEX ix_visits_staff_history ON loyalty.visits(confirmed_by, confirmed_at DESC, id DESC);
CREATE INDEX ix_visits_business_date ON loyalty.visits(business_date);

CREATE FUNCTION loyalty.reject_visit_history_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Visit history is append-only' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER visit_history_immutable BEFORE UPDATE OR DELETE ON loyalty.visits
    FOR EACH STATEMENT EXECUTE FUNCTION loyalty.reject_visit_history_change();
CREATE TRIGGER visit_history_no_truncate BEFORE TRUNCATE ON loyalty.visits
    FOR EACH STATEMENT EXECUTE FUNCTION loyalty.reject_visit_history_change();
-- No seed visits, point transactions or fictional earned-points snapshot.
