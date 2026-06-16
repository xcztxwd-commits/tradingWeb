package com.fxplatform.trading.service;

import com.fxplatform.account.repository.TradingAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "trading.fx-financing", name = "enabled", havingValue = "true")
public class ForexFinancingScheduler {

  private final TradingAccountRepository accountRepository;
  private final ForexFinancingService forexFinancingService;

  @Scheduled(fixedDelayString = "${trading.fx-financing.scan-ms:86400000}")
  @Transactional
  public void settleAllAccounts() {
    accountRepository.findAll()
        .forEach(account -> forexFinancingService.settleDailyFinancing(account.getId()));
  }
}
