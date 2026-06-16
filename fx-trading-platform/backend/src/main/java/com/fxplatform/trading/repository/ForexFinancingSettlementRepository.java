package com.fxplatform.trading.repository;

import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.trading.entity.ForexFinancingSettlementEntity;
import org.apache.ibatis.annotations.Insert;

public interface ForexFinancingSettlementRepository extends FxBaseMapper<ForexFinancingSettlementEntity> {

  default boolean insertIfAbsent(ForexFinancingSettlementEntity settlement) {
    return insertOnConflictDoNothing(settlement) == 1;
  }

  @Insert("""
      INSERT INTO trading.fx_financing_settlements (
        id,
        position_id,
        account_id,
        symbol,
        settlement_date,
        days_charged,
        rate,
        amount,
        asset,
        ledger_entry_id
      ) VALUES (
        #{id},
        #{positionId},
        #{accountId},
        #{symbol},
        #{settlementDate},
        #{daysCharged},
        #{rate},
        #{amount},
        #{asset},
        #{ledgerEntryId}
      )
      ON CONFLICT (position_id, settlement_date) DO NOTHING
      """)
  int insertOnConflictDoNothing(ForexFinancingSettlementEntity settlement);
}
