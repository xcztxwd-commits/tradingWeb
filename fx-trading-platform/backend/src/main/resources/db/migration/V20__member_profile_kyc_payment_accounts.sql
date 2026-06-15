CREATE TABLE IF NOT EXISTS auth.user_profiles (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL UNIQUE REFERENCES auth.users(id),
  real_name VARCHAR(120),
  phone VARCHAR(32),
  address TEXT,
  remark TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS auth.kyc_applications (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth.users(id),
  real_name VARCHAR(120) NOT NULL,
  document_type VARCHAR(64) NOT NULL,
  document_no VARCHAR(120) NOT NULL,
  front_image_url TEXT,
  back_image_url TEXT,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  review_reason TEXT,
  reviewed_by UUID REFERENCES auth.users(id),
  reviewed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_kyc_applications_user_time
  ON auth.kyc_applications(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS finance.member_payment_accounts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth.users(id),
  account_type VARCHAR(32) NOT NULL,
  currency VARCHAR(16) NOT NULL DEFAULT 'USD',
  network VARCHAR(64),
  holder_name VARCHAR(120),
  bank_name VARCHAR(120),
  branch_name VARCHAR(120),
  bank_code VARCHAR(64),
  account_no VARCHAR(240) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_member_payment_accounts_user_time
  ON finance.member_payment_accounts(user_id, created_at DESC);
