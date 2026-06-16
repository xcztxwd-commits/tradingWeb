package com.fxplatform.trading.repository;

import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.trading.entity.FundingSettlementEntity;
import org.apache.ibatis.annotations.Insert;

public interface FundingSettlementRepository extends FxBaseMapper<FundingSettlementEntity> {

  default boolean insertIfAbsent(FundingSettlementEntity settlement) {
    return insertOnConflictDoNothing(settlement) == 1;
  }

  @Insert("""
      INSERT INTO trading.funding_settlements (
        id,
        position_id,
        account_id,
        symbol,
        funding_time,
        funding_rate,
        amount,
        asset,
        ledger_entry_id
      ) VALUES (
        #{id},
        #{positionId},
        #{accountId},
        #{symbol},
        #{fundingTime},
        #{fundingRate},
        #{amount},
        #{asset},
        #{ledgerEntryId}
      )
      ON CONFLICT (position_id, funding_time) DO NOTHING
      """)
  int insertOnConflictDoNothing(FundingSettlementEntity settlement);
}
