-- Store scope only. No invented production stores, users, credentials or customer activity.
CREATE TABLE loyalty.stores (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL,
    address VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_stores_name_nonblank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_stores_address_nonblank CHECK (btrim(address) <> ''),
    CONSTRAINT ck_stores_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_stores_timestamps CHECK (updated_at >= created_at)
);
CREATE INDEX ix_stores_status_name ON loyalty.stores(status, name, id);

CREATE TABLE loyalty.staff_store (
    staff_user_id UUID NOT NULL,
    store_id UUID NOT NULL,
    active BOOLEAN NOT NULL,
    assigned_by UUID NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_staff_store PRIMARY KEY (staff_user_id, store_id),
    CONSTRAINT fk_staff_store_user FOREIGN KEY (staff_user_id)
        REFERENCES loyalty.users(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_staff_store_store FOREIGN KEY (store_id)
        REFERENCES loyalty.stores(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_staff_store_assigner FOREIGN KEY (assigned_by)
        REFERENCES loyalty.users(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_staff_store_timestamps CHECK (updated_at >= assigned_at)
);
-- The primary key covers unique pair lookup; the partial index covers active staff scope.
CREATE INDEX ix_staff_store_active_staff ON loyalty.staff_store(staff_user_id, store_id) WHERE active;
CREATE INDEX ix_staff_store_store ON loyalty.staff_store(store_id);
CREATE INDEX ix_staff_store_assigner ON loyalty.staff_store(assigned_by);
