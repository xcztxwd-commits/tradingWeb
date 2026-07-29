-- Durable reset-command receipts live outside every Flyway clean target. This migration is
-- intentionally replayable because cleaning public removes Flyway history while preserving
-- validation_control and its receipts.
CREATE TABLE IF NOT EXISTS validation_control.reset_receipts (
  operation_id UUID NOT NULL,
  run_id UUID NOT NULL,
  mode VARCHAR(16) NOT NULL,
  expected_generation BIGINT NOT NULL,
  target_generation BIGINT NOT NULL,
  reset_id UUID NOT NULL,
  request_fingerprint CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  receipt_json JSONB,
  error_code VARCHAR(80),
  started_at TIMESTAMPTZ NOT NULL,
  finished_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT pk_validation_control_reset_receipts PRIMARY KEY (operation_id),
  CONSTRAINT ck_validation_reset_receipt_mode CHECK (mode IN ('INITIAL', 'FINAL')),
  CONSTRAINT ck_validation_reset_receipt_expected_generation
    CHECK (expected_generation >= 0),
  CONSTRAINT ck_validation_reset_receipt_target_generation
    CHECK (target_generation = expected_generation + 1),
  CONSTRAINT ck_validation_reset_receipt_fingerprint
    CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_validation_reset_receipt_status
    CHECK (status IN ('IN_PROGRESS', 'SUCCEEDED', 'FAILED')),
  CONSTRAINT ck_validation_reset_receipt_json
    CHECK (receipt_json IS NULL OR jsonb_typeof(receipt_json) = 'object'),
  CONSTRAINT ck_validation_reset_receipt_terminal CHECK (
    (
      status = 'IN_PROGRESS'
      AND receipt_json IS NULL
      AND error_code IS NULL
      AND finished_at IS NULL
    )
    OR
    (
      status = 'SUCCEEDED'
      AND receipt_json IS NOT NULL
      AND error_code IS NULL
      AND finished_at IS NOT NULL
    )
    OR
    (
      status = 'FAILED'
      AND receipt_json IS NOT NULL
      AND error_code IS NOT NULL
      AND finished_at IS NOT NULL
    )
  )
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_validation_reset_receipt_run_mode
  ON validation_control.reset_receipts (run_id, mode);

CREATE INDEX IF NOT EXISTS idx_validation_reset_receipt_status_updated
  ON validation_control.reset_receipts (status, updated_at, operation_id);
