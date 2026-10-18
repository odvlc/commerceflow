ALTER TABLE orders
    ADD COLUMN public_reason_code varchar(40)
        CHECK (public_reason_code IN
               ('INSUFFICIENT_STOCK', 'INVENTORY_REJECTED', 'PAYMENT_DECLINED', 'PAYMENT_TIMEOUT', 'INVENTORY_TIMEOUT',
                'RESERVATION_EXPIRED', 'CUSTOMER_CANCELLED'));
CREATE TABLE order_status_history
(
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id           uuid        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    version            bigint      NOT NULL,
    status             varchar(40) NOT NULL,
    public_reason_code varchar(40),
    recorded_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (order_id, version)
);
CREATE INDEX order_history_order ON order_status_history (order_id, version);
CREATE FUNCTION capture_public_order_status() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
 IF
TG_OP='INSERT' OR NEW.status IS DISTINCT FROM OLD.status THEN
  INSERT INTO order_status_history(order_id,version,status,public_reason_code)
   VALUES(NEW.id,NEW.version,NEW.status,NEW.public_reason_code);
END IF;
RETURN NEW;
END $$;
CREATE TRIGGER order_public_history
    AFTER INSERT OR
UPDATE OF status
ON orders
    FOR EACH ROW EXECUTE FUNCTION capture_public_order_status();
-- Existing orders deliberately receive no fabricated historical cause or transition rows.
