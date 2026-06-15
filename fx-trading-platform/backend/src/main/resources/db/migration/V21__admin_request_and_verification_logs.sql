CREATE TABLE IF NOT EXISTS audit.request_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  method VARCHAR(16) NOT NULL,
  path TEXT NOT NULL,
  query_string TEXT,
  client_ip VARCHAR(128),
  user_agent TEXT,
  status_code INTEGER,
  duration_ms BIGINT,
  error_message TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_request_logs_created_at
  ON audit.request_logs(created_at DESC);

CREATE TABLE IF NOT EXISTS audit.verification_code_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  scene VARCHAR(64) NOT NULL,
  account VARCHAR(240) NOT NULL,
  channel VARCHAR(32) NOT NULL,
  code VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  error_message TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_verification_code_logs_created_at
  ON audit.verification_code_logs(created_at DESC);
