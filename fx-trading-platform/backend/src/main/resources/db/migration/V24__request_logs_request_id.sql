ALTER TABLE audit.request_logs
  ADD COLUMN IF NOT EXISTS request_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_request_logs_request_id
  ON audit.request_logs(request_id);
