package com.fxplatform.tradinglab.repository;

import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.tradinglab.entity.TradingLabScenarioEntity;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface TradingLabScenarioRepository extends FxBaseMapper<TradingLabScenarioEntity> {

  @Select("""
      SELECT id, name, description, status, negative_mode, seed, model_version,
             CAST(scenario_json AS text) AS scenario_json,
             CAST(config_snapshot_json AS text) AS config_snapshot_json,
             config_snapshot_hash, symbol_config_version, code_version,
             created_by, updated_by, created_at, updated_at, version
      FROM trading_lab.scenarios
      WHERE id = #{scenarioId}
      FOR UPDATE
      """)
  @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
  Optional<TradingLabScenarioEntity> findByIdForUpdate(@Param("scenarioId") UUID scenarioId);

  @Update("""
      UPDATE trading_lab.scenarios
      SET name = #{scenario.name},
          description = #{scenario.description},
          negative_mode = #{scenario.negativeMode},
          seed = #{scenario.seed},
          model_version = #{scenario.modelVersion},
          scenario_json = CAST(#{scenario.scenarioJson} AS jsonb),
          config_snapshot_json = CAST(#{scenario.configSnapshotJson} AS jsonb),
          config_snapshot_hash = #{scenario.configSnapshotHash},
          symbol_config_version = #{scenario.symbolConfigVersion},
          code_version = #{scenario.codeVersion},
          updated_by = #{scenario.updatedBy},
          updated_at = clock_timestamp(),
          version = version + 1
      WHERE id = #{scenario.id}
        AND status = 'DRAFT'
        AND version = #{expectedVersion}
      """)
  int updateDraft(
      @Param("scenario") TradingLabScenarioEntity scenario,
      @Param("expectedVersion") long expectedVersion);

  @Update("""
      UPDATE trading_lab.scenarios
      SET status = 'FROZEN',
          updated_by = #{actorId},
          updated_at = clock_timestamp(),
          version = version + 1
      WHERE id = #{scenarioId}
        AND status = 'DRAFT'
        AND version = #{expectedVersion}
        AND config_snapshot_hash = #{configSnapshotHash}
      """)
  int freezeDraft(
      @Param("scenarioId") UUID scenarioId,
      @Param("expectedVersion") long expectedVersion,
      @Param("configSnapshotHash") String configSnapshotHash,
      @Param("actorId") UUID actorId);

  @org.apache.ibatis.annotations.Delete("""
      DELETE FROM trading_lab.scenarios
      WHERE id = #{scenarioId}
        AND status = 'DRAFT'
        AND version = #{expectedVersion}
      """)
  int deleteDraft(
      @Param("scenarioId") UUID scenarioId,
      @Param("expectedVersion") long expectedVersion);
}
