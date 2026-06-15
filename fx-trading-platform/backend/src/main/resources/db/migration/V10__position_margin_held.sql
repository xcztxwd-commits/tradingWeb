ALTER TABLE trading.positions
  ADD COLUMN margin_held NUMERIC(24, 8) NOT NULL DEFAULT 0;
