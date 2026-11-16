-- A payment destination belongs to the order, not to the mutable farmer profile.
CREATE TABLE order_payment_snapshots (
    order_id UUID PRIMARY KEY REFERENCES orders(id),
    method_id UUID NOT NULL,
    method_type VARCHAR(20) NOT NULL CHECK (method_type IN ('bank_transfer', 'qris')),
    account_holder_name VARCHAR(255) NOT NULL,
    bank_name VARCHAR(100),
    account_number VARCHAR(50),
    qris_image BYTEA,
    qris_content_type VARCHAR(20),
    CHECK ((method_type = 'bank_transfer' AND bank_name IS NOT NULL AND account_number IS NOT NULL
            AND qris_image IS NULL AND qris_content_type IS NULL)
        OR (method_type = 'qris' AND bank_name IS NULL AND account_number IS NULL
            AND qris_image IS NOT NULL AND qris_content_type IN ('image/jpeg','image/png')))
);

CREATE FUNCTION protect_order_payment_snapshot() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Order payment snapshots are immutable';
END;
$$;
CREATE TRIGGER order_payment_snapshot_immutable BEFORE UPDATE OR DELETE ON order_payment_snapshots
    FOR EACH ROW EXECUTE FUNCTION protect_order_payment_snapshot();
