CREATE TABLE trading.orders (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth.users(id),
  account_id UUID NOT NULL REFERENCES core.trading_accounts(id),
  symbol VARCHAR(32) NOT NULL,
  side VARCHAR(16) NOT NULL,
  order_type VARCHAR(16) NOT NULL,
  status VARCHAR(32) NOT NULL,
  lots NUMERIC(12, 4) NOT NULL,
  requested_price NUMERIC(24, 10),
  execution_price NUMERIC(24, 10),
  stop_loss NUMERIC(24, 10),
  take_profit NUMERIC(24, 10),
  idempotency_key VARCHAR(128) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  filled_at TIMESTAMPTZ,
  UNIQUE(user_id, idempotency_key)
);

CREATE TABLE trading.trades (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  order_id UUID NOT NULL REFERENCES trading.orders(id),
  account_id UUID NOT NULL REFERENCES core.trading_accounts(id),
  symbol VARCHAR(32) NOT NULL,
  side VARCHAR(16) NOT NULL,
  lots NUMERIC(12, 4) NOT NULL,
  price NUMERIC(24, 10) NOT NULL,
  realized_pnl NUMERIC(24, 8) NOT NULL DEFAULT 0,
  executed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE trading.positions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id UUID NOT NULL REFERENCES core.trading_accounts(id),
  symbol VARCHAR(32) NOT NULL,
  side VARCHAR(16) NOT NULL,
  lots NUMERIC(12, 4) NOT NULL,
  open_price NUMERIC(24, 10) NOT NULL,
  current_price NUMERIC(24, 10),
  stop_loss NUMERIC(24, 10),
  take_profit NUMERIC(24, 10),
  floating_pnl NUMERIC(24, 8) NOT NULL DEFAULT 0,
  realized_pnl NUMERIC(24, 8) NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
  opened_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  closed_at TIMESTAMPTZ
);

CREATE INDEX idx_orders_user_status ON trading.orders(user_id, status);
CREATE INDEX idx_positions_account_status ON trading.positions(account_id, status);
