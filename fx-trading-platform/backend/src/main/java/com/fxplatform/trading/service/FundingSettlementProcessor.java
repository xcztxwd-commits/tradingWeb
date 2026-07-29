package com.fxplatform.trading.service;

import com.fxplatform.trading.entity.FundingRateEntity;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.FundingRateRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Settles only already-persisted due funding rates at an explicit authority time. */
@Service
public class FundingSettlementProcessor {

  private final FundingRateRepository fundingRateRepository;
  private final PositionRepository positionRepository;
  private final FundingService fundingService;

  public FundingSettlementProcessor(
      FundingRateRepository fundingRateRepository,
      PositionRepository positionRepository,
      FundingService fundingService
  ) {
    this.fundingRateRepository = fundingRateRepository;
    this.positionRepository = positionRepository;
    this.fundingService = fundingService;
  }

  public int settlePersistedDueRates(Instant authorityTime) {
    Objects.requireNonNull(authorityTime, "authorityTime");
    int settled = 0;
    for (FundingRateEntity rate : fundingRateRepository.findDueRates(null, authorityTime)) {
      for (var position : positionRepository.findBySymbolAndStatusOrderByOpenedAtAsc(
          rate.getSymbol(), PositionStatus.OPEN)) {
        if (fundingService.settleFundingForPositionOutcome(
            position,
            rate,
            authorityTime).inserted()) {
          settled++;
        }
      }
    }
    return settled;
  }
}
