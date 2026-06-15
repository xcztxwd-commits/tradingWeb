CREATE TABLE IF NOT EXISTS market.data_providers (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code VARCHAR(32) NOT NULL UNIQUE,
  name VARCHAR(64) NOT NULL,
  provider_type VARCHAR(32) NOT NULL,
  asset_classes TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  rest_base_url TEXT,
  ws_url TEXT,
  enabled BOOLEAN NOT NULL DEFAULT true,
  priority INTEGER NOT NULL DEFAULT 100,
  timeout_ms INTEGER NOT NULL DEFAULT 5000,
  rate_limit_per_minute INTEGER NOT NULL DEFAULT 1200,
  health_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
  last_health_check_at TIMESTAMPTZ,
  config_json JSONB NOT NULL DEFAULT '{}'::JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS market.data_provider_capabilities (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  provider_id UUID NOT NULL REFERENCES market.data_providers(id) ON DELETE CASCADE,
  capability VARCHAR(48) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT true,
  config_json JSONB NOT NULL DEFAULT '{}'::JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(provider_id, capability)
);

CREATE TABLE IF NOT EXISTS market.provider_instruments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  provider_id UUID NOT NULL REFERENCES market.data_providers(id) ON DELETE CASCADE,
  provider_symbol VARCHAR(96) NOT NULL,
  asset_class VARCHAR(32) NOT NULL,
  base_asset VARCHAR(32),
  quote_asset VARCHAR(32),
  display_name VARCHAR(96),
  listed BOOLEAN NOT NULL DEFAULT true,
  raw_json JSONB NOT NULL DEFAULT '{}'::JSONB,
  last_synced_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(provider_id, provider_symbol)
);

CREATE TABLE IF NOT EXISTS market.symbol_provider_bindings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  symbol_id UUID NOT NULL REFERENCES market.symbols(id) ON DELETE CASCADE,
  provider_id UUID NOT NULL REFERENCES market.data_providers(id),
  provider_instrument_id UUID REFERENCES market.provider_instruments(id),
  provider_symbol VARCHAR(96) NOT NULL,
  priority INTEGER NOT NULL DEFAULT 100,
  enabled BOOLEAN NOT NULL DEFAULT true,
  config_json JSONB NOT NULL DEFAULT '{}'::JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(symbol_id, provider_id, provider_symbol)
);

ALTER TABLE market.symbols
  ADD COLUMN IF NOT EXISTS icon_url TEXT,
  ADD COLUMN IF NOT EXISTS icon_asset_id UUID,
  ADD COLUMN IF NOT EXISTS display_enabled BOOLEAN NOT NULL DEFAULT true,
  ADD COLUMN IF NOT EXISTS quote_enabled BOOLEAN NOT NULL DEFAULT true,
  ADD COLUMN IF NOT EXISTS chart_enabled BOOLEAN NOT NULL DEFAULT true,
  ADD COLUMN IF NOT EXISTS order_book_enabled BOOLEAN NOT NULL DEFAULT true,
  ADD COLUMN IF NOT EXISTS tradable BOOLEAN NOT NULL DEFAULT true,
  ADD COLUMN IF NOT EXISTS featured BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS display_group VARCHAR(64),
  ADD COLUMN IF NOT EXISTS display_order INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_data_providers_enabled_priority
  ON market.data_providers(enabled, priority ASC);

CREATE INDEX IF NOT EXISTS idx_provider_instruments_provider_asset
  ON market.provider_instruments(provider_id, asset_class, listed);

CREATE INDEX IF NOT EXISTS idx_symbol_provider_bindings_symbol_priority
  ON market.symbol_provider_bindings(symbol_id, enabled, priority ASC);

CREATE INDEX IF NOT EXISTS idx_symbols_display
  ON market.symbols(display_enabled, asset_class, display_order ASC, symbol ASC);

INSERT INTO market.data_providers (code, name, provider_type, asset_classes, rest_base_url, enabled, priority, health_status)
VALUES
  ('massive', 'Massive Forex', 'REST', ARRAY['FOREX'], 'https://api.massive.com', true, 10, 'UNKNOWN'),
  ('binance', 'Binance Spot', 'REST_WS', ARRAY['CRYPTO'], 'https://api.binance.com', true, 20, 'UNKNOWN'),
  ('demo', 'Demo Market Data', 'LOCAL', ARRAY['FOREX','CRYPTO','METAL'], null, false, 900, 'UNKNOWN')
ON CONFLICT (code) DO NOTHING;

INSERT INTO market.data_provider_capabilities (provider_id, capability, enabled)
SELECT id, capability, true
FROM market.data_providers
CROSS JOIN (
  VALUES ('SYMBOLS'), ('QUOTE'), ('SNAPSHOT'), ('CANDLES')
) AS caps(capability)
WHERE code IN ('massive', 'binance', 'demo')
ON CONFLICT (provider_id, capability) DO NOTHING;

INSERT INTO market.data_provider_capabilities (provider_id, capability, enabled)
SELECT id, capability, true
FROM market.data_providers
CROSS JOIN (
  VALUES ('ORDER_BOOK'), ('TRADES')
) AS caps(capability)
WHERE code IN ('binance', 'demo')
ON CONFLICT (provider_id, capability) DO NOTHING;

INSERT INTO market.symbol_provider_bindings (symbol_id, provider_id, provider_symbol, priority, enabled)
SELECT s.id, p.id, COALESCE(s.provider_symbol, s.symbol), 100, true
FROM market.symbols s
JOIN market.data_providers p ON p.code = s.provider
ON CONFLICT (symbol_id, provider_id, provider_symbol) DO NOTHING;
