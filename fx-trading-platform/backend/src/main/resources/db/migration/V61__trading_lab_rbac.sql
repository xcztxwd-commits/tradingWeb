ALTER TABLE admin.roles
  ADD COLUMN IF NOT EXISTS system_managed BOOLEAN NOT NULL DEFAULT FALSE;

DO $$
DECLARE
  trusted_seed_graph BOOLEAN;
  seed_identity_present BOOLEAN;
BEGIN
  SELECT
    EXISTS (
      SELECT 1
      FROM admin.roles role_row
      WHERE role_row.id = '00000000-0000-0000-0000-000000000061'::uuid
        AND role_row.role_name = 'Super Admin'
        AND role_row.role_code = 'SUPER_ADMIN'
        AND role_row.enabled = TRUE
        AND role_row.sort_order = 0
        AND role_row.description = 'Full Trading Path Lab environment control'
        AND role_row.system_managed = TRUE
    )
    AND EXISTS (
      SELECT 1
      FROM admin.menus menu_row
      WHERE menu_row.id = '00000000-0000-0000-0000-000000000062'::uuid
        AND menu_row.parent_id IS NULL
        AND menu_row.menu_name = 'Trading Path Lab'
        AND menu_row.permission_key = 'TRADING_LAB_VIEW'
        AND menu_row.path = '/trading/lab'
        AND menu_row.component = 'TradingLabPage'
        AND menu_row.menu_type = 'MENU'
        AND menu_row.enabled = TRUE
        AND menu_row.sort_order = 90
    )
    AND EXISTS (
      SELECT 1
      FROM admin.role_menu_permissions permission_row
      WHERE permission_row.id = '00000000-0000-0000-0000-000000000063'::uuid
        AND permission_row.role_id = '00000000-0000-0000-0000-000000000061'::uuid
        AND permission_row.menu_id = '00000000-0000-0000-0000-000000000062'::uuid
        AND permission_row.buttons = '["TRADING_LAB_EXECUTE", "SUPER_ADMIN"]'::jsonb
        AND permission_row.enabled = TRUE
    )
  INTO trusted_seed_graph;

  SELECT
    EXISTS (
      SELECT 1 FROM admin.roles
      WHERE id = '00000000-0000-0000-0000-000000000061'::uuid
    )
    OR EXISTS (
      SELECT 1 FROM admin.menus
      WHERE id = '00000000-0000-0000-0000-000000000062'::uuid
    )
    OR EXISTS (
      SELECT 1 FROM admin.role_menu_permissions
      WHERE id = '00000000-0000-0000-0000-000000000063'::uuid
         OR (
           role_id = '00000000-0000-0000-0000-000000000061'::uuid
           AND menu_id = '00000000-0000-0000-0000-000000000062'::uuid
         )
    )
  INTO seed_identity_present;

  IF seed_identity_present AND NOT trusted_seed_graph THEN
    RAISE EXCEPTION 'Reserved Trading Lab RBAC authority collision';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM admin.roles role_row
    WHERE regexp_replace(
      role_row.role_code,
      '^[[:cntrl:] ]+|[[:cntrl:] ]+$',
      '',
      'g'
    ) IN ('TRADING_LAB_VIEW', 'TRADING_LAB_EXECUTE', 'SUPER_ADMIN')
      AND NOT (
        trusted_seed_graph
        AND role_row.id = '00000000-0000-0000-0000-000000000061'::uuid
      )
  ) OR EXISTS (
    SELECT 1
    FROM admin.menus menu_row
    WHERE regexp_replace(
      menu_row.permission_key,
      '^[[:cntrl:] ]+|[[:cntrl:] ]+$',
      '',
      'g'
    ) IN ('TRADING_LAB_VIEW', 'TRADING_LAB_EXECUTE', 'SUPER_ADMIN')
      AND NOT (
        trusted_seed_graph
        AND menu_row.id = '00000000-0000-0000-0000-000000000062'::uuid
      )
  ) OR EXISTS (
    SELECT 1
    FROM admin.role_menu_permissions permission_row
    CROSS JOIN LATERAL jsonb_array_elements_text(
      CASE
        WHEN jsonb_typeof(permission_row.buttons) = 'array' THEN permission_row.buttons
        ELSE '[]'::jsonb
      END
    ) reserved_authority(value)
    WHERE regexp_replace(
      reserved_authority.value,
      '^[[:cntrl:] ]+|[[:cntrl:] ]+$',
      '',
      'g'
    ) IN ('TRADING_LAB_VIEW', 'TRADING_LAB_EXECUTE', 'SUPER_ADMIN')
      AND NOT (
        trusted_seed_graph
        AND permission_row.id = '00000000-0000-0000-0000-000000000063'::uuid
      )
  ) THEN
    RAISE EXCEPTION 'Reserved Trading Lab RBAC authority collision';
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
  sort_order,
  created_at,
  updated_at
) VALUES (
  '00000000-0000-0000-0000-000000000062'::uuid,
  NULL,
  'Trading Path Lab',
  'TRADING_LAB_VIEW',
  '/trading/lab',
  'TradingLabPage',
  'MENU',
  TRUE,
  90,
  now(),
  now()
)
ON CONFLICT (id) DO UPDATE SET
  parent_id = EXCLUDED.parent_id,
  menu_name = EXCLUDED.menu_name,
  permission_key = EXCLUDED.permission_key,
  path = EXCLUDED.path,
  component = EXCLUDED.component,
  menu_type = EXCLUDED.menu_type,
  enabled = TRUE,
  sort_order = EXCLUDED.sort_order,
  updated_at = now();

INSERT INTO admin.roles (
  id,
  role_name,
  role_code,
  enabled,
  system_managed,
  sort_order,
  description,
  created_at,
  updated_at
) VALUES (
  '00000000-0000-0000-0000-000000000061'::uuid,
  'Super Admin',
  'SUPER_ADMIN',
  TRUE,
  TRUE,
  0,
  'Full Trading Path Lab environment control',
  now(),
  now()
)
ON CONFLICT (id) DO UPDATE SET
  role_name = EXCLUDED.role_name,
  role_code = EXCLUDED.role_code,
  enabled = TRUE,
  system_managed = TRUE,
  sort_order = EXCLUDED.sort_order,
  description = EXCLUDED.description,
  updated_at = now();

INSERT INTO admin.role_menu_permissions (
  id,
  role_id,
  menu_id,
  buttons,
  enabled,
  created_at,
  updated_at
)
VALUES (
  '00000000-0000-0000-0000-000000000063'::uuid,
  '00000000-0000-0000-0000-000000000061'::uuid,
  '00000000-0000-0000-0000-000000000062'::uuid,
  '["TRADING_LAB_EXECUTE", "SUPER_ADMIN"]'::jsonb,
  TRUE,
  now(),
  now()
)
ON CONFLICT (id) DO UPDATE SET
  role_id = EXCLUDED.role_id,
  menu_id = EXCLUDED.menu_id,
  buttons = EXCLUDED.buttons,
  enabled = TRUE,
  updated_at = now();
