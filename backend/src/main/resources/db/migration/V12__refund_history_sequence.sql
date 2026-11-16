-- A server clock may return the same microsecond for consecutive transitions.
-- Keep history display in insertion order even when timestamps are identical.
ALTER TABLE refund_history ADD COLUMN sequence_id BIGINT GENERATED ALWAYS AS IDENTITY;
CREATE UNIQUE INDEX refund_history_sequence_idx ON refund_history(sequence_id);
