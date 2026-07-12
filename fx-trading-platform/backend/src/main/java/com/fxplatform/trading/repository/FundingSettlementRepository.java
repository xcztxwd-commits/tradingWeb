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
        ledger_entry_id,
        position_side,
        margin_mode,
        mark_price,
        source,
        balance_after,
        isolated_margin_after,
        shortfall
      ) VALUES (
        #{id},
        #{positionId},
        #{accountId},
        #{symbol},
        #{fundingTime},
        #{fundingRate},
        #{amount},
        #{asset},
        #{ledgerEntryId},
        #{positionSide},
        #{marginMode},
        #{markPrice},
        #{source},
        #{balanceAfter},
        #{isolatedMarginAfter},
        #{shortfall}
      )
      ON CONFLICT (position_id, funding_time) DO NOTHING
      """)
  int insertOnConflictDoNothing(FundingSettlementEntity settlement);
}
