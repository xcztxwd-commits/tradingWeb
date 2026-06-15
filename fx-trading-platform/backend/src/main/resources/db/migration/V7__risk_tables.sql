CREATE TABLE risk.risk_configs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  symbol VARCHAR(32),
  max_leverage INTEGER NOT NULL DEFAULT 100,
  max_lots NUMERIC(12, 4) NOT NULL DEFAULT 100,
  margin_call_level NUMERIC(10, 4) NOT NULL DEFAULT 100,
  stop_out_level NUMERIC(10, 4) NOT NULL DEFAULT 50,
  enabled BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
