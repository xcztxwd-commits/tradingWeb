DROP INDEX IF EXISTS trading.ux_orders_user_account_client_order_id;

CREATE UNIQUE INDEX ux_orders_user_account_client_order_id
  ON trading.orders(user_id, account_id, client_order_id)
  WHERE client_order_id IS NOT NULL
    AND status IN (
      'RECEIVED',
      'VALIDATING',
      'ACCEPTED',
      'PENDING_ACTIVATION',
      'PENDING',
      'WORKING',
      'PARTIALLY_FILLED',
      'CANCEL_PENDING'
    );
