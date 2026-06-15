CREATE UNIQUE INDEX IF NOT EXISTS ux_admin_fund_operations_idempotency
  ON finance.admin_fund_operations(account_id, operation_type, idempotency_key)
  WHERE idempotency_key IS NOT NULL AND idempotency_key <> '';
