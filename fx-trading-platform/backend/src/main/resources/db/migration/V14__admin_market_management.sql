CREATE TABLE market.symbol_categories (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(128) NOT NULL,
  code VARCHAR(64) NOT NULL UNIQUE,
  sort_order INTEGER NOT NULL DEFAULT 0,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE market.symbol_admin_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  symbol_id UUID NOT NULL REFERENCES market.symbols(id),
  admin_user_id UUID NOT NULL REFERENCES auth.users(id),
  event_type VARCHAR(128) NOT NULL,
  before_value TEXT,
  after_value TEXT,
  reason TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE market.price_adjustments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  symbol_id UUID NOT NULL REFERENCES market.symbols(id),
  symbol VARCHAR(64) NOT NULL,
  mode VARCHAR(64) NOT NULL,
  adjustment_type VARCHAR(64) NOT NULL,
  target_price NUMERIC(28, 10) NOT NULL,
  starts_at TIMESTAMPTZ,
  ends_at TIMESTAMPTZ,
  status VARCHAR(32) NOT NULL DEFAULT 'SCHEDULED',
  admin_user_id UUID NOT NULL REFERENCES auth.users(id),
  reason TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_symbol_admin_events_symbol_time ON market.symbol_admin_events(symbol_id, created_at DESC);
CREATE INDEX idx_price_adjustments_symbol_time ON market.price_adjustments(symbol, created_at DESC);
