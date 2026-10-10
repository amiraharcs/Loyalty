-- Spring Session 4.1 PostgreSQL schema, owned by Flyway rather than auto-initialization.
CREATE TABLE loyalty.spring_session (
    primary_id CHAR(36) NOT NULL PRIMARY KEY,
    session_id CHAR(36) NOT NULL,
    creation_time BIGINT NOT NULL,
    last_access_time BIGINT NOT NULL,
    max_inactive_interval INT NOT NULL,
    expiry_time BIGINT NOT NULL,
    principal_name VARCHAR(100)
);
CREATE UNIQUE INDEX spring_session_ix1 ON loyalty.spring_session (session_id);
CREATE INDEX spring_session_ix2 ON loyalty.spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON loyalty.spring_session (principal_name);
CREATE TABLE loyalty.spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL,
    attribute_name VARCHAR(200) NOT NULL,
    attribute_bytes BYTEA NOT NULL,
    PRIMARY KEY (session_primary_id, attribute_name),
    FOREIGN KEY (session_primary_id) REFERENCES loyalty.spring_session(primary_id) ON DELETE CASCADE
);
-- Cascading is limited to transient attributes of a deleted session, never domain history.
