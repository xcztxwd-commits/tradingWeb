CREATE SCHEMA IF NOT EXISTS trading_lab;

CREATE SEQUENCE trading_lab.run_queue_sequence
  AS BIGINT
  START WITH 1
  INCREMENT BY 1
  MINVALUE 1
  NO MAXVALUE
  CACHE 1
  OWNED BY NONE;

CREATE TABLE trading_lab.scenarios (
  id UUID NOT NULL,
  name VARCHAR(200) NOT NULL,
  description TEXT,
  status VARCHAR(32) NOT NULL,
  negative_mode BOOLEAN NOT NULL DEFAULT FALSE,
  seed BIGINT,
  model_version VARCHAR(80) NOT NULL,
  scenario_json JSONB NOT NULL,
  config_snapshot_json JSONB NOT NULL,
  config_snapshot_hash VARCHAR(64) NOT NULL,
  symbol_config_version VARCHAR(120) NOT NULL,
  code_version VARCHAR(160) NOT NULL,
  created_by UUID NOT NULL,
  updated_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT pk_trading_lab_scenarios PRIMARY KEY (id),
  CONSTRAINT fk_trading_lab_scenarios_created_by
    FOREIGN KEY (created_by) REFERENCES auth.users(id) ON DELETE RESTRICT,
  CONSTRAINT fk_trading_lab_scenarios_updated_by
    FOREIGN KEY (updated_by) REFERENCES auth.users(id) ON DELETE RESTRICT
);

CREATE INDEX idx_trading_lab_scenarios_status
  ON trading_lab.scenarios (status);
CREATE INDEX idx_trading_lab_scenarios_created_at
  ON trading_lab.scenarios (created_at DESC);
CREATE INDEX idx_trading_lab_scenarios_updated_at
  ON trading_lab.scenarios (updated_at DESC);

CREATE TABLE trading_lab.reports (
  id UUID NOT NULL,
  scenario_id UUID,
  status VARCHAR(32) NOT NULL,
  model_version VARCHAR(80) NOT NULL,
  config_snapshot_hash VARCHAR(64) NOT NULL,
  code_version VARCHAR(160) NOT NULL,
  metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  uncompressed_bytes BIGINT NOT NULL DEFAULT 0,
  compressed_bytes BIGINT NOT NULL DEFAULT 0,
  chunk_count INTEGER NOT NULL DEFAULT 0,
  retained_until TIMESTAMPTZ NOT NULL,
  permanent BOOLEAN NOT NULL DEFAULT FALSE,
  failure_code VARCHAR(80),
  failure_message TEXT,
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT pk_trading_lab_reports PRIMARY KEY (id),
  CONSTRAINT fk_trading_lab_reports_scenario
    FOREIGN KEY (scenario_id) REFERENCES trading_lab.scenarios(id) ON DELETE RESTRICT,
  CONSTRAINT fk_trading_lab_reports_created_by
    FOREIGN KEY (created_by) REFERENCES auth.users(id) ON DELETE RESTRICT,
  CONSTRAINT ck_trading_lab_reports_byte_counts
    CHECK (uncompressed_bytes >= 0 AND compressed_bytes >= 0),
  CONSTRAINT ck_trading_lab_reports_chunk_count
    CHECK (chunk_count >= 0)
);

CREATE TABLE trading_lab.runs (
  id UUID NOT NULL,
  scenario_id UUID NOT NULL,
  state VARCHAR(32) NOT NULL,
  queue_sequence BIGINT NOT NULL
    DEFAULT nextval('trading_lab.run_queue_sequence'::regclass),
  lease_key SMALLINT,
  lease_owner VARCHAR(160),
  lease_until TIMESTAMPTZ,
  cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
  pause_requested BOOLEAN NOT NULL DEFAULT FALSE,
  virtual_started_at TIMESTAMPTZ,
  virtual_current_at TIMESTAMPTZ,
  processed_ticks BIGINT NOT NULL DEFAULT 0,
  total_ticks BIGINT NOT NULL DEFAULT 0,
  speed_multiplier NUMERIC(18, 6) NOT NULL DEFAULT 1,
  current_step BIGINT NOT NULL DEFAULT 0,
  failure_code VARCHAR(80),
  failure_message TEXT,
  report_id UUID,
  scenario_snapshot_json JSONB NOT NULL,
  config_snapshot_json JSONB NOT NULL,
  config_snapshot_hash VARCHAR(64) NOT NULL,
  model_version VARCHAR(80) NOT NULL,
  symbol_config_version VARCHAR(120) NOT NULL,
  code_version VARCHAR(160) NOT NULL,
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT pk_trading_lab_runs PRIMARY KEY (id),
  CONSTRAINT fk_trading_lab_runs_scenario
    FOREIGN KEY (scenario_id) REFERENCES trading_lab.scenarios(id) ON DELETE RESTRICT,
  CONSTRAINT fk_trading_lab_runs_report
    FOREIGN KEY (report_id) REFERENCES trading_lab.reports(id) ON DELETE SET NULL,
  CONSTRAINT fk_trading_lab_runs_created_by
    FOREIGN KEY (created_by) REFERENCES auth.users(id) ON DELETE RESTRICT,
  CONSTRAINT ux_trading_lab_runs_queue_sequence UNIQUE (queue_sequence),
  CONSTRAINT ux_trading_lab_runs_report UNIQUE (report_id),
  CONSTRAINT ck_trading_lab_runs_state CHECK (state IN (
    'DRAFT',
    'VALIDATING',
    'QUEUED',
    'RESETTING',
    'RUNNING',
    'PAUSED',
    'CANCELLING',
    'CANCELLED',
    'FAILED',
    'COMPLETED',
    'CLEANING'
  )),
  CONSTRAINT ck_trading_lab_runs_ticks
    CHECK (processed_ticks >= 0 AND total_ticks >= 0 AND current_step >= 0),
  CONSTRAINT ck_trading_lab_runs_speed_multiplier
    CHECK (speed_multiplier > 0),
  CONSTRAINT ck_trading_lab_runs_lease_key
    CHECK (lease_key IS NULL OR lease_key IN (1)),
  CONSTRAINT ck_trading_lab_runs_lease_fields CHECK (
    (lease_key IS NULL AND lease_owner IS NULL AND lease_until IS NULL)
    OR
    (lease_key IS NOT NULL AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
  )
);

CREATE UNIQUE INDEX ux_trading_lab_runs_singleton_lease
  ON trading_lab.runs (lease_key)
  WHERE lease_key IS NOT NULL;

CREATE INDEX idx_trading_lab_runs_state_queue
  ON trading_lab.runs (state, queue_sequence);

CREATE TABLE trading_lab.run_transitions (
  id UUID NOT NULL,
  run_id UUID NOT NULL,
  from_state VARCHAR(32) NOT NULL,
  to_state VARCHAR(32) NOT NULL,
  run_version BIGINT NOT NULL,
  reason TEXT,
  idempotency_key VARCHAR(160) NOT NULL,
  real_time TIMESTAMPTZ NOT NULL DEFAULT now(),
  virtual_time TIMESTAMPTZ,
  actor_id UUID,
  details_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  CONSTRAINT pk_trading_lab_run_transitions PRIMARY KEY (id),
  CONSTRAINT fk_trading_lab_run_transitions_run
    FOREIGN KEY (run_id) REFERENCES trading_lab.runs(id) ON DELETE CASCADE,
  CONSTRAINT fk_trading_lab_run_transitions_actor
    FOREIGN KEY (actor_id) REFERENCES auth.users(id) ON DELETE SET NULL,
  CONSTRAINT ux_trading_lab_run_transitions_idempotency
    UNIQUE (run_id, idempotency_key),
  CONSTRAINT ux_trading_lab_run_transitions_version
    UNIQUE (run_id, run_version),
  CONSTRAINT ck_trading_lab_run_transitions_version CHECK (run_version >= 0)
);

CREATE TABLE trading_lab.run_events (
  id UUID NOT NULL,
  run_id UUID NOT NULL,
  sequence BIGINT NOT NULL,
  event_type VARCHAR(120) NOT NULL,
  virtual_time TIMESTAMPTZ,
  real_time TIMESTAMPTZ NOT NULL,
  correlation_id VARCHAR(160),
  payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT pk_trading_lab_run_events PRIMARY KEY (id),
  CONSTRAINT fk_trading_lab_run_events_run
    FOREIGN KEY (run_id) REFERENCES trading_lab.runs(id) ON DELETE CASCADE,
  CONSTRAINT ux_trading_lab_run_events_sequence UNIQUE (run_id, sequence),
  CONSTRAINT ck_trading_lab_run_events_sequence CHECK (sequence >= 0)
);

CREATE TABLE trading_lab.report_chunks (
  id UUID NOT NULL,
  report_id UUID NOT NULL,
  section VARCHAR(80) NOT NULL,
  sequence BIGINT NOT NULL,
  encoding VARCHAR(32) NOT NULL,
  uncompressed_bytes BIGINT NOT NULL,
  compressed_bytes BIGINT NOT NULL,
  payload BYTEA NOT NULL,
  checksum VARCHAR(128) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT pk_trading_lab_report_chunks PRIMARY KEY (id),
  CONSTRAINT fk_trading_lab_report_chunks_report
    FOREIGN KEY (report_id) REFERENCES trading_lab.reports(id) ON DELETE CASCADE,
  CONSTRAINT ux_trading_lab_report_chunks_sequence
    UNIQUE (report_id, section, sequence),
  CONSTRAINT ck_trading_lab_report_chunks_sequence CHECK (sequence >= 0),
  CONSTRAINT ck_trading_lab_report_chunks_byte_counts
    CHECK (uncompressed_bytes >= 0 AND compressed_bytes >= 0)
);

CREATE TABLE trading_lab.audit_events (
  id UUID NOT NULL,
  actor_id UUID,
  client_ip VARCHAR(64) NOT NULL,
  request_id UUID NOT NULL,
  scenario_id UUID,
  run_id UUID,
  action VARCHAR(120) NOT NULL,
  result VARCHAR(64) NOT NULL,
  details_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT pk_trading_lab_audit_events PRIMARY KEY (id),
  CONSTRAINT fk_trading_lab_audit_actor
    FOREIGN KEY (actor_id) REFERENCES auth.users(id) ON DELETE SET NULL,
  CONSTRAINT fk_trading_lab_audit_scenario
    FOREIGN KEY (scenario_id) REFERENCES trading_lab.scenarios(id) ON DELETE SET NULL,
  CONSTRAINT fk_trading_lab_audit_run
    FOREIGN KEY (run_id) REFERENCES trading_lab.runs(id) ON DELETE SET NULL
);

CREATE INDEX idx_trading_lab_audit_request_id
  ON trading_lab.audit_events (request_id);
CREATE INDEX idx_trading_lab_audit_actor_created_at
  ON trading_lab.audit_events (actor_id, created_at DESC);
CREATE INDEX idx_trading_lab_audit_scenario_created_at
  ON trading_lab.audit_events (scenario_id, created_at DESC);
CREATE INDEX idx_trading_lab_audit_run_created_at
  ON trading_lab.audit_events (run_id, created_at DESC);
