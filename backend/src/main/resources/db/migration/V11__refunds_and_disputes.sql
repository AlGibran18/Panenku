CREATE TABLE refunds (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id),
    buyer_id UUID NOT NULL REFERENCES users(id),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    reason TEXT NOT NULL,
    evidence_path VARCHAR(512) NOT NULL,
    refund_amount BIGINT NOT NULL CHECK (refund_amount > 0),
    destination_type VARCHAR(20) NOT NULL CHECK (destination_type IN ('bank', 'e-wallet')),
    destination_provider VARCHAR(100) NOT NULL,
    destination_account VARCHAR(100) NOT NULL,
    destination_name VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL CHECK (status IN ('diajukan','disetujui','ditolak','menunggu_konfirmasi_dana','selesai','dieskalasi')),
    farmer_decision VARCHAR(20),
    farmer_reason TEXT,
    farmer_decided_at TIMESTAMPTZ,
    transfer_proof_path VARCHAR(512),
    transfer_submitted_at TIMESTAMPTZ,
    buyer_confirmed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(buyer_id, idempotency_key),
    CHECK ((farmer_decision IS NULL AND farmer_decided_at IS NULL)
        OR (farmer_decision IN ('terima','tolak') AND farmer_decided_at IS NOT NULL)),
    CHECK (farmer_decision IS DISTINCT FROM 'tolak' OR NULLIF(TRIM(farmer_reason), '') IS NOT NULL),
    CHECK ((transfer_proof_path IS NULL AND transfer_submitted_at IS NULL)
        OR (transfer_proof_path IS NOT NULL AND transfer_submitted_at IS NOT NULL)),
    CHECK (status <> 'selesai' OR (buyer_confirmed_at IS NOT NULL AND transfer_proof_path IS NOT NULL))
);
CREATE UNIQUE INDEX refunds_one_active_per_order ON refunds(order_id)
    WHERE status IN ('diajukan','disetujui','menunggu_konfirmasi_dana','dieskalasi');
CREATE INDEX refunds_order_idx ON refunds(order_id, created_at, id);
CREATE INDEX refunds_buyer_idx ON refunds(buyer_id, created_at DESC, id);

CREATE TABLE refund_history (
    id UUID PRIMARY KEY,
    refund_id UUID NOT NULL REFERENCES refunds(id),
    from_status VARCHAR(30), to_status VARCHAR(30) NOT NULL,
    actor_id UUID NOT NULL REFERENCES users(id), actor_role VARCHAR(20) NOT NULL,
    note TEXT, changed_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX refund_history_refund_idx ON refund_history(refund_id, changed_at, id);

CREATE TABLE refund_transfer_requests (
    farmer_id UUID NOT NULL REFERENCES users(id),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    refund_id UUID NOT NULL REFERENCES refunds(id),
    PRIMARY KEY (farmer_id, idempotency_key)
);

CREATE TABLE refund_disputes (
    id UUID PRIMARY KEY,
    refund_id UUID NOT NULL REFERENCES refunds(id),
    buyer_id UUID NOT NULL REFERENCES users(id),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    reason TEXT NOT NULL,
    previous_refund_status VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('terbuka','selesai')),
    decision VARCHAR(30), decision_reason TEXT,
    decided_by UUID REFERENCES users(id), decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(buyer_id, idempotency_key),
    CHECK ((status='terbuka' AND decision IS NULL AND decided_by IS NULL AND decided_at IS NULL AND decision_reason IS NULL)
        OR (status='selesai' AND decision IN ('setujui_refund','tutup_sengketa')
            AND NULLIF(TRIM(decision_reason),'') IS NOT NULL AND decided_by IS NOT NULL AND decided_at IS NOT NULL))
);
CREATE UNIQUE INDEX refund_disputes_one_open_per_refund ON refund_disputes(refund_id) WHERE status='terbuka';
CREATE INDEX refund_disputes_status_idx ON refund_disputes(status, created_at, id);
