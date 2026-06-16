ALTER TABLE market.symbols
  ADD COLUMN IF NOT EXISTS product_type VARCHAR(32);

UPDATE market.symbols
SET product_type = CASE
  WHEN upper(trim(asset_class)) = 'FOREX' THEN 'FX_MARGIN'
  WHEN upper(trim(asset_class)) IN ('SPOT', 'CRYPTO') THEN 'CRYPTO_SPOT'
  WHEN upper(trim(asset_class)) IN ('LINEAR_PERP', 'LINEAR_PERPETUAL', 'PERPETUAL', 'SWAP', 'FUTURES', 'CONTRACT') THEN 'LINEAR_PERP'
  WHEN upper(trim(asset_class)) IN ('INVERSE_PERP', 'INVERSE_PERPETUAL') THEN 'INVERSE_PERP'
  ELSE product_type
END
WHERE product_type IS NULL;
