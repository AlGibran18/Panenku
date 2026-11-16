-- A single revision represents a completed admin settings save, not each field.
CREATE TABLE admin_settings_revision (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    revision BIGINT NOT NULL CHECK (revision > 0),
    updated_by UUID REFERENCES users(id),
    updated_at TIMESTAMPTZ
);
INSERT INTO admin_settings_revision(id,revision) VALUES (1,1);
ALTER TABLE audit_logs ADD COLUMN settings_revision BIGINT CHECK (settings_revision > 0);
