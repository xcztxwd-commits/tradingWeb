CREATE SCHEMA IF NOT EXISTS finance;

CREATE TABLE IF NOT EXISTS finance.payment_methods (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(120) NOT NULL,
  method_type VARCHAR(64) NOT NULL,
  currency VARCHAR(16) NOT NULL DEFAULT 'USD',
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  display_order INTEGER NOT NULL DEFAULT 0,
  instructions TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_payment_methods_order
  ON finance.payment_methods(display_order ASC, created_at DESC);

CREATE TABLE IF NOT EXISTS finance.admin_fund_operations (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id UUID NOT NULL REFERENCES core.trading_accounts(id),
  user_id UUID NOT NULL,
  operation_type VARCHAR(32) NOT NULL,
  amount NUMERIC(24, 8) NOT NULL,
  currency VARCHAR(16) NOT NULL DEFAULT 'USD',
  before_balance NUMERIC(24, 8) NOT NULL,
  after_balance NUMERIC(24, 8) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
  admin_user_id UUID NOT NULL,
  reason TEXT NOT NULL,
  payment_method_id UUID REFERENCES finance.payment_methods(id),
  note TEXT,
  idempotency_key VARCHAR(128),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_admin_fund_operations_account_time
  ON finance.admin_fund_operations(account_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_admin_fund_operations_user_time
  ON finance.admin_fund_operations(user_id, created_at DESC);
