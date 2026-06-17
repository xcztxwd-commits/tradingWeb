CREATE TABLE IF NOT EXISTS auth.revoked_tokens (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  token_hash VARCHAR(64) NOT NULL UNIQUE,
  token_type VARCHAR(16) NOT NULL,
  expires_at TIMESTAMPTZ,
  revoked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_revoked_tokens_expires_at
  ON auth.revoked_tokens(expires_at);
