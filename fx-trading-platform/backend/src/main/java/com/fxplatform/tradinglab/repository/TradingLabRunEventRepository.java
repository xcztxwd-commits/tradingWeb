package com.fxplatform.tradinglab.repository;

import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.tradinglab.entity.TradingLabRunEventEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TradingLabRunEventRepository extends FxBaseMapper<TradingLabRunEventEntity> {

  String EVENT_COLUMNS = """
      id,
      run_id,
      sequence,
      event_type,
      virtual_time,
      real_time,
      correlation_id,
      CAST(payload_json AS text) AS payload_json,
      created_at
      """;

  @Select("SELECT " + EVENT_COLUMNS + """
      FROM trading_lab.run_events
      WHERE id = #{eventId}
      """)
  @Options(useCache = false)
  Optional<TradingLabRunEventEntity> findEvidenceById(@Param("eventId") UUID eventId);

  @Select("SELECT " + EVENT_COLUMNS + """
      FROM trading_lab.run_events
      WHERE run_id = #{runId}
        AND event_type = 'VALIDATION_HTTP_RESULT'
        AND payload_json ->> 'sourceKey' = #{sourceKey}
      ORDER BY
        CASE
          WHEN (payload_json #>> '{evidence,status}')::integer BETWEEN 200 AND 299
            AND payload_json #>> '{evidence,exception}' IS NULL
          THEN 0
          ELSE 1
        END,
        sequence DESC
      LIMIT 1
      """)
  @Options(useCache = false)
  Optional<TradingLabRunEventEntity> findPreferredHttpResult(
      @Param("runId") UUID runId,
      @Param("sourceKey") String sourceKey);

  @Select("SELECT " + EVENT_COLUMNS + """
      FROM trading_lab.run_events
      WHERE run_id = #{runId}
        AND sequence > #{afterSequence}
      ORDER BY sequence
      LIMIT #{limit}
      """)
  @Options(useCache = false)
  List<TradingLabRunEventEntity> findJournalAfter(
      @Param("runId") UUID runId,
      @Param("afterSequence") long afterSequence,
      @Param("limit") int limit);

  /**
   * Returns only journal rows still missing from at least one report projection.
   *
   * <p>Dedicated report sections are sparse, so their append cursor can remain {@code -1} for a
   * successful run. Filtering in PostgreSQL prevents that empty cursor from forcing every
   * coordinator turn to deserialize and replay the full global journal.
   */
  @Select("SELECT " + EVENT_COLUMNS + """
      FROM trading_lab.run_events
      WHERE run_id = #{runId}
        AND (
          sequence > #{lifecycleCursor}
          OR (
            event_type = 'VALIDATION_HTTP_RESULT'
            AND sequence > #{apiTraceCursor}
          )
          OR (
            event_type = 'VALIDATION_EVENT'
            AND (
              (
                payload_json #>> '{evidence,type}' = 'MARKET_TICK'
                AND sequence > #{marketTicksCursor}
              )
              OR (
                payload_json #>> '{evidence,type}' IN ('STATE_SNAPSHOT', 'CHECKPOINT')
                AND sequence > #{checkpointsCursor}
              )
              OR (
                payload_json #>> '{evidence,type}' = 'API_TRACE'
                AND payload_json #>> '{evidence,payload,traceScope}'
                  IN ('HTTP_HOP', 'COMMAND')
                AND jsonb_typeof(payload_json #> '{evidence,payload,trace}') = 'object'
                AND sequence > #{apiTraceCursor}
              )
              OR (
                sequence > #{errorsCursor}
                AND (
                  (
                    payload_json #>> '{evidence,type}' = 'RUN_STATE_CHANGED'
                    AND payload_json #>> '{evidence,payload,state}' = 'FAILED'
                  )
                  OR payload_json #>> '{evidence,type}' = 'RUN_EXECUTION_FAILED'
                  OR (
                    payload_json #>> '{evidence,type}' = 'API_TRACE'
                    AND payload_json #>> '{evidence,payload,outcome}' = 'FAILED'
                  )
                )
              )
            )
          )
        )
      ORDER BY sequence
      LIMIT #{limit}
      """)
  @Options(useCache = false)
  List<TradingLabRunEventEntity> findReportProjectionCandidates(
      @Param("runId") UUID runId,
      @Param("lifecycleCursor") long lifecycleCursor,
      @Param("apiTraceCursor") long apiTraceCursor,
      @Param("marketTicksCursor") long marketTicksCursor,
      @Param("checkpointsCursor") long checkpointsCursor,
      @Param("errorsCursor") long errorsCursor,
      @Param("limit") int limit);

  @Select("SELECT " + EVENT_COLUMNS + """
      FROM trading_lab.run_events
      WHERE run_id = #{runId}
        AND event_type = 'VALIDATION_EVENT'
        AND payload_json #>> '{evidence,type}' IN ('STATE_SNAPSHOT', 'CHECKPOINT')
      ORDER BY sequence DESC
      LIMIT 1
      """)
  @Options(useCache = false)
  Optional<TradingLabRunEventEntity> findLatestStateBearingValidationEvent(
      @Param("runId") UUID runId);

  /**
   * Admin SSE read path. This intentionally has no worker lease predicate: committed journal rows
   * remain replayable after the worker releases its lease and after a JVM restart.
   */
  @Select("SELECT " + EVENT_COLUMNS + """
      FROM trading_lab.run_events
      WHERE run_id = #{runId}
        AND sequence > #{afterSequence}
      ORDER BY sequence
      LIMIT #{limit}
      """)
  @Options(useCache = false)
  List<TradingLabRunEventEntity> findAdminSseAfter(
      @Param("runId") UUID runId,
      @Param("afterSequence") long afterSequence,
      @Param("limit") int limit);

  /**
   * Minimal Admin existence/terminal probe. Returning only state prevents the SSE boundary from
   * loading worker lease ownership or the frozen internal run snapshot.
   */
  @Select("""
      SELECT state
      FROM trading_lab.runs
      WHERE id = #{runId}
      """)
  @Options(useCache = false)
  Optional<String> findAdminSseRunState(@Param("runId") UUID runId);

  @Select("""
      SELECT max(sequence)
      FROM trading_lab.run_events
      WHERE run_id = #{runId}
      """)
  @Options(useCache = false)
  Long findHighestEvidenceSequence(@Param("runId") UUID runId);

  @Select("""
      SELECT max((payload_json ->> 'validationSequence')::bigint)
      FROM trading_lab.run_events
      WHERE run_id = #{runId}
        AND event_type = 'VALIDATION_EVENT'
      """)
  @Options(useCache = false)
  Long findHighestValidationSequence(@Param("runId") UUID runId);

  @Select("""
      SELECT max((payload_json ->> 'highWatermark')::bigint)
      FROM trading_lab.run_events
      WHERE run_id = #{runId}
        AND event_type = 'VALIDATION_HIGH_WATERMARK'
        AND (payload_json ->> 'validationGeneration')::bigint = #{generation}
      """)
  @Options(useCache = false)
  Long findHighestValidationHighWatermark(
      @Param("runId") UUID runId,
      @Param("generation") long generation);
}
