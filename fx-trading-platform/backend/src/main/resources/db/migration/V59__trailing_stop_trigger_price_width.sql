ALTER TABLE trading.orders
  ALTER COLUMN trigger_price TYPE NUMERIC(31, 10)
  USING trigger_price::NUMERIC(31, 10);

ALTER TABLE trading.orders
  ADD CONSTRAINT ck_orders_static_trigger_price_numeric24
  CHECK (
    COALESCE(
      trigger_price IS NULL
      OR trigger_price BETWEEN
        -99999999999999.9999999999 AND 99999999999999.9999999999
      OR (
        order_type = 'TRAILING_STOP_MARKET'
        AND
        product_type = 'LINEAR_PERP'
        AND order_origin = 'PROTECTIVE'
        AND protection_type = 'STOP_LOSS'
        AND reduce_only = TRUE
        AND parent_position_id IS NOT NULL
        AND ((trailing_delta IS NOT NULL) <> (trailing_rate IS NOT NULL))
      ),
      FALSE
    )
  );
