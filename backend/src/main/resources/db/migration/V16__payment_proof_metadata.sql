-- Existing proofs did not retain the original name or actual byte count.
ALTER TABLE payment_submissions ADD COLUMN original_file_name VARCHAR(255);
ALTER TABLE payment_submissions ADD COLUMN file_size_bytes BIGINT CHECK (file_size_bytes > 0 AND file_size_bytes <= 5242880);
