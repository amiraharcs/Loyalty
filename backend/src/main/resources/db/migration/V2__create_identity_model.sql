-- Identity only. No default users, credentials, sessions or loyalty state.
CREATE TABLE loyalty.users (
    id UUID PRIMARY KEY,
    canonical_email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_users_canonical_email UNIQUE (canonical_email),
    CONSTRAINT ck_users_email_canonical CHECK (
        canonical_email = lower(btrim(canonical_email)) AND canonical_email <> ''),
    CONSTRAINT ck_users_password_hash_nonblank CHECK (btrim(password_hash) <> ''),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_users_timestamps CHECK (updated_at >= created_at)
);
CREATE TABLE loyalty.roles (
    code VARCHAR(16) PRIMARY KEY,
    CONSTRAINT ck_roles_code CHECK (code IN ('CUSTOMER', 'STAFF', 'ADMIN'))
);
INSERT INTO loyalty.roles (code) VALUES ('CUSTOMER'), ('STAFF'), ('ADMIN');
CREATE TABLE loyalty.user_roles (
    user_id UUID NOT NULL REFERENCES loyalty.users(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    role_code VARCHAR(16) NOT NULL REFERENCES loyalty.roles(code) ON DELETE RESTRICT ON UPDATE RESTRICT,
    PRIMARY KEY (user_id, role_code)
);
CREATE INDEX ix_user_roles_role_code ON loyalty.user_roles(role_code);
CREATE TABLE loyalty.customer_profiles (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES loyalty.users(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    display_name VARCHAR(100) NOT NULL,
    leaderboard_alias VARCHAR(100) NOT NULL,
    leaderboard_private BOOLEAN NOT NULL,
    CONSTRAINT ck_customer_profiles_display_name CHECK (btrim(display_name) <> ''),
    CONSTRAINT ck_customer_profiles_alias CHECK (btrim(leaderboard_alias) <> '')
);
