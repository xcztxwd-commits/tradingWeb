CREATE TABLE IF NOT EXISTS finance.fund_orders (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth.users(id),
  account_id UUID NOT NULL REFERENCES core.trading_accounts(id),
  order_type VARCHAR(32) NOT NULL,
  amount NUMERIC(24, 8) NOT NULL,
  currency VARCHAR(16) NOT NULL DEFAULT 'USD',
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  payment_method_id UUID REFERENCES finance.payment_methods(id),
  applicant_note TEXT,
  review_reason TEXT,
  reviewed_by UUID REFERENCES auth.users(id),
  reviewed_at TIMESTAMPTZ,
  fund_operation_id UUID REFERENCES finance.admin_fund_operations(id),
  created_by UUID REFERENCES auth.users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_fund_orders_type_status_time
  ON finance.fund_orders(order_type, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_fund_orders_account_time
  ON finance.fund_orders(account_id, created_at DESC);
