CREATE TABLE IF NOT EXISTS trading.cross_liquidation_charges (
  order_id UUID PRIMARY KEY REFERENCES trading.orders(id),
  account_id UUID NOT NULL REFERENCES core.trading_accounts(id),
  position_id UUID NOT NULL REFERENCES trading.positions(id),
  fee_due NUMERIC(38, 8) NOT NULL DEFAULT 0 CHECK (fee_due >= 0),
  fee_charged NUMERIC(38, 8) NOT NULL DEFAULT 0 CHECK (fee_charged >= 0),
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING', 'SETTLED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  settled_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_cross_liquidation_charges_account_status
  ON trading.cross_liquidation_charges(account_id, status, order_id);
