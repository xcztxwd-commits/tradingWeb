INSERT INTO market.symbols (
  symbol, display_name, provider, provider_symbol, asset_class, base_currency, quote_currency,
  pip_size, tick_size, lot_size, min_lot, max_lot, leverage
) VALUES
  ('BTCUSDT', 'Bitcoin / Tether', 'binance', 'BTCUSDT', 'CRYPTO', 'BTC', 'USDT', 0.01, 0.1, 1, 0.0001, 100, 20),
  ('ETHUSDT', 'Ethereum / Tether', 'binance', 'ETHUSDT', 'CRYPTO', 'ETH', 'USDT', 0.01, 0.1, 1, 0.0001, 100, 20),
  ('SOLUSDT', 'Solana / Tether', 'binance', 'SOLUSDT', 'CRYPTO', 'SOL', 'USDT', 0.01, 0.1, 1, 0.01, 100, 20),
  ('XRPUSDT', 'XRP / Tether', 'binance', 'XRPUSDT', 'CRYPTO', 'XRP', 'USDT', 0.0001, 0.0001, 1, 1, 100000, 20)
ON CONFLICT (symbol) DO UPDATE SET
  display_name = EXCLUDED.display_name,
  provider = EXCLUDED.provider,
  provider_symbol = EXCLUDED.provider_symbol,
  asset_class = EXCLUDED.asset_class,
  base_currency = EXCLUDED.base_currency,
  quote_currency = EXCLUDED.quote_currency,
  updated_at = now();
