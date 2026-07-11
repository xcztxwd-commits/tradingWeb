ALTER TABLE core.trading_accounts
  ADD COLUMN IF NOT EXISTS position_mode VARCHAR(16) NOT NULL DEFAULT 'ONE_WAY',
  ADD COLUMN IF NOT EXISTS demo_generation BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS reset_at TIMESTAMPTZ;

ALTER TABLE core.trading_accounts
  ALTER COLUMN leverage SET DEFAULT 10;

-- V47 creates the active-DEMO uniqueness index after removing legacy DEMO rows.

CREATE TABLE IF NOT EXISTS trading.account_symbol_settings (
  account_id UUID NOT NULL REFERENCES core.trading_accounts(id),
  symbol VARCHAR(32) NOT NULL,
  leverage INTEGER NOT NULL DEFAULT 10 CHECK (leverage BETWEEN 1 AND 100),
  margin_mode VARCHAR(16) NOT NULL DEFAULT 'CROSS',
  quantity_unit VARCHAR(16) NOT NULL DEFAULT 'BASE',
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ux_account_symbol_settings_account_symbol UNIQUE(account_id, symbol)
);

ALTER TABLE trading.orders
  ADD COLUMN IF NOT EXISTS product_type VARCHAR(32) NOT NULL DEFAULT 'FX_MARGIN',
  ADD COLUMN IF NOT EXISTS position_mode VARCHAR(16) NOT NULL DEFAULT 'ONE_WAY',
  ADD COLUMN IF NOT EXISTS position_side VARCHAR(16) NOT NULL DEFAULT 'BOTH',
  ADD COLUMN IF NOT EXISTS margin_mode VARCHAR(16) NOT NULL DEFAULT 'CROSS',
  ADD COLUMN IF NOT EXISTS quantity_unit VARCHAR(16) NOT NULL DEFAULT 'BASE',
  ADD COLUMN IF NOT EXISTS original_quantity NUMERIC(24, 8),
  ADD COLUMN IF NOT EXISTS base_quantity NUMERIC(24, 8),
  ADD COLUMN IF NOT EXISTS time_in_force VARCHAR(16) NOT NULL DEFAULT 'GTC',
  ADD COLUMN IF NOT EXISTS reduce_only BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS order_origin VARCHAR(32) NOT NULL DEFAULT 'USER',
  ADD COLUMN IF NOT EXISTS system_reason VARCHAR(64),
  ADD COLUMN IF NOT EXISTS trigger_price NUMERIC(24, 10),
  ADD COLUMN IF NOT EXISTS trigger_price_type VARCHAR(32),
  ADD COLUMN IF NOT EXISTS trigger_execution_type VARCHAR(16),
  ADD COLUMN IF NOT EXISTS protection_type VARCHAR(32),
  ADD COLUMN IF NOT EXISTS parent_order_id UUID,
  ADD COLUMN IF NOT EXISTS parent_position_id UUID,
  ADD COLUMN IF NOT EXISTS contingency_group_id UUID,
  ADD COLUMN IF NOT EXISTS hold_owner_order_id UUID,
  ADD COLUMN IF NOT EXISTS liquidity_role VARCHAR(16),
  ADD COLUMN IF NOT EXISTS fee_asset VARCHAR(32),
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

UPDATE trading.orders o
SET product_type = s.product_type,
    margin_mode = CASE WHEN s.product_type = 'CRYPTO_SPOT' THEN 'CASH' ELSE 'CROSS' END,
    original_quantity = COALESCE(o.original_quantity, o.quantity, o.lots),
    base_quantity = COALESCE(o.base_quantity, o.quantity, o.lots)
FROM market.symbols s
WHERE s.symbol = o.symbol;

UPDATE trading.orders
SET original_quantity = COALESCE(original_quantity, quantity, lots),
    base_quantity = COALESCE(base_quantity, quantity, lots)
WHERE original_quantity IS NULL OR base_quantity IS NULL;

ALTER TABLE trading.trades
  ADD COLUMN IF NOT EXISTS product_type VARCHAR(32) NOT NULL DEFAULT 'FX_MARGIN',
  ADD COLUMN IF NOT EXISTS position_side VARCHAR(16) NOT NULL DEFAULT 'BOTH',
  ADD COLUMN IF NOT EXISTS margin_mode VARCHAR(16) NOT NULL DEFAULT 'CROSS',
  ADD COLUMN IF NOT EXISTS fee NUMERIC(24, 8) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS fee_asset VARCHAR(32),
  ADD COLUMN IF NOT EXISTS liquidity_role VARCHAR(16),
  ADD COLUMN IF NOT EXISTS system_reason VARCHAR(64),
  ADD COLUMN IF NOT EXISTS source_mode VARCHAR(32),
  ADD COLUMN IF NOT EXISTS provider_code VARCHAR(32);

UPDATE trading.trades t
SET product_type = s.product_type,
    margin_mode = CASE WHEN s.product_type = 'CRYPTO_SPOT' THEN 'CASH' ELSE 'CROSS' END
FROM market.symbols s
WHERE s.symbol = t.symbol;

ALTER TABLE trading.positions
  ADD COLUMN IF NOT EXISTS product_type VARCHAR(32) NOT NULL DEFAULT 'FX_MARGIN',
  ADD COLUMN IF NOT EXISTS position_mode VARCHAR(16) NOT NULL DEFAULT 'ONE_WAY',
  ADD COLUMN IF NOT EXISTS position_side VARCHAR(16) NOT NULL DEFAULT 'BOTH',
  ADD COLUMN IF NOT EXISTS margin_mode VARCHAR(16) NOT NULL DEFAULT 'CROSS',
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

UPDATE trading.positions p
SET product_type = s.product_type,
    margin_mode = CASE WHEN s.product_type = 'CRYPTO_SPOT' THEN 'CASH' ELSE 'CROSS' END
FROM market.symbols s
WHERE s.symbol = p.symbol;

-- V45 permits conflicting LIVE position rows. Slot uniqueness is deferred until
-- Task 8 can migrate those rows with explicit ONE_WAY/HEDGE semantics.
CREATE INDEX IF NOT EXISTS idx_positions_account_symbol_open
  ON trading.positions(account_id, symbol, position_mode, position_side)
  WHERE status = 'OPEN' AND product_type = 'LINEAR_PERP';

CREATE INDEX IF NOT EXISTS idx_orders_account_active
  ON trading.orders(account_id, status, created_at DESC)
  WHERE status IN (
    'RECEIVED', 'VALIDATING', 'ACCEPTED', 'PENDING_ACTIVATION', 'PENDING',
    'WORKING', 'PARTIALLY_FILLED', 'CANCEL_PENDING'
  );

CREATE INDEX IF NOT EXISTS idx_orders_active_contingency_group
  ON trading.orders(contingency_group_id, status)
  WHERE contingency_group_id IS NOT NULL
    AND status IN ('PENDING_ACTIVATION', 'PENDING', 'WORKING', 'PARTIALLY_FILLED');

CREATE INDEX IF NOT EXISTS idx_orders_active_parent_position
  ON trading.orders(parent_position_id, status)
  WHERE parent_position_id IS NOT NULL
    AND status IN ('PENDING_ACTIVATION', 'PENDING', 'WORKING', 'PARTIALLY_FILLED');
