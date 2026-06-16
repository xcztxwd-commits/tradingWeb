CREATE TABLE core.wallet_balances (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id UUID NOT NULL REFERENCES core.trading_accounts(id),
  asset VARCHAR(32) NOT NULL,
  total NUMERIC(24, 8) NOT NULL DEFAULT 0,
  available NUMERIC(24, 8) NOT NULL DEFAULT 0,
  locked NUMERIC(24, 8) NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ux_wallet_balances_account_asset UNIQUE(account_id, asset),
  CONSTRAINT ck_wallet_balances_non_negative CHECK (total >= 0 AND available >= 0 AND locked >= 0),
  CONSTRAINT ck_wallet_balances_total_matches_parts CHECK (total = available + locked)
);

CREATE INDEX idx_wallet_balances_account ON core.wallet_balances(account_id);

CREATE TABLE ledger.asset_ledger_entries (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id UUID NOT NULL REFERENCES core.trading_accounts(id),
  asset VARCHAR(32) NOT NULL,
  amount NUMERIC(24, 8) NOT NULL,
  balance_after NUMERIC(24, 8) NOT NULL,
  entry_type VARCHAR(32) NOT NULL,
  reference_type VARCHAR(32),
  reference_id UUID,
  description TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_asset_ledger_account_time ON ledger.asset_ledger_entries(account_id, created_at DESC);
CREATE INDEX idx_asset_ledger_reference ON ledger.asset_ledger_entries(reference_type, reference_id);
