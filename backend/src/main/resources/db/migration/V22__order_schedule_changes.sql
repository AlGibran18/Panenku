-- Preserve original checkout snapshots. Approved revisions define a separate effective schedule.
CREATE TABLE order_schedule_changes (
 id UUID PRIMARY KEY, order_id UUID NOT NULL REFERENCES orders(id),
 farmer_id UUID NOT NULL REFERENCES users(id), buyer_id UUID NOT NULL REFERENCES users(id),
 idempotency_key VARCHAR(128) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 base_version BIGINT NOT NULL CHECK(base_version >= 0),
 from_date DATE NOT NULL, from_slot VARCHAR(11) NOT NULL,
 proposed_date DATE NOT NULL, proposed_slot VARCHAR(11) NOT NULL,
 reason VARCHAR(1000) NOT NULL CHECK(length(trim(reason)) > 0),
 status VARCHAR(20) NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','accepted','rejected')),
 created_at TIMESTAMPTZ NOT NULL, decided_at TIMESTAMPTZ, decided_by UUID REFERENCES users(id),
 buyer_reason VARCHAR(1000), accepted_version BIGINT,
 UNIQUE(order_id,farmer_id,idempotency_key), UNIQUE(order_id,accepted_version),
 CHECK ((status='pending' AND decided_at IS NULL AND decided_by IS NULL AND buyer_reason IS NULL AND accepted_version IS NULL)
     OR (status='accepted' AND decided_at IS NOT NULL AND decided_by IS NOT NULL AND decided_by=buyer_id AND accepted_version IS NOT NULL AND accepted_version=base_version+1)
     OR (status='rejected' AND decided_at IS NOT NULL AND decided_by IS NOT NULL AND decided_by=buyer_id AND accepted_version IS NULL))
);
CREATE UNIQUE INDEX schedule_one_pending_idx ON order_schedule_changes(order_id) WHERE status='pending';
CREATE INDEX schedule_order_history_idx ON order_schedule_changes(order_id,created_at,id);
CREATE FUNCTION protect_schedule_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Schedule history cannot be deleted'; END IF;
 IF OLD.status <> 'pending' OR NEW.status NOT IN ('accepted','rejected') OR
 ROW(NEW.id,NEW.order_id,NEW.farmer_id,NEW.buyer_id,NEW.idempotency_key,NEW.request_hash,NEW.base_version,
 NEW.from_date,NEW.from_slot,NEW.proposed_date,NEW.proposed_slot,NEW.reason,NEW.created_at)
 IS DISTINCT FROM ROW(OLD.id,OLD.order_id,OLD.farmer_id,OLD.buyer_id,OLD.idempotency_key,OLD.request_hash,OLD.base_version,
 OLD.from_date,OLD.from_slot,OLD.proposed_date,OLD.proposed_slot,OLD.reason,OLD.created_at)
 THEN RAISE EXCEPTION 'Schedule proposal and completed decisions are immutable'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER schedule_change_immutable BEFORE UPDATE OR DELETE ON order_schedule_changes
 FOR EACH ROW EXECUTE FUNCTION protect_schedule_change();
