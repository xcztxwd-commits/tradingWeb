ALTER TABLE trading_lab.runs
  ADD COLUMN local_calculation_json JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE trading_lab.scenarios
  ADD CONSTRAINT ck_trading_lab_scenarios_status
  CHECK (status IN ('DRAFT', 'FROZEN'));
