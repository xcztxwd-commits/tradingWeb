package com.fxplatform.tradinglab.repository;

import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.tradinglab.entity.TradingLabReportChunkEntity;
import com.fxplatform.tradinglab.report.TradingLabReportChunkSequenceState;
import com.fxplatform.tradinglab.report.TradingLabReportChunkTotals;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultType;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.ResultSetType;

public interface TradingLabReportChunkRepository
    extends FxBaseMapper<TradingLabReportChunkEntity> {

  @Insert("""
      INSERT INTO trading_lab.report_chunks (
        id,
        report_id,
        section,
        sequence,
        encoding,
        uncompressed_bytes,
        compressed_bytes,
        payload,
        checksum
      ) VALUES (
        #{id},
        #{reportId},
        #{section},
        #{sequence},
        #{encoding},
        #{uncompressedBytes},
        #{compressedBytes},
        #{payload},
        #{checksum}
      )
      ON CONFLICT (report_id, section, sequence) DO NOTHING
      """)
  @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
  int insertIfAbsent(
      @Param("id") UUID id,
      @Param("reportId") UUID reportId,
      @Param("section") String section,
      @Param("sequence") long sequence,
      @Param("encoding") String encoding,
      @Param("uncompressedBytes") long uncompressedBytes,
      @Param("compressedBytes") long compressedBytes,
      @Param("payload") byte[] payload,
      @Param("checksum") String checksum);

  @Select("""
      SELECT id,
             report_id,
             section,
             sequence,
             encoding,
             uncompressed_bytes,
             compressed_bytes,
             payload,
             checksum,
             created_at
      FROM trading_lab.report_chunks
      WHERE report_id = #{reportId}
        AND section = #{section}
        AND sequence = #{sequence}
      """)
  @Options(useCache = false)
  Optional<TradingLabReportChunkEntity> findByKey(
      @Param("reportId") UUID reportId,
      @Param("section") String section,
      @Param("sequence") long sequence);

  @Select("""
      SELECT coalesce(max(sequence) + 1, 0)
      FROM trading_lab.report_chunks
      WHERE report_id = #{reportId}
        AND section = #{section}
      """)
  @Options(useCache = false)
  long findNextSequence(
      @Param("reportId") UUID reportId,
      @Param("section") String section);

  @Select("""
      SELECT count(*) AS chunk_count,
             min(sequence) AS min_sequence,
             max(sequence) AS max_sequence
      FROM trading_lab.report_chunks
      WHERE report_id = #{reportId}
        AND section = #{section}
      """)
  @Options(useCache = false)
  TradingLabReportChunkSequenceState sequenceState(
      @Param("reportId") UUID reportId,
      @Param("section") String section);

  @Select("""
      SELECT coalesce(sum(uncompressed_bytes), 0) AS uncompressed_bytes,
             coalesce(sum(compressed_bytes), 0) AS compressed_bytes,
             count(*) AS chunk_count
      FROM trading_lab.report_chunks
      WHERE report_id = #{reportId}
      """)
  @Options(useCache = false)
  TradingLabReportChunkTotals totals(@Param("reportId") UUID reportId);

  @Delete("""
      DELETE FROM trading_lab.report_chunks
      WHERE report_id = #{reportId}
      """)
  @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
  int deleteByReportId(@Param("reportId") UUID reportId);

  @Select("""
      SELECT id,
             report_id,
             section,
             sequence,
             encoding,
             uncompressed_bytes,
             compressed_bytes,
             payload,
             checksum,
             created_at
      FROM trading_lab.report_chunks
      WHERE report_id = #{reportId}
        AND section = #{section}
      ORDER BY sequence
      """)
  @ResultType(TradingLabReportChunkEntity.class)
  @Options(
      fetchSize = 1,
      resultSetType = ResultSetType.FORWARD_ONLY,
      useCache = false)
  Cursor<TradingLabReportChunkEntity> streamBySection(
      @Param("reportId") UUID reportId,
      @Param("section") String section);
}
