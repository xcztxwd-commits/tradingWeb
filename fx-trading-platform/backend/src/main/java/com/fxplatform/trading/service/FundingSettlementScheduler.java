package com.fxplatform.trading.service;

import com.fxplatform.trading.entity.FundingRateEntity;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.FundingRateRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "trading.funding", name = "enabled", havingValue = "true")
public class FundingSettlementScheduler {

  private final FundingRateRepository fundingRateRepository;
  private final PositionRepository positionRepository;
  private final FundingService fundingService;
  private Instant lastScanTime;

  @Scheduled(fixedDelayString = "${trading.funding.scan-ms:60000}")
  @Transactional
  public int settleDueFunding() {
    Instant scanTime = Instant.now();
    int settled = 0;
    for (FundingRateEntity rate : fundingRateRepository.findDueRates(lastScanTime, scanTime)) {
      for (var position : positionRepository.findBySymbolAndStatusOrderByOpenedAtAsc(rate.getSymbol(), PositionStatus.OPEN)) {
        fundingService.settleFundingForPosition(position, rate);
        settled++;
      }
    }
    lastScanTime = scanTime;
    return settled;
  }
}
