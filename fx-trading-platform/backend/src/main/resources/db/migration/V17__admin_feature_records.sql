CREATE TABLE IF NOT EXISTS admin.feature_records (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  page_key VARCHAR(120) NOT NULL,
  record_key VARCHAR(160) NOT NULL,
  data JSONB NOT NULL DEFAULT '{}'::jsonb,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_by UUID REFERENCES auth.users(id),
  updated_by UUID REFERENCES auth.users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ux_feature_records_page_record UNIQUE (page_key, record_key)
);

CREATE INDEX IF NOT EXISTS idx_feature_records_page_status_time
  ON admin.feature_records(page_key, status, created_at DESC);
