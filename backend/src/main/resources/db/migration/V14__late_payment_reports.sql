-- A buyer claim is not a verified payment and never changes the order or stock.
CREATE TABLE late_payment_reports (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id),
    buyer_id UUID NOT NULL REFERENCES users(id),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    reason TEXT NOT NULL,
    claimed_amount BIGINT NOT NULL CHECK (claimed_amount > 0),
    evidence_path VARCHAR(512) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('diajukan','tindak_lanjut','ditutup')),
    review_decision VARCHAR(30), review_reason TEXT,
    reviewed_by UUID REFERENCES users(id), reviewed_at TIMESTAMPTZ,
    close_reason TEXT, closed_by UUID REFERENCES users(id), closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(buyer_id, idempotency_key),
    CHECK ((status='diajukan' AND review_decision IS NULL AND review_reason IS NULL AND reviewed_by IS NULL AND reviewed_at IS NULL
            AND close_reason IS NULL AND closed_by IS NULL AND closed_at IS NULL)
        OR (status='tindak_lanjut' AND review_decision='tindak_lanjut' AND NULLIF(TRIM(review_reason),'') IS NOT NULL
            AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND close_reason IS NULL AND closed_by IS NULL AND closed_at IS NULL)
        OR (status='ditutup' AND review_decision IN ('tindak_lanjut','bukti_tidak_cukup') AND NULLIF(TRIM(review_reason),'') IS NOT NULL
            AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND NULLIF(TRIM(close_reason),'') IS NOT NULL
            AND closed_by IS NOT NULL AND closed_at IS NOT NULL))
);
CREATE UNIQUE INDEX late_payment_reports_one_open ON late_payment_reports(order_id) WHERE status IN ('diajukan','tindak_lanjut');
CREATE INDEX late_payment_reports_buyer_idx ON late_payment_reports(buyer_id,created_at DESC,id);
CREATE INDEX late_payment_reports_status_idx ON late_payment_reports(status,created_at,id);

CREATE FUNCTION protect_late_payment_report() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.order_id,NEW.buyer_id,NEW.idempotency_key,NEW.request_hash,NEW.reason,NEW.claimed_amount,NEW.evidence_path,NEW.created_at)
       IS DISTINCT FROM ROW(OLD.order_id,OLD.buyer_id,OLD.idempotency_key,OLD.request_hash,OLD.reason,OLD.claimed_amount,OLD.evidence_path,OLD.created_at) THEN
        RAISE EXCEPTION 'Late payment report evidence is immutable';
    END IF;
    IF OLD.reviewed_at IS NOT NULL AND ROW(NEW.review_decision,NEW.review_reason,NEW.reviewed_by,NEW.reviewed_at)
       IS DISTINCT FROM ROW(OLD.review_decision,OLD.review_reason,OLD.reviewed_by,OLD.reviewed_at) THEN
        RAISE EXCEPTION 'Late payment review is immutable';
    END IF;
    IF OLD.closed_at IS NOT NULL AND ROW(NEW.close_reason,NEW.closed_by,NEW.closed_at)
       IS DISTINCT FROM ROW(OLD.close_reason,OLD.closed_by,OLD.closed_at) THEN
        RAISE EXCEPTION 'Late payment closure is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER late_payment_report_immutable BEFORE UPDATE ON late_payment_reports
    FOR EACH ROW EXECUTE FUNCTION protect_late_payment_report();
