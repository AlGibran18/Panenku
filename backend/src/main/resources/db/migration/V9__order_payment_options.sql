-- Preserve every available destination at checkout; the buyer chooses after acceptance.
CREATE TABLE order_payment_options (
    order_id UUID NOT NULL REFERENCES orders(id),
    method_id UUID NOT NULL,
    method_type VARCHAR(20) NOT NULL CHECK (method_type IN ('bank_transfer', 'qris')),
    account_holder_name VARCHAR(255) NOT NULL,
    bank_name VARCHAR(100),
    account_number VARCHAR(50),
    qris_image BYTEA,
    qris_content_type VARCHAR(20),
    PRIMARY KEY (order_id, method_id),
    CHECK ((method_type = 'bank_transfer' AND bank_name IS NOT NULL AND account_number IS NOT NULL
            AND qris_image IS NULL AND qris_content_type IS NULL)
        OR (method_type = 'qris' AND bank_name IS NULL AND account_number IS NULL
            AND qris_image IS NOT NULL AND qris_content_type IN ('image/jpeg','image/png')))
);

CREATE FUNCTION protect_order_payment_option() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Order payment options are immutable';
END;
$$;
CREATE TRIGGER order_payment_options_immutable BEFORE UPDATE OR DELETE ON order_payment_options
    FOR EACH ROW EXECUTE FUNCTION protect_order_payment_option();

-- The selection may change before the first proof. The final snapshot becomes immutable
-- when a proof is submitted, preserving its recipient for later verification/refund.
CREATE TABLE order_payment_selections (
    order_id UUID PRIMARY KEY,
    method_id UUID NOT NULL,
    FOREIGN KEY (order_id, method_id) REFERENCES order_payment_options(order_id, method_id)
);
