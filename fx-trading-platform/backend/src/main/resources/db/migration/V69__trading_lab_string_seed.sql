LOCK TABLE trading_lab.scenarios, trading_lab.runs,
  validation_runtime.run_executions
  IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM trading_lab.runs
    WHERE state NOT IN ('CANCELLED', 'FAILED', 'COMPLETED')
      AND (
        jsonb_typeof(scenario_snapshot_json) = 'object'
        AND scenario_snapshot_json ? 'seed'
        AND jsonb_typeof(scenario_snapshot_json -> 'seed') = 'string'
        AND char_length(scenario_snapshot_json ->> 'seed') BETWEEN 1 AND 256
        AND btrim(scenario_snapshot_json ->> 'seed') <> ''
      ) IS NOT TRUE
  ) THEN
    RAISE EXCEPTION USING
      ERRCODE = '23514',
      MESSAGE =
        'Cannot migrate Trading Lab string seed while an incompatible non-terminal run exists';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM validation_runtime.run_executions
    WHERE state NOT IN ('CANCELLED', 'FAILED', 'COMPLETED')
      AND (
        jsonb_typeof(request_json) = 'object'
        AND request_json ? 'seed'
        AND jsonb_typeof(request_json -> 'seed') = 'string'
        AND char_length(request_json ->> 'seed') BETWEEN 1 AND 256
        AND btrim(request_json ->> 'seed') <> ''
      ) IS NOT TRUE
  ) THEN
    RAISE EXCEPTION USING
      ERRCODE = '23514',
      MESSAGE =
        'Cannot migrate Trading Lab string seed while an incompatible non-terminal validation runtime request exists';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM trading_lab.scenarios
    WHERE jsonb_typeof(scenario_json) <> 'object'
      OR (
        scenario_json ? 'seed'
        AND scenario_json -> 'seed' <> 'null'::jsonb
        AND (
          jsonb_typeof(scenario_json -> 'seed') NOT IN ('string', 'number')
          OR char_length(scenario_json ->> 'seed') NOT BETWEEN 1 AND 256
          OR btrim(scenario_json ->> 'seed') = ''
          OR (
            jsonb_typeof(scenario_json -> 'seed') = 'number'
            AND seed IS NOT NULL
            AND scenario_json ->> 'seed' <> seed::text
          )
        )
      )
  ) THEN
    RAISE EXCEPTION USING
      ERRCODE = '23514',
      MESSAGE = 'Cannot migrate an invalid Trading Lab scenario seed';
  END IF;
END
$$;

ALTER TABLE trading_lab.scenarios
  ALTER COLUMN seed TYPE VARCHAR(256)
  USING seed::text;

UPDATE trading_lab.scenarios
SET seed = CASE
  WHEN jsonb_typeof(scenario_json -> 'seed') IN ('string', 'number')
    THEN scenario_json ->> 'seed'
  WHEN seed IS NOT NULL THEN seed
  ELSE '0'
END;

UPDATE trading_lab.scenarios
SET scenario_json = jsonb_set(
  scenario_json,
  '{seed}',
  to_jsonb(seed),
  true
);

ALTER TABLE trading_lab.scenarios
  ALTER COLUMN seed SET NOT NULL;

ALTER TABLE trading_lab.scenarios
  ADD CONSTRAINT ck_trading_lab_scenarios_seed
  CHECK ((
    char_length(seed) BETWEEN 1 AND 256
    AND btrim(seed) <> ''
    AND jsonb_typeof(scenario_json) = 'object'
    AND scenario_json ? 'seed'
    AND jsonb_typeof(scenario_json -> 'seed') = 'string'
    AND scenario_json ->> 'seed' = seed
  ) IS TRUE);

ALTER TABLE validation_runtime.run_executions
  ADD CONSTRAINT ck_validation_runtime_run_string_seed
  CHECK ((
    state IN ('CANCELLED', 'FAILED', 'COMPLETED')
    OR (
      jsonb_typeof(request_json) = 'object'
      AND request_json ? 'seed'
      AND jsonb_typeof(request_json -> 'seed') = 'string'
      AND char_length(request_json ->> 'seed') BETWEEN 1 AND 256
      AND btrim(request_json ->> 'seed') <> ''
    )
  ) IS TRUE);
