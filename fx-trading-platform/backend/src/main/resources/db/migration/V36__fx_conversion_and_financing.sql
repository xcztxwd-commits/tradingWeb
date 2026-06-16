CREATE TABLE IF NOT EXISTS trading.fx_conversion_rates (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  from_currency VARCHAR(16) NOT NULL,
  to_currency VARCHAR(16) NOT NULL,
  rate NUMERIC(24, 10) NOT NULL,
  bid NUMERIC(24, 10),
  ask NUMERIC(24, 10),
  effective_at TIMESTAMPTZ NOT NULL,
  source VARCHAR(64) NOT NULL DEFAULT 'manual',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_fx_conversion_rates_pair_time
  ON trading.fx_conversion_rates(from_currency, to_currency, effective_at DESC);

CREATE TABLE IF NOT EXISTS trading.fx_financing_rates (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  symbol VARCHAR(32) NOT NULL,
  long_rate_annual NUMERIC(18, 10) NOT NULL,
  short_rate_annual NUMERIC(18, 10) NOT NULL,
  day_count INTEGER NOT NULL DEFAULT 360,
  effective_date DATE NOT NULL,
  source VARCHAR(64) NOT NULL DEFAULT 'manual',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_fx_financing_rates_symbol_date
  ON trading.fx_financing_rates(symbol, effective_date DESC);

ALTER TABLE trading.positions
  ADD COLUMN IF NOT EXISTS financing_accrued NUMERIC(24, 8) NOT NULL DEFAULT 0;
