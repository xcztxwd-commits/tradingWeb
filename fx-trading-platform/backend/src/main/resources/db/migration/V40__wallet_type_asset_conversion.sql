ALTER TABLE core.wallet_balances
  ADD COLUMN wallet_type VARCHAR(32) NOT NULL DEFAULT 'SPOT';

ALTER TABLE ledger.asset_ledger_entries
  ADD COLUMN wallet_type VARCHAR(32) NOT NULL DEFAULT 'SPOT';

ALTER TABLE core.wallet_balances
  DROP CONSTRAINT ux_wallet_balances_account_asset;

ALTER TABLE core.wallet_balances
  ADD CONSTRAINT ux_wallet_balances_account_wallet_asset UNIQUE(account_id, wallet_type, asset);

ALTER TABLE core.wallet_balances
  ADD CONSTRAINT ck_wallet_balances_wallet_type
    CHECK (wallet_type IN ('FX_MARGIN', 'SPOT', 'USDT_PERP', 'COIN_PERP', 'FUNDING'));

ALTER TABLE ledger.asset_ledger_entries
  ADD CONSTRAINT ck_asset_ledger_wallet_type
    CHECK (wallet_type IN ('FX_MARGIN', 'SPOT', 'USDT_PERP', 'COIN_PERP', 'FUNDING'));

CREATE INDEX idx_wallet_balances_account_wallet
  ON core.wallet_balances(account_id, wallet_type);

CREATE INDEX idx_asset_ledger_account_wallet_time
  ON ledger.asset_ledger_entries(account_id, wallet_type, created_at DESC);
