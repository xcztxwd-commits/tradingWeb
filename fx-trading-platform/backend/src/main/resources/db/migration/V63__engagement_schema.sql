CREATE TABLE content.content_items (
  id UUID CONSTRAINT pk_content_items PRIMARY KEY DEFAULT gen_random_uuid(),
  current_revision_id UUID,
  content_kind VARCHAR(32) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_content_items_kind
    CHECK (content_kind IN ('MESSAGE', 'POPUP_CAMPAIGN'))
);

CREATE TABLE content.content_assets (
  id UUID CONSTRAINT pk_content_assets PRIMARY KEY DEFAULT gen_random_uuid(),
  storage_key VARCHAR(512) NOT NULL,
  mime_type VARCHAR(64) NOT NULL,
  byte_size BIGINT NOT NULL,
  width INTEGER NOT NULL,
  height INTEGER NOT NULL,
  sha256 CHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at TIMESTAMPTZ,
  CONSTRAINT ux_content_assets_storage_key UNIQUE (storage_key),
  CONSTRAINT fk_content_assets_created_by
    FOREIGN KEY (created_by) REFERENCES auth.users(id) ON DELETE RESTRICT,
  CONSTRAINT ck_content_assets_mime_type
    CHECK (mime_type IN ('image/jpeg', 'image/png', 'image/webp')),
  CONSTRAINT ck_content_assets_dimensions
    CHECK (byte_size > 0 AND width > 0 AND height > 0),
  CONSTRAINT ck_content_assets_sha256
    CHECK (sha256 ~ '^[0-9a-fA-F]{64}$'),
  CONSTRAINT ck_content_assets_status
    CHECK (status IN ('ACTIVE', 'DELETED')),
  CONSTRAINT ck_content_assets_deleted
    CHECK ((status = 'DELETED') = (deleted_at IS NOT NULL))
);

CREATE TABLE content.content_revisions (
  id UUID CONSTRAINT pk_content_revisions PRIMARY KEY DEFAULT gen_random_uuid(),
  content_item_id UUID NOT NULL,
  revision_no INTEGER NOT NULL,
  title VARCHAR(200) NOT NULL,
  body_document JSONB NOT NULL,
  sanitized_html TEXT NOT NULL,
  cover_asset_id UUID,
  cta_label VARCHAR(80),
  cta_route_key VARCHAR(128),
  cta_params JSONB,
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ux_content_revisions_item_revision UNIQUE (content_item_id, revision_no),
  CONSTRAINT ux_content_revisions_item_id UNIQUE (content_item_id, id),
  CONSTRAINT fk_content_revisions_item
    FOREIGN KEY (content_item_id) REFERENCES content.content_items(id) ON DELETE RESTRICT,
  CONSTRAINT fk_content_revisions_cover_asset
    FOREIGN KEY (cover_asset_id) REFERENCES content.content_assets(id) ON DELETE RESTRICT,
  CONSTRAINT fk_content_revisions_created_by
    FOREIGN KEY (created_by) REFERENCES auth.users(id) ON DELETE RESTRICT,
  CONSTRAINT ck_content_revisions_revision_no CHECK (revision_no > 0),
  CONSTRAINT ck_content_revisions_title CHECK (btrim(title) <> ''),
  CONSTRAINT ck_content_revisions_body_document
    CHECK (jsonb_typeof(body_document) = 'object'),
  CONSTRAINT ck_content_revisions_cta
    CHECK (
      (cta_label IS NULL AND cta_route_key IS NULL AND cta_params IS NULL)
      OR (
        cta_label IS NOT NULL
        AND cta_route_key IS NOT NULL
        AND cta_params IS NOT NULL
        AND btrim(cta_label) <> ''
        AND btrim(cta_route_key) <> ''
        AND jsonb_typeof(cta_params) = 'object'
      )
    )
);

ALTER TABLE content.content_items
  ADD CONSTRAINT fk_content_items_current_revision_owner
  FOREIGN KEY (id, current_revision_id)
  REFERENCES content.content_revisions(content_item_id, id)
  ON DELETE RESTRICT;

CREATE TABLE content.popup_campaigns (
  id UUID CONSTRAINT pk_popup_campaigns PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(200) NOT NULL,
  content_item_id UUID NOT NULL,
  lifecycle_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  audience_type VARCHAR(32) NOT NULL,
  sync_to_inbox BOOLEAN NOT NULL DEFAULT FALSE,
  priority INTEGER NOT NULL DEFAULT 0,
  display_scope VARCHAR(32) NOT NULL DEFAULT 'ALL_BUSINESS_PAGES',
  page_keys JSONB NOT NULL DEFAULT '[]'::jsonb,
  device_scope VARCHAR(16) NOT NULL DEFAULT 'ALL',
  template_size VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
  time_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
  start_at TIMESTAMPTZ NOT NULL,
  end_at TIMESTAMPTZ NOT NULL,
  max_total_impressions INTEGER NOT NULL DEFAULT 3,
  max_daily_impressions INTEGER NOT NULL DEFAULT 1,
  min_interval_seconds INTEGER NOT NULL DEFAULT 14400,
  first_published_at TIMESTAMPTZ,
  last_published_at TIMESTAMPTZ,
  created_by UUID NOT NULL,
  updated_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  paused_at TIMESTAMPTZ,
  ended_at TIMESTAMPTZ,
  deleted_at TIMESTAMPTZ,
  CONSTRAINT ux_popup_campaigns_content_item UNIQUE (content_item_id),
  CONSTRAINT ux_popup_campaigns_id_content_item UNIQUE (id, content_item_id),
  CONSTRAINT fk_popup_campaigns_content_item
    FOREIGN KEY (content_item_id) REFERENCES content.content_items(id) ON DELETE RESTRICT,
  CONSTRAINT fk_popup_campaigns_created_by
    FOREIGN KEY (created_by) REFERENCES auth.users(id) ON DELETE RESTRICT,
  CONSTRAINT fk_popup_campaigns_updated_by
    FOREIGN KEY (updated_by) REFERENCES auth.users(id) ON DELETE RESTRICT,
  CONSTRAINT ck_popup_campaigns_name CHECK (btrim(name) <> ''),
  CONSTRAINT ck_popup_campaigns_lifecycle
    CHECK (lifecycle_status IN ('DRAFT', 'SCHEDULED', 'ACTIVE', 'PAUSED', 'ENDED', 'DELETED')),
  CONSTRAINT ck_popup_campaigns_audience
    CHECK (audience_type IN ('ALL', 'SELECTED')),
  CONSTRAINT ck_popup_campaigns_display_scope
    CHECK (display_scope IN ('ALL_BUSINESS_PAGES', 'SELECTED_PAGES')),
  CONSTRAINT ck_popup_campaigns_page_keys
    CHECK (
      CASE
        WHEN jsonb_typeof(page_keys) <> 'array' THEN FALSE
        WHEN display_scope = 'SELECTED_PAGES' THEN jsonb_array_length(page_keys) > 0
        ELSE TRUE
      END
    ),
  CONSTRAINT ck_popup_campaigns_device_scope
    CHECK (device_scope IN ('ALL', 'PC', 'MOBILE')),
  CONSTRAINT ck_popup_campaigns_template_size
    CHECK (template_size IN ('SMALL', 'MEDIUM', 'LARGE')),
  CONSTRAINT ck_popup_campaigns_time_zone CHECK (btrim(time_zone) <> ''),
  CONSTRAINT ck_popup_campaigns_time_window CHECK (end_at > start_at),
  CONSTRAINT ck_popup_campaigns_frequency
    CHECK (
      max_total_impressions > 0
      AND max_daily_impressions > 0
      AND max_daily_impressions <= max_total_impressions
      AND min_interval_seconds >= 0
    ),
  CONSTRAINT ck_popup_campaigns_publish_times
    CHECK (
      (
        (first_published_at IS NULL AND last_published_at IS NULL)
        OR (
          first_published_at IS NOT NULL
          AND last_published_at IS NOT NULL
          AND last_published_at >= first_published_at
        )
      )
      AND (
        lifecycle_status <> 'ACTIVE'
        OR (first_published_at IS NOT NULL AND last_published_at IS NOT NULL)
      )
    ),
  CONSTRAINT ck_popup_campaigns_deleted
    CHECK ((lifecycle_status = 'DELETED') = (deleted_at IS NOT NULL))
);

CREATE TABLE content.popup_campaign_targets (
  campaign_id UUID NOT NULL,
  user_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT pk_popup_campaign_targets PRIMARY KEY (campaign_id, user_id),
  CONSTRAINT fk_popup_campaign_targets_campaign
    FOREIGN KEY (campaign_id) REFERENCES content.popup_campaigns(id) ON DELETE RESTRICT,
  CONSTRAINT fk_popup_campaign_targets_user
    FOREIGN KEY (user_id) REFERENCES auth.users(id) ON DELETE RESTRICT
);

CREATE TABLE content.popup_campaign_user_states (
  campaign_id UUID NOT NULL,
  user_id UUID NOT NULL,
  total_impressions INTEGER NOT NULL DEFAULT 0,
  daily_bucket DATE,
  daily_impressions INTEGER NOT NULL DEFAULT 0,
  last_impression_at TIMESTAMPTZ,
  opted_out_at TIMESTAMPTZ,
  last_clicked_at TIMESTAMPTZ,
  active_delivery_id UUID,
  active_delivery_expires_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT pk_popup_campaign_user_states PRIMARY KEY (campaign_id, user_id),
  CONSTRAINT fk_popup_campaign_user_states_campaign
    FOREIGN KEY (campaign_id) REFERENCES content.popup_campaigns(id) ON DELETE RESTRICT,
  CONSTRAINT fk_popup_campaign_user_states_user
    FOREIGN KEY (user_id) REFERENCES auth.users(id) ON DELETE RESTRICT,
  CONSTRAINT ck_popup_campaign_user_states_counts
    CHECK (
      total_impressions >= 0
      AND daily_impressions >= 0
      AND daily_impressions <= total_impressions
      AND version >= 0
    ),
  CONSTRAINT ck_popup_campaign_user_states_daily_bucket
    CHECK (daily_bucket IS NOT NULL OR daily_impressions = 0),
  CONSTRAINT ck_popup_campaign_user_states_active_delivery
    CHECK ((active_delivery_id IS NULL) = (active_delivery_expires_at IS NULL))
);

CREATE TABLE content.popup_queue_sessions (
  id UUID CONSTRAINT pk_popup_queue_sessions PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL,
  trigger_type VARCHAR(64) NOT NULL,
  surface_page_key VARCHAR(64) NOT NULL,
  device_class VARCHAR(16) NOT NULL,
  max_items INTEGER NOT NULL,
  issued_count INTEGER NOT NULL DEFAULT 0,
  terminated_reason VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  terminated_at TIMESTAMPTZ,
  CONSTRAINT ux_popup_queue_sessions_id_user UNIQUE (id, user_id),
  CONSTRAINT fk_popup_queue_sessions_user
    FOREIGN KEY (user_id) REFERENCES auth.users(id) ON DELETE RESTRICT,
  CONSTRAINT ck_popup_queue_sessions_trigger
    CHECK (trigger_type ~ '^[A-Z][A-Z0-9_]*$'),
  CONSTRAINT ck_popup_queue_sessions_page_key CHECK (btrim(surface_page_key) <> ''),
  CONSTRAINT ck_popup_queue_sessions_device
    CHECK (device_class IN ('PC', 'MOBILE')),
  CONSTRAINT ck_popup_queue_sessions_counts
    CHECK (max_items > 0 AND issued_count >= 0 AND issued_count <= max_items),
  CONSTRAINT ck_popup_queue_sessions_expiry CHECK (expires_at > created_at),
  CONSTRAINT ck_popup_queue_sessions_termination
    CHECK (
      (terminated_reason IS NULL AND terminated_at IS NULL)
      OR (
        terminated_reason IS NOT NULL
        AND terminated_at IS NOT NULL
        AND terminated_reason ~ '^[A-Z][A-Z0-9_]*$'
      )
    )
);

CREATE TABLE content.popup_deliveries (
  id UUID CONSTRAINT pk_popup_deliveries PRIMARY KEY DEFAULT gen_random_uuid(),
  queue_session_id UUID NOT NULL,
  campaign_id UUID NOT NULL,
  user_id UUID NOT NULL,
  revision_id UUID NOT NULL,
  token_hash CHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ISSUED',
  page_key VARCHAR(64) NOT NULL,
  device_class VARCHAR(16) NOT NULL,
  issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  shown_at TIMESTAMPTZ,
  closed_at TIMESTAMPTZ,
  close_reason VARCHAR(64),
  clicked_at TIMESTAMPTZ,
  invalidated_at TIMESTAMPTZ,
  CONSTRAINT ux_popup_deliveries_token_hash UNIQUE (token_hash),
  CONSTRAINT ux_popup_deliveries_queue_campaign UNIQUE (queue_session_id, campaign_id),
  CONSTRAINT ux_popup_deliveries_id_campaign_user UNIQUE (id, campaign_id, user_id),
  CONSTRAINT fk_popup_deliveries_campaign
    FOREIGN KEY (campaign_id) REFERENCES content.popup_campaigns(id) ON DELETE RESTRICT,
  CONSTRAINT fk_popup_deliveries_user
    FOREIGN KEY (user_id) REFERENCES auth.users(id) ON DELETE RESTRICT,
  CONSTRAINT fk_popup_deliveries_revision
    FOREIGN KEY (revision_id) REFERENCES content.content_revisions(id) ON DELETE RESTRICT,
  CONSTRAINT fk_popup_deliveries_queue_user
    FOREIGN KEY (queue_session_id, user_id)
    REFERENCES content.popup_queue_sessions(id, user_id)
    ON DELETE RESTRICT,
  CONSTRAINT ck_popup_deliveries_token_hash
    CHECK (token_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_popup_deliveries_status
    CHECK (status IN ('ISSUED', 'SHOWN', 'CLOSED', 'CLICKED', 'INVALIDATED', 'EXPIRED')),
  CONSTRAINT ck_popup_deliveries_page_key CHECK (btrim(page_key) <> ''),
  CONSTRAINT ck_popup_deliveries_device
    CHECK (device_class IN ('PC', 'MOBILE')),
  CONSTRAINT ck_popup_deliveries_expiry CHECK (expires_at > issued_at),
  CONSTRAINT ck_popup_deliveries_close
    CHECK (
      (closed_at IS NULL AND close_reason IS NULL)
      OR (
        closed_at IS NOT NULL
        AND close_reason IS NOT NULL
        AND close_reason ~ '^[A-Z][A-Z0-9_]*$'
      )
    )
);

ALTER TABLE content.popup_campaign_user_states
  ADD CONSTRAINT fk_popup_campaign_user_states_active_delivery_owner
  FOREIGN KEY (active_delivery_id, campaign_id, user_id)
  REFERENCES content.popup_deliveries(id, campaign_id, user_id)
  ON DELETE RESTRICT;

CREATE TABLE content.message_publications (
  id UUID CONSTRAINT pk_message_publications PRIMARY KEY DEFAULT gen_random_uuid(),
  content_item_id UUID NOT NULL,
  source_type VARCHAR(16) NOT NULL,
  source_campaign_id UUID,
  audience_type VARCHAR(32) NOT NULL,
  lifecycle_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  category VARCHAR(64) NOT NULL,
  scheduled_at TIMESTAMPTZ,
  sent_at TIMESTAMPTZ,
  audience_cutoff_at TIMESTAMPTZ,
  created_by UUID NOT NULL,
  updated_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at TIMESTAMPTZ,
  CONSTRAINT ux_message_publications_source_campaign UNIQUE (source_campaign_id),
  CONSTRAINT fk_message_publications_content_item
    FOREIGN KEY (content_item_id) REFERENCES content.content_items(id) ON DELETE RESTRICT,
  CONSTRAINT fk_message_publications_campaign_content
    FOREIGN KEY (source_campaign_id, content_item_id)
    REFERENCES content.popup_campaigns(id, content_item_id)
    ON DELETE RESTRICT,
  CONSTRAINT fk_message_publications_created_by
    FOREIGN KEY (created_by) REFERENCES auth.users(id) ON DELETE RESTRICT,
  CONSTRAINT fk_message_publications_updated_by
    FOREIGN KEY (updated_by) REFERENCES auth.users(id) ON DELETE RESTRICT,
  CONSTRAINT ck_message_publications_source
    CHECK (
      (source_type = 'MANUAL' AND source_campaign_id IS NULL)
      OR (source_type = 'CAMPAIGN' AND source_campaign_id IS NOT NULL)
    ),
  CONSTRAINT ck_message_publications_audience
    CHECK (audience_type IN ('ALL', 'SELECTED')),
  CONSTRAINT ck_message_publications_lifecycle
    CHECK (lifecycle_status IN ('DRAFT', 'SCHEDULED', 'SENT', 'DELETED')),
  CONSTRAINT ck_message_publications_category CHECK (btrim(category) <> ''),
  CONSTRAINT ck_message_publications_timing
    CHECK (
      (lifecycle_status <> 'SCHEDULED' OR (scheduled_at IS NOT NULL AND sent_at IS NULL))
      AND (lifecycle_status <> 'SENT' OR (sent_at IS NOT NULL AND audience_cutoff_at IS NOT NULL))
      AND (lifecycle_status <> 'DRAFT' OR sent_at IS NULL)
    ),
  CONSTRAINT ck_message_publications_deleted
    CHECK ((lifecycle_status = 'DELETED') = (deleted_at IS NOT NULL))
);

CREATE TABLE content.message_targets (
  publication_id UUID NOT NULL,
  user_id UUID NOT NULL,
  CONSTRAINT pk_message_targets PRIMARY KEY (publication_id, user_id),
  CONSTRAINT fk_message_targets_publication
    FOREIGN KEY (publication_id) REFERENCES content.message_publications(id) ON DELETE RESTRICT,
  CONSTRAINT fk_message_targets_user
    FOREIGN KEY (user_id) REFERENCES auth.users(id) ON DELETE RESTRICT
);

CREATE TABLE content.message_receipts (
  publication_id UUID NOT NULL,
  user_id UUID NOT NULL,
  delivered_at TIMESTAMPTZ NOT NULL,
  read_at TIMESTAMPTZ,
  read_source VARCHAR(32),
  hidden_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT pk_message_receipts PRIMARY KEY (publication_id, user_id),
  CONSTRAINT fk_message_receipts_publication
    FOREIGN KEY (publication_id) REFERENCES content.message_publications(id) ON DELETE RESTRICT,
  CONSTRAINT fk_message_receipts_user
    FOREIGN KEY (user_id) REFERENCES auth.users(id) ON DELETE RESTRICT,
  CONSTRAINT ck_message_receipts_read
    CHECK (
      (read_at IS NULL AND read_source IS NULL)
      OR (
        read_at IS NOT NULL
        AND read_source IS NOT NULL
        AND read_source IN ('USER', 'POPUP', 'READ_ALL')
      )
    )
);

CREATE TABLE content.engagement_outbox (
  id UUID CONSTRAINT pk_engagement_outbox PRIMARY KEY DEFAULT gen_random_uuid(),
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id UUID NOT NULL,
  event_type VARCHAR(128) NOT NULL,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at TIMESTAMPTZ,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  CONSTRAINT ck_engagement_outbox_aggregate_type
    CHECK (aggregate_type ~ '^[A-Z][A-Z0-9_]*$'),
  CONSTRAINT ck_engagement_outbox_event_type
    CHECK (event_type ~ '^[A-Z][A-Z0-9_]*$'),
  CONSTRAINT ck_engagement_outbox_payload
    CHECK (jsonb_typeof(payload) = 'object'),
  CONSTRAINT ck_engagement_outbox_attempt_count CHECK (attempt_count >= 0)
);

CREATE FUNCTION content.reject_content_revision_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'content revisions are immutable'
    USING ERRCODE = 'object_not_in_prerequisite_state';
  RETURN OLD;
END
$$;

CREATE TRIGGER trg_content_revisions_immutable
BEFORE UPDATE OR DELETE ON content.content_revisions
FOR EACH ROW
EXECUTE FUNCTION content.reject_content_revision_mutation();

CREATE FUNCTION content.enforce_popup_delivery_revision_owner()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  PERFORM 1
  FROM content.popup_campaigns campaign
  JOIN content.content_revisions revision
    ON revision.content_item_id = campaign.content_item_id
  WHERE campaign.id = NEW.campaign_id
    AND revision.id = NEW.revision_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'popup delivery revision must belong to its campaign content item'
      USING ERRCODE = 'foreign_key_violation';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_popup_deliveries_revision_owner
BEFORE INSERT OR UPDATE OF campaign_id, revision_id ON content.popup_deliveries
FOR EACH ROW
EXECUTE FUNCTION content.enforce_popup_delivery_revision_owner();

CREATE FUNCTION content.reject_popup_campaign_content_rebind()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.content_item_id IS DISTINCT FROM OLD.content_item_id THEN
    RAISE EXCEPTION 'popup campaign content item is immutable'
      USING ERRCODE = 'object_not_in_prerequisite_state';
  END IF;

  RETURN NEW;
END
$$;

CREATE TRIGGER trg_popup_campaigns_content_item_immutable
BEFORE UPDATE OF content_item_id ON content.popup_campaigns
FOR EACH ROW
EXECUTE FUNCTION content.reject_popup_campaign_content_rebind();

CREATE INDEX idx_auth_users_business_created
  ON auth.users(created_at, id)
  WHERE role = 'USER';

CREATE INDEX idx_content_assets_status_created
  ON content.content_assets(status, created_at DESC);

CREATE INDEX idx_popup_campaigns_claim_order
  ON content.popup_campaigns(priority DESC, first_published_at ASC, id ASC)
  WHERE lifecycle_status = 'ACTIVE' AND deleted_at IS NULL;

CREATE INDEX idx_popup_campaigns_schedule
  ON content.popup_campaigns(start_at, id)
  WHERE lifecycle_status = 'SCHEDULED' AND deleted_at IS NULL;

CREATE INDEX idx_popup_campaigns_end
  ON content.popup_campaigns(end_at, id)
  WHERE lifecycle_status IN ('ACTIVE', 'PAUSED') AND deleted_at IS NULL;

CREATE INDEX idx_popup_campaign_targets_user
  ON content.popup_campaign_targets(user_id, campaign_id);

CREATE INDEX idx_popup_campaign_user_states_user
  ON content.popup_campaign_user_states(user_id, campaign_id);

CREATE INDEX idx_popup_campaign_user_states_active_expiry
  ON content.popup_campaign_user_states(active_delivery_expires_at, user_id, campaign_id)
  WHERE active_delivery_id IS NOT NULL;

CREATE INDEX idx_popup_queue_sessions_user_created
  ON content.popup_queue_sessions(user_id, created_at DESC);

CREATE INDEX idx_popup_queue_sessions_expiry
  ON content.popup_queue_sessions(expires_at, id)
  WHERE terminated_at IS NULL;

CREATE UNIQUE INDEX ux_popup_deliveries_user_active
  ON content.popup_deliveries(user_id)
  WHERE status IN ('ISSUED', 'SHOWN') AND invalidated_at IS NULL;

CREATE INDEX idx_popup_deliveries_user_issued
  ON content.popup_deliveries(user_id, issued_at DESC);

CREATE INDEX idx_popup_deliveries_campaign_issued
  ON content.popup_deliveries(campaign_id, issued_at DESC);

CREATE INDEX idx_popup_deliveries_retention
  ON content.popup_deliveries(issued_at, id);

CREATE INDEX idx_message_publications_content_item
  ON content.message_publications(content_item_id);

CREATE INDEX idx_message_publications_list
  ON content.message_publications(sent_at DESC, id DESC)
  WHERE lifecycle_status = 'SENT' AND deleted_at IS NULL;

CREATE INDEX idx_message_publications_schedule
  ON content.message_publications(scheduled_at, id)
  WHERE lifecycle_status = 'SCHEDULED' AND deleted_at IS NULL;

CREATE INDEX idx_message_targets_user
  ON content.message_targets(user_id, publication_id);

CREATE INDEX idx_message_receipts_user_delivered
  ON content.message_receipts(user_id, delivered_at DESC, publication_id);

CREATE INDEX idx_message_receipts_user_unread
  ON content.message_receipts(user_id, delivered_at DESC, publication_id)
  WHERE read_at IS NULL AND hidden_at IS NULL;

CREATE INDEX idx_engagement_outbox_unpublished
  ON content.engagement_outbox(created_at, id)
  WHERE published_at IS NULL;

DO $$
DECLARE
  invalid_message_id UUID;
BEGIN
  SELECT message.id
    INTO invalid_message_id
  FROM content.messages message
  WHERE upper(btrim(message.status)) NOT IN ('DRAFT', 'PUBLISHED')
  ORDER BY message.id
  LIMIT 1;

  IF invalid_message_id IS NOT NULL THEN
    RAISE EXCEPTION 'V63 legacy message % has unsupported status', invalid_message_id
      USING ERRCODE = 'check_violation';
  END IF;

  SELECT message.id
    INTO invalid_message_id
  FROM content.messages message
  LEFT JOIN auth.users actor ON actor.id = message.sent_by
  WHERE actor.id IS NULL
  ORDER BY message.id
  LIMIT 1;

  IF invalid_message_id IS NOT NULL THEN
    RAISE EXCEPTION 'V63 legacy message % has an orphan sent_by', invalid_message_id
      USING ERRCODE = 'foreign_key_violation';
  END IF;

  SELECT message.id
    INTO invalid_message_id
  FROM content.messages message
  LEFT JOIN auth.users target ON target.id = message.target_user_id
  WHERE message.target_user_id IS NOT NULL
    AND (target.id IS NULL OR target.role <> 'USER')
  ORDER BY message.id
  LIMIT 1;

  IF invalid_message_id IS NOT NULL THEN
    RAISE EXCEPTION 'V63 legacy message % has an invalid business-user target', invalid_message_id
      USING ERRCODE = 'foreign_key_violation';
  END IF;
END
$$;

INSERT INTO content.content_items (
  id, current_revision_id, content_kind, created_at, updated_at
)
SELECT message.id, NULL, 'MESSAGE', message.created_at, message.updated_at
FROM content.messages message
ON CONFLICT (id) DO NOTHING;

INSERT INTO content.content_revisions (
  id,
  content_item_id,
  revision_no,
  title,
  body_document,
  sanitized_html,
  cover_asset_id,
  cta_label,
  cta_route_key,
  cta_params,
  created_by,
  created_at
)
SELECT
  message.id,
  message.id,
  1,
  message.title,
  jsonb_build_object(
    'type', 'doc',
    'content', jsonb_build_array(
      CASE
        WHEN message.body = '' THEN jsonb_build_object('type', 'paragraph')
        ELSE jsonb_build_object(
          'type', 'paragraph',
          'content', jsonb_build_array(
            jsonb_build_object('type', 'text', 'text', message.body)
          )
        )
      END
    )
  ),
  '<p>'
    || replace(
         replace(
         replace(
           replace(
             replace(
               replace(
                 replace(
                   replace(
                     replace(message.body, '&', '&amp;'),
                     '<', '&lt;'),
                   '>', '&gt;'),
                 '"', '&quot;'),
               '''', '&#39;'),
             '=', '&#61;'),
             E'\r\n', '<br>'),
           E'\r', '<br>'),
         E'\n', '<br>')
    || '</p>',
  NULL,
  NULL,
  NULL,
  NULL,
  message.sent_by,
  message.updated_at
FROM content.messages message
ON CONFLICT (id) DO NOTHING;

UPDATE content.content_items item
SET current_revision_id = message.id
FROM content.messages message
WHERE item.id = message.id
  AND item.content_kind = 'MESSAGE';

INSERT INTO content.message_publications (
  id,
  content_item_id,
  source_type,
  source_campaign_id,
  audience_type,
  lifecycle_status,
  category,
  scheduled_at,
  sent_at,
  audience_cutoff_at,
  created_by,
  updated_by,
  created_at,
  updated_at,
  deleted_at
)
SELECT
  message.id,
  message.id,
  'MANUAL',
  NULL,
  CASE WHEN message.target_user_id IS NULL THEN 'ALL' ELSE 'SELECTED' END,
  CASE upper(btrim(message.status)) WHEN 'PUBLISHED' THEN 'SENT' ELSE 'DRAFT' END,
  message.message_type,
  NULL,
  CASE
    WHEN upper(btrim(message.status)) = 'PUBLISHED'
      THEN coalesce(message.published_at, message.created_at)
    ELSE NULL
  END,
  CASE
    WHEN upper(btrim(message.status)) = 'PUBLISHED'
      OR message.target_user_id IS NULL
      THEN coalesce(message.published_at, message.created_at)
    ELSE NULL
  END,
  message.sent_by,
  message.sent_by,
  message.created_at,
  message.updated_at,
  NULL
FROM content.messages message
ON CONFLICT (id) DO NOTHING;

INSERT INTO content.message_targets (publication_id, user_id)
SELECT message.id, message.target_user_id
FROM content.messages message
WHERE message.target_user_id IS NOT NULL
ON CONFLICT (publication_id, user_id) DO NOTHING;

DO $$
DECLARE
  invalid_message_id UUID;
BEGIN
  WITH expected_messages AS (
    SELECT
      message.*,
      CASE WHEN message.target_user_id IS NULL THEN 'ALL' ELSE 'SELECTED' END
        AS expected_audience_type,
      CASE upper(btrim(message.status)) WHEN 'PUBLISHED' THEN 'SENT' ELSE 'DRAFT' END
        AS expected_lifecycle_status,
      CASE
        WHEN upper(btrim(message.status)) = 'PUBLISHED'
          THEN coalesce(message.published_at, message.created_at)
        ELSE NULL
      END AS expected_sent_at,
      CASE
        WHEN upper(btrim(message.status)) = 'PUBLISHED'
          OR message.target_user_id IS NULL
          THEN coalesce(message.published_at, message.created_at)
        ELSE NULL
      END AS expected_audience_cutoff_at,
      jsonb_build_object(
        'type', 'doc',
        'content', jsonb_build_array(
          CASE
            WHEN message.body = '' THEN jsonb_build_object('type', 'paragraph')
            ELSE jsonb_build_object(
              'type', 'paragraph',
              'content', jsonb_build_array(
                jsonb_build_object('type', 'text', 'text', message.body)
              )
            )
          END
        )
      ) AS expected_body_document,
      '<p>'
        || replace(
             replace(
               replace(
                 replace(
                   replace(
                     replace(
                       replace(
                         replace(
                           replace(message.body, '&', '&amp;'),
                           '<', '&lt;'),
                         '>', '&gt;'),
                       '"', '&quot;'),
                     '''', '&#39;'),
                   '=', '&#61;'),
                 E'\r\n', '<br>'),
               E'\r', '<br>'),
             E'\n', '<br>')
        || '</p>' AS expected_sanitized_html
    FROM content.messages message
  )
  SELECT message.id
    INTO invalid_message_id
  FROM expected_messages message
  LEFT JOIN content.content_items item
    ON item.id = message.id
   AND item.content_kind = 'MESSAGE'
   AND item.current_revision_id = message.id
   AND item.created_at = message.created_at
   AND item.updated_at = message.updated_at
  LEFT JOIN content.content_revisions revision
    ON revision.id = message.id
   AND revision.content_item_id = message.id
   AND revision.revision_no = 1
   AND revision.title = message.title
   AND revision.body_document = message.expected_body_document
   AND revision.sanitized_html = message.expected_sanitized_html
   AND revision.cover_asset_id IS NULL
   AND revision.cta_label IS NULL
   AND revision.cta_route_key IS NULL
   AND revision.cta_params IS NULL
   AND revision.created_by = message.sent_by
   AND revision.created_at = message.updated_at
  LEFT JOIN content.message_publications publication
    ON publication.id = message.id
   AND publication.content_item_id = message.id
   AND publication.source_type = 'MANUAL'
   AND publication.source_campaign_id IS NULL
   AND publication.audience_type = message.expected_audience_type
   AND publication.lifecycle_status = message.expected_lifecycle_status
   AND publication.category = message.message_type
   AND publication.scheduled_at IS NULL
   AND publication.sent_at IS NOT DISTINCT FROM message.expected_sent_at
   AND publication.audience_cutoff_at
     IS NOT DISTINCT FROM message.expected_audience_cutoff_at
   AND publication.created_by = message.sent_by
   AND publication.updated_by = message.sent_by
   AND publication.created_at = message.created_at
   AND publication.updated_at = message.updated_at
   AND publication.deleted_at IS NULL
  WHERE item.id IS NULL
     OR revision.id IS NULL
     OR publication.id IS NULL
  ORDER BY message.id
  LIMIT 1;

  IF invalid_message_id IS NOT NULL THEN
    RAISE EXCEPTION 'V63 legacy message % did not backfill deterministically', invalid_message_id
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;

  SELECT message.id INTO invalid_message_id
  FROM content.messages message
  WHERE (
      SELECT count(*)
      FROM content.message_targets target
      WHERE target.publication_id = message.id
    ) <> CASE WHEN message.target_user_id IS NULL THEN 0 ELSE 1 END
     OR (
       message.target_user_id IS NOT NULL
       AND NOT EXISTS (
         SELECT 1
         FROM content.message_targets expected_target
         WHERE expected_target.publication_id = message.id
           AND expected_target.user_id = message.target_user_id
       )
     )
  ORDER BY message.id
  LIMIT 1;

  IF invalid_message_id IS NOT NULL THEN
    RAISE EXCEPTION 'V63 legacy message % target did not backfill deterministically', invalid_message_id
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
END
$$;
