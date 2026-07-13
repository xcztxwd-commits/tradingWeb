ALTER TABLE trading.trades
    ADD COLUMN IF NOT EXISTS canonical_full_fill BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE trading.trades
    ADD CONSTRAINT ck_trades_canonical_full_fill_product
    CHECK (NOT canonical_full_fill OR product_type IN ('CRYPTO_SPOT', 'LINEAR_PERP'));

UPDATE trading.trades AS trade_row
SET canonical_full_fill = TRUE
FROM core.trading_accounts AS account_row
WHERE account_row.id = trade_row.account_id
  AND account_row.account_type = 'DEMO'
  AND trade_row.product_type IN ('CRYPTO_SPOT', 'LINEAR_PERP')
  AND trade_row.canonical_full_fill = FALSE;

CREATE UNIQUE INDEX IF NOT EXISTS ux_trades_p0_order_full_fill
    ON trading.trades(order_id)
    WHERE canonical_full_fill = TRUE;
