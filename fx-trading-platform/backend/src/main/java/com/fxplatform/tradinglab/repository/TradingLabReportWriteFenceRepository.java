package com.fxplatform.tradinglab.repository;

import com.fxplatform.tradinglab.report.TradingLabReportRunFenceRow;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TradingLabReportWriteFenceRepository {

  @Select("""
      SELECT id AS run_id,
             report_id,
             state,
             lease_key,
             lease_owner,
             lease_until,
             coalesce(lease_until > clock_timestamp(), false) AS lease_live
      FROM trading_lab.runs
      WHERE report_id = #{reportId}
      """)
  @Options(useCache = false)
  Optional<TradingLabReportRunFenceRow> findRunForReport(@Param("reportId") UUID reportId);

  @Select("""
      SELECT id AS run_id,
             report_id,
             state,
             lease_key,
             lease_owner,
             lease_until,
             coalesce(lease_until > clock_timestamp(), false) AS lease_live
      FROM trading_lab.runs
      WHERE id = #{runId}
        AND report_id = #{reportId}
      FOR UPDATE
      """)
  @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
  Optional<TradingLabReportRunFenceRow> lockFencedRun(
      @Param("runId") UUID runId,
      @Param("reportId") UUID reportId);

  @Select("""
      SELECT id AS run_id,
             report_id,
             state,
             lease_key,
             lease_owner,
             lease_until,
             coalesce(lease_until > clock_timestamp(), false) AS lease_live
      FROM trading_lab.runs
      WHERE report_id = #{reportId}
      FOR UPDATE
      """)
  @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
  Optional<TradingLabReportRunFenceRow> lockRunForReport(@Param("reportId") UUID reportId);

  @Select("""
      SELECT EXISTS (
        SELECT 1
        FROM trading_lab.runs
        WHERE id = #{runId}
          AND report_id = #{reportId}
          AND lease_key = 1
          AND lease_owner = #{claimOwner}
          AND lease_until > clock_timestamp()
      )
      """)
  @Options(useCache = false)
  boolean fenceIsLive(
      @Param("runId") UUID runId,
      @Param("reportId") UUID reportId,
      @Param("claimOwner") String claimOwner);
}
