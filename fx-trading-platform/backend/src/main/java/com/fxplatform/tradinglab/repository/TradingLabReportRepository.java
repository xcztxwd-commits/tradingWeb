package com.fxplatform.tradinglab.repository;

import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.tradinglab.entity.TradingLabReportEntity;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface TradingLabReportRepository extends FxBaseMapper<TradingLabReportEntity> {

  String REPORT_COLUMNS = """
      id,
      scenario_id,
      status,
      model_version,
      config_snapshot_hash,
      code_version,
      metadata_json,
      uncompressed_bytes,
      compressed_bytes,
      chunk_count,
      retained_until,
      permanent,
      failure_code,
      failure_message,
      created_by,
      created_at,
      completed_at,
      version
      """;

  @Select("SELECT " + REPORT_COLUMNS + """
      FROM trading_lab.reports
      WHERE id = #{reportId}
      """)
  @Options(useCache = false)
  Optional<TradingLabReportEntity> findById(@Param("reportId") UUID reportId);

  @Select("SELECT " + REPORT_COLUMNS + """
      FROM trading_lab.reports
      WHERE id = #{reportId}
      FOR UPDATE
      """)
  @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
  Optional<TradingLabReportEntity> lockById(@Param("reportId") UUID reportId);

  @Select("SELECT " + REPORT_COLUMNS + """
      FROM trading_lab.reports
      WHERE id = #{reportId}
      FOR SHARE
      """)
  @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
  Optional<TradingLabReportEntity> lockForStream(@Param("reportId") UUID reportId);

  @Select("""
      SELECT report.metadata_json::text = CAST(#{metadataJson} AS jsonb)::text
      FROM trading_lab.reports AS report
      WHERE report.id = #{reportId}
      """)
  boolean metadataMatches(
      @Param("reportId") UUID reportId,
      @Param("metadataJson") String metadataJson);

  @Update("""
      UPDATE trading_lab.reports AS report
      SET metadata_json = CAST(#{metadataJson} AS jsonb),
          version = report.version + 1
      WHERE report.id = #{reportId}
        AND report.version = #{expectedVersion}
        AND report.metadata_json = '{}'::jsonb
        AND report.completed_at IS NULL
        AND report.status IN ('PENDING', 'WRITING')
        AND EXISTS (
          SELECT 1
          FROM trading_lab.runs run
          WHERE run.id = #{runId}
            AND run.report_id = report.id
            AND run.lease_key = 1
            AND run.lease_owner = #{claimOwner}
            AND run.lease_until > clock_timestamp()
        )
      """)
  int initializeMetadataFenced(
      @Param("reportId") UUID reportId,
      @Param("metadataJson") String metadataJson,
      @Param("expectedVersion") long expectedVersion,
      @Param("runId") UUID runId,
      @Param("claimOwner") String claimOwner);

  @Update("""
      UPDATE trading_lab.reports AS report
      SET status = CASE WHEN report.status = 'PENDING' THEN 'WRITING' ELSE report.status END,
          uncompressed_bytes = report.uncompressed_bytes + #{uncompressedBytes},
          compressed_bytes = report.compressed_bytes + #{compressedBytes},
          chunk_count = report.chunk_count + #{chunkCount},
          version = report.version + 1
      WHERE report.id = #{reportId}
        AND report.completed_at IS NULL
        AND report.status IN ('PENDING', 'WRITING')
      """)
  int addChunkTotals(
      @Param("reportId") UUID reportId,
      @Param("uncompressedBytes") long uncompressedBytes,
      @Param("compressedBytes") long compressedBytes,
      @Param("chunkCount") int chunkCount);

  @Update("""
      UPDATE trading_lab.reports AS report
      SET status = CASE WHEN report.status = 'PENDING' THEN 'WRITING' ELSE report.status END,
          uncompressed_bytes = report.uncompressed_bytes + #{uncompressedBytes},
          compressed_bytes = report.compressed_bytes + #{compressedBytes},
          chunk_count = report.chunk_count + #{chunkCount},
          version = report.version + 1
      WHERE report.id = #{reportId}
        AND report.completed_at IS NULL
        AND report.status IN ('PENDING', 'WRITING')
        AND EXISTS (
          SELECT 1
          FROM trading_lab.runs run
          WHERE run.id = #{runId}
            AND run.report_id = report.id
            AND run.lease_key = 1
            AND run.lease_owner = #{claimOwner}
            AND run.lease_until > clock_timestamp()
        )
      """)
  int addChunkTotalsFenced(
      @Param("reportId") UUID reportId,
      @Param("uncompressedBytes") long uncompressedBytes,
      @Param("compressedBytes") long compressedBytes,
      @Param("chunkCount") int chunkCount,
      @Param("runId") UUID runId,
      @Param("claimOwner") String claimOwner);

  @Update("""
      UPDATE trading_lab.reports AS report
      SET status = 'WRITING',
          model_version = '[REDACTED]',
          metadata_json = '{}'::jsonb,
          uncompressed_bytes = 0,
          compressed_bytes = 0,
          chunk_count = 0,
          failure_code = 'TRADING_LAB_REPORT_UNSAFE_TRACE',
          failure_message = 'Trading Lab report contains unsafe trace evidence',
          version = report.version + 1
      WHERE report.id = #{reportId}
        AND report.completed_at IS NULL
        AND report.status IN ('PENDING', 'WRITING')
      """)
  int quarantineEvidence(@Param("reportId") UUID reportId);

  @Update("""
      UPDATE trading_lab.reports AS report
      SET status = 'WRITING',
          model_version = '[REDACTED]',
          metadata_json = '{}'::jsonb,
          uncompressed_bytes = 0,
          compressed_bytes = 0,
          chunk_count = 0,
          failure_code = 'TRADING_LAB_REPORT_UNSAFE_TRACE',
          failure_message = 'Trading Lab report contains unsafe trace evidence',
          version = report.version + 1
      WHERE report.id = #{reportId}
        AND report.completed_at IS NULL
        AND report.status IN ('PENDING', 'WRITING')
        AND EXISTS (
          SELECT 1
          FROM trading_lab.runs run
          WHERE run.id = #{runId}
            AND run.report_id = report.id
            AND run.lease_key = 1
            AND run.lease_owner = #{claimOwner}
            AND run.lease_until > clock_timestamp()
        )
      """)
  int quarantineEvidenceFenced(
      @Param("reportId") UUID reportId,
      @Param("runId") UUID runId,
      @Param("claimOwner") String claimOwner);

  @Update("""
      WITH closed AS MATERIALIZED (
        SELECT clock_timestamp() AS closed_at
      )
      UPDATE trading_lab.reports AS report
      SET status = #{status},
          failure_code = #{failureCode},
          failure_message = #{failureMessage},
          completed_at = closed.closed_at,
          uncompressed_bytes = #{exactUncompressedBytes},
          compressed_bytes = #{compressedBytes},
          chunk_count = #{chunkCount},
          retained_until = closed.closed_at
              + (#{retentionMillis} * interval '1 millisecond'),
          version = report.version + 1
      FROM closed
      WHERE report.id = #{reportId}
        AND report.version = #{expectedVersion}
        AND report.completed_at IS NULL
        AND report.status IN ('PENDING', 'WRITING')
      """)
  int finalizeReport(
      @Param("reportId") UUID reportId,
      @Param("expectedVersion") long expectedVersion,
      @Param("status") String status,
      @Param("failureCode") String failureCode,
      @Param("failureMessage") String failureMessage,
      @Param("exactUncompressedBytes") long exactUncompressedBytes,
      @Param("compressedBytes") long compressedBytes,
      @Param("chunkCount") int chunkCount,
      @Param("retentionMillis") long retentionMillis);

  @Update("""
      WITH closed AS MATERIALIZED (
        SELECT clock_timestamp() AS closed_at
      )
      UPDATE trading_lab.reports AS report
      SET status = #{status},
          failure_code = #{failureCode},
          failure_message = #{failureMessage},
          completed_at = closed.closed_at,
          uncompressed_bytes = #{exactUncompressedBytes},
          compressed_bytes = #{compressedBytes},
          chunk_count = #{chunkCount},
          retained_until = closed.closed_at
              + (#{retentionMillis} * interval '1 millisecond'),
          version = report.version + 1
      FROM closed
      WHERE report.id = #{reportId}
        AND report.version = #{expectedVersion}
        AND report.completed_at IS NULL
        AND report.status IN ('PENDING', 'WRITING')
        AND EXISTS (
          SELECT 1
          FROM trading_lab.runs run
          WHERE run.id = #{runId}
            AND run.report_id = report.id
            AND run.lease_key = 1
            AND run.lease_owner = #{claimOwner}
            AND run.lease_until > clock_timestamp()
        )
      """)
  int finalizeReportFenced(
      @Param("reportId") UUID reportId,
      @Param("expectedVersion") long expectedVersion,
      @Param("status") String status,
      @Param("failureCode") String failureCode,
      @Param("failureMessage") String failureMessage,
      @Param("exactUncompressedBytes") long exactUncompressedBytes,
      @Param("compressedBytes") long compressedBytes,
      @Param("chunkCount") int chunkCount,
      @Param("retentionMillis") long retentionMillis,
      @Param("runId") UUID runId,
      @Param("claimOwner") String claimOwner);

  @Update("""
      UPDATE trading_lab.reports
      SET permanent = #{permanent},
          version = version + 1
      WHERE id = #{reportId}
        AND version = #{expectedVersion}
        AND completed_at IS NOT NULL
        AND status IN ('COMPLETED', 'FAILED', 'CANCELLED')
      """)
  int setPermanent(
      @Param("reportId") UUID reportId,
      @Param("expectedVersion") long expectedVersion,
      @Param("permanent") boolean permanent);

  @Delete("""
      DELETE FROM trading_lab.reports
      WHERE id = #{reportId}
        AND version = #{expectedVersion}
        AND completed_at IS NOT NULL
        AND status IN ('COMPLETED', 'FAILED', 'CANCELLED')
      """)
  int deleteTerminalByIdAndVersion(
      @Param("reportId") UUID reportId,
      @Param("expectedVersion") long expectedVersion);

  @Delete("""
      DELETE FROM trading_lab.reports
      WHERE id IN (
        SELECT report.id
        FROM trading_lab.reports report
        WHERE report.completed_at IS NOT NULL
          AND report.status IN ('COMPLETED', 'FAILED', 'CANCELLED')
          AND report.permanent = false
          AND report.retained_until < clock_timestamp()
          AND NOT EXISTS (
            SELECT 1
            FROM trading_lab.runs run
            WHERE run.report_id = report.id
              AND run.state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
          )
        ORDER BY report.retained_until, report.id
        LIMIT #{batchSize}
        FOR UPDATE SKIP LOCKED
      )
      """)
  int deleteExpiredTerminalReports(@Param("batchSize") int batchSize);
}
