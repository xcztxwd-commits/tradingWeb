package com.fxplatform.market.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.market.entity.DataProviderEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface DataProviderRepository extends FxBaseMapper<DataProviderEntity> {

  default Optional<DataProviderEntity> findByCode(String code) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<DataProviderEntity>()
        .eq(DataProviderEntity::getCode, code)));
  }

  default List<DataProviderEntity> findByIds(Collection<UUID> providerIds) {
    if (providerIds == null || providerIds.isEmpty()) {
      return List.of();
    }
    return selectList(new LambdaQueryWrapper<DataProviderEntity>()
        .in(DataProviderEntity::getId, providerIds));
  }

  @Update("""
      UPDATE market.data_providers
      SET health_status = 'UP',
          last_health_check_at = #{observedAt},
          last_success_at = #{observedAt},
          last_quote_success_at = #{observedAt},
          avg_latency_ms = #{latencyMs},
          quote_staleness_ms = #{quoteStalenessMs},
          updated_at = #{observedAt}
      WHERE id = #{providerId}
      """)
  int updateQuoteSuccessHealth(
      @Param("providerId") UUID providerId,
      @Param("observedAt") Instant observedAt,
      @Param("latencyMs") long latencyMs,
      @Param("quoteStalenessMs") Long quoteStalenessMs);

  @Update("""
      UPDATE market.data_providers
      SET health_status = 'DOWN',
          last_health_check_at = #{observedAt},
          last_failure_at = #{observedAt},
          avg_latency_ms = CASE
            WHEN avg_latency_ms IS NULL THEN #{latencyMs}
            ELSE (
              avg_latency_ms * (COALESCE(failure_count, 0) + 1) + #{latencyMs}
            ) / (COALESCE(failure_count, 0) + 2)
          END,
          failure_count = COALESCE(failure_count, 0) + 1,
          updated_at = #{observedAt}
      WHERE id = #{providerId}
      """)
  int updateFailureHealth(
      @Param("providerId") UUID providerId,
      @Param("observedAt") Instant observedAt,
      @Param("latencyMs") long latencyMs);

  @Update("""
      UPDATE market.data_providers
      SET health_status = 'UP',
          last_health_check_at = #{observedAt},
          last_success_at = #{observedAt},
          last_instrument_sync_at = #{observedAt},
          last_instrument_sync_count = #{syncedCount},
          updated_at = #{observedAt}
      WHERE id = #{providerId}
      """)
  int updateInstrumentSyncSuccessHealth(
      @Param("providerId") UUID providerId,
      @Param("observedAt") Instant observedAt,
      @Param("syncedCount") int syncedCount);
}
