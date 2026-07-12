package com.fxplatform.trading.service;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Scans untriggered position-bound protection carriers and delegates canonical close execution. */
@Service
@Slf4j
@ConditionalOnProperty(
    prefix = "trading",
    name = "protective-order-execution-enabled",
    havingValue = "true")
public class ProtectiveOrderExecutionService {

  private final OrderRepository orderRepository;
  private final MarketBundleResolver marketBundleResolver;
  private final ProtectionOrderService protectionOrderService;
  private final SystemCloseOrderService systemCloseOrderService;
  private final TradingAccountRepository accountRepository;
  private final DemoExecutionGuard demoExecutionGuard;

  @Autowired
  public ProtectiveOrderExecutionService(
      OrderRepository orderRepository,
      MarketBundleResolver marketBundleResolver,
      ProtectionOrderService protectionOrderService,
      SystemCloseOrderService systemCloseOrderService,
      TradingAccountRepository accountRepository,
      DemoExecutionGuard demoExecutionGuard
  ) {
    this.orderRepository = orderRepository;
    this.marketBundleResolver = marketBundleResolver;
    this.protectionOrderService = protectionOrderService;
    this.systemCloseOrderService = systemCloseOrderService;
    this.accountRepository = accountRepository;
    this.demoExecutionGuard = demoExecutionGuard;
  }

  @Scheduled(fixedDelayString = "${trading.protective-order-scan-ms:1000}")
  public int executeProtectiveOrders() {
    List<OrderEntity> candidates = orderRepository.findBoundProtectionsByStatus(
        OrderStatus.PENDING_ACTIVATION);
    if (candidates == null || candidates.isEmpty()) {
      return 0;
    }

    int executed = 0;
    for (OrderEntity protection : candidates) {
      if (!isBoundProtection(protection)) {
        continue;
      }
      try {
        requireDemoAccount(protection);
        if (executeCandidateWithRetry(protection)) {
          executed++;
        }
      } catch (BusinessException exception) {
        log.debug(
            "Protection order {} was not executed after business rejection {}",
            protection.getId(),
            exception.getCode());
      } catch (RuntimeException exception) {
        log.warn(
            "Protection order {} failed without aborting later candidates: {}: {}",
            protection.getId(),
            exception.getClass().getSimpleName(),
            exception.getMessage());
      }
    }
    return executed;
  }

  private boolean executeCandidateWithRetry(OrderEntity protection) {
    for (int attempt = 0; attempt < 2; attempt++) {
      ExecutableMarketSnapshot snapshot = resolveSnapshot(protection);
      if (!protectionOrderService.isTriggered(protection, snapshot.mark())) {
        return false;
      }
      try {
        systemCloseOrderService.executeProtection(protection.getId(), snapshot);
        return true;
      } catch (BusinessException exception) {
        if (!ErrorCode.MARKET_DATA_STALE.equals(exception.getCode()) || attempt > 0) {
          throw exception;
        }
      }
    }
    return false;
  }

  private void requireDemoAccount(OrderEntity protection) {
    TradingAccountEntity account = accountRepository.findById(protection.getAccountId())
        .orElseThrow(() -> new BusinessException(
            ErrorCode.ACCOUNT_NOT_FOUND,
            "Protection account not found"));
    demoExecutionGuard.requireDemo(
        account,
        ProductType.LINEAR_PERP,
        protection.getSymbol());
  }

  private boolean isBoundProtection(OrderEntity order) {
    return order != null
        && order.getId() != null
        && order.getAccountId() != null
        && order.getSymbol() != null
        && !order.getSymbol().isBlank()
        && order.getProductType() == ProductType.LINEAR_PERP
        && order.getStatus() == OrderStatus.PENDING_ACTIVATION
        && order.getProtectionType() != null
        && order.getParentPositionId() != null;
  }

  private ExecutableMarketSnapshot resolveSnapshot(OrderEntity protection) {
    Instant to = Instant.now();
    CandleRequest candles = new CandleRequest(
        "1m",
        to.minus(Duration.ofMinutes(30)),
        to);
    ExecutableMarketSnapshot snapshot = ExecutableMarketSnapshot.from(
        marketBundleResolver.resolvePerp(protection.getSymbol(), candles));
    if (snapshot.productType() != ProductType.LINEAR_PERP
        || snapshot.platformSymbol() == null
        || !SymbolNormalizer.normalize(protection.getSymbol()).equals(
            SymbolNormalizer.normalize(snapshot.platformSymbol()))
        || !positive(snapshot.mark())) {
      throw new BusinessException(
          ErrorCode.MARKET_BUNDLE_INCOMPLETE,
          "Protection trigger requires a matching positive Perpetual authority mark");
    }
    return snapshot;
  }

  private boolean positive(BigDecimal value) {
    return value != null && value.compareTo(BigDecimal.ZERO) > 0;
  }
}
