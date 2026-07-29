CREATE SCHEMA IF NOT EXISTS validation_control;

-- This singleton survives validation business-schema clean.  V66 is intentionally replayable
-- after Flyway history is rebuilt, so every control-plane DDL statement is idempotent.
CREATE TABLE IF NOT EXISTS validation_control.reset_state (
  singleton_key SMALLINT NOT NULL DEFAULT 1,
  generation BIGINT NOT NULL DEFAULT 0,
  state VARCHAR(24) NOT NULL DEFAULT 'UNINITIALIZED',
  reset_id UUID,
  database_name VARCHAR(255),
  redis_generation BIGINT,
  memory_generation BIGINT,
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ,
  steps_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  error_code VARCHAR(80),
  version BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT pk_validation_control_reset_state PRIMARY KEY (singleton_key),
  CONSTRAINT ux_validation_control_reset_generation UNIQUE (singleton_key, generation),
  CONSTRAINT ck_validation_control_singleton CHECK (singleton_key = 1),
  CONSTRAINT ck_validation_control_generation CHECK (generation >= 0),
  CONSTRAINT ck_validation_control_state CHECK (state IN (
    'UNINITIALIZED', 'RESETTING', 'READY', 'FAILED'
  )),
  CONSTRAINT ck_validation_control_generations CHECK (
    (redis_generation IS NULL OR redis_generation >= 0)
    AND (memory_generation IS NULL OR memory_generation >= 0)
  ),
  CONSTRAINT ck_validation_control_steps_json CHECK (jsonb_typeof(steps_json) = 'array'),
  CONSTRAINT ck_validation_control_version CHECK (version >= 0)
);

INSERT INTO validation_control.reset_state (singleton_key, generation, state)
VALUES (1, 0, 'UNINITIALIZED')
ON CONFLICT (singleton_key) DO NOTHING;

CREATE SCHEMA IF NOT EXISTS validation_runtime;

CREATE TABLE validation_runtime.run_executions (
  id UUID NOT NULL,
  reset_key SMALLINT NOT NULL DEFAULT 1,
  generation BIGINT NOT NULL,
  request_fingerprint VARCHAR(160) NOT NULL,
  request_json JSONB NOT NULL,
  user_id UUID,
  account_id UUID,
  state VARCHAR(32) NOT NULL,
  pause_requested BOOLEAN NOT NULL DEFAULT FALSE,
  cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
  last_completed_tick_sequence BIGINT NOT NULL DEFAULT -1,
  virtual_started_at TIMESTAMPTZ,
  virtual_current_at TIMESTAMPTZ,
  next_event_sequence BIGINT NOT NULL DEFAULT 0,
  speed_multiplier NUMERIC(18, 6) NOT NULL DEFAULT 1,
  lease_owner VARCHAR(160),
  lease_token UUID,
  lease_until TIMESTAMPTZ,
  failure_code VARCHAR(80),
  failure_message TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT pk_validation_runtime_run_executions PRIMARY KEY (id),
  CONSTRAINT ux_validation_runtime_run_generation UNIQUE (id, generation),
  CONSTRAINT ux_validation_runtime_one_run_per_generation UNIQUE (generation),
  CONSTRAINT fk_validation_runtime_run_generation
    FOREIGN KEY (reset_key, generation)
    REFERENCES validation_control.reset_state(singleton_key, generation)
    ON DELETE RESTRICT,
  CONSTRAINT ck_validation_runtime_run_reset_key CHECK (reset_key = 1),
  CONSTRAINT ck_validation_runtime_run_generation CHECK (generation > 0),
  CONSTRAINT ck_validation_runtime_run_request_json CHECK (jsonb_typeof(request_json) = 'object'),
  CONSTRAINT ck_validation_runtime_run_state CHECK (state IN (
    'ACCEPTED', 'STARTING', 'RUNNING', 'PAUSED', 'RECOVERING',
    'RECOVERY_BLOCKED', 'CANCELLING', 'CANCELLED', 'FAILED', 'COMPLETED'
  )),
  CONSTRAINT ck_validation_runtime_run_tick CHECK (last_completed_tick_sequence >= -1),
  CONSTRAINT ck_validation_runtime_run_event_sequence CHECK (next_event_sequence >= 0),
  CONSTRAINT ck_validation_runtime_run_speed CHECK (speed_multiplier > 0),
  CONSTRAINT ck_validation_runtime_run_lease CHECK (
    (lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)
    OR
    (lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
  ),
  CONSTRAINT ck_validation_runtime_run_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX ux_validation_runtime_single_active_run
  ON validation_runtime.run_executions ((1))
  WHERE state IN (
    'ACCEPTED', 'STARTING', 'RUNNING', 'PAUSED', 'RECOVERING',
    'RECOVERY_BLOCKED', 'CANCELLING'
  );

CREATE INDEX idx_validation_runtime_run_state_created
  ON validation_runtime.run_executions (state, created_at, id);

CREATE TABLE validation_runtime.run_events (
  id UUID NOT NULL DEFAULT gen_random_uuid(),
  run_id UUID NOT NULL,
  generation BIGINT NOT NULL,
  sequence BIGINT NOT NULL,
  durable_event_key VARCHAR(200) NOT NULL,
  request_fingerprint VARCHAR(160) NOT NULL,
  event_type VARCHAR(120) NOT NULL,
  virtual_time TIMESTAMPTZ,
  real_time TIMESTAMPTZ NOT NULL DEFAULT now(),
  correlation_id VARCHAR(160),
  payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  CONSTRAINT pk_validation_runtime_run_events PRIMARY KEY (id),
  CONSTRAINT fk_validation_runtime_run_events_run
    FOREIGN KEY (run_id, generation)
    REFERENCES validation_runtime.run_executions(id, generation)
    ON DELETE CASCADE,
  CONSTRAINT ux_validation_runtime_run_event_sequence UNIQUE (run_id, sequence),
  CONSTRAINT ux_validation_runtime_run_event_key UNIQUE (run_id, durable_event_key),
  CONSTRAINT ck_validation_runtime_run_event_sequence CHECK (sequence > 0),
  CONSTRAINT ck_validation_runtime_run_event_payload CHECK (jsonb_typeof(payload_json) = 'object')
);

CREATE INDEX idx_validation_runtime_run_events_after_sequence
  ON validation_runtime.run_events (run_id, sequence);

CREATE TABLE validation_runtime.operations (
  id UUID NOT NULL DEFAULT gen_random_uuid(),
  run_id UUID NOT NULL,
  generation BIGINT NOT NULL,
  operation_sequence BIGINT NOT NULL,
  operation VARCHAR(32) NOT NULL,
  idempotency_key VARCHAR(200) NOT NULL,
  request_fingerprint VARCHAR(160) NOT NULL,
  request_json JSONB NOT NULL,
  state VARCHAR(24) NOT NULL,
  virtual_time TIMESTAMPTZ,
  http_status SMALLINT,
  correlation_id VARCHAR(160),
  response_json JSONB,
  failure_code VARCHAR(80),
  failure_message TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ,
  CONSTRAINT pk_validation_runtime_operations PRIMARY KEY (id),
  CONSTRAINT fk_validation_runtime_operations_run
    FOREIGN KEY (run_id, generation)
    REFERENCES validation_runtime.run_executions(id, generation)
    ON DELETE CASCADE,
  CONSTRAINT ux_validation_runtime_operation_sequence
    UNIQUE (run_id, operation_sequence),
  CONSTRAINT ux_validation_runtime_operation_key
    UNIQUE (run_id, idempotency_key),
  CONSTRAINT ck_validation_runtime_operation_sequence CHECK (operation_sequence >= 0),
  CONSTRAINT ck_validation_runtime_operation CHECK (operation IN (
    'REGISTER', 'SEED_ACCOUNT', 'CONFIGURE_ACCOUNT', 'START_MARKET_PATH',
    'SYSTEM_STEP', 'PUBLIC_ACTION', 'QUERY_STATE'
  )),
  CONSTRAINT ck_validation_runtime_operation_state CHECK (state IN (
    'INTENT', 'COMPLETED', 'RECOVERED', 'FAILED'
  )),
  CONSTRAINT ck_validation_runtime_operation_request CHECK (jsonb_typeof(request_json) = 'object'),
  CONSTRAINT ck_validation_runtime_operation_response CHECK (
    response_json IS NULL OR jsonb_typeof(response_json) = 'object'
  ),
  CONSTRAINT ck_validation_runtime_operation_http_status CHECK (
    http_status IS NULL OR http_status BETWEEN 100 AND 599
  )
);

CREATE INDEX idx_validation_runtime_unfinished_operations
  ON validation_runtime.operations (run_id, operation_sequence)
  WHERE state = 'INTENT';

CREATE TABLE validation_runtime.seed_receipts (
  seed_id UUID NOT NULL,
  reset_key SMALLINT NOT NULL DEFAULT 1,
  generation BIGINT NOT NULL,
  account_id UUID NOT NULL,
  request_fingerprint CHAR(64) NOT NULL,
  canonical_request_json JSONB NOT NULL,
  perpetual_usdt_balance NUMERIC(24, 8) NOT NULL,
  spot_balances_json JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT pk_validation_runtime_seed_receipts PRIMARY KEY (seed_id),
  CONSTRAINT ux_validation_runtime_seed_account UNIQUE (account_id, generation),
  CONSTRAINT fk_validation_runtime_seed_generation
    FOREIGN KEY (reset_key, generation)
    REFERENCES validation_control.reset_state(singleton_key, generation)
    ON DELETE RESTRICT,
  CONSTRAINT ck_validation_runtime_seed_reset_key CHECK (reset_key = 1),
  CONSTRAINT ck_validation_runtime_seed_generation CHECK (generation > 0),
  CONSTRAINT ck_validation_runtime_seed_fingerprint CHECK (
    request_fingerprint ~ '^[0-9a-f]{64}$'
  ),
  CONSTRAINT ck_validation_runtime_seed_request CHECK (
    jsonb_typeof(canonical_request_json) = 'object'
  ),
  CONSTRAINT ck_validation_runtime_seed_perpetual CHECK (perpetual_usdt_balance >= 0),
  CONSTRAINT ck_validation_runtime_seed_spot CHECK (jsonb_typeof(spot_balances_json) = 'object')
);

CREATE TABLE validation_runtime.system_step_receipts (
  id UUID NOT NULL DEFAULT gen_random_uuid(),
  run_id UUID NOT NULL,
  generation BIGINT NOT NULL,
  tick_sequence BIGINT NOT NULL,
  phase VARCHAR(16) NOT NULL,
  request_fingerprint VARCHAR(160) NOT NULL,
  virtual_time TIMESTAMPTZ NOT NULL,
  receipt_json JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT pk_validation_runtime_system_step_receipts PRIMARY KEY (id),
  CONSTRAINT ux_validation_runtime_system_step
    UNIQUE (run_id, tick_sequence, phase),
  CONSTRAINT fk_validation_runtime_system_step_run
    FOREIGN KEY (run_id, generation)
    REFERENCES validation_runtime.run_executions(id, generation)
    ON DELETE CASCADE,
  CONSTRAINT ck_validation_runtime_system_step_sequence CHECK (tick_sequence > 0),
  CONSTRAINT ck_validation_runtime_system_step_phase CHECK (
    phase IN ('PRE_ACTIONS', 'POST_ACTIONS', 'FULL')
  ),
  CONSTRAINT ck_validation_runtime_system_step_receipt CHECK (jsonb_typeof(receipt_json) = 'object')
);
