-- Retry POST with the same farmer/key returns the same bank method.
CREATE TABLE bank_method_requests (
    farmer_id UUID NOT NULL REFERENCES users(id),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    payment_method_id UUID NOT NULL REFERENCES farmer_payment_methods(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (farmer_id, idempotency_key)
);
