package com.fxplatform.trading.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.market.entity.DataProviderEntity;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.entity.SymbolProviderBindingEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class FundingPersistenceContractTest {

  @Test
  void symbolArrayConfigurationUsesTheGeneratedMybatisResultMap() {
    TableName table = SymbolEntity.class.getAnnotation(TableName.class);

    assertThat(table).isNotNull();
    assertThat(table.autoResultMap()).isTrue();
    assertThat(DataProviderEntity.class.getAnnotation(TableName.class).autoResultMap()).isTrue();
    assertThat(SymbolProviderBindingEntity.class.getAnnotation(TableName.class).autoResultMap())
        .isTrue();
  }

  @Test
  void dueRateQueryUsesPersistentPositionAndSettlementEligibility() throws Exception {
    Select select = FundingRateRepository.class
        .getMethod("findDueRates", Instant.class, Instant.class)
        .getAnnotation(Select.class);

    assertThat(select).isNotNull();
    String sql = String.join(" ", select.value()).replaceAll("\\s+", " ").toLowerCase();
    assertThat(sql).contains("p.opened_at <= fr.funding_time");
    assertThat(sql).contains("p.product_type = 'linear_perp'");
    assertThat(sql).contains("not exists");
    assertThat(sql).contains("trading.funding_settlements");
  }

  @Test
  void canonicalAndSettlementInsertsCarryEveryV47AuditColumn() throws Exception {
    String rateSql = String.join(" ", FundingRateRepository.class
        .getMethod("insertOnConflictDoNothing", com.fxplatform.trading.entity.FundingRateEntity.class)
        .getAnnotation(Insert.class).value()).toLowerCase();
    String settlementSql = String.join(" ", FundingSettlementRepository.class
        .getMethod(
            "insertOnConflictDoNothing",
            com.fxplatform.trading.entity.FundingSettlementEntity.class)
        .getAnnotation(Insert.class).value()).toLowerCase();

    assertThat(rateSql).contains(
        "provider_code", "source_mode", "as_of", "interval_minutes", "raw_payload_hash");
    assertThat(settlementSql).contains(
        "position_side", "margin_mode", "mark_price", "source", "balance_after",
        "isolated_margin_after", "shortfall");
  }
}
