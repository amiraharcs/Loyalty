CREATE TABLE loyalty.rewards (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL CHECK (name ~ '[^[:space:]]'),
    description VARCHAR(2000) NOT NULL,
    point_cost BIGINT NOT NULL CHECK (point_cost BETWEEN 0 AND 9007199254740991),
    active BOOLEAN NOT NULL,
    available_from TIMESTAMPTZ,
    available_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(updated_at) AND updated_at >= created_at),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT ck_rewards_availability CHECK (
        (available_from IS NULL OR (available_from >= TIMESTAMPTZ '0001-01-01 00:00:00+00' AND available_from < TIMESTAMPTZ '10000-01-01 00:00:00+00'))
        AND (available_until IS NULL OR (available_until >= TIMESTAMPTZ '0001-01-01 00:00:00+00' AND available_until < TIMESTAMPTZ '10000-01-01 00:00:00+00'))
        AND (available_from IS NULL OR available_until IS NULL OR available_until > available_from))
);
-- Covers deterministic keyset order; availability is evaluated at request time (never in an index predicate).
CREATE INDEX ix_rewards_catalogue ON loyalty.rewards(active, point_cost, name, id);

-- The architecture's one initial production reward, not a demo catalogue or an entitlement.
INSERT INTO loyalty.rewards(id,name,description,point_cost,active,created_at,updated_at)
VALUES ('e83bb76e-b4bf-470b-a3cc-5fc67495f162','Free coffee','One free coffee.',3000,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
