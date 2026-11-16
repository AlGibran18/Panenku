CREATE FUNCTION protect_refund_snapshot() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.order_id, NEW.buyer_id, NEW.idempotency_key, NEW.request_hash, NEW.reason, NEW.evidence_path,
           NEW.refund_amount, NEW.destination_type, NEW.destination_provider, NEW.destination_account, NEW.destination_name, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.order_id, OLD.buyer_id, OLD.idempotency_key, OLD.request_hash, OLD.reason, OLD.evidence_path,
           OLD.refund_amount, OLD.destination_type, OLD.destination_provider, OLD.destination_account, OLD.destination_name, OLD.created_at) THEN
        RAISE EXCEPTION 'Refund snapshots are immutable';
    END IF;
    IF OLD.transfer_proof_path IS NOT NULL AND
       ROW(NEW.transfer_proof_path, NEW.transfer_submitted_at) IS DISTINCT FROM ROW(OLD.transfer_proof_path, OLD.transfer_submitted_at) THEN
        RAISE EXCEPTION 'Refund transfer proof is immutable';
    END IF;
    IF OLD.farmer_decision IS NOT NULL AND
       ROW(NEW.farmer_decision, NEW.farmer_reason, NEW.farmer_decided_at) IS DISTINCT FROM ROW(OLD.farmer_decision, OLD.farmer_reason, OLD.farmer_decided_at) THEN
        RAISE EXCEPTION 'Refund farmer decision is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER refund_snapshot_immutable BEFORE UPDATE ON refunds FOR EACH ROW EXECUTE FUNCTION protect_refund_snapshot();

CREATE FUNCTION protect_refund_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Refund history is immutable';
END;
$$;
CREATE TRIGGER refund_history_immutable BEFORE UPDATE OR DELETE ON refund_history FOR EACH ROW EXECUTE FUNCTION protect_refund_history();
