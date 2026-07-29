ALTER TABLE trading.orders
    ADD COLUMN IF NOT EXISTS request_fingerprint CHAR(64);
