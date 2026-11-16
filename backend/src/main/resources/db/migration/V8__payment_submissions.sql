CREATE TABLE payment_submissions (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id),
    buyer_id UUID NOT NULL REFERENCES users(id),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    expected_amount BIGINT NOT NULL CHECK (expected_amount >= 0),
    proof_image_path VARCHAR(512) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('menunggu_verifikasi', 'bukti_ditolak', 'terverifikasi')),
    farmer_decision VARCHAR(20),
    farmer_decision_reason TEXT,
    submitted_at TIMESTAMPTZ NOT NULL,
    farmer_decided_at TIMESTAMPTZ,
    UNIQUE (buyer_id, idempotency_key),
    CHECK ((status = 'menunggu_verifikasi' AND farmer_decision IS NULL AND farmer_decided_at IS NULL AND farmer_decision_reason IS NULL)
        OR (status = 'bukti_ditolak' AND farmer_decision = 'tolak' AND farmer_decided_at IS NOT NULL
            AND NULLIF(TRIM(farmer_decision_reason), '') IS NOT NULL)
        OR (status = 'terverifikasi' AND farmer_decision = 'terima' AND farmer_decided_at IS NOT NULL))
);
CREATE UNIQUE INDEX payment_submissions_one_pending_per_order ON payment_submissions(order_id) WHERE status = 'menunggu_verifikasi';
CREATE INDEX payment_submissions_order_idx ON payment_submissions(order_id, submitted_at, id);
