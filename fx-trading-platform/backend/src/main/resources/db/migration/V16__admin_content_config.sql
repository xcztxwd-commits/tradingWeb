CREATE SCHEMA IF NOT EXISTS content;
CREATE SCHEMA IF NOT EXISTS config;

CREATE TABLE IF NOT EXISTS content.messages (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  target_user_id UUID,
  title VARCHAR(200) NOT NULL,
  body TEXT NOT NULL,
  message_type VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  sent_by UUID NOT NULL,
  published_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_content_messages_target_time
  ON content.messages(target_user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS content.articles (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  article_type VARCHAR(64) NOT NULL,
  title VARCHAR(200) NOT NULL,
  summary TEXT,
  body TEXT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  language VARCHAR(16) NOT NULL DEFAULT 'zh-CN',
  sort_order INTEGER NOT NULL DEFAULT 0,
  published_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_content_articles_type_order
  ON content.articles(article_type, sort_order ASC, created_at DESC);

CREATE TABLE IF NOT EXISTS config.system_dictionaries (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  group_key VARCHAR(120) NOT NULL,
  item_key VARCHAR(120) NOT NULL,
  item_value VARCHAR(500) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  display_order INTEGER NOT NULL DEFAULT 0,
  description TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ux_system_dictionaries_group_item UNIQUE (group_key, item_key)
);

CREATE INDEX IF NOT EXISTS idx_system_dictionaries_group_order
  ON config.system_dictionaries(group_key, display_order ASC);

CREATE TABLE IF NOT EXISTS config.system_settings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  setting_key VARCHAR(160) NOT NULL UNIQUE,
  setting_value TEXT NOT NULL,
  value_type VARCHAR(32) NOT NULL DEFAULT 'STRING',
  description TEXT,
  editable BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
