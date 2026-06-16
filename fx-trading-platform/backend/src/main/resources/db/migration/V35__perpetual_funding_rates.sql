CREATE TABLE IF NOT EXISTS trading.funding_rates (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  symbol VARCHAR(32) NOT NULL,
  funding_rate NUMERIC(18, 10) NOT NULL,
  funding_time TIMESTAMPTZ NOT NULL,
  next_funding_time TIMESTAMPTZ,
  mark_price NUMERIC(24, 10) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_funding_rates_symbol_time
  ON trading.funding_rates(symbol, funding_time DESC);

ALTER TABLE trading.positions
  ADD COLUMN IF NOT EXISTS funding_pnl NUMERIC(24, 8) NOT NULL DEFAULT 0;
