package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutionAdapter;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DepthPartialOrderCancellationTest {

  private static final String SPOT_SYMBOL = "BTCUSDT";
  private static final String PERPETUAL_SYMBOL = "BTCUSDT-PERP";
  private static final BigDecimal FILLED_QUANTITY = new BigDecimal("0.4000");
  private static final BigDecimal AVG_FILL_PRICE = new BigDecimal("100.2500");
  private static final BigDecimal FEE = new BigDecimal("0.02005000");

  private OrderRepository orderRepository;
  @Mock private TradingAccountRepository accountRepository;
  @Mock private RiskCheckService riskCheckService;
  @Mock private ExecutionAdapter executionAdapter;
  @Mock private OrderFillService orderFillService;
  @Mock private LedgerService ledgerService;
  @Mock private WalletService walletService;
  @Mock private OrderEventService orderEventService;
  @Mock private DemoExecutionGuard demoExecutionGuard;
  @Mock private WalletBalanceRepository walletBalanceRepository;
  @Mock private PositionRepository positionRepository;
  @Mock private SpotPositionService spotPositionService;
  @Mock private MarketBundleResolver marketBundleResolver;
  @Mock private FullFillCoordinator fullFillCoordinator;
  @Mock private TradingTransactionExecutor transactionExecutor;
  @Mock private SymbolRepository symbolRepository;
  @Mock private InstrumentRulesEngine instrumentRulesEngine;
  @Mock private AccountSymbolSettingRepository accountSymbolSettingRepository;
  @Mock private PerpetualOrderRiskService perpetualOrderRiskService;
  @Mock private PerpetualAccountRiskSnapshotService perpetualAccountRiskSnapshotService;
  @Mock private ProtectionOrderService protectionOrderService;

  private OrderService service;
  private UserPrincipal principal;
  private UUID userId;
  private UUID accountId;

  @BeforeEach
  void setUp() {
    orderRepository = mock(OrderRepository.class, invocation -> {
      if (invocation.getMethod().getName().equals("cancelPartiallyFilled")) {
        return 1;
      }
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    userId = UUID.randomUUID();
    accountId = UUID.randomUUID();
    principal = new UserPrincipal(userId, "partial-cancel@example.com", "TRADER");
    service = new OrderService(
        orderRepository,
        accountRepository,
        riskCheckService,
        executionAdapter,
        orderFillService,
        ledgerService,
        walletService,
        orderEventService,
        new OrderCommandFactory(),
        new OrderEntityFactory(),
        new OrderResponseMapper(),
        new OrderStatusPolicy(),
        demoExecutionGuard,
        walletBalanceRepository,
        positionRepository,
        spotPositionService,
        marketBundleResolver,
        fullFillCoordinator,
        transactionExecutor,
        symbolRepository,
        instrumentRulesEngine,
        new QuantityConversionService(),
        mock(OrderHoldCalculator.class),
        accountSymbolSettingRepository,
        perpetualOrderRiskService,
        perpetualAccountRiskSnapshotService);
    service.setProtectionOrderService(protectionOrderService);
  }

  @Test
  void spotPartialCancelUsesPartialStatusCasAndReleasesOnlyTheRemainingHold() {
    BigDecimal remainingHold = new BigDecimal("60.03000000");
    OrderEntity order = partialOrder(
        SPOT_SYMBOL,
        ProductType.CRYPTO_SPOT,
        MarginMode.CASH,
        remainingHold);
    TradingAccountEntity account = account(BigDecimal.ZERO, new BigDecimal("50000.00000000"));
    stubOwnedOrder(order, order, account);
    lenient().when(riskCheckService.isSpotSymbol(SPOT_SYMBOL)).thenReturn(true);

    Object outcome = capture(() -> service.cancelOrder(principal, order.getId()));

    assertAll(
        () -> assertThat(outcome).isInstanceOfSatisfying(OrderResponse.class, canceled -> assertAll(
            () -> assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED.name()),
            () -> assertThat(canceled.remainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO),
            () -> assertThat(canceled.holdAmount()).isEqualByComparingTo(BigDecimal.ZERO),
            () -> assertCumulativeFillIsPreserved(canceled, order))),
        () -> assertPartialCancelCasCount(1));
    verify(walletService).releaseLockedWithEntryType(
        eq(accountId),
        eq("USDT"),
        eq(remainingHold),
        eq("ORDER"),
        eq(order.getId()),
        anyString(),
        eq("SPOT_ORDER_RELEASE"));
    verify(orderRepository, never()).cancelPending(any());
    verify(orderRepository, never()).cancelPendingActivation(any());
    verify(orderEventService).record(
        eq(order.getId()),
        eq("ORDER_CANCELED"),
        eq(OrderStatus.PARTIALLY_FILLED),
        eq(OrderStatus.CANCELED),
        eq(null),
        anyString());
  }

  @Test
  void partialCancelThatFindsFilledAfterLockRejectsWithoutReleasingAnything() {
    BigDecimal remainingHold = new BigDecimal("60.03000000");
    OrderEntity snapshot = partialOrder(
        SPOT_SYMBOL,
        ProductType.CRYPTO_SPOT,
        MarginMode.CASH,
        remainingHold);
    OrderEntity locked = partialOrder(
        SPOT_SYMBOL,
        ProductType.CRYPTO_SPOT,
        MarginMode.CASH,
        remainingHold);
    locked.setId(snapshot.getId());
    locked.setStatus(OrderStatus.FILLED);
    locked.setFilledQuantity(BigDecimal.ONE);
    locked.setRemainingQuantity(BigDecimal.ZERO);
    locked.setHoldAmount(BigDecimal.ZERO);
    TradingAccountEntity account = account(BigDecimal.ZERO, new BigDecimal("50000.00000000"));
    stubOwnedOrder(snapshot, locked, account);
    lenient().when(riskCheckService.isSpotSymbol(SPOT_SYMBOL)).thenReturn(true);

    assertThatThrownBy(() -> service.cancelOrder(principal, snapshot.getId()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertAll(
                () -> assertThat(exception.getCode()).isEqualTo("ORDER_NOT_CANCELABLE"),
                () -> assertThat(exception.getMessage()).contains("partially filled")));

    assertAll(
        () -> verify(orderRepository).findByIdForUpdate(snapshot.getId()),
        () -> assertPartialCancelCasCount(0),
        () -> assertThat(locked.getStatus()).isEqualTo(OrderStatus.FILLED),
        () -> assertThat(locked.getFilledQuantity()).isEqualByComparingTo(BigDecimal.ONE),
        () -> assertThat(locked.getRemainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO));
    verify(walletService, never()).releaseLockedWithEntryType(
        any(UUID.class),
        any(String.class),
        any(BigDecimal.class),
        any(String.class),
        any(UUID.class),
        any(String.class),
        any(String.class));
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderRelease(any(), any(), any(), anyString());
    verify(orderEventService, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void crossPerpetualPartialCancelReleasesOnlyH1AndPreservesCumulativeFill() {
    BigDecimal remainingHold = new BigDecimal("6.12000000");
    OrderEntity order = partialOrder(
        PERPETUAL_SYMBOL,
        ProductType.LINEAR_PERP,
        MarginMode.CROSS,
        remainingHold);
    order.setReduceOnly(true);
    TradingAccountEntity account = account(
        new BigDecimal("16.12000000"),
        new BigDecimal("49983.88000000"));
    stubOwnedOrder(order, order, account);

    Object outcome = capture(() -> service.cancelOrder(principal, order.getId()));

    assertAll(
        () -> assertThat(outcome).isInstanceOfSatisfying(OrderResponse.class, canceled -> assertAll(
            () -> assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED.name()),
            () -> assertThat(canceled.remainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO),
            () -> assertThat(canceled.holdAmount()).isEqualByComparingTo(BigDecimal.ZERO),
            () -> assertCumulativeFillIsPreserved(canceled, order))),
        () -> assertThat(account.getUsedMargin()).isEqualByComparingTo("10.00000000"),
        () -> assertThat(account.getFreeMargin()).isEqualByComparingTo("49990.00000000"),
        () -> assertPartialCancelCasCount(1));
    verify(accountRepository).save(account);
    verify(ledgerService).recordOrderRelease(
        eq(account), eq(remainingHold), eq(order.getId()), anyString());
    verify(walletService, never()).releaseLockedWithEntryType(
        any(UUID.class),
        any(String.class),
        any(BigDecimal.class),
        any(String.class),
        any(UUID.class),
        any(String.class),
        any(String.class));
    verify(orderRepository, never()).cancelPending(any());
    verify(orderRepository, never()).cancelPendingActivation(any());
  }

  @Test
  void isolatedInternalPartialCancelReducesUsedMarginWithoutCreditingFreeMargin() {
    BigDecimal remainingHold = new BigDecimal("6.12000000");
    OrderEntity order = partialOrder(
        PERPETUAL_SYMBOL,
        ProductType.LINEAR_PERP,
        MarginMode.ISOLATED,
        remainingHold);
    order.setReduceOnly(true);
    order.setParentPositionId(UUID.randomUUID());
    TradingAccountEntity account = account(
        new BigDecimal("16.12000000"),
        new BigDecimal("49983.88000000"));
    stubOwnedOrder(order, order, account);

    Object outcome = capture(() -> service.cancelOrder(principal, order.getId()));

    assertAll(
        () -> assertThat(outcome).isInstanceOfSatisfying(OrderResponse.class, canceled -> assertAll(
            () -> assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED.name()),
            () -> assertThat(canceled.remainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO),
            () -> assertThat(canceled.holdAmount()).isEqualByComparingTo(BigDecimal.ZERO),
            () -> assertCumulativeFillIsPreserved(canceled, order))),
        () -> assertThat(account.getUsedMargin()).isEqualByComparingTo("10.00000000"),
        () -> assertThat(account.getFreeMargin()).isEqualByComparingTo("49983.88000000"),
        () -> assertPartialCancelCasCount(1));
    verify(accountRepository).save(account);
    verify(ledgerService).recordOrderRelease(
        eq(account), eq(remainingHold), eq(order.getId()), anyString());
    verify(walletService, never()).releaseLockedWithEntryType(
        any(UUID.class),
        any(String.class),
        any(BigDecimal.class),
        any(String.class),
        any(UUID.class),
        any(String.class),
        any(String.class));
  }

  private void stubOwnedOrder(
      OrderEntity snapshot,
      OrderEntity locked,
      TradingAccountEntity account
  ) {
    lenient().when(orderRepository.findByUserIdAndId(userId, snapshot.getId()))
        .thenReturn(Optional.of(snapshot));
    lenient().when(orderRepository.findByIdForUpdate(snapshot.getId()))
        .thenReturn(Optional.of(locked));
    lenient().when(accountRepository.findByIdAndUserId(accountId, userId))
        .thenReturn(Optional.of(account));
    lenient().when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId))
        .thenReturn(Optional.of(account));
    lenient().when(accountSymbolSettingRepository.findByAccountIdAndSymbolForUpdate(
        accountId, snapshot.getSymbol())).thenReturn(Optional.empty());
    lenient().when(positionRepository.findOpenLinearPerpBySymbolForUpdate(
        accountId, snapshot.getSymbol())).thenReturn(List.of());
    lenient().when(orderRepository.findActiveLinearPerpBySymbolForUpdate(
        accountId, snapshot.getSymbol())).thenReturn(List.of());
  }

  private void assertCumulativeFillIsPreserved(OrderResponse response, OrderEntity order) {
    assertAll(
        () -> assertThat(response.filledQuantity()).isEqualByComparingTo(FILLED_QUANTITY),
        () -> assertThat(response.avgFillPrice()).isEqualByComparingTo(AVG_FILL_PRICE),
        () -> assertThat(response.fee()).isEqualByComparingTo(FEE),
        () -> assertThat(response.liquidityRole()).isEqualTo(LiquidityRole.TAKER),
        () -> assertThat(order.getFilledQuantity()).isEqualByComparingTo(FILLED_QUANTITY),
        () -> assertThat(order.getAvgFillPrice()).isEqualByComparingTo(AVG_FILL_PRICE),
        () -> assertThat(order.getFee()).isEqualByComparingTo(FEE),
        () -> assertThat(order.getLiquidityRole()).isEqualTo(LiquidityRole.TAKER));
  }

  private void assertPartialCancelCasCount(long expected) {
    long invocations = mockingDetails(orderRepository).getInvocations().stream()
        .filter(invocation -> invocation.getMethod().getName().equals("cancelPartiallyFilled"))
        .count();
    assertThat(invocations)
        .as("OrderRepository.cancelPartiallyFilled expected-status CAS invocation count")
        .isEqualTo(expected);
  }

  private Object capture(Supplier<?> action) {
    try {
      return action.get();
    } catch (RuntimeException exception) {
      return exception;
    }
  }

  private OrderEntity partialOrder(
      String symbol,
      ProductType productType,
      MarginMode marginMode,
      BigDecimal remainingHold
  ) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setUserId(userId);
    order.setAccountId(accountId);
    order.setSymbol(symbol);
    order.setProductType(productType);
    order.setPositionMode(PositionMode.ONE_WAY);
    order.setPositionSide(PositionSide.BOTH);
    order.setMarginMode(marginMode);
    order.setSide(OrderSide.BUY);
    order.setOrderType(OrderType.LIMIT);
    order.setStatus(OrderStatus.PARTIALLY_FILLED);
    order.setLots(BigDecimal.ONE);
    order.setQuantity(BigDecimal.ONE);
    order.setOriginalQuantity(BigDecimal.ONE);
    order.setBaseQuantity(BigDecimal.ONE);
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setRequestedPrice(new BigDecimal("101.0000"));
    order.setPrice(new BigDecimal("101.0000"));
    order.setExecutionPrice(AVG_FILL_PRICE);
    order.setFilledQuantity(FILLED_QUANTITY);
    order.setRemainingQuantity(new BigDecimal("0.6000"));
    order.setAvgFillPrice(AVG_FILL_PRICE);
    order.setFee(FEE);
    order.setFeeAsset("USDT");
    order.setLiquidityRole(LiquidityRole.TAKER);
    order.setHoldAmount(remainingHold);
    order.setHoldCurrency("USDT");
    order.setTimeInForce(TimeInForce.GTC);
    order.setPostOnly(false);
    order.setReduceOnly(false);
    order.setLeverage(productType == ProductType.LINEAR_PERP ? 10 : 1);
    order.setVersion(3L);
    return order;
  }

  private TradingAccountEntity account(BigDecimal usedMargin, BigDecimal freeMargin) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    account.setBaseCurrency("USDT");
    account.setBalance(new BigDecimal("50000.00000000"));
    account.setEquity(new BigDecimal("50000.00000000"));
    account.setUsedMargin(usedMargin);
    account.setFreeMargin(freeMargin);
    account.setPositionMode(PositionMode.ONE_WAY);
    return account;
  }
}
