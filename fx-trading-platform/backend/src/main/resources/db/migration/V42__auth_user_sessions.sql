CREATE TABLE IF NOT EXISTS auth.user_sessions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth.users(id),
  refresh_token_hash VARCHAR(64) NOT NULL,
  access_token_jti VARCHAR(64) NOT NULL,
  device_id VARCHAR(128),
  ip_address VARCHAR(64),
  user_agent TEXT,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  revoke_reason VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_user_sessions_user_status
  ON auth.user_sessions(user_id, status);

CREATE UNIQUE INDEX IF NOT EXISTS idx_user_sessions_refresh_token_hash
  ON auth.user_sessions(refresh_token_hash);

CREATE INDEX IF NOT EXISTS idx_user_sessions_expires_at
  ON auth.user_sessions(expires_at);
