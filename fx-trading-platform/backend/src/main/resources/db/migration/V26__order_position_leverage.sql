ALTER TABLE trading.orders
  ADD COLUMN leverage INTEGER;

UPDATE trading.orders o
SET leverage = a.leverage
FROM core.trading_accounts a
WHERE o.account_id = a.id
  AND o.leverage IS NULL;

ALTER TABLE trading.orders
  ALTER COLUMN leverage SET NOT NULL;

ALTER TABLE trading.positions
  ADD COLUMN leverage INTEGER;

UPDATE trading.positions p
SET leverage = a.leverage
FROM core.trading_accounts a
WHERE p.account_id = a.id
  AND p.leverage IS NULL;

ALTER TABLE trading.positions
  ALTER COLUMN leverage SET NOT NULL;
