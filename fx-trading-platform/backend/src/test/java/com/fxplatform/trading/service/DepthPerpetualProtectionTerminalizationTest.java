package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
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
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DepthPerpetualProtectionTerminalizationTest {

  private static final String SYMBOL = "BTCUSDT-PERP";
  private static final BigDecimal ORIGINAL_QUANTITY = new BigDecimal("1.0000");
  private static final BigDecimal FILLED_QUANTITY = new BigDecimal("0.4000");
  private static final BigDecimal REMAINING_QUANTITY = new BigDecimal("0.6000");
  private static final BigDecimal AUTHORITY_MARK = new BigDecimal("100.0000");

  @Mock private OrderRepository orderRepository;
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
  @Mock private AccountSymbolSettingRepository settingRepository;
  @Mock private PerpetualOrderRiskService perpetualOrderRiskService;
  @Mock private PerpetualAccountRiskSnapshotService perpetualRiskSnapshotService;

  private UUID userId;
  private UUID accountId;
  private TradingAccountEntity account;
  private OrderService orderService;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    accountId = UUID.randomUUID();
    account = account();

    ProtectionOrderService protectionOrderService = new ProtectionOrderService(
        accountRepository,
        positionRepository,
        orderRepository,
        settingRepository,
        symbolRepository,
        marketBundleResolver,
        fullFillCoordinator,
        new QuantityConversionService(),
        instrumentRulesEngine,
        demoExecutionGuard,
        ledgerService,
        orderEventService,
        new OrderResponseMapper(),
        new TradingTransactionExecutor());

    orderService = new OrderService(
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
        org.mockito.Mockito.mock(OrderHoldCalculator.class),
        settingRepository,
        perpetualOrderRiskService,
        perpetualRiskSnapshotService);
    orderService.setProtectionOrderService(protectionOrderService);
  }

  @Test
  void partialOpeningParentCancelBindsAndShrinksAttachedProtectionsToActualTargetPosition() {
    OrderEntity parent = partiallyFilledParent(OrderSide.BUY);
    PositionEntity longPosition = position(OrderSide.BUY, FILLED_QUANTITY);
    OrderEntity takeProfit = attached(parent, ProtectionType.TAKE_PROFIT, "110.0000");
    OrderEntity stopLoss = attached(parent, ProtectionType.STOP_LOSS, "90.0000");
    stubCancelMutation(parent, List.of(longPosition), List.of(takeProfit, stopLoss));

    OrderResponse canceled = orderService.cancelOrder(principal(), parent.getId());

    assertAll(
        () -> assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED.name()),
        () -> assertThat(canceled.filledQuantity()).isEqualByComparingTo(FILLED_QUANTITY),
        () -> assertThat(canceled.remainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO),
        () -> assertBoundAndResized(takeProfit, longPosition),
        () -> assertBoundAndResized(stopLoss, longPosition));
    verify(orderEventService).record(
        takeProfit.getId(),
        "PROTECTION_ACTIVATED",
        OrderStatus.PENDING_ACTIVATION,
        OrderStatus.PENDING_ACTIVATION,
        null,
        null);
    verify(orderEventService).record(
        stopLoss.getId(),
        "PROTECTION_ACTIVATED",
        OrderStatus.PENDING_ACTIVATION,
        OrderStatus.PENDING_ACTIVATION,
        null,
        null);
    verify(orderEventService, never()).record(
        eq(takeProfit.getId()),
        eq("PROTECTION_EXPIRED"),
        any(),
        any(),
        any(),
        any());
    verify(orderEventService, never()).record(
        eq(stopLoss.getId()),
        eq("PROTECTION_EXPIRED"),
        any(),
        any(),
        any(),
        any());
  }

  @Test
  void partialParentCancelWithoutTargetSlotExpiresUnboundAttachedProtectionsFailClosed() {
    OrderEntity parent = partiallyFilledParent(OrderSide.SELL);
    PositionEntity reducedLongPosition = position(OrderSide.BUY, REMAINING_QUANTITY);
    OrderEntity takeProfit = attached(parent, ProtectionType.TAKE_PROFIT, "90.0000");
    OrderEntity stopLoss = attached(parent, ProtectionType.STOP_LOSS, "110.0000");
    stubCancelMutation(parent, List.of(reducedLongPosition), List.of(takeProfit, stopLoss));

    OrderResponse canceled = orderService.cancelOrder(principal(), parent.getId());

    assertAll(
        () -> assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED.name()),
        () -> assertThat(canceled.filledQuantity()).isEqualByComparingTo(FILLED_QUANTITY),
        () -> assertThat(canceled.remainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO),
        () -> assertExpiredAndUnbound(takeProfit),
        () -> assertExpiredAndUnbound(stopLoss));
    verify(orderEventService).record(
        takeProfit.getId(),
        "PROTECTION_EXPIRED",
        OrderStatus.PENDING_ACTIVATION,
        OrderStatus.EXPIRED,
        null,
        null);
    verify(orderEventService).record(
        stopLoss.getId(),
        "PROTECTION_EXPIRED",
        OrderStatus.PENDING_ACTIVATION,
        OrderStatus.EXPIRED,
        null,
        null);
    verify(orderEventService, never()).record(
        eq(takeProfit.getId()),
        eq("PROTECTION_ACTIVATED"),
        any(),
        any(),
        any(),
        any());
    verify(orderEventService, never()).record(
        eq(stopLoss.getId()),
        eq("PROTECTION_ACTIVATED"),
        any(),
        any(),
        any(),
        any());
  }

  @Test
  void partialOpeningCancelAllocatesOnlyResidualCapacityAfterExistingBoundProtection() {
    OrderEntity parent = partiallyFilledParent(OrderSide.BUY);
    PositionEntity longPosition = position(OrderSide.BUY, new BigDecimal("0.6000"));
    OrderEntity existing = attached(parent, ProtectionType.TAKE_PROFIT, "110.0000");
    existing.setParentOrderId(UUID.randomUUID());
    existing.setParentPositionId(longPosition.getId());
    resize(existing, new BigDecimal("0.2000"));
    OrderEntity newlyAttached = attached(parent, ProtectionType.TAKE_PROFIT, "111.0000");
    stubCancelMutation(
        parent,
        List.of(longPosition),
        List.of(existing, newlyAttached),
        List.of(newlyAttached));

    OrderResponse canceled = orderService.cancelOrder(principal(), parent.getId());

    assertAll(
        () -> assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED.name()),
        () -> assertThat(existing.getBaseQuantity()).isEqualByComparingTo("0.2000"),
        () -> assertThat(existing.getParentPositionId()).isEqualTo(longPosition.getId()),
        () -> assertBoundAndResized(newlyAttached, longPosition));
    verify(orderEventService).record(
        newlyAttached.getId(),
        "PROTECTION_ACTIVATED",
        OrderStatus.PENDING_ACTIVATION,
        OrderStatus.PENDING_ACTIVATION,
        null,
        null);
  }

  @Test
  void partialOpeningCancelFailsClosedWhenExistingProtectionExceedsPositionCapacity() {
    OrderEntity parent = partiallyFilledParent(OrderSide.BUY);
    PositionEntity longPosition = position(OrderSide.BUY, FILLED_QUANTITY);
    OrderEntity existing = attached(parent, ProtectionType.TAKE_PROFIT, "110.0000");
    existing.setParentOrderId(UUID.randomUUID());
    existing.setParentPositionId(longPosition.getId());
    resize(existing, new BigDecimal("0.5000"));
    OrderEntity newlyAttached = attached(parent, ProtectionType.TAKE_PROFIT, "111.0000");
    stubCancelMutation(
        parent,
        List.of(longPosition),
        List.of(existing, newlyAttached),
        List.of(newlyAttached));

    assertThatThrownBy(() -> orderService.cancelOrder(principal(), parent.getId()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.PROTECTION_QUANTITY_EXCEEDED));
    verify(orderRepository, never()).updateById(newlyAttached);
    verify(orderEventService, never()).record(
        eq(newlyAttached.getId()),
        any(),
        any(),
        any(),
        any(),
        any());
  }

  @Test
  void multipleSameTypeAttachedProtectionsUseOldestFirstCapacityAndExpireZeroTail() {
    OrderEntity parent = partiallyFilledParent(OrderSide.BUY);
    PositionEntity longPosition = position(OrderSide.BUY, FILLED_QUANTITY);
    OrderEntity oldest = attached(parent, ProtectionType.TAKE_PROFIT, "110.0000");
    OrderEntity newest = attached(parent, ProtectionType.TAKE_PROFIT, "111.0000");
    resize(oldest, new BigDecimal("0.5000"));
    resize(newest, new BigDecimal("0.5000"));
    oldest.setCreatedAt(Instant.parse("2026-07-18T00:00:00Z"));
    newest.setCreatedAt(Instant.parse("2026-07-18T00:00:01Z"));
    stubCancelMutation(
        parent,
        List.of(longPosition),
        List.of(oldest, newest),
        List.of(oldest, newest));

    OrderResponse canceled = orderService.cancelOrder(principal(), parent.getId());

    assertAll(
        () -> assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED.name()),
        () -> assertBoundAndResized(oldest, longPosition),
        () -> assertExpiredAndUnbound(newest));
    verify(orderEventService).record(
        newest.getId(),
        "PROTECTION_EXPIRED",
        OrderStatus.PENDING_ACTIVATION,
        OrderStatus.EXPIRED,
        null,
        null);
    verify(orderEventService, never()).record(
        eq(newest.getId()),
        eq("PROTECTION_ACTIVATED"),
        any(),
        any(),
        any(),
        any());
  }

  @Test
  void zeroShareAttachedCarrierDoesNotExceedActiveProtectionLimit() {
    OrderEntity parent = partiallyFilledParent(OrderSide.BUY);
    PositionEntity longPosition = position(OrderSide.BUY, FILLED_QUANTITY);
    List<OrderEntity> activeProtections = new java.util.ArrayList<>();
    for (int index = 0; index < 10; index++) {
      OrderEntity existing = attached(parent, ProtectionType.TAKE_PROFIT, "110.0000");
      existing.setParentOrderId(UUID.randomUUID());
      existing.setParentPositionId(longPosition.getId());
      resize(existing, new BigDecimal("0.0400"));
      activeProtections.add(existing);
    }
    OrderEntity zeroShare = attached(parent, ProtectionType.TAKE_PROFIT, "111.0000");
    stubCancelMutation(
        parent,
        List.of(longPosition),
        activeProtections,
        List.of(zeroShare));

    OrderResponse canceled = orderService.cancelOrder(principal(), parent.getId());

    assertAll(
        () -> assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED.name()),
        () -> assertThat(activeProtections).hasSize(10),
        () -> assertThat(activeProtections)
            .allSatisfy(protection ->
                assertThat(protection.getBaseQuantity()).isEqualByComparingTo("0.0400")),
        () -> assertExpiredAndUnbound(zeroShare));
    verify(orderEventService).record(
        zeroShare.getId(),
        "PROTECTION_EXPIRED",
        OrderStatus.PENDING_ACTIVATION,
        OrderStatus.EXPIRED,
        null,
        null);
    verify(orderEventService, never()).record(
        eq(zeroShare.getId()),
        eq("PROTECTION_ACTIVATED"),
        any(),
        any(),
        any(),
        any());
  }

  @Test
  void zeroShareAttachedCarrierIgnoresDirectionAndReleasesItsHoldBeforeExpiry() {
    OrderEntity parent = partiallyFilledParent(OrderSide.BUY);
    PositionEntity longPosition = position(OrderSide.BUY, FILLED_QUANTITY);
    OrderEntity existing = attached(parent, ProtectionType.TAKE_PROFIT, "110.0000");
    existing.setParentOrderId(UUID.randomUUID());
    existing.setParentPositionId(longPosition.getId());
    resize(existing, FILLED_QUANTITY);
    OrderEntity zeroShare = attached(parent, ProtectionType.TAKE_PROFIT, "90.0000");
    BigDecimal hold = new BigDecimal("0.05000000");
    zeroShare.setHoldAmount(hold);
    account.setUsedMargin(hold);
    account.setFreeMargin(account.getFreeMargin().subtract(hold));
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    stubCancelMutation(
        parent,
        List.of(longPosition),
        List.of(existing, zeroShare),
        List.of(zeroShare));

    OrderResponse canceled = orderService.cancelOrder(principal(), parent.getId());

    assertAll(
        () -> assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED.name()),
        () -> assertExpiredAndUnbound(zeroShare),
        () -> assertThat(account.getUsedMargin()).isEqualByComparingTo(BigDecimal.ZERO),
        () -> assertThat(account.getFreeMargin()).isEqualByComparingTo("50000.00000000"));
    verify(accountRepository).save(account);
    verify(ledgerService).recordOrderRelease(
        account,
        hold,
        zeroShare.getId(),
        "Attached protection capacity exhausted");
    verify(orderEventService).record(
        zeroShare.getId(),
        "PROTECTION_EXPIRED",
        OrderStatus.PENDING_ACTIVATION,
        OrderStatus.EXPIRED,
        null,
        null);
    verify(orderEventService, never()).record(
        eq(zeroShare.getId()),
        eq("PROTECTION_ACTIVATED"),
        any(),
        any(),
        any(),
        any());
  }

  private void stubCancelMutation(
      OrderEntity parent,
      List<PositionEntity> lockedPositions,
      List<OrderEntity> attached
  ) {
    stubCancelMutation(parent, lockedPositions, attached, attached);
  }

  private void stubCancelMutation(
      OrderEntity parent,
      List<PositionEntity> lockedPositions,
      List<OrderEntity> activeOrders,
      List<OrderEntity> attached
  ) {
    when(orderRepository.findByUserIdAndId(userId, parent.getId()))
        .thenReturn(Optional.of(parent));
    when(accountRepository.findByIdAndUserId(accountId, userId))
        .thenReturn(Optional.of(account));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId))
        .thenReturn(Optional.of(account));
    when(settingRepository.findByAccountIdAndSymbolForUpdate(accountId, SYMBOL))
        .thenReturn(Optional.empty());
    when(positionRepository.findOpenLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenReturn(lockedPositions);
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenReturn(activeOrders);
    when(orderRepository.findByIdForUpdate(parent.getId()))
        .thenReturn(Optional.of(parent));
    when(orderRepository.cancelPartiallyFilled(parent)).thenReturn(1);
    when(orderRepository.findProtectionsByParentOrderIdForUpdate(parent.getId()))
        .thenReturn(attached);
    when(orderRepository.updateById(any(OrderEntity.class))).thenReturn(1);
  }

  private void resize(OrderEntity protection, BigDecimal quantity) {
    protection.setLots(quantity);
    protection.setQuantity(quantity);
    protection.setOriginalQuantity(quantity);
    protection.setBaseQuantity(quantity);
    protection.setRemainingQuantity(quantity);
  }

  private void assertBoundAndResized(OrderEntity protection, PositionEntity position) {
    assertAll(
        () -> assertThat(protection.getStatus()).isEqualTo(OrderStatus.PENDING_ACTIVATION),
        () -> assertThat(protection.getParentPositionId()).isEqualTo(position.getId()),
        () -> assertThat(protection.getSide()).isEqualTo(OrderSide.SELL),
        () -> assertThat(protection.getBaseQuantity()).isEqualByComparingTo(FILLED_QUANTITY),
        () -> assertThat(protection.getLots()).isEqualByComparingTo(FILLED_QUANTITY),
        () -> assertThat(protection.getQuantity()).isEqualByComparingTo(FILLED_QUANTITY),
        () -> assertThat(protection.getOriginalQuantity()).isEqualByComparingTo(FILLED_QUANTITY),
        () -> assertThat(protection.getRemainingQuantity()).isEqualByComparingTo(FILLED_QUANTITY));
  }

  private void assertExpiredAndUnbound(OrderEntity protection) {
    assertAll(
        () -> assertThat(protection.getStatus()).isEqualTo(OrderStatus.EXPIRED),
        () -> assertThat(protection.getParentPositionId()).isNull(),
        () -> assertThat(protection.getBaseQuantity()).isEqualByComparingTo(BigDecimal.ZERO),
        () -> assertThat(protection.getLots()).isEqualByComparingTo(BigDecimal.ZERO),
        () -> assertThat(protection.getQuantity()).isEqualByComparingTo(BigDecimal.ZERO),
        () -> assertThat(protection.getOriginalQuantity()).isEqualByComparingTo(BigDecimal.ZERO),
        () -> assertThat(protection.getRemainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO),
        () -> assertThat(protection.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO));
  }

  private UserPrincipal principal() {
    return new UserPrincipal(userId, "protection-terminalization@example.com", "TRADER");
  }

  private TradingAccountEntity account() {
    TradingAccountEntity result = new TradingAccountEntity();
    result.setId(accountId);
    result.setUserId(userId);
    result.setAccountType(AccountType.DEMO);
    result.setStatus(AccountStatus.ACTIVE);
    result.setBaseCurrency("USDT");
    result.setBalance(new BigDecimal("50000.00000000"));
    result.setEquity(new BigDecimal("50000.00000000"));
    result.setUsedMargin(BigDecimal.ZERO);
    result.setFreeMargin(new BigDecimal("50000.00000000"));
    result.setPositionMode(PositionMode.ONE_WAY);
    return result;
  }

  private OrderEntity partiallyFilledParent(OrderSide side) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setUserId(userId);
    order.setAccountId(accountId);
    order.setSymbol(SYMBOL);
    order.setProductType(ProductType.LINEAR_PERP);
    order.setPositionMode(PositionMode.ONE_WAY);
    order.setPositionSide(PositionSide.BOTH);
    order.setMarginMode(MarginMode.CROSS);
    order.setSide(side);
    order.setOrderType(OrderType.LIMIT);
    order.setStatus(OrderStatus.PARTIALLY_FILLED);
    order.setLots(ORIGINAL_QUANTITY);
    order.setQuantity(ORIGINAL_QUANTITY);
    order.setOriginalQuantity(ORIGINAL_QUANTITY);
    order.setBaseQuantity(ORIGINAL_QUANTITY);
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setRequestedPrice(AUTHORITY_MARK);
    order.setPrice(AUTHORITY_MARK);
    order.setExecutionPrice(AUTHORITY_MARK);
    order.setFilledQuantity(FILLED_QUANTITY);
    order.setRemainingQuantity(REMAINING_QUANTITY);
    order.setAvgFillPrice(AUTHORITY_MARK);
    order.setFee(new BigDecimal("0.02000000"));
    order.setFeeAsset("USDT");
    order.setLiquidityRole(LiquidityRole.MAKER);
    order.setHoldAmount(BigDecimal.ZERO);
    order.setHoldCurrency("USDT");
    order.setTimeInForce(TimeInForce.GTC);
    order.setPostOnly(false);
    order.setReduceOnly(false);
    order.setOrderOrigin(OrderOrigin.USER);
    order.setLeverage(10);
    order.setVersion(3L);
    return order;
  }

  private PositionEntity position(OrderSide side, BigDecimal quantity) {
    PositionEntity position = new PositionEntity();
    position.setId(UUID.randomUUID());
    position.setAccountId(accountId);
    position.setSymbol(SYMBOL);
    position.setProductType(ProductType.LINEAR_PERP);
    position.setPositionMode(PositionMode.ONE_WAY);
    position.setPositionSide(PositionSide.BOTH);
    position.setMarginMode(MarginMode.CROSS);
    position.setSide(side);
    position.setLots(quantity);
    position.setOpenPrice(AUTHORITY_MARK);
    position.setCurrentPrice(AUTHORITY_MARK);
    position.setMarkPrice(AUTHORITY_MARK);
    position.setSettlementAsset("USDT");
    position.setMarginAsset("USDT");
    position.setLeverage(10);
    position.setStatus(PositionStatus.OPEN);
    position.setVersion(2L);
    return position;
  }

  private OrderEntity attached(
      OrderEntity parent,
      ProtectionType protectionType,
      String triggerPrice
  ) {
    OrderEntity protection = new OrderEntity();
    protection.setId(UUID.randomUUID());
    protection.setUserId(userId);
    protection.setAccountId(accountId);
    protection.setSymbol(SYMBOL);
    protection.setProductType(ProductType.LINEAR_PERP);
    protection.setPositionMode(PositionMode.ONE_WAY);
    protection.setPositionSide(PositionSide.BOTH);
    protection.setMarginMode(MarginMode.CROSS);
    protection.setSide(parent.getSide() == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY);
    protection.setOrderType(OrderType.STOP_MARKET);
    protection.setStatus(OrderStatus.PENDING_ACTIVATION);
    protection.setLots(ORIGINAL_QUANTITY);
    protection.setQuantity(ORIGINAL_QUANTITY);
    protection.setOriginalQuantity(ORIGINAL_QUANTITY);
    protection.setBaseQuantity(ORIGINAL_QUANTITY);
    protection.setRemainingQuantity(ORIGINAL_QUANTITY);
    protection.setQuantityUnit(QuantityUnit.BASE);
    protection.setTimeInForce(TimeInForce.GTC);
    protection.setReduceOnly(true);
    protection.setOrderOrigin(OrderOrigin.PROTECTIVE);
    protection.setTriggerPrice(new BigDecimal(triggerPrice));
    protection.setTriggerPriceType(TriggerPriceType.MARK_PRICE);
    protection.setTriggerExecutionType(TriggerExecutionType.MARKET);
    protection.setProtectionType(protectionType);
    protection.setParentOrderId(parent.getId());
    protection.setHoldAmount(BigDecimal.ZERO);
    protection.setHoldCurrency("USDT");
    protection.setLeverage(10);
    protection.setVersion(0L);
    return protection;
  }
}
