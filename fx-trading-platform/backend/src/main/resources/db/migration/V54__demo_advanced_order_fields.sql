ALTER TABLE trading.orders
  ADD COLUMN IF NOT EXISTS post_only BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS activation_price NUMERIC(30, 12),
  ADD COLUMN IF NOT EXISTS trailing_delta NUMERIC(30, 12),
  ADD COLUMN IF NOT EXISTS trailing_rate NUMERIC(18, 10),
  ADD COLUMN IF NOT EXISTS trailing_extreme NUMERIC(30, 12);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'ck_orders_trailing_callback'
      AND conrelid = 'trading.orders'::regclass
  ) THEN
    ALTER TABLE trading.orders
      ADD CONSTRAINT ck_orders_trailing_callback
      CHECK (
        order_type <> 'TRAILING_STOP_MARKET'
        OR ((trailing_delta IS NOT NULL) <> (trailing_rate IS NOT NULL))
      ) NOT VALID;
  END IF;
END
$$;
