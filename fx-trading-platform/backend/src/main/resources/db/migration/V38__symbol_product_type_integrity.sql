UPDATE market.symbols
SET product_type = CASE
  WHEN upper(trim(asset_class)) IN ('FOREX', 'FX') THEN 'FX_MARGIN'
  WHEN upper(trim(asset_class)) IN ('SPOT', 'CRYPTO') THEN 'CRYPTO_SPOT'
  WHEN upper(trim(asset_class)) IN ('LINEAR_PERP', 'LINEAR_PERPETUAL', 'PERPETUAL', 'SWAP', 'FUTURES', 'CONTRACT') THEN 'LINEAR_PERP'
  WHEN upper(trim(asset_class)) IN ('INVERSE_PERP', 'INVERSE_PERPETUAL') THEN 'INVERSE_PERP'
  ELSE 'FX_MARGIN'
END
WHERE product_type IS NULL;

ALTER TABLE market.symbols
  ALTER COLUMN product_type SET DEFAULT 'FX_MARGIN',
  ALTER COLUMN product_type SET NOT NULL;

ALTER TABLE market.symbols
  DROP CONSTRAINT IF EXISTS symbols_product_type_check;

ALTER TABLE market.symbols
  ADD CONSTRAINT symbols_product_type_check
  CHECK (product_type IN ('FX_MARGIN', 'CRYPTO_SPOT', 'LINEAR_PERP', 'INVERSE_PERP'));
