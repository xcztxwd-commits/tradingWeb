package com.fxplatform.tradinglab.repository;

import com.fxplatform.tradinglab.entity.TradingLabReportAppendEntity;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TradingLabReportAppendRepository {

  @Insert("""
      INSERT INTO trading_lab.report_appends (
        report_id,
        section,
        source_sequence,
        canonical_bytes,
        canonical_checksum
      ) VALUES (
        #{reportId},
        #{section},
        #{sourceSequence},
        #{canonicalBytes},
        #{canonicalChecksum}
      )
      ON CONFLICT (report_id, section, source_sequence) DO NOTHING
      """)
  @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
  int insertIfAbsent(
      @Param("reportId") UUID reportId,
      @Param("section") String section,
      @Param("sourceSequence") long sourceSequence,
      @Param("canonicalBytes") long canonicalBytes,
      @Param("canonicalChecksum") String canonicalChecksum);

  @Select("""
      SELECT report_id,
             section,
             source_sequence,
             canonical_bytes,
             canonical_checksum,
             created_at
      FROM trading_lab.report_appends
      WHERE report_id = #{reportId}
        AND section = #{section}
        AND source_sequence = #{sourceSequence}
      """)
  @Options(useCache = false)
  Optional<TradingLabReportAppendEntity> findByKey(
      @Param("reportId") UUID reportId,
      @Param("section") String section,
      @Param("sourceSequence") long sourceSequence);

  @Select("""
      SELECT max(source_sequence)
      FROM trading_lab.report_appends
      WHERE report_id = #{reportId}
        AND section = #{section}
        AND source_sequence >= 0
      """)
  @Options(useCache = false)
  Long findHighestSourceSequence(
      @Param("reportId") UUID reportId,
      @Param("section") String section);

  @Delete("""
      DELETE FROM trading_lab.report_appends
      WHERE report_id = #{reportId}
      """)
  @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
  int deleteByReportId(@Param("reportId") UUID reportId);
}
