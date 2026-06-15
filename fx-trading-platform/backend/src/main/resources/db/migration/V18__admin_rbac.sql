CREATE TABLE IF NOT EXISTS admin.roles (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  role_name VARCHAR(120) NOT NULL,
  role_code VARCHAR(120) NOT NULL UNIQUE,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order INTEGER NOT NULL DEFAULT 0,
  description TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS admin.menus (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  parent_id UUID REFERENCES admin.menus(id),
  menu_name VARCHAR(120) NOT NULL,
  permission_key VARCHAR(160) NOT NULL UNIQUE,
  path VARCHAR(240),
  component VARCHAR(240),
  menu_type VARCHAR(32) NOT NULL DEFAULT 'MENU',
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS admin.role_menu_permissions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  role_id UUID NOT NULL REFERENCES admin.roles(id),
  menu_id UUID NOT NULL REFERENCES admin.menus(id),
  buttons JSONB NOT NULL DEFAULT '[]'::jsonb,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ux_role_menu_permissions UNIQUE (role_id, menu_id)
);

CREATE TABLE IF NOT EXISTS admin.user_roles (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth.users(id),
  role_id UUID NOT NULL REFERENCES admin.roles(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ux_admin_user_roles UNIQUE (user_id, role_id)
);

CREATE TABLE IF NOT EXISTS admin.departments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  department_name VARCHAR(120) NOT NULL,
  parent_id UUID REFERENCES admin.departments(id),
  leader VARCHAR(120),
  phone VARCHAR(32),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS admin.posts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  post_name VARCHAR(120) NOT NULL,
  post_code VARCHAR(120) NOT NULL UNIQUE,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS admin.role_data_scopes (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  role_id UUID NOT NULL REFERENCES admin.roles(id),
  scope_type VARCHAR(64) NOT NULL,
  department_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ux_role_data_scopes UNIQUE (role_id)
);

CREATE INDEX IF NOT EXISTS idx_admin_menus_parent_sort ON admin.menus(parent_id, sort_order ASC);
CREATE INDEX IF NOT EXISTS idx_admin_roles_sort ON admin.roles(sort_order ASC, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_admin_user_roles_user ON admin.user_roles(user_id);
