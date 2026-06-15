CREATE TABLE IF NOT EXISTS admin.table_column_preferences (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth.users(id),
  page_key VARCHAR(128) NOT NULL,
  hidden_columns JSONB NOT NULL DEFAULT '[]'::jsonb,
  table_size VARCHAR(32) NOT NULL DEFAULT 'large',
  show_border BOOLEAN NOT NULL DEFAULT FALSE,
  zebra BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_table_column_preferences_user_page UNIQUE(user_id, page_key)
);

CREATE TABLE IF NOT EXISTS admin.export_tasks (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  page_key VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
  filter_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  file_url TEXT,
  created_by UUID NOT NULL REFERENCES auth.users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS admin.import_tasks (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  page_key VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
  file_name TEXT NOT NULL,
  total_rows INTEGER NOT NULL DEFAULT 0,
  success_rows INTEGER NOT NULL DEFAULT 0,
  failed_rows INTEGER NOT NULL DEFAULT 0,
  created_by UUID NOT NULL REFERENCES auth.users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS admin.batch_operations (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  page_key VARCHAR(128) NOT NULL,
  operation VARCHAR(64) NOT NULL,
  row_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
  reason TEXT,
  status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
  created_by UUID NOT NULL REFERENCES auth.users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_export_tasks_page_time ON admin.export_tasks(page_key, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_import_tasks_page_time ON admin.import_tasks(page_key, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_batch_operations_page_time ON admin.batch_operations(page_key, created_at DESC);
