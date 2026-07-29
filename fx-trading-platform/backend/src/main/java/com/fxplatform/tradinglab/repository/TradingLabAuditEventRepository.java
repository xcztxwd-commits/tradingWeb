package com.fxplatform.tradinglab.repository;

import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.tradinglab.entity.TradingLabAuditEventEntity;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TradingLabAuditEventRepository
    extends FxBaseMapper<TradingLabAuditEventEntity> {

  @Select("""
      SELECT
        id,
        actor_id,
        client_ip,
        request_id,
        scenario_id,
        run_id,
        action,
        result,
        CAST(details_json AS text) AS details_json,
        created_at
      FROM trading_lab.audit_events
      WHERE run_id = #{runId}
        AND action = 'TRADING_LAB_RUN_CREATE'
        AND result = 'SUCCESS'
      ORDER BY created_at, id
      LIMIT 2
      """)
  @Options(useCache = false)
  List<TradingLabAuditEventEntity> findRunCreationSuccessAudits(
      @Param("runId") UUID runId);
}
