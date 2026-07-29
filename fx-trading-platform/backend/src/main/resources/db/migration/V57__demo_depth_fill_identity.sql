ALTER TABLE trading.trades
  ADD COLUMN IF NOT EXISTS fill_identity VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS ux_trades_order_fill_identity
  ON trading.trades(order_id, fill_identity)
  WHERE fill_identity IS NOT NULL;
