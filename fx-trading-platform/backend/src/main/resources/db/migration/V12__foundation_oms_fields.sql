ALTER TABLE trading.orders
  ADD COLUMN IF NOT EXISTS client_order_id VARCHAR(128),
  ADD COLUMN IF NOT EXISTS quantity NUMERIC(12, 4),
  ADD COLUMN IF NOT EXISTS price NUMERIC(24, 10),
  ADD COLUMN IF NOT EXISTS filled_quantity NUMERIC(12, 4) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS remaining_quantity NUMERIC(12, 4),
  ADD COLUMN IF NOT EXISTS avg_fill_price NUMERIC(24, 10),
  ADD COLUMN IF NOT EXISTS hold_amount NUMERIC(24, 8),
  ADD COLUMN IF NOT EXISTS hold_currency VARCHAR(16),
  ADD COLUMN IF NOT EXISTS reject_code VARCHAR(64),
  ADD COLUMN IF NOT EXISTS reject_message TEXT,
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  ADD COLUMN IF NOT EXISTS canceled_at TIMESTAMPTZ;

-- Keep legacy columns populated while new services standardize on client_order_id/quantity/price.
UPDATE trading.orders
SET
  client_order_id = COALESCE(client_order_id, idempotency_key),
  quantity = COALESCE(quantity, lots),
  price = COALESCE(price, requested_price),
  avg_fill_price = COALESCE(avg_fill_price, execution_price),
  remaining_quantity = COALESCE(remaining_quantity, CASE WHEN status = 'FILLED' THEN 0 ELSE lots END),
  hold_currency = COALESCE(hold_currency, 'USD')
WHERE client_order_id IS NULL
   OR quantity IS NULL
   OR price IS NULL
   OR avg_fill_price IS NULL
   OR remaining_quantity IS NULL
   OR hold_currency IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_orders_user_account_client_order_id
  ON trading.orders(user_id, account_id, client_order_id)
  WHERE client_order_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS trading.order_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  order_id UUID NOT NULL REFERENCES trading.orders(id),
  event_type VARCHAR(64) NOT NULL,
  from_status VARCHAR(32),
  to_status VARCHAR(32) NOT NULL,
  reason_code VARCHAR(64),
  message TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_order_events_order_time
  ON trading.order_events(order_id, created_at DESC);
