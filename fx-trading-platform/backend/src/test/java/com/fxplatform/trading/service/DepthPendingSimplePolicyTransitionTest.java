package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoBookLevel;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.DemoMatchingMode;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.execution.FullFillRequest;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The pending route may not commit a SIMPLE fill after its policy authority changes to DEPTH. */
@ExtendWith(MockitoExtension.class)
class DepthPendingSimplePolicyTransitionTest {

  @Mock private OrderRepository orderRepository;
  @Mock private TradingAccountRepository accountRepository;
  @Mock private DemoExecutionGuard demoExecutionGuard;
  @Mock private WalletBalanceRepository walletBalanceRepository;
  @Mock private WalletService walletService;
  @Mock private SpotPositionService spotPositionService;
  @Mock private FullFillCoordinator fullFillCoordinator;
  @Mock private OrderFillService orderFillService;
  @Mock private OrderEventService orderEventService;
  @Mock private TradingTransactionExecutor transactionExecutor;
  @Mock private DepthOrderExecutionService depthOrderExecutionService;

  @Test
  void simpleToDepthFlipWhileWaitingForLocksFailsBeforePricingClaimOrFill() {
    DemoExecutionPolicy simple = DemoExecutionPolicy.defaults();
    DemoExecutionPolicy depth = depthPolicy();
    AtomicReference<DemoExecutionPolicy> current = new AtomicReference<>(simple);
    OrderEntity order = pendingLimit();
    TradingAccountEntity account = account(order);
    ExecutableMarketSnapshot snapshot = snapshot();
    PendingOrderExecutionProcessor processor = processor();
    processor.setDepthOrderExecutionService(depthOrderExecutionService);

    when(depthOrderExecutionService.currentPolicy()).thenAnswer(invocation -> current.get());
    when(depthOrderExecutionService.isDepth(any(DemoExecutionPolicy.class)))
        .thenAnswer(invocation ->
            ((DemoExecutionPolicy) invocation.getArgument(0)).matchingMode()
                == DemoMatchingMode.DEPTH);
    doCallRealMethod().when(depthOrderExecutionService)
        .requireCurrentSimplePolicy(any(DemoExecutionPolicy.class));
    when(transactionExecutor.execute(any())).thenAnswer(invocation -> {
      current.set(depth);
      return ((Supplier<?>) invocation.getArgument(0)).get();
    });
    when(accountRepository.findByIdForUpdate(order.getAccountId()))
        .thenReturn(Optional.of(account));
    when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
    lenient().doThrow(new AssertionError("stale SIMPLE policy reached the pricing boundary"))
        .when(fullFillCoordinator).execute(any(FullFillRequest.class), any());

    assertThatThrownBy(() -> processor.process(order, snapshot))
        .isInstanceOfSatisfying(BusinessException.class, failure ->
            assertThat(failure.getCode()).isEqualTo(ErrorCode.MARKET_DATA_STALE));

    verify(fullFillCoordinator, never()).execute(any(FullFillRequest.class), any());
    verify(orderRepository, never()).claimPending(any());
    verify(orderRepository, never()).activateStopLimitPending(any());
    verify(orderRepository, never()).activateStopLimitWorking(any());
    verify(orderRepository, never()).save(any(OrderEntity.class));
    verify(orderFillService, never()).fill(
        any(OrderEntity.class),
        any(OrderEntity.class),
        any(TradingAccountEntity.class),
        any(FullFillResult.class),
        any(BigDecimal.class),
        any(String.class));
    verify(orderEventService, never()).record(any(), any(), any(), any(), any(), any());
  }

  private PendingOrderExecutionProcessor processor() {
    return new PendingOrderExecutionProcessor(
        orderRepository,
        accountRepository,
        demoExecutionGuard,
        walletBalanceRepository,
        walletService,
        spotPositionService,
        fullFillCoordinator,
        orderFillService,
        orderEventService,
        transactionExecutor);
  }

  private static DemoExecutionPolicy depthPolicy() {
    return new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.001"),
        new BigDecimal("0.002"),
        new BigDecimal("0.001"),
        new BigDecimal("0.0001"),
        List.of(new DemoBookLevel(new BigDecimal("99"), BigDecimal.ONE)),
        List.of(new DemoBookLevel(new BigDecimal("100"), BigDecimal.ONE)),
        null);
  }

  private static OrderEntity pendingLimit() {
    UUID accountId = UUID.randomUUID();
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setUserId(accountId);
    order.setAccountId(accountId);
    order.setSymbol("BTCUSDT");
    order.setProductType(ProductType.CRYPTO_SPOT);
    order.setSide(OrderSide.BUY);
    order.setOrderType(OrderType.LIMIT);
    order.setStatus(OrderStatus.PENDING);
    order.setOrderOrigin(OrderOrigin.USER);
    order.setLots(new BigDecimal("0.1000"));
    order.setQuantity(new BigDecimal("0.1000"));
    order.setOriginalQuantity(new BigDecimal("0.1000"));
    order.setBaseQuantity(new BigDecimal("0.1000"));
    order.setRemainingQuantity(new BigDecimal("0.1000"));
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setPrice(new BigDecimal("101"));
    order.setRequestedPrice(new BigDecimal("101"));
    order.setPositionSide(PositionSide.BOTH);
    order.setMarginMode(MarginMode.CASH);
    order.setTimeInForce(TimeInForce.GTC);
    order.setHoldAmount(new BigDecimal("10.12020000"));
    order.setHoldCurrency("USDT");
    order.setCreatedAt(Instant.now().minusSeconds(60));
    return order;
  }

  private static TradingAccountEntity account(OrderEntity order) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(order.getAccountId());
    account.setUserId(order.getUserId());
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    return account;
  }

  private static ExecutableMarketSnapshot snapshot() {
    Instant now = Instant.now();
    return new ExecutableMarketSnapshot(
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        "binance",
        "BTCUSDT",
        MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("99"),
        new BigDecimal("100"),
        new BigDecimal("100"),
        null,
        null,
        now.minusSeconds(1),
        now.plusSeconds(30));
  }
}
