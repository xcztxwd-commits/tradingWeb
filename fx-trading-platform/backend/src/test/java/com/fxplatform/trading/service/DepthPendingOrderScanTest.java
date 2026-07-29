package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.model.SpotMarketBundle;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DepthPendingOrderScanTest {

  private static final UUID ACCOUNT_ID =
      UUID.fromString("00000000-0000-0000-0000-0000000000aa");
  private static final UUID USER_ID =
      UUID.fromString("00000000-0000-0000-0000-0000000000bb");
  private static final Instant BASE_TIME = Instant.parse("2026-07-18T00:00:00Z");

  @Test
  void scannerBuildsOneGloballyOrderedCandidateStreamFromAllApprovedSources() {
    OrderRepository orderRepository = mock(OrderRepository.class);
    TradingAccountRepository accountRepository = mock(TradingAccountRepository.class);
    MarketBundleResolver marketBundleResolver = mock(MarketBundleResolver.class);
    FullFillCoordinator fullFillCoordinator = mock(FullFillCoordinator.class);
    PendingOrderExecutionProcessor processor = mock(PendingOrderExecutionProcessor.class);

    UUID activationId = id(1);
    UUID duplicateId = id(2);
    UUID partialId = id(3);
    UUID latePendingId = id(4);
    OrderEntity latePending = pending(latePendingId, BASE_TIME.plusSeconds(2));
    OrderEntity duplicatePending = pending(duplicateId, BASE_TIME.plusSeconds(1));
    OrderEntity partial = pending(partialId, BASE_TIME);
    partial.setStatus(OrderStatus.PARTIALLY_FILLED);
    OrderEntity duplicatePartial = pending(duplicateId, BASE_TIME.plusSeconds(1));
    duplicatePartial.setStatus(OrderStatus.PARTIALLY_FILLED);
    OrderEntity activation = activation(activationId, BASE_TIME.plusSeconds(1));
    OrderEntity duplicateActivation = activation(duplicateId, BASE_TIME.plusSeconds(1));

    when(orderRepository.findByStatus(OrderStatus.PENDING))
        .thenReturn(List.of(latePending, duplicatePending));
    when(orderRepository.findByStatus(OrderStatus.PARTIALLY_FILLED))
        .thenReturn(List.of(partial, duplicatePartial));
    when(orderRepository.findUserStopLimitsAwaitingActivation())
        .thenReturn(List.of(activation, duplicateActivation));
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(ACCOUNT_ID);
    account.setUserId(USER_ID);
    when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any())).thenReturn(spotBundle());
    when(processor.process(any(OrderEntity.class), any(ExecutableMarketSnapshot.class)))
        .thenReturn(false);

    PendingOrderExecutionService service = new PendingOrderExecutionService(
        orderRepository,
        accountRepository,
        mock(QuoteService.class),
        mock(RiskCheckService.class),
        mock(OrderFillService.class),
        mock(OrderEventService.class),
        mock(DemoExecutionGuard.class),
        mock(WalletBalanceRepository.class),
        mock(WalletService.class),
        mock(SpotPositionService.class),
        mock(PositionRepository.class),
        mock(TradingTransactionExecutor.class),
        marketBundleResolver,
        fullFillCoordinator);
    service.setPendingOrderExecutionProcessor(processor);

    assertThat(service.executePendingOrders()).isZero();

    verify(orderRepository).findByStatus(OrderStatus.PENDING);
    verify(orderRepository).findByStatus(OrderStatus.PARTIALLY_FILLED);
    verify(orderRepository).findUserStopLimitsAwaitingActivation();
    verify(orderRepository, never()).findByStatus(OrderStatus.PENDING_ACTIVATION);
    ArgumentCaptor<OrderEntity> delegated = ArgumentCaptor.forClass(OrderEntity.class);
    verify(processor, times(4)).process(
        delegated.capture(), any(ExecutableMarketSnapshot.class));
    assertThat(delegated.getAllValues())
        .extracting(OrderEntity::getId)
        .containsExactly(partialId, activationId, duplicateId, latePendingId);
  }

  private static OrderEntity pending(UUID id, Instant createdAt) {
    OrderEntity order = new OrderEntity();
    order.setId(id);
    order.setUserId(USER_ID);
    order.setAccountId(ACCOUNT_ID);
    order.setSymbol("BTCUSDT");
    order.setProductType(ProductType.CRYPTO_SPOT);
    order.setSide(OrderSide.BUY);
    order.setOrderType(OrderType.LIMIT);
    order.setStatus(OrderStatus.PENDING);
    order.setLots(new BigDecimal("0.01"));
    order.setQuantity(new BigDecimal("0.01"));
    order.setBaseQuantity(new BigDecimal("0.01"));
    order.setRemainingQuantity(new BigDecimal("0.01"));
    order.setPrice(new BigDecimal("101"));
    order.setRequestedPrice(new BigDecimal("101"));
    order.setClientOrderId("scan-" + id);
    order.setIdempotencyKey("scan-" + id);
    order.setCreatedAt(createdAt);
    return order;
  }

  private static OrderEntity activation(UUID id, Instant createdAt) {
    OrderEntity order = pending(id, createdAt);
    order.setOrderType(OrderType.STOP_LIMIT);
    order.setStatus(OrderStatus.PENDING_ACTIVATION);
    order.setOrderOrigin(OrderOrigin.USER);
    order.setTriggerPrice(new BigDecimal("99.5"));
    order.setTriggerPriceType(TriggerPriceType.LAST_PRICE);
    order.setTriggerExecutionType(TriggerExecutionType.LIMIT);
    return order;
  }

  private static SpotMarketBundle spotBundle() {
    Instant now = Instant.now();
    return new SpotMarketBundle(
        "BTCUSDT",
        "BTCUSDT",
        "binance",
        MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("99"),
        new BigDecimal("100"),
        new BigDecimal("100"),
        null,
        List.of(),
        List.of(),
        now.minusSeconds(1),
        now.plusSeconds(2));
  }

  private static UUID id(int value) {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(value));
  }
}
