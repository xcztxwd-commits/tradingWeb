CREATE TABLE market.symbols (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  symbol VARCHAR(32) NOT NULL UNIQUE,
  display_name VARCHAR(64) NOT NULL,
  provider VARCHAR(32) NOT NULL DEFAULT 'massive',
  provider_symbol VARCHAR(64) NOT NULL,
  asset_class VARCHAR(32) NOT NULL DEFAULT 'FOREX',
  base_currency VARCHAR(16) NOT NULL,
  quote_currency VARCHAR(16) NOT NULL,
  pip_size NUMERIC(18, 10) NOT NULL,
  tick_size NUMERIC(18, 10) NOT NULL,
  lot_size NUMERIC(24, 8) NOT NULL DEFAULT 100000,
  min_lot NUMERIC(12, 4) NOT NULL DEFAULT 0.01,
  max_lot NUMERIC(12, 4) NOT NULL DEFAULT 100,
  leverage INTEGER NOT NULL DEFAULT 100,
  spread_markup NUMERIC(18, 10) NOT NULL DEFAULT 0,
  enabled BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE market.candles (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  symbol VARCHAR(32) NOT NULL,
  timeframe VARCHAR(16) NOT NULL,
  open_time TIMESTAMPTZ NOT NULL,
  open NUMERIC(24, 10) NOT NULL,
  high NUMERIC(24, 10) NOT NULL,
  low NUMERIC(24, 10) NOT NULL,
  close NUMERIC(24, 10) NOT NULL,
  volume NUMERIC(24, 8) NOT NULL DEFAULT 0,
  source VARCHAR(32) NOT NULL DEFAULT 'massive',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(symbol, timeframe, open_time)
);

CREATE INDEX idx_candles_symbol_timeframe_time ON market.candles(symbol, timeframe, open_time);
