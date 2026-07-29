CREATE SEQUENCE ledger.entry_sequence AS BIGINT
  START WITH 1
  INCREMENT BY 1
  MINVALUE 1
  NO MAXVALUE
  CACHE 1
  NO CYCLE
  OWNED BY NONE;

ALTER TABLE ledger.ledger_entries
  ADD COLUMN sequence_no BIGINT;

ALTER TABLE ledger.asset_ledger_entries
  ADD COLUMN sequence_no BIGINT;

ALTER TABLE ledger.ledger_entries
  ALTER COLUMN sequence_no
  SET DEFAULT nextval('ledger.entry_sequence'::regclass);

ALTER TABLE ledger.asset_ledger_entries
  ALTER COLUMN sequence_no
  SET DEFAULT nextval('ledger.entry_sequence'::regclass);

WITH ordered AS MATERIALIZED (
  SELECT
    ledger_kind,
    id,
    row_number() OVER (ORDER BY created_at, id, ledger_kind) AS sequence_no
  FROM (
    SELECT 0 AS ledger_kind, id, created_at
    FROM ledger.ledger_entries
    UNION ALL
    SELECT 1 AS ledger_kind, id, created_at
    FROM ledger.asset_ledger_entries
  ) existing
),
cash_update AS (
  UPDATE ledger.ledger_entries entry
  SET sequence_no = ordered.sequence_no
  FROM ordered
  WHERE ordered.ledger_kind = 0
    AND ordered.id = entry.id
  RETURNING entry.id
)
UPDATE ledger.asset_ledger_entries entry
SET sequence_no = ordered.sequence_no
FROM ordered
WHERE ordered.ledger_kind = 1
  AND ordered.id = entry.id;

SELECT setval(
  'ledger.entry_sequence'::regclass,
  COALESCE((
    SELECT max(sequence_no)
    FROM (
      SELECT sequence_no FROM ledger.ledger_entries
      UNION ALL
      SELECT sequence_no FROM ledger.asset_ledger_entries
    ) all_entries
  ), 0) + 1,
  false
);

ALTER TABLE ledger.ledger_entries
  ALTER COLUMN sequence_no SET NOT NULL;

ALTER TABLE ledger.asset_ledger_entries
  ALTER COLUMN sequence_no SET NOT NULL;

CREATE INDEX idx_ledger_account_sequence
  ON ledger.ledger_entries(account_id, sequence_no DESC);

CREATE INDEX idx_asset_ledger_account_sequence
  ON ledger.asset_ledger_entries(account_id, sequence_no DESC);
