INSERT INTO market.symbols (
  symbol, display_name, provider, provider_symbol, asset_class, base_currency, quote_currency,
  pip_size, tick_size, lot_size, min_lot, max_lot, leverage
) VALUES
  ('EURUSD', 'Euro / US Dollar', 'demo', 'EURUSD', 'FOREX', 'EUR', 'USD', 0.0001, 0.00001, 100000, 0.01, 100, 100),
  ('GBPUSD', 'British Pound / US Dollar', 'demo', 'GBPUSD', 'FOREX', 'GBP', 'USD', 0.0001, 0.00001, 100000, 0.01, 100, 100),
  ('USDJPY', 'US Dollar / Japanese Yen', 'demo', 'USDJPY', 'FOREX', 'USD', 'JPY', 0.01, 0.001, 100000, 0.01, 100, 100),
  ('AUDUSD', 'Australian Dollar / US Dollar', 'demo', 'AUDUSD', 'FOREX', 'AUD', 'USD', 0.0001, 0.00001, 100000, 0.01, 100, 100),
  ('XAUUSD', 'Gold Spot / US Dollar', 'demo', 'XAUUSD', 'METALS', 'XAU', 'USD', 0.01, 0.1, 100, 0.01, 100, 100),
  ('BTCUSDT', 'Bitcoin / Tether', 'demo', 'BTCUSDT', 'CRYPTO', 'BTC', 'USDT', 0.01, 0.1, 1, 0.01, 100, 20),
  ('ETHUSDT', 'Ethereum / Tether', 'demo', 'ETHUSDT', 'CRYPTO', 'ETH', 'USDT', 0.01, 0.1, 1, 0.01, 100, 20),
  ('US100', 'US Tech 100 Index', 'demo', 'US100', 'INDICES', 'US100', 'USD', 0.1, 0.1, 1, 0.01, 100, 50)
ON CONFLICT (symbol) DO NOTHING;

WITH seed_window AS (
  SELECT
    date_trunc('hour', now()) - interval '2 years' AS seed_from,
    date_trunc('hour', now()) AS seed_to
),
timeframes(timeframe, step) AS (
  VALUES
    ('5m', interval '5 minutes'),
    ('15m', interval '15 minutes'),
    ('1h', interval '1 hour')
),
symbol_profiles AS (
  SELECT
    symbol,
    CASE symbol
      WHEN 'BTCUSDT' THEN 67240.0
      WHEN 'ETHUSDT' THEN 3420.0
      WHEN 'XAUUSD' THEN 2348.4
      WHEN 'US100' THEN 18924.6
      WHEN 'USDJPY' THEN 156.420
      WHEN 'GBPUSD' THEN 1.27120
      WHEN 'AUDUSD' THEN 0.66420
      ELSE 1.08320
    END::numeric AS base_price,
    CASE symbol
      WHEN 'BTCUSDT' THEN 135.0
      WHEN 'ETHUSDT' THEN 8.0
      WHEN 'XAUUSD' THEN 4.2
      WHEN 'US100' THEN 26.0
      WHEN 'USDJPY' THEN 0.16
      WHEN 'GBPUSD' THEN 0.0016
      WHEN 'AUDUSD' THEN 0.0012
      ELSE 0.0014
    END::numeric AS amplitude,
    CASE symbol
      WHEN 'BTCUSDT' THEN 280.0
      WHEN 'ETHUSDT' THEN 1800.0
      WHEN 'XAUUSD' THEN 96.0
      WHEN 'US100' THEN 320.0
      ELSE 900.0
    END::numeric AS volume_base
  FROM market.symbols
  WHERE symbol IN ('EURUSD', 'GBPUSD', 'USDJPY', 'AUDUSD', 'XAUUSD', 'BTCUSDT', 'ETHUSDT', 'US100')
),
series AS (
  SELECT
    symbol_profiles.symbol,
    symbol_profiles.base_price,
    symbol_profiles.amplitude,
    symbol_profiles.volume_base,
    timeframes.timeframe,
    bars.open_time,
    row_number() OVER (
      PARTITION BY symbol_profiles.symbol, timeframes.timeframe
      ORDER BY bars.open_time
    ) - 1 AS bar_index
  FROM symbol_profiles
  CROSS JOIN timeframes
  CROSS JOIN seed_window
  CROSS JOIN LATERAL generate_series(seed_window.seed_from, seed_window.seed_to, timeframes.step) AS bars(open_time)
),
prices AS (
  SELECT
    symbol,
    timeframe,
    open_time,
    amplitude,
    volume_base,
    (
      base_price::double precision
      + sin(bar_index::double precision / 37.0) * amplitude::double precision
      + cos(bar_index::double precision / 91.0) * amplitude::double precision * 0.42
      + ((bar_index % 97) - 48)::double precision * amplitude::double precision * 0.002
    ) AS open_value,
    (
      base_price::double precision
      + sin((bar_index::double precision + 1.0) / 37.0) * amplitude::double precision
      + cos((bar_index::double precision + 1.0) / 91.0) * amplitude::double precision * 0.42
      + (((bar_index + 1) % 97) - 48)::double precision * amplitude::double precision * 0.002
    ) AS close_value,
    bar_index
  FROM series
)
INSERT INTO market.candles (symbol, timeframe, open_time, open, high, low, close, volume, source)
SELECT
  symbol,
  timeframe,
  open_time,
  round(open_value::numeric, 10),
  round((GREATEST(open_value, close_value) + amplitude::double precision * 0.35)::numeric, 10),
  round((LEAST(open_value, close_value) - amplitude::double precision * 0.35)::numeric, 10),
  round(close_value::numeric, 10),
  round((volume_base::double precision * (1.0 + abs(sin(bar_index::double precision / 11.0))))::numeric, 8),
  'demo-history'
FROM prices
ON CONFLICT (symbol, timeframe, open_time) DO NOTHING;
