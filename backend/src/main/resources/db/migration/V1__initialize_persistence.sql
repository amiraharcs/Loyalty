-- Flyway creates the dedicated schema before running this versioned migration.
-- Domain tables belong to later issues; this establishes schema history only.
COMMENT ON SCHEMA loyalty IS 'Loyalty application schema; managed exclusively by Flyway';
REVOKE CREATE ON SCHEMA loyalty FROM PUBLIC;
