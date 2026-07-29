package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.execution.FullFillExecutionPath;
import com.fxplatform.execution.FullFillRequest;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import com.fxplatform.trading.dto.request.ClosePositionRequest;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemCloseOrderServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-12T08:00:00Z");
  private static final String SYMBOL = "BTCUSDT-PERP";

  @Mock private OrderRepository orderRepository;
  @Mock private PositionRepository positionRepository;
  @Mock private TradingAccountRepository accountRepository;
  @Mock private AccountSymbolSettingRepository settingRepository;
  @Mock private SymbolRepository symbolRepository;
  @Mock private MarketBundleResolver marketBundleResolver;
  @Mock private InstrumentRulesEngine instrumentRulesEngine;
  @Mock private PerpetualOrderRiskService perpetualOrderRiskService;
  @Mock private PerpetualAccountRiskSnapshotService accountRiskSnapshotService;
  @Mock private ProtectionOrderService protectionOrderService;
  @Mock private FullFillCoordinator fullFillCoordinator;
  @Mock private OrderFillService orderFillService;
  @Mock private OrderEventService orderEventService;
  @Mock private LedgerService ledgerService;
  @Mock private AuditLogService auditLogService;
  @Mock private DemoExecutionGuard demoExecutionGuard;
  @Mock private TradingTransactionExecutor transactionExecutor;

  private final QuantityConversionService quantityConversionService =
      new QuantityConversionService();
  private final OrderEntityFactory orderEntityFactory = new OrderEntityFactory();

  private UUID userId;
  private UUID accountId;
  private UUID positionId;
  private TradingAccountEntity account;
  private PositionEntity position;
  private AccountSymbolSettingEntity setting;
  private SymbolEntity symbol;
  private PerpetualMarketBundle bundle;
  private AtomicBoolean insideTransaction;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    accountId = UUID.randomUUID();
    positionId = UUID.randomUUID();
    account = account(userId, accountId, PositionMode.ONE_WAY);
    position = position(accountId, positionId, PositionMode.ONE_WAY, PositionSide.BOTH, OrderSide.BUY);
    setting = setting(accountId, MarginMode.CROSS);
    symbol = symbol();
    bundle = bundle();
    insideTransaction = new AtomicBoolean();

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(accountRepository.selectById(accountId)).thenReturn(account);
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(account));
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(positionRepository.selectById(positionId)).thenAnswer(invocation -> position);
    when(symbolRepository.findBySymbol(SYMBOL)).thenReturn(Optional.of(symbol));
    when(instrumentRulesEngine.rules(symbol)).thenReturn(rules());
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        eq(userId), eq(accountId), anyString())).thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(eq(userId), anyString()))
        .thenReturn(Optional.empty());
    when(marketBundleResolver.resolvePerp(eq(SYMBOL), any())).thenAnswer(invocation -> {
      assertThat(insideTransaction.get()).isFalse();
      return bundle;
    });
    when(transactionExecutor.execute(any())).thenAnswer(invocation -> {
      insideTransaction.set(true);
      try {
        return ((Supplier<?>) invocation.getArgument(0)).get();
      } finally {
        insideTransaction.set(false);
      }
    });
    when(settingRepository.findByAccountIdAndSymbolForUpdate(accountId, SYMBOL))
        .thenReturn(Optional.of(setting));
    when(positionRepository.findOpenLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenAnswer(invocation -> List.of(position));
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenReturn(List.of());
    when(perpetualOrderRiskService.evaluate(
        any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> closeRisk(
            invocation.getArgument(3),
            invocation.getArgument(7)));
    when(perpetualOrderRiskService.evaluateLiquidationClose(
        any(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> closeRisk(
            invocation.getArgument(3),
            invocation.getArgument(5)));
    when(protectionOrderService.isTriggered(any(OrderEntity.class), any(BigDecimal.class)))
        .thenCallRealMethod();
    when(fullFillCoordinator.execute(any(FullFillRequest.class), any(ExecutableMarketSnapshot.class)))
        .thenAnswer(invocation -> fill(invocation.getArgument(0)));
    when(orderRepository.save(any(OrderEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.save(any(TradingAccountEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(orderFillService.fillPerpetual(
        any(OrderEntity.class),
        any(TradingAccountEntity.class),
        any(FullFillResult.class),
        any(BigDecimal.class),
        anyInt(),
        anyString())).thenAnswer(invocation -> {
          OrderEntity order = invocation.getArgument(0);
          BigDecimal remaining = position.getLots().subtract(order.getBaseQuantity());
          position.setLots(remaining);
          if (remaining.compareTo(BigDecimal.ZERO) == 0) {
            position.setStatus(PositionStatus.CLOSED);
          }
          order.setStatus(OrderStatus.FILLED);
          order.setFilledQuantity(order.getBaseQuantity());
          order.setRemainingQuantity(BigDecimal.ZERO);
          order.setHoldAmount(BigDecimal.ZERO);
          return order;
        });
  }

  @Test
  void userPartialLongCloseBuildsCanonicalReduceOnlyMarketChain() {
    ClosePositionRequest request = request("1.0000", "partial-long");

    SystemCloseOrderService.CloseResult result = service().closeUser(
        userId, accountId, positionId, request);

    OrderEntity order = result.order();
    assertAll(
        () -> assertThat(result.replayed()).isFalse(),
        () -> assertThat(order.getProductType()).isEqualTo(ProductType.LINEAR_PERP),
        () -> assertThat(order.getOrderType()).isEqualTo(OrderType.MARKET),
        () -> assertThat(order.getSide()).isEqualTo(OrderSide.SELL),
        () -> assertThat(order.getPositionMode()).isEqualTo(PositionMode.ONE_WAY),
        () -> assertThat(order.getPositionSide()).isEqualTo(PositionSide.BOTH),
        () -> assertThat(order.getReduceOnly()).isTrue(),
        () -> assertThat(order.getOrderOrigin()).isEqualTo(OrderOrigin.USER),
        () -> assertThat(order.getParentPositionId()).isEqualTo(positionId),
        () -> assertThat(order.getBaseQuantity()).isEqualByComparingTo("1.0000"),
        () -> assertThat(order.getOriginalQuantity()).isEqualByComparingTo("1.0000"),
        () -> assertThat(order.getQuantityUnit()).isEqualTo(QuantityUnit.BASE),
        () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED),
        () -> assertThat(result.position().getLots()).isEqualByComparingTo("1.0000"),
        () -> assertThat(result.position().getOpenPrice()).isEqualByComparingTo("100"));
    ArgumentCaptor<FullFillRequest> fillRequest = ArgumentCaptor.forClass(FullFillRequest.class);
    verify(fullFillCoordinator).execute(fillRequest.capture(), any(ExecutableMarketSnapshot.class));
    assertAll(
        () -> assertThat(fillRequest.getValue().executionPath())
            .isEqualTo(FullFillExecutionPath.MARKET),
        () -> assertThat(fillRequest.getValue().requestedBaseQuantity())
            .isEqualByComparingTo("1.0000"),
        () -> assertThat(fillRequest.getValue().executionIntent().reduceOnly()).isTrue(),
        () -> assertThat(fillRequest.getValue().executionIntent().quantityUnit())
            .isEqualTo(QuantityUnit.BASE));
    verify(orderFillService).fillPerpetual(
        eq(order),
        eq(account),
        any(FullFillResult.class),
        eq(new BigDecimal("100")),
        eq(10),
        eq("System close position margin"));
    verify(orderEventService).record(
        order.getId(),
        "ORDER_FILLED",
        OrderStatus.ACCEPTED,
        OrderStatus.FILLED,
        null,
        "Perpetual position closed");
    InOrder writes = inOrder(accountRepository, orderRepository, ledgerService, orderFillService);
    writes.verify(accountRepository).save(account);
    writes.verify(orderRepository).save(order);
    writes.verify(ledgerService).recordOrderHold(
        account,
        new BigDecimal("0.04950000"),
        order.getId(),
        "Perpetual close order margin reserved");
    writes.verify(orderFillService).fillPerpetual(
        eq(order), eq(account), any(FullFillResult.class), eq(new BigDecimal("100")), eq(10), anyString());
  }

  @ParameterizedTest
  @MethodSource("closeQuantityConversions")
  void closeQuantityUnitsPersistOriginalIntentAndOneCanonicalBase(
      QuantityUnit unit,
      String requestedQuantity,
      String expectedBase
  ) {
    ClosePositionRequest request = new ClosePositionRequest(
        new BigDecimal(requestedQuantity),
        unit,
        "convert-" + unit.name().toLowerCase());

    SystemCloseOrderService.CloseResult result = service().closeUser(
        userId, accountId, positionId, request);

    assertAll(
        () -> assertThat(result.order().getQuantityUnit()).isEqualTo(unit),
        () -> assertThat(result.order().getOriginalQuantity())
            .isEqualByComparingTo(requestedQuantity),
        () -> assertThat(result.order().getBaseQuantity()).isEqualByComparingTo(expectedBase),
        () -> assertThat(result.order().getFilledQuantity()).isEqualByComparingTo(expectedBase));
    ArgumentCaptor<FullFillRequest> fillRequest = ArgumentCaptor.forClass(FullFillRequest.class);
    verify(fullFillCoordinator).execute(
        fillRequest.capture(), any(ExecutableMarketSnapshot.class));
    assertThat(fillRequest.getValue().requestedBaseQuantity())
        .isEqualByComparingTo(expectedBase);
  }

  @Test
  void userPartialShortCloseBuysOnlyTheRequestedQuantity() {
    position = position(
        accountId,
        positionId,
        PositionMode.ONE_WAY,
        PositionSide.BOTH,
        OrderSide.SELL);

    SystemCloseOrderService.CloseResult result = service().closeUser(
        userId,
        accountId,
        positionId,
        request("0.5000", "partial-short"));

    assertAll(
        () -> assertThat(result.order().getSide()).isEqualTo(OrderSide.BUY),
        () -> assertThat(result.order().getBaseQuantity()).isEqualByComparingTo("0.5000"),
        () -> assertThat(result.position().getStatus()).isEqualTo(PositionStatus.OPEN),
        () -> assertThat(result.position().getLots()).isEqualByComparingTo("1.5000"));
  }

  @Test
  void exactHedgeShortClosePreservesLegAndNeverTouchesLongPeer() {
    account.setPositionMode(PositionMode.HEDGE);
    position = position(accountId, positionId, PositionMode.HEDGE, PositionSide.SHORT, OrderSide.SELL);
    PositionEntity longPeer = position(
        accountId, UUID.randomUUID(), PositionMode.HEDGE, PositionSide.LONG, OrderSide.BUY);
    when(positionRepository.findOpenLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenReturn(List.of(longPeer, position));
    ClosePositionRequest request = request("2.0000", "exact-short");

    SystemCloseOrderService.CloseResult result = service().closeUser(
        userId, accountId, positionId, request);

    assertAll(
        () -> assertThat(result.order().getSide()).isEqualTo(OrderSide.BUY),
        () -> assertThat(result.order().getPositionMode()).isEqualTo(PositionMode.HEDGE),
        () -> assertThat(result.order().getPositionSide()).isEqualTo(PositionSide.SHORT),
        () -> assertThat(result.position().getStatus()).isEqualTo(PositionStatus.CLOSED),
        () -> assertThat(longPeer.getStatus()).isEqualTo(PositionStatus.OPEN),
        () -> assertThat(longPeer.getLots()).isEqualByComparingTo("2.0000"));
    verify(perpetualOrderRiskService).evaluate(
        PositionMode.HEDGE,
        setting,
        List.of(longPeer, position),
        OrderSide.BUY,
        PositionSide.SHORT,
        true,
        OrderType.MARKET,
        new BigDecimal("2.0000"),
        null,
        ExecutableMarketSnapshot.from(bundle),
        new BigDecimal("0.005"));
  }

  @Test
  void overCloseRejectsBeforeTransactionAndEveryWrite() {
    ClosePositionRequest request = request("2.0001", "over-close");

    assertThatThrownBy(() -> service().closeUser(userId, accountId, positionId, request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.REDUCE_ONLY_EXCEEDS_POSITION));

    verify(transactionExecutor, never()).execute(any());
    verify(accountRepository, never()).save(any());
    verify(orderRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), anyString());
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
  }

  @Test
  void lockedPositionLeverageDriftRejectsBeforeAnyCloseMutation() {
    position.setLeverage(5);

    assertThatThrownBy(() -> service().closeUser(
        userId,
        accountId,
        positionId,
        request("1.0000", "leverage-drift")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("INVALID_INSTRUMENT_RULES"));

    verify(orderRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), anyString());
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
  }

  @Test
  void userCloseRejectsReservedSystemNamespaceBeforeMarketOrLocks() {
    assertThatThrownBy(() -> service().closeUser(
        userId,
        accountId,
        positionId,
        request("1.0000", "__SYSTEM__:forged-user-close")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.DUPLICATE_CLIENT_ORDER_ID));

    verify(marketBundleResolver, never()).resolvePerp(anyString(), any());
    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
  }

  @Test
  void isolatedManualCloseRejectsAggregateTask9HoldBeforeEveryMutation() {
    setting.setMarginMode(MarginMode.ISOLATED);
    position.setMarginMode(MarginMode.ISOLATED);
    OrderEntity existing = task9IsolatedClose("0.5000", "1000000");
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenReturn(List.of(existing));

    assertThatThrownBy(() -> service().closeUser(
        userId,
        accountId,
        positionId,
        request("1.0000", "isolated-hold-conflict")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.MARGIN_REDUCTION_UNSAFE));

    assertAll(
        () -> assertThat(position.getLots()).isEqualByComparingTo("2.0000"),
        () -> assertThat(account.getUsedMargin()).isEqualByComparingTo("20"));
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(orderRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), anyString());
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
  }

  @Test
  void sameClientOrderIdReplaysCommittedCloseWithoutProviderOrMutation() {
    ClosePositionRequest request = request("1.0000", "replay-close");
    OrderEntity existing = existingClose(request, OrderStatus.FILLED);
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, request.clientOrderId())).thenReturn(Optional.of(existing));

    SystemCloseOrderService.CloseResult result = service().closeUser(
        userId, accountId, positionId, request);

    assertAll(
        () -> assertThat(result.replayed()).isTrue(),
        () -> assertThat(result.order()).isSameAs(existing),
        () -> assertThat(result.position()).isSameAs(position),
        () -> assertThat(result.account()).isSameAs(account));
    verify(marketBundleResolver, never()).resolvePerp(anyString(), any());
    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
  }

  @Test
  void sameClientOrderIdWithDifferentQuantityRejectsAsConflict() {
    ClosePositionRequest original = request("1.0000", "conflict-close");
    OrderEntity existing = existingClose(original, OrderStatus.FILLED);
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, original.clientOrderId())).thenReturn(Optional.of(existing));
    ClosePositionRequest conflicting = request("1.5000", original.clientOrderId());

    assertThatThrownBy(() -> service().closeUser(
        userId, accountId, positionId, conflicting))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.DUPLICATE_CLIENT_ORDER_ID));

    verify(marketBundleResolver, never()).resolvePerp(anyString(), any());
    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
  }

  @Test
  void sameClientOrderIdFromAnotherPositionRejectsInsteadOfReplaying() {
    ClosePositionRequest request = request("1.0000", "old-position-close");
    OrderEntity existing = existingClose(request, OrderStatus.FILLED);
    existing.setParentPositionId(UUID.randomUUID());
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, request.clientOrderId())).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service().closeUser(userId, accountId, positionId, request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.DUPLICATE_CLIENT_ORDER_ID));

    verify(marketBundleResolver, never()).resolvePerp(anyString(), any());
    verify(transactionExecutor, never()).execute(any());
  }

  @Test
  void wholeIntentCannotReplayAnEarlierPartialCloseAfterAnotherOrderClosedTheRest() {
    String callerKey = "whole-after-partial";
    String persistedKey = OrderIdempotencyKeyPolicy.systemClose(
        accountId, positionId, OrderOrigin.USER, callerKey);
    ClosePositionRequest partial = request("1.0000", persistedKey);
    OrderEntity existing = existingClose(partial, OrderStatus.FILLED);
    position.setLots(new BigDecimal("1.0000"));
    position.setStatus(PositionStatus.CLOSED);
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, partial.clientOrderId())).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service().closeUserWhole(
        userId, accountId, positionId, callerKey))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.DUPLICATE_CLIENT_ORDER_ID));

    verify(marketBundleResolver, never()).resolvePerp(anyString(), any());
    verify(transactionExecutor, never()).execute(any());
  }

  @Test
  void committedWholeUserCloseReplaysWithoutProviderOrAnotherTransaction() {
    String key = "whole-replay";
    String persistedKey = OrderIdempotencyKeyPolicy.systemClose(
        accountId, positionId, OrderOrigin.USER, key);
    OrderEntity existing = existingClose(request("2.0000", persistedKey), OrderStatus.FILLED);
    existing.setSystemReason(OrderSystemReasonPolicy.USER_WHOLE_CLOSE);
    position.setStatus(PositionStatus.CLOSED);
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, persistedKey)).thenReturn(Optional.of(existing));

    SystemCloseOrderService.CloseResult replay = service().closeUserWhole(
        userId, accountId, positionId, key);

    assertAll(
        () -> assertThat(replay.replayed()).isTrue(),
        () -> assertThat(replay.order()).isSameAs(existing),
        () -> assertThat(OrderSystemReasonPolicy.external(replay.order())).isNull());
    verify(marketBundleResolver, never()).resolvePerp(anyString(), any());
    verify(transactionExecutor, never()).execute(any());
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
  }

  @Test
  void ordinaryUserKeyCannotPreemptServerDerivedWholeUserClose() {
    String callerRequestId = "position-close-" + positionId;
    OrderEntity occupied = existingClose(
        request("2.0000", callerRequestId), OrderStatus.FILLED);
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, callerRequestId)).thenReturn(Optional.of(occupied));

    SystemCloseOrderService.CloseResult result = service().closeUserWhole(
        userId, accountId, positionId, callerRequestId);

    assertAll(
        () -> assertThat(result.replayed()).isFalse(),
        () -> assertThat(result.order().getOrderOrigin()).isEqualTo(OrderOrigin.USER),
        () -> assertThat(result.order().getSystemReason())
            .isEqualTo(OrderSystemReasonPolicy.USER_WHOLE_CLOSE),
        () -> assertThat(result.order().getClientOrderId())
            .startsWith("__SYSTEM__:")
            .isNotEqualTo(callerRequestId));
    verify(orderRepository).save(result.order());
    verify(orderFillService).fillPerpetual(
        eq(result.order()), eq(account), any(), any(), anyInt(), anyString());
  }

  @Test
  void exactCloseLockWaitLoserReplaysAfterWinnerClosedThePosition() {
    ClosePositionRequest request = request("2.0000", "exact-race");
    OrderEntity existing = existingClose(request, OrderStatus.FILLED);
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, request.clientOrderId()))
        .thenReturn(Optional.empty(), Optional.of(existing));
    when(positionRepository.findOpenLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenAnswer(invocation -> {
          position.setLots(BigDecimal.ZERO);
          position.setStatus(PositionStatus.CLOSED);
          return List.of();
        });

    SystemCloseOrderService.CloseResult result = service().closeUser(
        userId, accountId, positionId, request);

    assertAll(
        () -> assertThat(result.replayed()).isTrue(),
        () -> assertThat(result.order()).isSameAs(existing),
        () -> assertThat(result.position().getStatus()).isEqualTo(PositionStatus.CLOSED));
    verify(marketBundleResolver).resolvePerp(eq(SYMBOL), any());
    verify(orderRepository, never()).save(any());
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
  }

  @Test
  void staleLockedAttemptResolvesProviderAgainOutsideTransactionAndWritesOnce() {
    FullFillResult firstFill = fill(new FullFillRequest(
        executionIntent(OrderSide.SELL, new BigDecimal("1.0000"), "stale-close"),
        SYMBOL,
        ProductType.LINEAR_PERP,
        OrderSide.SELL,
        FullFillExecutionPath.MARKET,
        new BigDecimal("1.0000"),
        null));
    doThrow(new BusinessException(ErrorCode.MARKET_DATA_STALE, "lock wait expired snapshot"))
        .doNothing()
        .when(fullFillCoordinator)
        .requireFresh(any(FullFillResult.class));
    when(fullFillCoordinator.execute(any(FullFillRequest.class), any(ExecutableMarketSnapshot.class)))
        .thenReturn(firstFill);

    SystemCloseOrderService.CloseResult result = service().closeUser(
        userId, accountId, positionId, request("1.0000", "stale-close"));

    assertThat(result.order().getStatus()).isEqualTo(OrderStatus.FILLED);
    verify(marketBundleResolver, times(2)).resolvePerp(eq(SYMBOL), any());
    verify(transactionExecutor, times(2)).execute(any());
    verify(accountRepository).save(account);
    verify(orderRepository).save(any(OrderEntity.class));
    verify(ledgerService).recordOrderHold(any(), any(), any(), anyString());
    verify(orderFillService).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
  }

  @Test
  void wholeUserCloseRetriesWithTheCurrentLockedQuantityInsteadOfOverClosing() {
    AtomicBoolean firstLock = new AtomicBoolean(true);
    when(positionRepository.findOpenLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenAnswer(invocation -> {
          if (firstLock.getAndSet(false)) {
            position.setLots(new BigDecimal("1.2500"));
          }
          return List.of(position);
        });

    SystemCloseOrderService.CloseResult result = service().closeUserWhole(
        userId,
        accountId,
        positionId,
        "whole-user-close");

    assertAll(
        () -> assertThat(result.order().getOrderOrigin()).isEqualTo(OrderOrigin.USER),
        () -> assertThat(result.order().getSystemReason())
            .isEqualTo(OrderSystemReasonPolicy.USER_WHOLE_CLOSE),
        () -> assertThat(OrderSystemReasonPolicy.external(result.order())).isNull(),
        () -> assertThat(result.order().getBaseQuantity()).isEqualByComparingTo("1.2500"),
        () -> assertThat(result.order().getOriginalQuantity()).isEqualByComparingTo("1.2500"),
        () -> assertThat(result.position().getStatus()).isEqualTo(PositionStatus.CLOSED),
        () -> assertThat(result.position().getLots()).isZero());
    verify(marketBundleResolver, times(2)).resolvePerp(eq(SYMBOL), any());
    verify(transactionExecutor, times(2)).execute(any());
    verify(fullFillCoordinator).execute(any(FullFillRequest.class), any(ExecutableMarketSnapshot.class));
    verify(orderFillService).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
  }

  @Test
  void wholeSystemClosePersistsOriginReasonAndDerivedIdempotency() {
    String requestId = "admin-force-42";
    AtomicBoolean lockedGuardRan = new AtomicBoolean();
    String expectedKey = OrderIdempotencyKeyPolicy.systemClose(
        accountId, positionId, OrderOrigin.ADMIN_FORCE_CLOSE, requestId);

    SystemCloseOrderService.CloseResult result = service().closeWhole(
        accountId,
        positionId,
        OrderOrigin.ADMIN_FORCE_CLOSE,
        "risk operator cleanup",
        requestId,
        () -> {
          assertThat(insideTransaction.get()).isTrue();
          verify(accountRepository).findByIdForUpdate(accountId);
          verify(positionRepository).findOpenLinearPerpBySymbolForUpdate(accountId, SYMBOL);
          verify(orderRepository).findActiveLinearPerpBySymbolForUpdate(accountId, SYMBOL);
          verify(orderRepository, never()).save(any());
          lockedGuardRan.set(true);
        });

    assertAll(
        () -> assertThat(result.order().getOrderOrigin()).isEqualTo(OrderOrigin.ADMIN_FORCE_CLOSE),
        () -> assertThat(result.order().getSystemReason()).isEqualTo("risk operator cleanup"),
        () -> assertThat(result.order().getClientOrderId()).isEqualTo(expectedKey),
        () -> assertThat(result.order().getIdempotencyKey()).isEqualTo(expectedKey),
        () -> assertThat(result.order().getOriginalQuantity()).isEqualByComparingTo("2.0000"),
        () -> assertThat(result.order().getBaseQuantity()).isEqualByComparingTo("2.0000"),
        () -> assertThat(lockedGuardRan).isTrue());
    verify(accountRepository, never()).findByIdAndUserIdForUpdate(any(), any());
  }

  @Test
  void userBatchCloseRechecksActiveAccountInsideTheMutationTransaction() {
    org.mockito.Mockito.doNothing()
        .doThrow(new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE, "cleanup gate acquired"))
        .when(demoExecutionGuard)
        .requireDemo(account, ProductType.LINEAR_PERP, SYMBOL);

    assertThatThrownBy(() -> service().closeWhole(
        accountId,
        positionId,
        OrderOrigin.BATCH_CLOSE,
        "USER_CLOSE_ALL",
        "batch-gate-race"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVE));

    verify(accountRepository).findByIdForUpdate(accountId);
    verify(orderRepository, never()).save(any());
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
  }

  @Test
  void liquidationFeeChargesOnlyRemainingCrossBalanceAndRecordsExactShortfall() {
    symbol.setLiquidationFeeRate(new BigDecimal("0.005"));
    account.setBalance(new BigDecimal("5.00000000"));
    account.setEquity(new BigDecimal("5.00000000"));
    account.setFreeMargin(new BigDecimal("5.00000000"));
    doAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      order.setStatus(OrderStatus.FILLED);
      order.setExecutionPrice(new BigDecimal("99.00000000"));
      order.setFilledQuantity(new BigDecimal("2.0000"));
      order.setRemainingQuantity(BigDecimal.ZERO);
      order.setHoldAmount(BigDecimal.ZERO);
      position.setStatus(PositionStatus.CLOSED);
      position.setMarginHeld(BigDecimal.ZERO);
      account.setBalance(new BigDecimal("0.50000000"));
      account.setEquity(new BigDecimal("0.50000000"));
      account.setUsedMargin(BigDecimal.ZERO);
      account.setFreeMargin(new BigDecimal("0.50000000"));
      return order;
    }).when(orderFillService).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());

    SystemCloseOrderService.CloseResult result = service().closeWhole(
        accountId,
        positionId,
        OrderOrigin.LIQUIDATION,
        "CROSS_MAINTENANCE_MARGIN",
        "liquidation-cross-shortfall");

    assertAll(
        () -> assertThat(result.order().getStatus()).isEqualTo(OrderStatus.FILLED),
        () -> assertThat(account.getBalance()).isEqualByComparingTo("0.00000000"),
        () -> assertThat(account.getEquity()).isEqualByComparingTo("0.00000000"),
        () -> assertThat(account.getFreeMargin()).isEqualByComparingTo("0.00000000"));
    verify(ledgerService).recordLiquidationFee(
        account,
        new BigDecimal("0.50000000"),
        positionId,
        "Liquidation fee charged");
    verify(ledgerService).recordBankruptcyShortfall(
        account,
        new BigDecimal("0.49000000"),
        result.order().getId(),
        "Liquidation bankruptcy shortfall");
    verify(auditLogService).record(
        null,
        "BANKRUPTCY_SHORTFALL",
        "ORDER",
        result.order().getId().toString(),
        "{\"amount\":0.49000000,\"positionId\":\"" + positionId + "\"}");
  }

  @Test
  void isolatedLiquidationConsumesOnlyItsSlotPoolAndCreditsTheGapAsShortfall() {
    symbol.setLiquidationFeeRate(new BigDecimal("0.005"));
    setting.setMarginMode(MarginMode.ISOLATED);
    position.setMarginMode(MarginMode.ISOLATED);
    position.setMarginHeld(new BigDecimal("3.00000000"));
    account.setBalance(new BigDecimal("100.00000000"));
    account.setEquity(new BigDecimal("100.00000000"));
    account.setFreeMargin(new BigDecimal("97.00000000"));
    doAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      order.setStatus(OrderStatus.FILLED);
      order.setExecutionPrice(new BigDecimal("99.00000000"));
      order.setFilledQuantity(new BigDecimal("2.0000"));
      order.setRemainingQuantity(BigDecimal.ZERO);
      order.setHoldAmount(BigDecimal.ZERO);
      position.setStatus(PositionStatus.CLOSED);
      position.setMarginHeld(BigDecimal.ZERO);
      account.setBalance(new BigDecimal("90.00000000"));
      account.setEquity(new BigDecimal("90.00000000"));
      account.setUsedMargin(BigDecimal.ZERO);
      account.setFreeMargin(new BigDecimal("90.00000000"));
      return order;
    }).when(orderFillService).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());

    SystemCloseOrderService.CloseResult result = service().closeWhole(
        accountId,
        positionId,
        OrderOrigin.LIQUIDATION,
        "ISOLATED_MAINTENANCE_MARGIN",
        "liquidation-isolated-shortfall");

    assertAll(
        () -> assertThat(account.getBalance()).isEqualByComparingTo("97.00000000"),
        () -> assertThat(account.getEquity()).isEqualByComparingTo("97.00000000"),
        () -> assertThat(account.getFreeMargin()).isEqualByComparingTo("97.00000000"));
    verify(ledgerService, never()).recordLiquidationFee(
        any(), any(), any(), anyString());
    verify(ledgerService).recordBankruptcyShortfall(
        account,
        new BigDecimal("7.99000000"),
        result.order().getId(),
        "Liquidation bankruptcy shortfall");
  }

  @Test
  void ordinaryUserKeyCannotPreemptTheDerivedSystemCloseNamespace() {
    String callerRequestId = "system-close-admin_force_close-" + positionId;
    ClosePositionRequest occupiedRequest = request("2.0000", callerRequestId);
    OrderEntity occupied = existingClose(occupiedRequest, OrderStatus.FILLED);
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, callerRequestId)).thenReturn(Optional.of(occupied));

    SystemCloseOrderService.CloseResult result = service().closeWhole(
        accountId,
        positionId,
        OrderOrigin.ADMIN_FORCE_CLOSE,
        "risk operator cleanup",
        callerRequestId);

    assertAll(
        () -> assertThat(result.replayed()).isFalse(),
        () -> assertThat(result.order().getClientOrderId())
            .startsWith("__SYSTEM__:")
            .isNotEqualTo(callerRequestId),
        () -> assertThat(result.order().getIdempotencyKey())
            .isEqualTo(result.order().getClientOrderId()));
    verify(orderRepository).save(result.order());
    verify(orderFillService).fillPerpetual(
        eq(result.order()), eq(account), any(), any(), anyInt(), anyString());
  }

  @Test
  void isolatedProtectionRejectsAggregateTask9QuantityBeforeEveryMutation() {
    setting.setMarginMode(MarginMode.ISOLATED);
    position.setMarginMode(MarginMode.ISOLATED);
    OrderEntity protection = protection(TriggerExecutionType.MARKET, null);
    protection.setMarginMode(MarginMode.ISOLATED);
    OrderEntity existing = task9IsolatedClose("1.5000", "0.01000000");
    prepareProtection(protection, protection);
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenReturn(List.of(existing, protection));

    assertThatThrownBy(() -> service().executeProtection(
        protection.getId(), ExecutableMarketSnapshot.from(bundle)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.REDUCE_ONLY_EXCEEDS_POSITION));

    assertAll(
        () -> assertThat(position.getLots()).isEqualByComparingTo("2.0000"),
        () -> assertThat(protection.getStatus()).isEqualTo(OrderStatus.PENDING_ACTIVATION),
        () -> assertThat(account.getUsedMargin()).isEqualByComparingTo("20"));
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(orderRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), anyString());
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
  }

  @Test
  void marketProtectionConvertsAndFillsTheSameCarrierWithMetadataIntact() {
    OrderEntity protection = protection(TriggerExecutionType.MARKET, null);
    prepareProtection(protection, protection);
    AtomicBoolean acceptedCarrierSaved = new AtomicBoolean();
    when(orderRepository.save(protection)).thenAnswer(invocation -> {
      assertThat(protection.getStatus()).isEqualTo(OrderStatus.ACCEPTED);
      acceptedCarrierSaved.set(true);
      return protection;
    });

    SystemCloseOrderService.CloseResult result = service().executeProtection(
        protection.getId(), ExecutableMarketSnapshot.from(bundle));

    assertAll(
        () -> assertThat(result.replayed()).isFalse(),
        () -> assertThat(result.order()).isSameAs(protection),
        () -> assertThat(acceptedCarrierSaved).isTrue(),
        () -> assertThat(protection.getOrderType()).isEqualTo(OrderType.MARKET),
        () -> assertThat(protection.getStatus()).isEqualTo(OrderStatus.FILLED),
        () -> assertThat(protection.getOrderOrigin()).isEqualTo(OrderOrigin.PROTECTIVE),
        () -> assertThat(protection.getProtectionType()).isEqualTo(ProtectionType.TAKE_PROFIT),
        () -> assertThat(protection.getTriggerPrice()).isEqualByComparingTo("100"),
        () -> assertThat(protection.getTriggerPriceType()).isEqualTo(TriggerPriceType.MARK_PRICE),
        () -> assertThat(protection.getTriggerExecutionType())
            .isEqualTo(TriggerExecutionType.MARKET),
        () -> assertThat(protection.getParentPositionId()).isEqualTo(positionId));
    ArgumentCaptor<FullFillRequest> fillRequest = ArgumentCaptor.forClass(FullFillRequest.class);
    verify(fullFillCoordinator).execute(
        fillRequest.capture(), eq(ExecutableMarketSnapshot.from(bundle)));
    assertAll(
        () -> assertThat(fillRequest.getValue().executionPath())
            .isEqualTo(FullFillExecutionPath.MARKET),
        () -> assertThat(fillRequest.getValue().executionIntent().orderType())
            .isEqualTo(OrderType.MARKET),
        () -> assertThat(fillRequest.getValue().executionIntent().reduceOnly()).isTrue(),
        () -> assertThat(fillRequest.getValue().requestedBaseQuantity())
            .isEqualByComparingTo("1.0000"));
    verify(orderRepository).findByIdForUpdate(protection.getId());
    verify(orderFillService).fillPerpetual(
        eq(protection),
        eq(account),
        any(FullFillResult.class),
        eq(new BigDecimal("100")),
        eq(10),
        eq("Protection order margin"));
  }

  @Test
  void marketableLimitProtectionConvertsSameCarrierAndFillsAsImmediateTaker() {
    OrderEntity protection = protection(TriggerExecutionType.LIMIT, new BigDecimal("99"));
    prepareProtection(protection, protection);
    AtomicBoolean acceptedCarrierSaved = new AtomicBoolean();
    when(orderRepository.save(protection)).thenAnswer(invocation -> {
      assertThat(protection.getStatus()).isEqualTo(OrderStatus.ACCEPTED);
      acceptedCarrierSaved.set(true);
      return protection;
    });

    SystemCloseOrderService.CloseResult result = service().executeProtection(
        protection.getId(), ExecutableMarketSnapshot.from(bundle));

    assertAll(
        () -> assertThat(result.replayed()).isFalse(),
        () -> assertThat(result.order()).isSameAs(protection),
        () -> assertThat(acceptedCarrierSaved).isTrue(),
        () -> assertThat(protection.getOrderType()).isEqualTo(OrderType.LIMIT),
        () -> assertThat(protection.getStatus()).isEqualTo(OrderStatus.FILLED),
        () -> assertThat(protection.getPrice()).isEqualByComparingTo("99"),
        () -> assertThat(protection.getRequestedPrice()).isEqualByComparingTo("99"),
        () -> assertThat(protection.getTriggerExecutionType())
            .isEqualTo(TriggerExecutionType.LIMIT),
        () -> assertThat(protection.getProtectionType()).isEqualTo(ProtectionType.TAKE_PROFIT),
        () -> assertThat(protection.getParentPositionId()).isEqualTo(positionId));
    ArgumentCaptor<FullFillRequest> fillRequest = ArgumentCaptor.forClass(FullFillRequest.class);
    verify(fullFillCoordinator).execute(
        fillRequest.capture(), eq(ExecutableMarketSnapshot.from(bundle)));
    assertAll(
        () -> assertThat(fillRequest.getValue().executionPath())
            .isEqualTo(FullFillExecutionPath.IMMEDIATE_LIMIT),
        () -> assertThat(fillRequest.getValue().limitPrice()).isEqualByComparingTo("99"),
        () -> assertThat(fillRequest.getValue().executionIntent().orderType())
            .isEqualTo(OrderType.LIMIT),
        () -> assertThat(fillRequest.getValue().executionIntent().reduceOnly()).isTrue());
  }

  @Test
  void nonMarketableLimitProtectionBecomesOnePendingGtcOrderWithRealHoldAndReplays() {
    OrderEntity protection = protection(TriggerExecutionType.LIMIT, new BigDecimal("102"));
    prepareProtection(protection, protection);
    AtomicBoolean pendingCarrierSaved = new AtomicBoolean();
    when(orderRepository.save(protection)).thenAnswer(invocation -> {
      assertThat(protection.getStatus()).isEqualTo(OrderStatus.PENDING);
      pendingCarrierSaved.set(true);
      return protection;
    });

    SystemCloseOrderService.CloseResult first = service().executeProtection(
        protection.getId(), ExecutableMarketSnapshot.from(bundle));
    SystemCloseOrderService.CloseResult replay = service().executeProtection(
        protection.getId(), ExecutableMarketSnapshot.from(bundle));

    assertAll(
        () -> assertThat(first.replayed()).isFalse(),
        () -> assertThat(replay.replayed()).isTrue(),
        () -> assertThat(replay.order()).isSameAs(protection),
        () -> assertThat(pendingCarrierSaved).isTrue(),
        () -> assertThat(protection.getOrderType()).isEqualTo(OrderType.LIMIT),
        () -> assertThat(protection.getStatus()).isEqualTo(OrderStatus.PENDING),
        () -> assertThat(protection.getTimeInForce()).isEqualTo(TimeInForce.GTC),
        () -> assertThat(protection.getHoldAmount()).isEqualByComparingTo("0.04950000"),
        () -> assertThat(protection.getHoldCurrency()).isEqualTo("USDT"),
        () -> assertThat(account.getUsedMargin()).isEqualByComparingTo("20.04950000"),
        () -> assertThat(account.getFreeMargin()).isEqualByComparingTo("49979.95050000"));
    verify(transactionExecutor).execute(any());
    verify(orderRepository).save(protection);
    verify(ledgerService).recordOrderHold(
        account,
        new BigDecimal("0.04950000"),
        protection.getId(),
        "Perpetual protection order margin reserved");
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
    verify(orderEventService).record(
        protection.getId(),
        "PROTECTION_TRIGGERED",
        OrderStatus.PENDING_ACTIVATION,
        OrderStatus.PENDING,
        null,
        "Protection LIMIT triggered and is resting");
  }

  @Test
  void restingLimitProtectionRejectsAnIncompleteWholeBundleBeforeTransaction() {
    OrderEntity protection = protection(TriggerExecutionType.LIMIT, new BigDecimal("102"));
    prepareProtection(protection, protection);
    ExecutableMarketSnapshot complete = ExecutableMarketSnapshot.from(bundle);
    ExecutableMarketSnapshot missingIndex = new ExecutableMarketSnapshot(
        complete.platformSymbol(),
        complete.productType(),
        complete.providerCode(),
        complete.providerSymbol(),
        complete.sourceMode(),
        complete.bid(),
        complete.ask(),
        complete.last(),
        complete.mark(),
        null,
        complete.asOf(),
        complete.expiresAt());

    assertThatThrownBy(() -> service().executeProtection(protection.getId(), missingIndex))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.MARKET_BUNDLE_INCOMPLETE));

    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), anyString());
  }

  @Test
  void triggerRaceLoserReplaysTheLockedFilledCarrierWithoutAnotherTrade() {
    OrderEntity preflight = protection(TriggerExecutionType.MARKET, null);
    OrderEntity committed = protection(TriggerExecutionType.MARKET, null);
    committed.setId(preflight.getId());
    committed.setStatus(OrderStatus.FILLED);
    committed.setOrderType(OrderType.MARKET);
    committed.setFilledQuantity(committed.getBaseQuantity());
    committed.setRemainingQuantity(BigDecimal.ZERO);
    when(orderRepository.selectById(preflight.getId())).thenReturn(preflight);
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenReturn(List.of());
    when(orderRepository.findByIdForUpdate(preflight.getId())).thenReturn(Optional.of(committed));

    SystemCloseOrderService.CloseResult result = service().executeProtection(
        preflight.getId(), ExecutableMarketSnapshot.from(bundle));

    assertAll(
        () -> assertThat(result.replayed()).isTrue(),
        () -> assertThat(result.order()).isSameAs(committed),
        () -> assertThat(result.position()).isSameAs(position),
        () -> assertThat(result.account()).isSameAs(account));
    verify(orderRepository).findByIdForUpdate(preflight.getId());
    verify(orderRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), anyString());
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
  }

  @Test
  void canceledProtectionIsNotReportedAsAnExecutedReplayDuringPreflight() {
    OrderEntity canceled = protection(TriggerExecutionType.MARKET, null);
    canceled.setStatus(OrderStatus.CANCELED);
    when(orderRepository.selectById(canceled.getId())).thenReturn(canceled);

    assertThatThrownBy(() -> service().executeProtection(
        canceled.getId(), ExecutableMarketSnapshot.from(bundle)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("PROTECTION_NOT_EXECUTABLE"));

    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
  }

  @Test
  void expiredProtectionThatWinsTheRowLockRejectsInsteadOfReplaying() {
    OrderEntity preflight = protection(TriggerExecutionType.MARKET, null);
    OrderEntity expired = protection(TriggerExecutionType.MARKET, null);
    expired.setId(preflight.getId());
    expired.setStatus(OrderStatus.EXPIRED);
    when(orderRepository.selectById(preflight.getId())).thenReturn(preflight);
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenReturn(List.of());
    when(orderRepository.findByIdForUpdate(preflight.getId())).thenReturn(Optional.of(expired));

    assertThatThrownBy(() -> service().executeProtection(
        preflight.getId(), ExecutableMarketSnapshot.from(bundle)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("PROTECTION_NOT_EXECUTABLE"));

    verify(transactionExecutor).execute(any());
    verify(orderRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), anyString());
    verify(orderFillService, never()).fillPerpetual(
        any(), any(), any(), any(), anyInt(), anyString());
  }

  private SystemCloseOrderService service() {
    SystemCloseOrderService service = new SystemCloseOrderService(
        orderRepository,
        positionRepository,
        accountRepository,
        settingRepository,
        symbolRepository,
        marketBundleResolver,
        quantityConversionService,
        instrumentRulesEngine,
        perpetualOrderRiskService,
        accountRiskSnapshotService,
        protectionOrderService,
        fullFillCoordinator,
        orderFillService,
        orderEntityFactory,
        orderEventService,
        ledgerService,
        demoExecutionGuard,
        transactionExecutor);
    service.setAuditLogService(auditLogService);
    return service;
  }

  private ClosePositionRequest request(String quantity, String clientOrderId) {
    return new ClosePositionRequest(
        new BigDecimal(quantity),
        QuantityUnit.BASE,
        clientOrderId);
  }

  private static Stream<Arguments> closeQuantityConversions() {
    return Stream.of(
        Arguments.of(QuantityUnit.BASE, "1.0000", "1.0000"),
        Arguments.of(QuantityUnit.QUOTE, "100.0000", "1.0000"),
        Arguments.of(QuantityUnit.CONTRACTS, "1", "1.0000"));
  }

  private OrderEntity existingClose(ClosePositionRequest request, OrderStatus status) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setUserId(userId);
    order.setAccountId(accountId);
    order.setSymbol(SYMBOL);
    order.setProductType(ProductType.LINEAR_PERP);
    order.setPositionMode(position.getPositionMode());
    order.setPositionSide(position.getPositionSide());
    order.setMarginMode(position.getMarginMode());
    order.setSide(position.getSide() == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY);
    order.setOrderType(OrderType.MARKET);
    order.setStatus(status);
    order.setReduceOnly(true);
    order.setOrderOrigin(OrderOrigin.USER);
    order.setQuantity(request.quantity());
    order.setOriginalQuantity(request.quantity());
    order.setBaseQuantity(request.quantity());
    order.setQuantityUnit(request.quantityUnit());
    order.setClientOrderId(request.clientOrderId());
    order.setIdempotencyKey(request.clientOrderId());
    order.setParentPositionId(positionId);
    return order;
  }

  private OrderEntity task9IsolatedClose(String remainingQuantity, String holdAmount) {
    OrderEntity order = existingClose(
        request(remainingQuantity, "task9-close-" + UUID.randomUUID()),
        OrderStatus.PENDING);
    order.setMarginMode(MarginMode.ISOLATED);
    order.setRemainingQuantity(new BigDecimal(remainingQuantity));
    order.setHoldAmount(new BigDecimal(holdAmount));
    order.setProtectionType(null);
    order.setParentPositionId(positionId);
    return order;
  }

  private void prepareProtection(OrderEntity preflight, OrderEntity locked) {
    when(orderRepository.selectById(preflight.getId())).thenReturn(preflight);
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(accountId, SYMBOL))
        .thenReturn(List.of(locked));
    when(orderRepository.findByIdForUpdate(preflight.getId())).thenReturn(Optional.of(locked));
  }

  private OrderEntity protection(
      TriggerExecutionType executionType,
      BigDecimal limitPrice
  ) {
    UUID id = UUID.randomUUID();
    OrderEntity order = new OrderEntity();
    order.setId(id);
    order.setUserId(userId);
    order.setAccountId(accountId);
    order.setSymbol(SYMBOL);
    order.setProductType(ProductType.LINEAR_PERP);
    order.setPositionMode(position.getPositionMode());
    order.setPositionSide(position.getPositionSide());
    order.setMarginMode(position.getMarginMode());
    order.setSide(OrderSide.SELL);
    order.setOrderType(OrderType.STOP_MARKET);
    order.setStatus(OrderStatus.PENDING_ACTIVATION);
    order.setQuantity(new BigDecimal("1.0000"));
    order.setOriginalQuantity(new BigDecimal("1.0000"));
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setBaseQuantity(new BigDecimal("1.0000"));
    order.setLots(new BigDecimal("1.0000"));
    order.setRemainingQuantity(new BigDecimal("1.0000"));
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setPrice(limitPrice);
    order.setRequestedPrice(limitPrice);
    order.setTimeInForce(TimeInForce.GTC);
    order.setReduceOnly(true);
    order.setOrderOrigin(OrderOrigin.PROTECTIVE);
    order.setTriggerPrice(new BigDecimal("100"));
    order.setTriggerPriceType(TriggerPriceType.MARK_PRICE);
    order.setTriggerExecutionType(executionType);
    order.setProtectionType(ProtectionType.TAKE_PROFIT);
    order.setParentPositionId(positionId);
    order.setClientOrderId("protection-" + id);
    order.setIdempotencyKey("protection-" + id);
    order.setHoldAmount(BigDecimal.ZERO);
    order.setHoldCurrency("USDT");
    order.setLeverage(10);
    order.setVersion(0L);
    return order;
  }

  private PerpetualOrderRiskService.OrderRisk closeRisk(OrderSide side, BigDecimal quantity) {
    BigDecimal price = side == OrderSide.BUY ? new BigDecimal("101") : new BigDecimal("99");
    BigDecimal fee = quantity.multiply(price).multiply(new BigDecimal("0.0005"))
        .setScale(8, java.math.RoundingMode.HALF_UP);
    return new PerpetualOrderRiskService.OrderRisk(
        account.getPositionMode(),
        position.getPositionSide(),
        setting.getMarginMode(),
        setting.getLeverage(),
        quantity,
        BigDecimal.ZERO,
        price,
        price,
        BigDecimal.ZERO.setScale(8),
        fee,
        BigDecimal.ZERO.setScale(8),
        fee,
        new BigDecimal("1000000"),
        "USDT");
  }

  private FullFillResult fill(FullFillRequest request) {
    BigDecimal price = request.side() == OrderSide.BUY
        ? bundle.ask()
        : bundle.bid();
    BigDecimal fee = request.requestedBaseQuantity()
        .multiply(price)
        .multiply(new BigDecimal("0.0005"));
    return new FullFillResult(
        price,
        NOW,
        request.requestedBaseQuantity(),
        BigDecimal.ZERO,
        new BigDecimal("0.0005"),
        fee,
        "USDT",
        LiquidityRole.TAKER,
        BigDecimal.ZERO,
        MarketSourceMode.PUBLIC_EXTERNAL,
        "binance-usdm",
        "BTCUSDT",
        NOW.minusSeconds(1),
        NOW.plusSeconds(30));
  }

  private com.fxplatform.trading.dto.request.CreateOrderRequest executionIntent(
      OrderSide side,
      BigDecimal quantity,
      String clientOrderId
  ) {
    return new com.fxplatform.trading.dto.request.CreateOrderRequest(
        accountId,
        SYMBOL,
        side,
        OrderType.MARKET,
        quantity,
        null,
        null,
        null,
        clientOrderId,
        clientOrderId,
        quantity,
        null,
        null,
        position.getPositionSide(),
        QuantityUnit.BASE,
        position.getMarginMode(),
        null,
        null,
        true,
        List.of());
  }

  private static TradingAccountEntity account(UUID userId, UUID accountId, PositionMode mode) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    account.setBaseCurrency("USDT");
    account.setBalance(new BigDecimal("50000"));
    account.setEquity(new BigDecimal("50000"));
    account.setUsedMargin(new BigDecimal("20"));
    account.setFreeMargin(new BigDecimal("49980"));
    account.setPositionMode(mode);
    return account;
  }

  private static PositionEntity position(
      UUID accountId,
      UUID positionId,
      PositionMode mode,
      PositionSide positionSide,
      OrderSide side
  ) {
    PositionEntity position = new PositionEntity();
    position.setId(positionId);
    position.setAccountId(accountId);
    position.setSymbol(SYMBOL);
    position.setProductType(ProductType.LINEAR_PERP);
    position.setPositionMode(mode);
    position.setPositionSide(positionSide);
    position.setMarginMode(MarginMode.CROSS);
    position.setSide(side);
    position.setLots(new BigDecimal("2.0000"));
    position.setOpenPrice(new BigDecimal("100"));
    position.setCurrentPrice(new BigDecimal("100"));
    position.setMarkPrice(new BigDecimal("100"));
    position.setNotional(new BigDecimal("200"));
    position.setInitialMargin(new BigDecimal("20"));
    position.setMaintenanceMargin(new BigDecimal("1"));
    position.setMarginHeld(new BigDecimal("20"));
    position.setFloatingPnl(BigDecimal.ZERO);
    position.setFundingPnl(BigDecimal.ZERO);
    position.setLeverage(10);
    position.setStatus(PositionStatus.OPEN);
    position.setVersion(0L);
    return position;
  }

  private static AccountSymbolSettingEntity setting(UUID accountId, MarginMode marginMode) {
    AccountSymbolSettingEntity setting = new AccountSymbolSettingEntity();
    setting.setAccountId(accountId);
    setting.setSymbol(SYMBOL);
    setting.setLeverage(10);
    setting.setMarginMode(marginMode);
    setting.setQuantityUnit(QuantityUnit.BASE);
    setting.setVersion(1L);
    return setting;
  }

  private static SymbolEntity symbol() {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(UUID.randomUUID());
    symbol.setSymbol(SYMBOL);
    symbol.setProductType(ProductType.LINEAR_PERP);
    symbol.setAssetClass("LINEAR_PERP");
    symbol.setBaseCurrency("BTC");
    symbol.setQuoteCurrency("USDT");
    symbol.setMarginAsset("USDT");
    symbol.setSettlementAsset("USDT");
    symbol.setContractSize(BigDecimal.ONE);
    symbol.setContractMultiplier(BigDecimal.ONE);
    symbol.setMinLot(new BigDecimal("0.0001"));
    symbol.setMaintenanceMarginRate(new BigDecimal("0.005"));
    symbol.setLeverage(100);
    symbol.setEnabled(true);
    symbol.setTradable(true);
    symbol.setQuoteEnabled(true);
    return symbol;
  }

  private static InstrumentRules rules() {
    return new InstrumentRules(
        SYMBOL,
        true,
        true,
        true,
        true,
        true,
        true,
        true,
        ProductType.LINEAR_PERP,
        new BigDecimal("0.01"),
        new BigDecimal("0.0001"),
        new BigDecimal("0.0001"),
        new BigDecimal("1000"),
        null,
        null,
        new BigDecimal("0.0001"),
        new BigDecimal("1000"),
        100,
        10,
        "USDT",
        "USDT",
        BigDecimal.ONE,
        BigDecimal.ONE,
        "DEFAULT",
        "ALWAYS",
        "NONE",
        "NORMAL");
  }

  private static PerpetualMarketBundle bundle() {
    return new PerpetualMarketBundle(
        SYMBOL,
        "BTCUSDT",
        "binance-usdm",
        MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("99"),
        new BigDecimal("101"),
        new BigDecimal("100"),
        new BigDecimal("100"),
        new BigDecimal("100"),
        null,
        List.of(),
        List.of(),
        NOW.minusSeconds(1),
        NOW.plusSeconds(30));
  }
}
