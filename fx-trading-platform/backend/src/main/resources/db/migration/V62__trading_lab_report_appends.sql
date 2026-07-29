CREATE TABLE trading_lab.report_appends (
  report_id UUID NOT NULL,
  section VARCHAR(80) NOT NULL,
  source_sequence BIGINT NOT NULL,
  canonical_bytes BIGINT NOT NULL,
  canonical_checksum VARCHAR(128) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT pk_trading_lab_report_appends
    PRIMARY KEY (report_id, section, source_sequence),
  CONSTRAINT fk_trading_lab_report_appends_report
    FOREIGN KEY (report_id) REFERENCES trading_lab.reports(id) ON DELETE CASCADE,
  CONSTRAINT ck_trading_lab_report_appends_section CHECK (section IN (
    'METADATA',
    'ACTOR',
    'ENVIRONMENT',
    'SCENARIO',
    'MODEL_VERSION',
    'CONFIG_SNAPSHOT',
    'LOCAL_CALCULATION',
    'LIFECYCLE',
    'API_TRACE',
    'MARKET_TICKS',
    'CHECKPOINTS',
    'ACTUAL_STATE',
    'ERRORS',
    'CLEANUP'
  )),
  CONSTRAINT ck_trading_lab_report_appends_source_sequence
    CHECK (source_sequence >= -1),
  CONSTRAINT ck_trading_lab_report_appends_canonical_bytes
    CHECK (canonical_bytes > 0),
  CONSTRAINT ck_trading_lab_report_appends_checksum
    CHECK (canonical_checksum ~ '^sha256:[0-9a-f]{64}$')
);

CREATE INDEX idx_trading_lab_report_appends_created_at
  ON trading_lab.report_appends (created_at);
