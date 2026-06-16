ALTER TABLE market.symbols
  ADD COLUMN IF NOT EXISTS contract_size NUMERIC(24, 8),
  ADD COLUMN IF NOT EXISTS contract_multiplier NUMERIC(24, 8) NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS settlement_asset VARCHAR(32),
  ADD COLUMN IF NOT EXISTS margin_asset VARCHAR(32),
  ADD COLUMN IF NOT EXISTS maintenance_margin_rate NUMERIC(18, 8) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS liquidation_fee_rate NUMERIC(18, 8) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS mark_price_source VARCHAR(64);

UPDATE market.symbols
SET contract_size = lot_size
WHERE contract_size IS NULL
  AND asset_class IN ('LINEAR_PERP', 'LINEAR_PERPETUAL', 'INVERSE_PERP', 'INVERSE_PERPETUAL', 'PERPETUAL', 'SWAP', 'FUTURES', 'CONTRACT');

UPDATE market.symbols
SET settlement_asset = COALESCE(settlement_asset, quote_currency),
    margin_asset = COALESCE(margin_asset, quote_currency),
    mark_price_source = COALESCE(mark_price_source, 'quote_mid')
WHERE asset_class IN ('LINEAR_PERP', 'LINEAR_PERPETUAL', 'PERPETUAL', 'SWAP', 'FUTURES', 'CONTRACT')
  AND NOT (asset_class IN ('INVERSE_PERP', 'INVERSE_PERPETUAL') OR quote_currency = 'USD');

UPDATE market.symbols
SET settlement_asset = COALESCE(settlement_asset, base_currency),
    margin_asset = COALESCE(margin_asset, base_currency),
    mark_price_source = COALESCE(mark_price_source, 'quote_mid')
WHERE asset_class IN ('INVERSE_PERP', 'INVERSE_PERPETUAL')
   OR (asset_class IN ('PERPETUAL', 'SWAP', 'FUTURES', 'CONTRACT') AND quote_currency = 'USD');

ALTER TABLE trading.positions
  ADD COLUMN IF NOT EXISTS notional NUMERIC(24, 8) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS initial_margin NUMERIC(24, 8) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS maintenance_margin NUMERIC(24, 8) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS mark_price NUMERIC(24, 10),
  ADD COLUMN IF NOT EXISTS settlement_asset VARCHAR(32),
  ADD COLUMN IF NOT EXISTS margin_asset VARCHAR(32);

UPDATE trading.positions
SET initial_margin = margin_held
WHERE initial_margin = 0
  AND margin_held > 0;
