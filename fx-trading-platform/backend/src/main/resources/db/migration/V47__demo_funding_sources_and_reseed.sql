ALTER TABLE market.symbols
  ADD COLUMN IF NOT EXISTS fixed_funding_rate NUMERIC(18, 10) NOT NULL DEFAULT 0.0001,
  ADD COLUMN IF NOT EXISTS fixed_funding_interval_minutes INTEGER NOT NULL DEFAULT 480,
  ADD COLUMN IF NOT EXISTS funding_source_priority TEXT[] NOT NULL DEFAULT ARRAY['BINANCE', 'OKX', 'FIXED'],
  ADD COLUMN IF NOT EXISTS funding_stale_seconds INTEGER NOT NULL DEFAULT 900;

ALTER TABLE trading.funding_rates
  ADD COLUMN IF NOT EXISTS provider_code VARCHAR(32),
  ADD COLUMN IF NOT EXISTS source_mode VARCHAR(32),
  ADD COLUMN IF NOT EXISTS as_of TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS interval_minutes INTEGER,
  ADD COLUMN IF NOT EXISTS raw_payload_hash VARCHAR(128);

UPDATE trading.funding_rates
SET provider_code = COALESCE(provider_code, 'legacy'),
    source_mode = COALESCE(source_mode, 'LEGACY'),
    as_of = COALESCE(as_of, funding_time),
    interval_minutes = COALESCE(
      interval_minutes,
      CASE
        WHEN next_funding_time IS NOT NULL
          THEN greatest(1, extract(epoch FROM (next_funding_time - funding_time))::INTEGER / 60)
        ELSE 480
      END
    );

-- V35 did not enforce the business key. Keep the newest row deterministically
-- before making existing V45 databases conform to the V47 uniqueness contract.
DELETE FROM trading.funding_rates duplicate
USING trading.funding_rates retained
WHERE duplicate.symbol = retained.symbol
  AND duplicate.funding_time = retained.funding_time
  AND (duplicate.created_at, duplicate.id) < (retained.created_at, retained.id);

CREATE UNIQUE INDEX IF NOT EXISTS ux_funding_rates_symbol_time
  ON trading.funding_rates(symbol, funding_time);

ALTER TABLE trading.funding_settlements
  ADD COLUMN IF NOT EXISTS position_side VARCHAR(16),
  ADD COLUMN IF NOT EXISTS margin_mode VARCHAR(16),
  ADD COLUMN IF NOT EXISTS mark_price NUMERIC(24, 10),
  ADD COLUMN IF NOT EXISTS source VARCHAR(32),
  ADD COLUMN IF NOT EXISTS balance_after NUMERIC(24, 8),
  ADD COLUMN IF NOT EXISTS isolated_margin_after NUMERIC(24, 8),
  ADD COLUMN IF NOT EXISTS shortfall NUMERIC(24, 8) NOT NULL DEFAULT 0;

UPDATE trading.funding_settlements fs
SET position_side = COALESCE(fs.position_side, p.position_side),
    margin_mode = COALESCE(fs.margin_mode, p.margin_mode),
    mark_price = COALESCE(fs.mark_price, p.mark_price, p.current_price),
    source = COALESCE(fs.source, 'LEGACY'),
    balance_after = COALESCE(fs.balance_after, a.balance),
    isolated_margin_after = COALESCE(
      fs.isolated_margin_after,
      CASE WHEN p.margin_mode = 'ISOLATED' THEN p.initial_margin ELSE 0 END
    )
FROM trading.positions p
JOIN core.trading_accounts a ON a.id = p.account_id
WHERE p.id = fs.position_id;

INSERT INTO market.data_providers (
  code, name, provider_type, asset_classes, rest_base_url, ws_url,
  enabled, priority, health_status, config_json
) VALUES
  (
    'binance-usdm', 'Binance USD-M', 'REST_WS', ARRAY['CRYPTO', 'LINEAR_PERP'],
    'https://fapi.binance.com', 'wss://fstream.binance.com/ws',
    true, 10, 'UNKNOWN', '{"market":"linear-perp"}'::jsonb
  ),
  (
    'okx-swap', 'OKX Swap', 'REST_WS', ARRAY['CRYPTO', 'LINEAR_PERP'],
    'https://www.okx.com', 'wss://ws.okx.com:8443/ws/v5/public',
    true, 20, 'UNKNOWN', '{"market":"linear-perp"}'::jsonb
  ),
  (
    'local-spot', 'Local Spot Simulation', 'LOCAL', ARRAY['CRYPTO'],
    null, null, true, 900, 'HEALTHY', '{"market":"spot"}'::jsonb
  ),
  (
    'local-perp', 'Local Perpetual Simulation', 'LOCAL', ARRAY['CRYPTO', 'LINEAR_PERP'],
    null, null, true, 900, 'HEALTHY', '{"market":"linear-perp"}'::jsonb
  )
ON CONFLICT (code) DO NOTHING;

UPDATE market.data_providers
SET enabled = true,
    updated_at = now()
WHERE code IN (
  'binance', 'okx', 'local-spot', 'binance-usdm', 'okx-swap', 'local-perp'
);

INSERT INTO market.data_provider_capabilities (provider_id, capability, enabled)
SELECT p.id, capabilities.capability, true
FROM market.data_providers p
CROSS JOIN (
  VALUES
    ('SYMBOLS'),
    ('QUOTE'),
    ('SNAPSHOT'),
    ('CANDLES'),
    ('ORDER_BOOK'),
    ('TRADES'),
    ('MARK_PRICE'),
    ('INDEX_PRICE'),
    ('FUNDING')
) AS capabilities(capability)
WHERE p.code IN (
  'binance', 'okx', 'local-spot', 'binance-usdm', 'okx-swap', 'local-perp'
)
ON CONFLICT (provider_id, capability) DO UPDATE SET
  enabled = true,
  updated_at = now();

UPDATE market.symbols
SET tradable = false,
    updated_at = now()
WHERE tradable = true;

INSERT INTO market.symbols (
  symbol, display_name, provider, provider_symbol, asset_class, product_type,
  base_currency, quote_currency, pip_size, tick_size, lot_size, min_lot, max_lot,
  leverage, spread_markup, enabled, display_enabled, quote_enabled, chart_enabled,
  order_book_enabled, tradable, display_group, display_order, contract_size,
  contract_multiplier, settlement_asset, margin_asset, maintenance_margin_rate,
  liquidation_fee_rate, mark_price_source, fixed_funding_rate,
  fixed_funding_interval_minutes, funding_source_priority, funding_stale_seconds
) VALUES
  (
    'BTCUSDT', 'Bitcoin / Tether', 'binance', 'BTCUSDT', 'CRYPTO', 'CRYPTO_SPOT',
    'BTC', 'USDT', 0.01, 0.10, 1, 0.0001, 100, 100, 0, true, true, true, true, true,
    true, 'CRYPTO_SPOT', 10, null, 1, 'USDT', null, 0, 0, 'quote_mid', 0, 480,
    ARRAY['BINANCE', 'OKX', 'FIXED'], 900
  ),
  (
    'ETHUSDT', 'Ethereum / Tether', 'binance', 'ETHUSDT', 'CRYPTO', 'CRYPTO_SPOT',
    'ETH', 'USDT', 0.01, 0.10, 1, 0.0001, 1000, 100, 0, true, true, true, true, true,
    true, 'CRYPTO_SPOT', 20, null, 1, 'USDT', null, 0, 0, 'quote_mid', 0, 480,
    ARRAY['BINANCE', 'OKX', 'FIXED'], 900
  ),
  (
    'BNBUSDT', 'BNB / Tether', 'binance', 'BNBUSDT', 'CRYPTO', 'CRYPTO_SPOT',
    'BNB', 'USDT', 0.01, 0.01, 1, 0.001, 10000, 100, 0, true, true, true, true, true,
    true, 'CRYPTO_SPOT', 30, null, 1, 'USDT', null, 0, 0, 'quote_mid', 0, 480,
    ARRAY['BINANCE', 'OKX', 'FIXED'], 900
  ),
  (
    'SOLUSDT', 'Solana / Tether', 'binance', 'SOLUSDT', 'CRYPTO', 'CRYPTO_SPOT',
    'SOL', 'USDT', 0.01, 0.01, 1, 0.01, 100000, 100, 0, true, true, true, true, true,
    true, 'CRYPTO_SPOT', 40, null, 1, 'USDT', null, 0, 0, 'quote_mid', 0, 480,
    ARRAY['BINANCE', 'OKX', 'FIXED'], 900
  ),
  (
    'XRPUSDT', 'XRP / Tether', 'binance', 'XRPUSDT', 'CRYPTO', 'CRYPTO_SPOT',
    'XRP', 'USDT', 0.0001, 0.0001, 1, 1, 1000000, 100, 0, true, true, true, true, true,
    true, 'CRYPTO_SPOT', 50, null, 1, 'USDT', null, 0, 0, 'quote_mid', 0, 480,
    ARRAY['BINANCE', 'OKX', 'FIXED'], 900
  ),
  (
    'BTCUSDT-PERP', 'Bitcoin / Tether Perpetual', 'binance-usdm', 'BTCUSDT',
    'LINEAR_PERP', 'LINEAR_PERP', 'BTC', 'USDT', 0.01, 0.10, 1, 0.0001, 100,
    100, 0, true, true, true, true, true, true, 'LINEAR_PERP', 110, 1, 1, 'USDT',
    'USDT', 0.005, 0.005, 'provider_mark', 0.0001, 480, ARRAY['BINANCE', 'OKX', 'FIXED'], 900
  ),
  (
    'ETHUSDT-PERP', 'Ethereum / Tether Perpetual', 'binance-usdm', 'ETHUSDT',
    'LINEAR_PERP', 'LINEAR_PERP', 'ETH', 'USDT', 0.01, 0.10, 1, 0.0001, 1000,
    100, 0, true, true, true, true, true, true, 'LINEAR_PERP', 120, 1, 1, 'USDT',
    'USDT', 0.005, 0.005, 'provider_mark', 0.0001, 480, ARRAY['BINANCE', 'OKX', 'FIXED'], 900
  ),
  (
    'BNBUSDT-PERP', 'BNB / Tether Perpetual', 'binance-usdm', 'BNBUSDT',
    'LINEAR_PERP', 'LINEAR_PERP', 'BNB', 'USDT', 0.01, 0.01, 1, 0.001, 10000,
    100, 0, true, true, true, true, true, true, 'LINEAR_PERP', 130, 1, 1, 'USDT',
    'USDT', 0.005, 0.005, 'provider_mark', 0.0001, 480, ARRAY['BINANCE', 'OKX', 'FIXED'], 900
  ),
  (
    'SOLUSDT-PERP', 'Solana / Tether Perpetual', 'binance-usdm', 'SOLUSDT',
    'LINEAR_PERP', 'LINEAR_PERP', 'SOL', 'USDT', 0.01, 0.01, 1, 0.01, 100000,
    100, 0, true, true, true, true, true, true, 'LINEAR_PERP', 140, 1, 1, 'USDT',
    'USDT', 0.005, 0.005, 'provider_mark', 0.0001, 480, ARRAY['BINANCE', 'OKX', 'FIXED'], 900
  ),
  (
    'XRPUSDT-PERP', 'XRP / Tether Perpetual', 'binance-usdm', 'XRPUSDT',
    'LINEAR_PERP', 'LINEAR_PERP', 'XRP', 'USDT', 0.0001, 0.0001, 1, 1, 1000000,
    100, 0, true, true, true, true, true, true, 'LINEAR_PERP', 150, 1, 1, 'USDT',
    'USDT', 0.005, 0.005, 'provider_mark', 0.0001, 480, ARRAY['BINANCE', 'OKX', 'FIXED'], 900
  )
ON CONFLICT (symbol) DO UPDATE SET
  display_name = EXCLUDED.display_name,
  provider = EXCLUDED.provider,
  provider_symbol = EXCLUDED.provider_symbol,
  asset_class = EXCLUDED.asset_class,
  product_type = EXCLUDED.product_type,
  base_currency = EXCLUDED.base_currency,
  quote_currency = EXCLUDED.quote_currency,
  pip_size = EXCLUDED.pip_size,
  tick_size = EXCLUDED.tick_size,
  lot_size = EXCLUDED.lot_size,
  min_lot = EXCLUDED.min_lot,
  max_lot = EXCLUDED.max_lot,
  leverage = EXCLUDED.leverage,
  spread_markup = EXCLUDED.spread_markup,
  enabled = EXCLUDED.enabled,
  display_enabled = EXCLUDED.display_enabled,
  quote_enabled = EXCLUDED.quote_enabled,
  chart_enabled = EXCLUDED.chart_enabled,
  order_book_enabled = EXCLUDED.order_book_enabled,
  tradable = EXCLUDED.tradable,
  display_group = EXCLUDED.display_group,
  display_order = EXCLUDED.display_order,
  contract_size = EXCLUDED.contract_size,
  contract_multiplier = EXCLUDED.contract_multiplier,
  settlement_asset = EXCLUDED.settlement_asset,
  margin_asset = EXCLUDED.margin_asset,
  maintenance_margin_rate = EXCLUDED.maintenance_margin_rate,
  liquidation_fee_rate = EXCLUDED.liquidation_fee_rate,
  mark_price_source = EXCLUDED.mark_price_source,
  fixed_funding_rate = EXCLUDED.fixed_funding_rate,
  fixed_funding_interval_minutes = EXCLUDED.fixed_funding_interval_minutes,
  funding_source_priority = EXCLUDED.funding_source_priority,
  funding_stale_seconds = EXCLUDED.funding_stale_seconds,
  updated_at = now();

WITH binding_seed(symbol, provider_code, provider_symbol, priority) AS (
  VALUES
    ('BTCUSDT', 'binance', 'BTCUSDT', 10),
    ('BTCUSDT', 'okx', 'BTC-USDT', 20),
    ('BTCUSDT', 'local-spot', 'BTCUSDT', 900),
    ('ETHUSDT', 'binance', 'ETHUSDT', 10),
    ('ETHUSDT', 'okx', 'ETH-USDT', 20),
    ('ETHUSDT', 'local-spot', 'ETHUSDT', 900),
    ('BNBUSDT', 'binance', 'BNBUSDT', 10),
    ('BNBUSDT', 'okx', 'BNB-USDT', 20),
    ('BNBUSDT', 'local-spot', 'BNBUSDT', 900),
    ('SOLUSDT', 'binance', 'SOLUSDT', 10),
    ('SOLUSDT', 'okx', 'SOL-USDT', 20),
    ('SOLUSDT', 'local-spot', 'SOLUSDT', 900),
    ('XRPUSDT', 'binance', 'XRPUSDT', 10),
    ('XRPUSDT', 'okx', 'XRP-USDT', 20),
    ('XRPUSDT', 'local-spot', 'XRPUSDT', 900),
    ('BTCUSDT-PERP', 'binance-usdm', 'BTCUSDT', 10),
    ('BTCUSDT-PERP', 'okx-swap', 'BTC-USDT-SWAP', 20),
    ('BTCUSDT-PERP', 'local-perp', 'BTCUSDT-PERP', 900),
    ('ETHUSDT-PERP', 'binance-usdm', 'ETHUSDT', 10),
    ('ETHUSDT-PERP', 'okx-swap', 'ETH-USDT-SWAP', 20),
    ('ETHUSDT-PERP', 'local-perp', 'ETHUSDT-PERP', 900),
    ('BNBUSDT-PERP', 'binance-usdm', 'BNBUSDT', 10),
    ('BNBUSDT-PERP', 'okx-swap', 'BNB-USDT-SWAP', 20),
    ('BNBUSDT-PERP', 'local-perp', 'BNBUSDT-PERP', 900),
    ('SOLUSDT-PERP', 'binance-usdm', 'SOLUSDT', 10),
    ('SOLUSDT-PERP', 'okx-swap', 'SOL-USDT-SWAP', 20),
    ('SOLUSDT-PERP', 'local-perp', 'SOLUSDT-PERP', 900),
    ('XRPUSDT-PERP', 'binance-usdm', 'XRPUSDT', 10),
    ('XRPUSDT-PERP', 'okx-swap', 'XRP-USDT-SWAP', 20),
    ('XRPUSDT-PERP', 'local-perp', 'XRPUSDT-PERP', 900)
)
INSERT INTO market.symbol_provider_bindings (
  symbol_id, provider_id, provider_symbol, priority, enabled
)
SELECT s.id, p.id, binding_seed.provider_symbol, binding_seed.priority, true
FROM binding_seed
JOIN market.symbols s ON s.symbol = binding_seed.symbol
JOIN market.data_providers p ON p.code = binding_seed.provider_code
ON CONFLICT (symbol_id, provider_id, provider_symbol) DO UPDATE SET
  priority = EXCLUDED.priority,
  enabled = true,
  updated_at = now();

CREATE TEMPORARY TABLE demo_accounts_to_rebuild ON COMMIT DROP AS
SELECT id
FROM core.trading_accounts
WHERE account_type = 'DEMO';

DELETE FROM finance.fund_orders
WHERE account_id IN (SELECT id FROM demo_accounts_to_rebuild);

DELETE FROM finance.admin_fund_operations
WHERE account_id IN (SELECT id FROM demo_accounts_to_rebuild);

DELETE FROM trading.order_events
WHERE order_id IN (
  SELECT id
  FROM trading.orders
  WHERE account_id IN (SELECT id FROM demo_accounts_to_rebuild)
);

DELETE FROM trading.trades
WHERE account_id IN (SELECT id FROM demo_accounts_to_rebuild);

DELETE FROM trading.funding_settlements
WHERE account_id IN (SELECT id FROM demo_accounts_to_rebuild);

DELETE FROM trading.fx_financing_settlements
WHERE account_id IN (SELECT id FROM demo_accounts_to_rebuild);

DELETE FROM trading.spot_positions
WHERE account_id IN (SELECT id FROM demo_accounts_to_rebuild);

DELETE FROM trading.positions
WHERE account_id IN (SELECT id FROM demo_accounts_to_rebuild);

DELETE FROM trading.orders
WHERE account_id IN (SELECT id FROM demo_accounts_to_rebuild);

DELETE FROM trading.account_symbol_settings
WHERE account_id IN (SELECT id FROM demo_accounts_to_rebuild);

DELETE FROM ledger.asset_ledger_entries
WHERE account_id IN (SELECT id FROM demo_accounts_to_rebuild);

DELETE FROM ledger.ledger_entries
WHERE account_id IN (SELECT id FROM demo_accounts_to_rebuild);

DELETE FROM core.wallet_daily_snapshots
WHERE account_id IN (SELECT id FROM demo_accounts_to_rebuild);

DELETE FROM core.account_daily_snapshots
WHERE account_id IN (SELECT id FROM demo_accounts_to_rebuild);

DELETE FROM core.wallet_balances
WHERE account_id IN (SELECT id FROM demo_accounts_to_rebuild);

DELETE FROM core.trading_accounts
WHERE id IN (SELECT id FROM demo_accounts_to_rebuild);

CREATE UNIQUE INDEX IF NOT EXISTS ux_trading_accounts_user_active_demo
  ON core.trading_accounts(user_id)
  WHERE account_type = 'DEMO' AND status = 'ACTIVE';

INSERT INTO core.trading_accounts (
  user_id, account_type, base_currency, balance, equity, used_margin, free_margin,
  margin_level, leverage, status, position_mode, demo_generation, reset_at
)
SELECT
  u.id, 'DEMO', 'USDT', 50000, 50000, 0, 50000, null, 10, 'ACTIVE',
  'ONE_WAY', 1, null
FROM auth.users u
WHERE u.status = 'ACTIVE' AND u.role = 'USER';

INSERT INTO core.wallet_balances (
  account_id, wallet_type, asset, total, available, locked
)
SELECT id, 'SPOT', 'USDT', 50000, 50000, 0
FROM core.trading_accounts
WHERE account_type = 'DEMO' AND status = 'ACTIVE' AND demo_generation = 1;

INSERT INTO ledger.asset_ledger_entries (
  account_id, wallet_type, asset, amount, balance_after, entry_type,
  operation_type, reference_type, reference_id, description
)
SELECT
  id, 'SPOT', 'USDT', 50000, 50000, 'DEMO_INIT', 'DEMO_INIT',
  'DEMO_ACCOUNT', id, 'Initialize Demo Spot balance'
FROM core.trading_accounts
WHERE account_type = 'DEMO' AND status = 'ACTIVE' AND demo_generation = 1;

INSERT INTO ledger.ledger_entries (
  account_id, entry_type, amount, balance_after, currency, reference_type,
  reference_id, description, operation_type
)
SELECT
  id, 'DEMO_INIT', 50000, 50000, 'USDT', 'DEMO_ACCOUNT', id,
  'Initialize Demo perpetual balance', 'DEMO_INIT'
FROM core.trading_accounts
WHERE account_type = 'DEMO' AND status = 'ACTIVE' AND demo_generation = 1;

INSERT INTO trading.account_symbol_settings (
  account_id, symbol, leverage, margin_mode, quantity_unit, version
)
SELECT a.id, s.symbol, 10, 'CROSS', 'BASE', 0
FROM core.trading_accounts a
CROSS JOIN market.symbols s
WHERE a.account_type = 'DEMO'
  AND a.status = 'ACTIVE'
  AND a.demo_generation = 1
  AND s.tradable = true;
