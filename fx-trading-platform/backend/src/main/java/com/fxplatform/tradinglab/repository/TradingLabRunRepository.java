package com.fxplatform.tradinglab.repository;

import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.tradinglab.entity.TradingLabRunEntity;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface TradingLabRunRepository extends FxBaseMapper<TradingLabRunEntity> {

  String WORKER_SNAPSHOT_COLUMNS = """
      lab_run.id AS run_id,
      lab_run.scenario_id,
      lab_run.report_id,
      lab_report.status AS report_status,
      CASE
        WHEN lab_report.model_version = '[REDACTED]'
          AND lab_report.compressed_bytes = 0
          AND lab_report.chunk_count = 0
          AND lab_report.failure_message =
              'Trading Lab report contains unsafe trace evidence'
          AND (
            (
              lab_report.completed_at IS NULL
              AND lab_report.status IN ('PENDING', 'WRITING')
              AND lab_report.failure_code = 'TRADING_LAB_REPORT_UNSAFE_TRACE'
              AND lab_report.metadata_json = '{}'::jsonb
              AND lab_report.uncompressed_bytes = 0
            )
            OR (
              lab_report.completed_at IS NOT NULL
              AND (
                (
                  lab_report.status = 'FAILED'
                  AND lab_report.failure_code = 'TRADING_LAB_REPORT_UNSAFE_TRACE'
                )
                OR (
                  lab_report.status = 'CANCELLED'
                  AND lab_report.failure_code = 'CANCELLED'
                )
              )
            )
          )
        THEN TRUE
        ELSE FALSE
      END AS report_quarantined,
      lab_run.state,
      lab_run.version,
      lab_run.pause_requested,
      lab_run.cancel_requested,
      lab_run.virtual_started_at,
      lab_run.virtual_current_at,
      lab_run.processed_ticks,
      lab_run.total_ticks,
      lab_run.speed_multiplier,
      CAST(lab_run.scenario_snapshot_json AS text) AS scenario_snapshot_json,
      CAST(lab_run.config_snapshot_json AS text) AS config_snapshot_json,
      CAST(lab_run.local_calculation_json AS text) AS local_calculation_json,
      lab_run.config_snapshot_hash,
      lab_run.model_version,
      lab_run.symbol_config_version,
      lab_run.code_version,
      lab_run.created_by
      """;

  @Select("SELECT " + WORKER_SNAPSHOT_COLUMNS + """
      FROM trading_lab.runs AS lab_run
      INNER JOIN trading_lab.reports AS lab_report
        ON lab_report.id = lab_run.report_id
      WHERE lab_run.id = #{runId}
        AND lab_run.lease_key = 1
        AND lab_run.lease_owner = #{claimOwner}
        AND lab_run.lease_until > clock_timestamp()
      """)
  @Options(useCache = false)
  Optional<TradingLabWorkerRunSnapshot> findLiveWorkerSnapshot(
      @Param("runId") UUID runId,
      @Param("claimOwner") String claimOwner);

  @Select("SELECT " + WORKER_SNAPSHOT_COLUMNS + """
      FROM trading_lab.runs AS lab_run
      INNER JOIN trading_lab.reports AS lab_report
        ON lab_report.id = lab_run.report_id
      WHERE lab_run.id = #{runId}
        AND lab_run.lease_key = 1
        AND lab_run.lease_owner = #{claimOwner}
        AND lab_run.lease_until > clock_timestamp()
      FOR UPDATE OF lab_run, lab_report
      """)
  @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
  Optional<TradingLabWorkerRunSnapshot> lockLiveWorkerSnapshot(
      @Param("runId") UUID runId,
      @Param("claimOwner") String claimOwner);

  @Select("""
      SELECT 1
      FROM trading_lab.runs
      WHERE id = #{runId}
      FOR UPDATE
      """)
  @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
  Optional<Integer> lockForStateTransition(@Param("runId") UUID runId);

  @Update("""
      UPDATE trading_lab.runs
      SET state = #{targetState},
          version = version + 1,
          updated_at = now()
      WHERE id = #{runId}
        AND state = #{expectedState}
        AND version = #{expectedVersion}
        AND lease_key IS NULL
      """)
  int compareAndSetUnleasedState(
      @Param("runId") UUID runId,
      @Param("expectedState") String expectedState,
      @Param("targetState") String targetState,
      @Param("expectedVersion") long expectedVersion);

  @Update("""
      UPDATE trading_lab.runs
      SET state = #{targetState},
          lease_key = CASE
              WHEN #{targetState} IN ('COMPLETED', 'CANCELLED', 'FAILED') THEN NULL
              ELSE lease_key
          END,
          lease_owner = CASE
              WHEN #{targetState} IN ('COMPLETED', 'CANCELLED', 'FAILED') THEN NULL
              ELSE lease_owner
          END,
          lease_until = CASE
              WHEN #{targetState} IN ('COMPLETED', 'CANCELLED', 'FAILED') THEN NULL
              ELSE lease_until
          END,
          finished_at = CASE
              WHEN #{targetState} IN ('COMPLETED', 'CANCELLED', 'FAILED')
                  THEN clock_timestamp()
              ELSE finished_at
          END,
          version = version + 1,
          updated_at = clock_timestamp()
      WHERE id = #{runId}
        AND state = #{expectedState}
        AND version = #{expectedVersion}
        AND lease_key = 1
        AND lease_owner = #{claimOwner}
        AND lease_until > clock_timestamp()
      """)
  int compareAndSetFencedState(
      @Param("runId") UUID runId,
      @Param("expectedState") String expectedState,
      @Param("targetState") String targetState,
      @Param("expectedVersion") long expectedVersion,
      @Param("claimOwner") String claimOwner);

  @Update("""
      UPDATE trading_lab.runs
      SET processed_ticks = LEAST(total_ticks, #{processedTicks}),
          updated_at = clock_timestamp()
      WHERE id = #{runId}
        AND lease_key = 1
        AND lease_owner = #{claimOwner}
        AND lease_until > clock_timestamp()
        AND #{processedTicks} >= 0
        AND processed_ticks < LEAST(total_ticks, #{processedTicks})
      """)
  int advanceFencedProcessedTicks(
      @Param("runId") UUID runId,
      @Param("claimOwner") String claimOwner,
      @Param("processedTicks") long processedTicks);

  @Update("""
      UPDATE trading_lab.runs
      SET pause_requested = #{pauseRequested},
          version = version + 1,
          updated_at = now()
      WHERE id = #{runId}
        AND state = #{expectedState}
        AND version = #{expectedVersion}
      """)
  int compareAndSetPauseRequested(
      @Param("runId") UUID runId,
      @Param("expectedState") String expectedState,
      @Param("expectedVersion") long expectedVersion,
      @Param("pauseRequested") boolean pauseRequested);

  @Update("""
      UPDATE trading_lab.runs
      SET cancel_requested = TRUE,
          version = version + 1,
          updated_at = now()
      WHERE id = #{runId}
        AND state = #{expectedState}
        AND version = #{expectedVersion}
      """)
  int compareAndSetCancelRequested(
      @Param("runId") UUID runId,
      @Param("expectedState") String expectedState,
      @Param("expectedVersion") long expectedVersion);
}
