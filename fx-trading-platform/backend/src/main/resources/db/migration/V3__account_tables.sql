CREATE TABLE core.trading_accounts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth.users(id),
  account_type VARCHAR(16) NOT NULL DEFAULT 'DEMO',
  base_currency VARCHAR(16) NOT NULL DEFAULT 'USD',
  balance NUMERIC(24, 8) NOT NULL DEFAULT 0,
  equity NUMERIC(24, 8) NOT NULL DEFAULT 0,
  used_margin NUMERIC(24, 8) NOT NULL DEFAULT 0,
  free_margin NUMERIC(24, 8) NOT NULL DEFAULT 0,
  margin_level NUMERIC(24, 8),
  leverage INTEGER NOT NULL DEFAULT 100,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_trading_accounts_user ON core.trading_accounts(user_id);
