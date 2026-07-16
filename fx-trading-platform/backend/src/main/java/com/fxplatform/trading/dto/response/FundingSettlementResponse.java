package com.fxplatform.trading.dto.response;

import com.fxplatform.trading.entity.FundingSettlementEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.PositionSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable authenticated funding history projection. */
public record FundingSettlementResponse(
    UUID id,
    UUID positionId,
    UUID accountId,
    String symbol,
    Instant fundingTime,
    BigDecimal fundingRate,
    BigDecimal amount,
    String asset,
    UUID ledgerEntryId,
    PositionSide positionSide,
    MarginMode marginMode,
    BigDecimal markPrice,
    String source,
    BigDecimal balanceAfter,
    BigDecimal isolatedMarginAfter,
    BigDecimal shortfall,
    Instant createdAt
) {

  public static FundingSettlementResponse from(FundingSettlementEntity entity) {
    return new FundingSettlementResponse(
        entity.getId(),
        entity.getPositionId(),
        entity.getAccountId(),
        entity.getSymbol(),
        entity.getFundingTime(),
        entity.getFundingRate(),
        entity.getAmount(),
        entity.getAsset(),
        entity.getLedgerEntryId(),
        entity.getPositionSide(),
        entity.getMarginMode(),
        entity.getMarkPrice(),
        entity.getSource(),
        entity.getBalanceAfter(),
        entity.getIsolatedMarginAfter(),
        entity.getShortfall(),
        entity.getCreatedAt());
  }
}
