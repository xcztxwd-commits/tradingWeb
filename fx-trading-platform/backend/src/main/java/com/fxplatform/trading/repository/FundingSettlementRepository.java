package com.fxplatform.trading.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.trading.entity.FundingSettlementEntity;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;

public interface FundingSettlementRepository extends FxBaseMapper<FundingSettlementEntity> {

  default Page<FundingSettlementEntity> findPageByAccountId(
      UUID accountId,
      String symbol,
      Page<FundingSettlementEntity> page
  ) {
    LambdaQueryWrapper<FundingSettlementEntity> query =
        new LambdaQueryWrapper<FundingSettlementEntity>()
            .eq(FundingSettlementEntity::getAccountId, accountId);
    if (symbol != null && !symbol.isBlank()) {
      query.eq(FundingSettlementEntity::getSymbol, symbol);
    }
    return selectPage(page, query
        .orderByDesc(FundingSettlementEntity::getFundingTime)
        .orderByDesc(FundingSettlementEntity::getId));
  }

  default boolean insertIfAbsent(FundingSettlementEntity settlement) {
    return insertOnConflictDoNothing(settlement) == 1;
  }

  default List<FundingSettlementEntity> findByAccountIdOrderByFundingTimeDesc(UUID accountId) {
    return selectList(new LambdaQueryWrapper<FundingSettlementEntity>()
        .eq(FundingSettlementEntity::getAccountId, accountId)
        .orderByDesc(FundingSettlementEntity::getFundingTime)
        .orderByDesc(FundingSettlementEntity::getId));
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
