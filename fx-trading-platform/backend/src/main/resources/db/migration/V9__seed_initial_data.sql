INSERT INTO market.symbols (
  symbol, display_name, provider_symbol, base_currency, quote_currency,
  pip_size, tick_size, lot_size, min_lot, max_lot, leverage
) VALUES
  ('EURUSD', 'Euro / US Dollar', 'C:EURUSD', 'EUR', 'USD', 0.0001, 0.00001, 100000, 0.01, 100, 100),
  ('GBPUSD', 'British Pound / US Dollar', 'C:GBPUSD', 'GBP', 'USD', 0.0001, 0.00001, 100000, 0.01, 100, 100),
  ('USDJPY', 'US Dollar / Japanese Yen', 'C:USDJPY', 'USD', 'JPY', 0.01, 0.001, 100000, 0.01, 100, 100),
  ('AUDUSD', 'Australian Dollar / US Dollar', 'C:AUDUSD', 'AUD', 'USD', 0.0001, 0.00001, 100000, 0.01, 100, 100)
ON CONFLICT (symbol) DO NOTHING;

INSERT INTO risk.risk_configs (symbol, max_leverage, max_lots, margin_call_level, stop_out_level)
VALUES (NULL, 100, 100, 100, 50);
