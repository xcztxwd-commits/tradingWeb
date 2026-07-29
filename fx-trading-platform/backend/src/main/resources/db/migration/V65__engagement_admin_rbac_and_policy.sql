DO $$
DECLARE
  reserved_authorities TEXT[] := ARRAY[
    'content:campaign:read',
    'content:campaign:edit',
    'content:campaign:publish',
    'content:campaign:delete',
    'content:campaign:stats',
    'content:campaign:user-detail',
    'content:popup-policy:update',
    'content:message:read',
    'content:message:edit',
    'content:message:send',
    'content:message:delete'
  ];
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM admin.roles role_row
    WHERE role_row.id = '00000000-0000-0000-0000-000000000061'::uuid
      AND role_row.role_code = 'SUPER_ADMIN'
      AND role_row.enabled = TRUE
      AND role_row.system_managed = TRUE
  ) THEN
    RAISE EXCEPTION 'Trusted system-managed SUPER_ADMIN role is required';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM admin.roles role_row
    WHERE regexp_replace(
      role_row.role_code,
      '^[[:cntrl:] ]+|[[:cntrl:] ]+$',
      '',
      'g'
    ) = ANY (reserved_authorities)
  ) OR EXISTS (
    SELECT 1
    FROM admin.menus menu_row
    WHERE (
      menu_row.id IN (
        '00000000-0000-0000-0000-000000000651'::uuid,
        '00000000-0000-0000-0000-000000000652'::uuid
      )
      OR regexp_replace(
        menu_row.permission_key,
        '^[[:cntrl:] ]+|[[:cntrl:] ]+$',
        '',
        'g'
      ) = ANY (reserved_authorities)
    )
      AND NOT (
        (
          menu_row.id = '00000000-0000-0000-0000-000000000651'::uuid
          AND menu_row.parent_id IS NULL
          AND menu_row.menu_name = 'Popup Campaigns'
          AND menu_row.permission_key = 'content:campaign:read'
          AND menu_row.path = '/content/popup-campaigns'
          AND menu_row.component = 'PopupCampaignPage'
          AND menu_row.menu_type = 'MENU'
          AND menu_row.enabled = TRUE
          AND menu_row.sort_order = 70
        ) OR (
          menu_row.id = '00000000-0000-0000-0000-000000000652'::uuid
          AND menu_row.parent_id IS NULL
          AND menu_row.menu_name = 'Messages'
          AND menu_row.permission_key = 'content:message:read'
          AND menu_row.path = '/content/messages'
          AND menu_row.component = 'MessageManagementPage'
          AND menu_row.menu_type = 'MENU'
          AND menu_row.enabled = TRUE
          AND menu_row.sort_order = 71
        )
      )
  ) OR EXISTS (
    SELECT 1
    FROM admin.role_menu_permissions permission_row
    WHERE (
      permission_row.id IN (
        '00000000-0000-0000-0000-000000000653'::uuid,
        '00000000-0000-0000-0000-000000000654'::uuid
      )
      OR (
        permission_row.role_id = '00000000-0000-0000-0000-000000000061'::uuid
        AND permission_row.menu_id IN (
          '00000000-0000-0000-0000-000000000651'::uuid,
          '00000000-0000-0000-0000-000000000652'::uuid
        )
      )
      OR EXISTS (
        SELECT 1
        FROM jsonb_array_elements_text(
          CASE
            WHEN jsonb_typeof(permission_row.buttons) = 'array' THEN permission_row.buttons
            ELSE '[]'::jsonb
          END
        ) AS button(value)
        WHERE regexp_replace(
          button.value,
          '^[[:cntrl:] ]+|[[:cntrl:] ]+$',
          '',
          'g'
        ) = ANY (reserved_authorities)
      )
    )
      AND NOT (
        (
          permission_row.id = '00000000-0000-0000-0000-000000000653'::uuid
          AND permission_row.role_id = '00000000-0000-0000-0000-000000000061'::uuid
          AND permission_row.menu_id = '00000000-0000-0000-0000-000000000651'::uuid
          AND permission_row.buttons = '["content:campaign:edit", "content:campaign:publish", "content:campaign:delete", "content:campaign:stats", "content:campaign:user-detail", "content:popup-policy:update"]'::jsonb
          AND permission_row.enabled = TRUE
        ) OR (
          permission_row.id = '00000000-0000-0000-0000-000000000654'::uuid
          AND permission_row.role_id = '00000000-0000-0000-0000-000000000061'::uuid
          AND permission_row.menu_id = '00000000-0000-0000-0000-000000000652'::uuid
          AND permission_row.buttons = '["content:message:edit", "content:message:send", "content:message:delete"]'::jsonb
          AND permission_row.enabled = TRUE
        )
      )
  ) OR EXISTS (
    SELECT 1
    FROM config.system_settings setting_row
    WHERE (
      setting_row.id = '00000000-0000-0000-0000-000000000655'::uuid
      AND setting_row.setting_key <> 'engagement.popup.maxSequentialPopups'
    ) OR (
      setting_row.id = '00000000-0000-0000-0000-000000000656'::uuid
      AND setting_row.setting_key <> 'engagement.popup.deliveryRetentionDays'
    )
  ) THEN
    RAISE EXCEPTION 'Reserved engagement RBAC authority collision';
  END IF;
END
$$;

INSERT INTO admin.menus (
  id,
  parent_id,
  menu_name,
  permission_key,
  path,
  component,
  menu_type,
  enabled,
  sort_order
) VALUES (
  '00000000-0000-0000-0000-000000000651'::uuid,
  NULL,
  'Popup Campaigns',
  'content:campaign:read',
  '/content/popup-campaigns',
  'PopupCampaignPage',
  'MENU',
  TRUE,
  70
)
ON CONFLICT DO NOTHING;

INSERT INTO admin.menus (
  id,
  parent_id,
  menu_name,
  permission_key,
  path,
  component,
  menu_type,
  enabled,
  sort_order
) VALUES (
  '00000000-0000-0000-0000-000000000652'::uuid,
  NULL,
  'Messages',
  'content:message:read',
  '/content/messages',
  'MessageManagementPage',
  'MENU',
  TRUE,
  71
)
ON CONFLICT DO NOTHING;

INSERT INTO admin.role_menu_permissions (
  id,
  role_id,
  menu_id,
  buttons,
  enabled
) VALUES (
  '00000000-0000-0000-0000-000000000653'::uuid,
  '00000000-0000-0000-0000-000000000061'::uuid,
  '00000000-0000-0000-0000-000000000651'::uuid,
  '["content:campaign:edit", "content:campaign:publish", "content:campaign:delete", "content:campaign:stats", "content:campaign:user-detail", "content:popup-policy:update"]'::jsonb,
  TRUE
)
ON CONFLICT DO NOTHING;

INSERT INTO admin.role_menu_permissions (
  id,
  role_id,
  menu_id,
  buttons,
  enabled
) VALUES (
  '00000000-0000-0000-0000-000000000654'::uuid,
  '00000000-0000-0000-0000-000000000061'::uuid,
  '00000000-0000-0000-0000-000000000652'::uuid,
  '["content:message:edit", "content:message:send", "content:message:delete"]'::jsonb,
  TRUE
)
ON CONFLICT DO NOTHING;

INSERT INTO config.system_settings (
  id,
  setting_key,
  setting_value,
  value_type,
  description,
  editable
) VALUES (
  '00000000-0000-0000-0000-000000000655'::uuid,
  'engagement.popup.maxSequentialPopups',
  '3',
  'INTEGER',
  'Maximum sequential popups in one queue session',
  TRUE
)
ON CONFLICT DO NOTHING;

INSERT INTO config.system_settings (
  id,
  setting_key,
  setting_value,
  value_type,
  description,
  editable
) VALUES (
  '00000000-0000-0000-0000-000000000656'::uuid,
  'engagement.popup.deliveryRetentionDays',
  '365',
  'INTEGER',
  'Raw popup delivery detail retention in days',
  TRUE
)
ON CONFLICT DO NOTHING;
