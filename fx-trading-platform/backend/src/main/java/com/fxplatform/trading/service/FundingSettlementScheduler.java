package com.fxplatform.trading.service;

import com.fxplatform.trading.entity.FundingRateEntity;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.FundingRateRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@ConditionalOnProperty(
    prefix = "trading.funding",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
public class FundingSettlementScheduler {

  private final FundingRateIngestionService fundingRateIngestionService;
  private final FundingRateRepository fundingRateRepository;
  private final PositionRepository positionRepository;
  private final FundingService fundingService;
  private final Clock clock;

  @Autowired
  public FundingSettlementScheduler(
      FundingRateIngestionService fundingRateIngestionService,
      FundingRateRepository fundingRateRepository,
      PositionRepository positionRepository,
      FundingService fundingService
  ) {
    this(
        fundingRateIngestionService,
        fundingRateRepository,
        positionRepository,
        fundingService,
        Clock.systemUTC());
  }

  FundingSettlementScheduler(
      FundingRateIngestionService fundingRateIngestionService,
      FundingRateRepository fundingRateRepository,
      PositionRepository positionRepository,
      FundingService fundingService,
      Clock clock
  ) {
    this.fundingRateIngestionService = fundingRateIngestionService;
    this.fundingRateRepository = fundingRateRepository;
    this.positionRepository = positionRepository;
    this.fundingService = fundingService;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${trading.funding.scan-ms:60000}")
  public int settleDueFunding() {
    Instant scanTime = clock.instant();
    fundingRateIngestionService.ingestDueRates(scanTime);
    int settled = 0;
    for (FundingRateEntity rate : fundingRateRepository.findDueRates(null, scanTime)) {
      for (var position : positionRepository.findBySymbolAndStatusOrderByOpenedAtAsc(rate.getSymbol(), PositionStatus.OPEN)) {
        try {
          FundingService.FundingSettlementOutcome outcome =
              fundingService.settleFundingForPositionOutcome(position, rate);
          if (outcome.inserted()) {
            settled++;
          }
        } catch (RuntimeException exception) {
          log.warn(
              "Funding settlement failed: rateId={}, positionId={}",
              rate.getId(),
              position.getId(),
              exception);
        }
      }
    }
    return settled;
  }
}
