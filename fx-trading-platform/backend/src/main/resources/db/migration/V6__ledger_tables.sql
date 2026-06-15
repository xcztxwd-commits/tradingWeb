CREATE TABLE ledger.ledger_entries (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id UUID NOT NULL REFERENCES core.trading_accounts(id),
  entry_type VARCHAR(32) NOT NULL,
  amount NUMERIC(24, 8) NOT NULL,
  balance_after NUMERIC(24, 8) NOT NULL,
  currency VARCHAR(16) NOT NULL DEFAULT 'USD',
  reference_type VARCHAR(32),
  reference_id UUID,
  description TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_account_time ON ledger.ledger_entries(account_id, created_at DESC);
