package com.fxplatform.tradinglab.repository;

import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.tradinglab.entity.TradingLabRunTransitionEntity;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TradingLabRunTransitionRepository
    extends FxBaseMapper<TradingLabRunTransitionEntity> {

  @Select("""
      SELECT id, run_id, from_state, to_state, run_version, reason, idempotency_key,
             real_time, virtual_time, actor_id, details_json
      FROM trading_lab.run_transitions
      WHERE run_id = #{runId}
        AND idempotency_key = #{idempotencyKey}
      """)
  Optional<TradingLabRunTransitionEntity> findByRunIdAndIdempotencyKey(
      @Param("runId") UUID runId,
      @Param("idempotencyKey") String idempotencyKey);
}
