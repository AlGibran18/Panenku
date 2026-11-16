-- Development-only sample identity applications; never use real national identifiers.
CREATE TABLE ktp_dummy_applications (
    sequence_id BIGSERIAL UNIQUE NOT NULL,
    id UUID PRIMARY KEY,
    farmer_id UUID NOT NULL REFERENCES users(id),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    dummy_name VARCHAR(255) NOT NULL,
    dummy_nik VARCHAR(40) NOT NULL,
    image_path VARCHAR(512) NOT NULL,
    file_name VARCHAR(255),
    file_size BIGINT NOT NULL CHECK (file_size BETWEEN 1 AND 5242880),
    submitted_at TIMESTAMPTZ NOT NULL,
    UNIQUE(farmer_id,idempotency_key)
);
CREATE INDEX ktp_dummy_applications_farmer_sequence_idx ON ktp_dummy_applications(farmer_id,sequence_id DESC);
