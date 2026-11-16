-- Keep the method row for idempotency records while excluding it from new orders.
ALTER TABLE farmer_payment_methods ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT true;
