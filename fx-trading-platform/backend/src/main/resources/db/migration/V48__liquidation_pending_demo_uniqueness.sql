DROP INDEX IF EXISTS core.ux_trading_accounts_user_active_demo;

CREATE UNIQUE INDEX ux_trading_accounts_user_active_demo
  ON core.trading_accounts(user_id)
  WHERE account_type = 'DEMO'
    AND status IN (
      'ACTIVE', 'RISK_REDUCTION_PENDING',
      'ISOLATED_LIQUIDATION_PENDING', 'LIQUIDATION_PENDING'
    );
