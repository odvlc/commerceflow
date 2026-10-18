ALTER TABLE outbox_events ADD COLUMN sequence bigint GENERATED ALWAYS AS IDENTITY;
ALTER TABLE outbox_events ADD COLUMN attempts integer NOT NULL DEFAULT 0;
ALTER TABLE outbox_events ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE outbox_events ADD COLUMN last_error varchar(500);
CREATE INDEX outbox_sequence_idx ON outbox_events(aggregate_id,sequence) WHERE published_at IS NULL;
