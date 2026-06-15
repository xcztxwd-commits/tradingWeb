CREATE TABLE IF NOT EXISTS core.home_counters (
  counter_key VARCHAR(64) PRIMARY KEY,
  counter_value BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO core.home_counters (counter_key, counter_value)
VALUES ('users', 321443508)
ON CONFLICT (counter_key) DO UPDATE
SET counter_value = GREATEST(core.home_counters.counter_value, EXCLUDED.counter_value),
    updated_at = now();

CREATE TABLE IF NOT EXISTS core.home_promo_cards (
  slot VARCHAR(32) PRIMARY KEY,
  front_rank VARCHAR(32) NOT NULL,
  front_label VARCHAR(80) NOT NULL,
  back_title VARCHAR(80) NOT NULL,
  back_value VARCHAR(80) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  display_order INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO core.home_promo_cards (
  slot,
  front_rank,
  front_label,
  back_title,
  back_value,
  display_order
)
VALUES
  ('asset', 'No.1', '客户资产', '资产', '$134,166,872,529', 10),
  ('volume', 'No.1', '交易量', '24H', '$44,301,728,218', 20)
ON CONFLICT (slot) DO NOTHING;
