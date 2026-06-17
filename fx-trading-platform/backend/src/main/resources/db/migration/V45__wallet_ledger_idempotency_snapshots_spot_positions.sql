ALTER TABLE ledger.asset_ledger_entries
  ADD COLUMN IF NOT EXISTS operation_type VARCHAR(64);

UPDATE ledger.asset_ledger_entries
SET operation_type = entry_type
WHERE operation_type IS NULL;

ALTER TABLE ledger.asset_ledger_entries
  ALTER COLUMN operation_type SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_asset_ledger_business_operation
  ON ledger.asset_ledger_entries(account_id, wallet_type, asset, reference_type, reference_id, operation_type)
  WHERE reference_type IS NOT NULL AND reference_id IS NOT NULL AND operation_type IS NOT NULL;

ALTER TABLE ledger.ledger_entries
  ADD COLUMN IF NOT EXISTS operation_type VARCHAR(64);

UPDATE ledger.ledger_entries
SET operation_type = entry_type
WHERE operation_type IS NULL;

ALTER TABLE ledger.ledger_entries
  ALTER COLUMN operation_type SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_cash_ledger_business_operation
  ON ledger.ledger_entries(account_id, reference_type, reference_id, operation_type)
  WHERE reference_type IS NOT NULL
    AND reference_id IS NOT NULL
    AND operation_type IS NOT NULL
    AND reference_type IN ('ADMIN_FUND_OPERATION', 'FUNDING_SETTLEMENT', 'FX_FINANCING_SETTLEMENT', 'TRADE');

CREATE TABLE IF NOT EXISTS core.wallet_daily_snapshots (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id UUID NOT NULL REFERENCES core.trading_accounts(id),
  wallet_type VARCHAR(16) NOT NULL,
  asset VARCHAR(32) NOT NULL,
  total NUMERIC(24, 8) NOT NULL,
  available NUMERIC(24, 8) NOT NULL,
  locked NUMERIC(24, 8) NOT NULL,
  snapshot_date DATE NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ux_wallet_daily_snapshots_account_wallet_asset_date
    UNIQUE(account_id, wallet_type, asset, snapshot_date)
);

CREATE INDEX IF NOT EXISTS idx_wallet_daily_snapshots_account_date
  ON core.wallet_daily_snapshots(account_id, snapshot_date DESC);

CREATE TABLE IF NOT EXISTS core.account_daily_snapshots (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id UUID NOT NULL REFERENCES core.trading_accounts(id),
  wallet_type VARCHAR(16) NOT NULL DEFAULT 'MARGIN',
  asset VARCHAR(32) NOT NULL,
  balance NUMERIC(24, 8) NOT NULL,
  equity NUMERIC(24, 8) NOT NULL,
  used_margin NUMERIC(24, 8) NOT NULL,
  free_margin NUMERIC(24, 8) NOT NULL,
  open_pnl NUMERIC(24, 8) NOT NULL,
  realized_pnl NUMERIC(24, 8) NOT NULL,
  snapshot_date DATE NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ux_account_daily_snapshots_account_wallet_asset_date
    UNIQUE(account_id, wallet_type, asset, snapshot_date)
);

CREATE INDEX IF NOT EXISTS idx_account_daily_snapshots_account_date
  ON core.account_daily_snapshots(account_id, snapshot_date DESC);

CREATE TABLE IF NOT EXISTS trading.spot_positions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id UUID NOT NULL REFERENCES core.trading_accounts(id),
  wallet_type VARCHAR(16) NOT NULL DEFAULT 'SPOT',
  asset VARCHAR(32) NOT NULL,
  quantity NUMERIC(24, 8) NOT NULL DEFAULT 0,
  average_cost NUMERIC(24, 8) NOT NULL DEFAULT 0,
  cost_asset VARCHAR(32) NOT NULL,
  realized_pnl NUMERIC(24, 8) NOT NULL DEFAULT 0,
  unrealized_pnl NUMERIC(24, 8) NOT NULL DEFAULT 0,
  fee_cost NUMERIC(24, 8) NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ux_spot_positions_account_wallet_asset_cost
    UNIQUE(account_id, wallet_type, asset, cost_asset),
  CONSTRAINT ck_spot_positions_non_negative_quantity CHECK (quantity >= 0)
);

CREATE INDEX IF NOT EXISTS idx_spot_positions_account_wallet
  ON trading.spot_positions(account_id, wallet_type);
