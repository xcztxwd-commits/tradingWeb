CREATE TABLE IF NOT EXISTS trading.funding_settlements (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  position_id UUID NOT NULL,
  account_id UUID NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  funding_time TIMESTAMPTZ NOT NULL,
  funding_rate NUMERIC(18, 10) NOT NULL,
  amount NUMERIC(24, 8) NOT NULL,
  asset VARCHAR(16) NOT NULL,
  ledger_entry_id UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_funding_settlements_position_time UNIQUE (position_id, funding_time)
);

CREATE INDEX IF NOT EXISTS idx_funding_settlements_account_time
  ON trading.funding_settlements(account_id, funding_time DESC);

CREATE TABLE IF NOT EXISTS trading.fx_financing_settlements (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  position_id UUID NOT NULL,
  account_id UUID NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  settlement_date DATE NOT NULL,
  days_charged INTEGER NOT NULL,
  rate NUMERIC(18, 10) NOT NULL,
  amount NUMERIC(24, 8) NOT NULL,
  asset VARCHAR(16) NOT NULL,
  ledger_entry_id UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_fx_financing_settlements_position_date UNIQUE (position_id, settlement_date)
);

CREATE INDEX IF NOT EXISTS idx_fx_financing_settlements_account_date
  ON trading.fx_financing_settlements(account_id, settlement_date DESC);
