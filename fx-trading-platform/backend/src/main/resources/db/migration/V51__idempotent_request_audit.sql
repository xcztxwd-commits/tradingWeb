WITH ranked AS (
  SELECT id,
         ROW_NUMBER() OVER (
           PARTITION BY action, target_type, target_id, request_id
           ORDER BY created_at, id
         ) AS duplicate_rank
  FROM audit.audit_logs
  WHERE request_id IS NOT NULL
)
DELETE FROM audit.audit_logs log
USING ranked
WHERE log.id = ranked.id
  AND ranked.duplicate_rank > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_audit_logs_request_action_target
  ON audit.audit_logs(action, target_type, target_id, request_id)
  WHERE request_id IS NOT NULL;
