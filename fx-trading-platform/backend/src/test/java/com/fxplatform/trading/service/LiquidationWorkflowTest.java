package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.dto.AccountSnapshot;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.account.service.AccountSnapshotService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.repository.RiskConfigRepository;
import com.fxplatform.risk.service.PerpetualRiskService.PositionRisk;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.service.PerpetualAccountRiskSnapshotService.AccountRiskProjection;
import com.fxplatform.trading.service.PerpetualAccountRiskSnapshotService.PositionFingerprint;
import com.fxplatform.trading.service.PerpetualAccountRiskSnapshotService.PositionProjection;
import com.fxplatform.trading.service.PerpetualAccountRiskSnapshotService.PreparedAccountRisk;
import com.fxplatform.trading.service.PerpetualAccountRiskSnapshotService.PreparedSymbolRisk;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LiquidationWorkflowTest {

  private static final String BTC = "BTCUSDT-PERP";
  private static final String ETH = "ETHUSDT-PERP";
  private static final String SOL = "SOLUSDT-PERP";
  private static final Instant NOW = Instant.parse("2026-07-12T10:00:00Z");

  @Mock AccountSnapshotService accountSnapshotService;
  @Mock TradingAccountRepository accountRepository;
  @Mock PositionRepository positionRepository;
  @Mock PositionService positionService;
  @Mock SymbolRepository symbolRepository;
  @Mock RiskConfigRepository riskConfigRepository;
  @Mock LedgerService ledgerService;
  @Mock QuoteService quoteService;
  @Mock WalletService walletService;
  @Mock DemoExecutionGuard demoExecutionGuard;
  @Mock TradingTransactionExecutor transactionExecutor;
  @Mock OrderRepository orderRepository;
  @Mock CancelAllOrderService cancelAllOrderService;
  @Mock PerpetualAccountRiskSnapshotService accountRiskSnapshotService;
  @Mock SystemCloseOrderService systemCloseOrderService;
  @Mock LiquidationSettlementService liquidationSettlementService;

  @InjectMocks LiquidationService liquidationService;

  private final AtomicBoolean insideMutationTransaction = new AtomicBoolean();

  @BeforeEach
  void runPreparedMutationsInTheTestTransactionBoundary() {
    lenient().when(accountRepository.findById(any(UUID.class)))
        .thenAnswer(invocation -> Optional.of(account(invocation.getArgument(0))));
    lenient().when(transactionExecutor.execute(any())).thenAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      Supplier<Object> mutation = invocation.getArgument(0);
      assertThat(insideMutationTransaction.compareAndSet(false, true)).isTrue();
      try {
        return mutation.get();
      } finally {
        insideMutationTransaction.set(false);
      }
    });
    lenient().when(riskConfigRepository.findFirstEnabledWithStopOutLevel())
        .thenReturn(Optional.empty());
  }

  @Test
  void isolatedCancellationRepreparesFreshMarkOutsideLocksAndStopsWhenSlotBecomesSafe() {
    UUID accountId = uuid(100);
    TradingAccountEntity account = account(accountId);
    PositionEntity isolated = position(uuid(1), accountId, BTC, MarginMode.ISOLATED);
    PreparedAccountRisk beforeCancel = prepared(accountId, List.of(isolated), "95", "120");
    PreparedAccountRisk afterCancel = prepared(accountId, List.of(isolated), "105", "80");
    stubWorkflow(
        account,
        List.of(isolated),
        List.of(beforeCancel, afterCancel),
        List.of(
            projection(List.of(projected(isolated, true, "95")), false),
            projection(List.of(projected(isolated, false, "105")), false)));

    int closed = liquidationService.scanAccount(accountId);

    assertThat(closed).isZero();
    verify(cancelAllOrderService).cancelRiskIncreasingSlot(accountId, isolated.getId());
    verify(cancelAllOrderService, never()).cancelActiveSlot(any(), any());
    verify(accountRiskSnapshotService, times(2)).prepare(eq(accountId), anyMap());
    verify(systemCloseOrderService, never()).closeWhole(
        any(), any(), any(), anyString(), anyString());
    verify(positionService, never()).closeSystemPosition(any(), any(), anyString());
    verify(quoteService, never()).freshQuote(anyString());

    InOrder workflow = inOrder(
        accountRiskSnapshotService,
        transactionExecutor,
        cancelAllOrderService);
    workflow.verify(accountRiskSnapshotService).prepare(eq(accountId), anyMap());
    workflow.verify(transactionExecutor).execute(any());
    workflow.verify(accountRiskSnapshotService).project(
        any(), anyList(), anyList(), eq(beforeCancel));
    workflow.verify(cancelAllOrderService).cancelRiskIncreasingSlot(accountId, isolated.getId());
    workflow.verify(accountRiskSnapshotService).prepare(eq(accountId), anyMap());
    workflow.verify(transactionExecutor).execute(any());
    workflow.verify(accountRiskSnapshotService).project(
        any(), anyList(), anyList(), eq(afterCancel));
  }

  @Test
  void restartReleasesTypedIsolatedGateWhenItsPositionIsAlreadyClosed() {
    UUID accountId = uuid(110);
    TradingAccountEntity account = account(accountId);
    account.setStatus(
        com.fxplatform.account.enums.AccountStatus.ISOLATED_LIQUIDATION_PENDING);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(
        accountId, PositionStatus.OPEN)).thenReturn(List.of());
    when(positionRepository.findOpenLinearPerpByAccountId(accountId)).thenReturn(List.of());

    assertThat(liquidationService.scanAccount(accountId)).isZero();

    assertThat(account.getStatus()).isEqualTo(
        com.fxplatform.account.enums.AccountStatus.ACTIVE);
    verify(accountRepository).save(account);
    verify(accountRiskSnapshotService, never()).prepare(any(), anyMap());
  }

  @Test
  void lockedProjectionStopsAnInFlightScanAfterAdminAcquiresTheGate() {
    UUID accountId = uuid(111);
    TradingAccountEntity account = account(accountId);
    PositionEntity cross = position(uuid(53), accountId, BTC, MarginMode.CROSS);
    PreparedAccountRisk prepared = prepared(accountId, List.of(cross), "95", "120");
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(
        accountId, PositionStatus.OPEN)).thenReturn(List.of());
    when(positionRepository.findOpenLinearPerpByAccountId(accountId)).thenReturn(List.of(cross));
    when(accountRiskSnapshotService.prepare(eq(accountId), anyMap())).thenAnswer(invocation -> {
      account.setStatus(com.fxplatform.account.enums.AccountStatus.RISK_REDUCTION_PENDING);
      return prepared;
    });

    assertThatThrownBy(() -> liquidationService.scanAccount(accountId))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("ACCOUNT_CLEANUP_PENDING"));

    verify(accountRiskSnapshotService, never()).project(any(), anyList(), anyList(), any());
    verify(systemCloseOrderService, never()).closeWhole(
        any(), any(), any(), anyString(), anyString());
  }

  @Test
  void isolatedSlotStillUnsafeAfterCancellationSubmitsExactlyOneWholeLiquidationClose() {
    UUID accountId = uuid(101);
    TradingAccountEntity account = account(accountId);
    PositionEntity isolated = position(uuid(2), accountId, BTC, MarginMode.ISOLATED);
    PreparedAccountRisk beforeCancel = prepared(accountId, List.of(isolated), "95", "120");
    PreparedAccountRisk afterCancel = prepared(accountId, List.of(isolated), "94", "120");
    PreparedAccountRisk afterSlotDrain = prepared(accountId, List.of(isolated), "93", "120");
    stubWorkflow(
        account,
        List.of(isolated),
        List.of(beforeCancel, afterCancel, afterSlotDrain),
        List.of(
            projection(List.of(projected(isolated, true, "95")), false),
            projection(List.of(projected(isolated, true, "94")), false),
            projection(List.of(projected(isolated, true, "93")), false)));
    doAnswer(invocation -> {
      assertThat(account.getStatus()).isEqualTo(
          com.fxplatform.account.enums.AccountStatus.ISOLATED_LIQUIDATION_PENDING);
      return null;
    }).when(cancelAllOrderService).cancelActiveSlot(accountId, isolated.getId());
    when(systemCloseOrderService.closeWhole(
        eq(accountId),
        eq(isolated.getId()),
        eq(OrderOrigin.LIQUIDATION),
        anyString(),
        anyString()))
        .thenAnswer(invocation -> {
          assertThat(account.getStatus()).isEqualTo(
              com.fxplatform.account.enums.AccountStatus.ISOLATED_LIQUIDATION_PENDING);
          return closeResult(account, isolated);
        });

    int closed = liquidationService.scanAccount(accountId);

    assertThat(closed).isEqualTo(1);
    assertThat(account.getStatus()).isEqualTo(
        com.fxplatform.account.enums.AccountStatus.ACTIVE);
    InOrder workflow = inOrder(cancelAllOrderService, accountRiskSnapshotService, systemCloseOrderService);
    workflow.verify(cancelAllOrderService).cancelRiskIncreasingSlot(accountId, isolated.getId());
    workflow.verify(accountRiskSnapshotService).prepare(eq(accountId), anyMap());
    workflow.verify(cancelAllOrderService).cancelActiveSlot(accountId, isolated.getId());
    workflow.verify(accountRiskSnapshotService).prepare(eq(accountId), anyMap());
    workflow.verify(systemCloseOrderService).closeWhole(
        eq(accountId),
        eq(isolated.getId()),
        eq(OrderOrigin.LIQUIDATION),
        anyString(),
        anyString());
    verify(systemCloseOrderService, times(1)).closeWhole(
        eq(accountId),
        eq(isolated.getId()),
        eq(OrderOrigin.LIQUIDATION),
        anyString(),
        anyString());
    verify(positionService, never()).closeSystemPosition(any(), any(), anyString());
    verify(quoteService, never()).freshQuote(anyString());
  }

  @Test
  void failedIsolatedCloseReleasesItsTypedGateWithoutTurningSafeCrossIntoRetryScope() {
    UUID accountId = uuid(109);
    TradingAccountEntity account = account(accountId);
    PositionEntity isolated = position(uuid(51), accountId, BTC, MarginMode.ISOLATED);
    PositionEntity safeCross = position(uuid(52), accountId, ETH, MarginMode.CROSS);
    List<PositionEntity> positions = List.of(isolated, safeCross);
    PreparedAccountRisk initial = prepared(accountId, positions, "95", "120");
    PreparedAccountRisk afterCancel = prepared(accountId, positions, "94", "120");
    PreparedAccountRisk afterDrain = prepared(accountId, positions, "93", "120");
    List<PositionProjection> projections = List.of(
        projected(isolated, true, "93"),
        projected(safeCross, false, "105"));
    stubWorkflow(
        account,
        positions,
        List.of(initial, afterCancel, afterDrain),
        List.of(
            projection(projections, false),
            projection(projections, false),
            projection(projections, false)));
    when(systemCloseOrderService.closeWhole(
        eq(accountId), eq(isolated.getId()), eq(OrderOrigin.LIQUIDATION),
        anyString(), anyString()))
        .thenThrow(new BusinessException("MARKET_DATA_STALE", "fresh mark unavailable"));

    assertThat(liquidationService.scanAccount(accountId)).isZero();

    assertThat(account.getStatus()).isEqualTo(
        com.fxplatform.account.enums.AccountStatus.ACTIVE);
    verify(systemCloseOrderService).closeWhole(
        eq(accountId), eq(isolated.getId()), eq(OrderOrigin.LIQUIDATION),
        anyString(), anyString());
    verify(systemCloseOrderService, never()).closeWhole(
        eq(accountId), eq(safeCross.getId()), any(), anyString(), anyString());
    verify(cancelAllOrderService, never()).cancelActivePerpetual(accountId);
  }

  @Test
  void crossCancellationRepreparesAggregateAndRestoresActiveWhenTheAccountBecomesSafe() {
    UUID accountId = uuid(102);
    TradingAccountEntity account = account(accountId);
    PositionEntity cross = position(uuid(3), accountId, BTC, MarginMode.CROSS);
    PreparedAccountRisk beforeCancel = prepared(accountId, List.of(cross), "95", "120");
    PreparedAccountRisk afterCancel = prepared(accountId, List.of(cross), "105", "80");
    stubWorkflow(
        account,
        List.of(cross),
        List.of(beforeCancel, afterCancel),
        List.of(
            projection(List.of(projected(cross, false, "95")), true),
            projection(List.of(projected(cross, false, "105")), false)));
    doAnswer(invocation -> {
      assertThat(account.getStatus().name()).isEqualTo("LIQUIDATION_PENDING");
      return null;
    }).when(cancelAllOrderService).cancelActivePerpetual(accountId);

    int closed = liquidationService.scanAccount(accountId);

    assertThat(closed).isZero();
    assertThat(account.getStatus().name()).isEqualTo("ACTIVE");
    verify(cancelAllOrderService).cancelActivePerpetual(accountId);
    verify(accountRiskSnapshotService, times(2)).prepare(eq(accountId), anyMap());
    verify(systemCloseOrderService, never()).closeWhole(
        any(), any(), any(), anyString(), anyString());
    verify(quoteService, never()).freshQuote(anyString());
  }

  @Test
  void crossLiquidationClosesEveryCrossSlotAndRestoresActiveAfterAllItemsSucceed() {
    UUID accountId = uuid(103);
    TradingAccountEntity account = account(accountId);
    PositionEntity first = position(uuid(10), accountId, BTC, MarginMode.CROSS);
    PositionEntity second = position(uuid(20), accountId, ETH, MarginMode.CROSS);
    PositionEntity isolated = position(uuid(30), accountId, SOL, MarginMode.ISOLATED);
    List<PositionEntity> deliberatelyUnsorted = List.of(second, isolated, first);
    stubUnsafeCross(account, deliberatelyUnsorted);
    when(systemCloseOrderService.closeWhole(
        eq(accountId), eq(first.getId()), eq(OrderOrigin.LIQUIDATION), anyString(), anyString()))
        .thenReturn(closeResult(account, first));
    when(systemCloseOrderService.closeWhole(
        eq(accountId), eq(second.getId()), eq(OrderOrigin.LIQUIDATION), anyString(), anyString()))
        .thenReturn(closeResult(account, second));

    int closed = liquidationService.scanAccount(accountId);

    assertThat(closed).isEqualTo(2);
    assertThat(account.getStatus().name()).isEqualTo("ACTIVE");
    verify(cancelAllOrderService).cancelActivePerpetual(accountId);
    InOrder closes = inOrder(systemCloseOrderService);
    closes.verify(systemCloseOrderService).closeWhole(
        eq(accountId), eq(first.getId()), eq(OrderOrigin.LIQUIDATION), anyString(), anyString());
    closes.verify(systemCloseOrderService).closeWhole(
        eq(accountId), eq(second.getId()), eq(OrderOrigin.LIQUIDATION), anyString(), anyString());
    verify(systemCloseOrderService, never()).closeWhole(
        eq(accountId), eq(isolated.getId()), any(), anyString(), anyString());
  }

  @Test
  void liquidationPendingRetryFinishesRemainingCrossEvenWhenFreshProjectionLooksSafe() {
    UUID accountId = uuid(105);
    TradingAccountEntity account = account(accountId);
    account.setStatus(com.fxplatform.account.enums.AccountStatus.LIQUIDATION_PENDING);
    PositionEntity remaining = position(uuid(40), accountId, BTC, MarginMode.CROSS);
    PreparedAccountRisk initial = prepared(accountId, List.of(remaining), "105", "120");
    PreparedAccountRisk afterCancel = prepared(accountId, List.of(remaining), "106", "120");
    stubWorkflow(
        account,
        List.of(remaining),
        List.of(initial, afterCancel),
        List.of(
            projection(List.of(projected(remaining, false, "105")), false),
            projection(List.of(projected(remaining, false, "106")), false)));
    when(systemCloseOrderService.closeWhole(
        eq(accountId), eq(remaining.getId()), eq(OrderOrigin.LIQUIDATION),
        anyString(), anyString()))
        .thenReturn(closeResult(account, remaining));

    int closed = liquidationService.scanAccount(accountId);

    assertThat(closed).isEqualTo(1);
    assertThat(account.getStatus().name()).isEqualTo("ACTIVE");
    verify(cancelAllOrderService).cancelActivePerpetual(accountId);
    verify(systemCloseOrderService).closeWhole(
        eq(accountId), eq(remaining.getId()), eq(OrderOrigin.LIQUIDATION),
        anyString(), anyString());
  }

  @Test
  void oneFailedCrossItemKeepsSuccessPendingAndStabilizesTheCommittedAccount() {
    UUID accountId = uuid(104);
    TradingAccountEntity account = account(accountId);
    PositionEntity first = position(uuid(11), accountId, BTC, MarginMode.CROSS);
    PositionEntity second = position(uuid(21), accountId, ETH, MarginMode.CROSS);
    PositionEntity isolated = position(uuid(31), accountId, SOL, MarginMode.ISOLATED);
    stubUnsafeCross(account, List.of(second, isolated, first));
    liquidationService.setLiquidationSettlementService(liquidationSettlementService);
    when(systemCloseOrderService.closeWhole(
        eq(accountId), eq(first.getId()), eq(OrderOrigin.LIQUIDATION), anyString(), anyString()))
        .thenAnswer(invocation -> {
          account.setBalance(decimal("-10"));
          account.setEquity(decimal("-10"));
          account.setFreeMargin(decimal("-10"));
          return closeResult(account, first);
        });
    when(systemCloseOrderService.closeWhole(
        eq(accountId), eq(second.getId()), eq(OrderOrigin.LIQUIDATION), anyString(), anyString()))
        .thenThrow(new BusinessException("MARKET_DATA_STALE", "fresh mark unavailable"));

    int closed = liquidationService.scanAccount(accountId);

    assertThat(closed).isEqualTo(1);
    assertThat(account.getStatus().name()).isEqualTo("LIQUIDATION_PENDING");
    verify(systemCloseOrderService).closeWhole(
        eq(accountId), eq(first.getId()), eq(OrderOrigin.LIQUIDATION), anyString(), anyString());
    verify(systemCloseOrderService).closeWhole(
        eq(accountId), eq(second.getId()), eq(OrderOrigin.LIQUIDATION), anyString(), anyString());
    verify(systemCloseOrderService, never()).closeWhole(
        eq(accountId), eq(isolated.getId()), any(), anyString(), anyString());
    verify(liquidationSettlementService).settleIfReady(accountId);

    ArgumentCaptor<String> stableRequestIds = ArgumentCaptor.forClass(String.class);
    verify(systemCloseOrderService, times(2)).closeWhole(
        eq(accountId),
        any(UUID.class),
        eq(OrderOrigin.LIQUIDATION),
        anyString(),
        stableRequestIds.capture());
    assertThat(stableRequestIds.getAllValues()).allSatisfy(
        value -> assertThat(value).isNotBlank());
  }

  private void stubUnsafeCross(
      TradingAccountEntity account,
      List<PositionEntity> positions
  ) {
    PreparedAccountRisk beforeCancel = prepared(account.getId(), positions, "95", "120");
    PreparedAccountRisk afterCancel = prepared(account.getId(), positions, "94", "120");
    List<PositionProjection> unsafeProjections = positions.stream()
        .map(position -> projected(position, false, "94"))
        .toList();
    stubWorkflow(
        account,
        positions,
        List.of(beforeCancel, afterCancel),
        List.of(
            projection(unsafeProjections, true),
            projection(unsafeProjections, true)));
    doAnswer(invocation -> {
      assertThat(account.getStatus().name()).isEqualTo("LIQUIDATION_PENDING");
      return null;
    }).when(cancelAllOrderService).cancelActivePerpetual(account.getId());
  }

  private void stubWorkflow(
      TradingAccountEntity account,
      List<PositionEntity> positions,
      List<PreparedAccountRisk> preparedSequence,
      List<AccountRiskProjection> projectionSequence
  ) {
    UUID accountId = account.getId();
    lenient().when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    lenient().when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    lenient().when(positionRepository.findOpenLinearPerpByAccountId(accountId))
        .thenReturn(positions);
    lenient().when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(positions);
    lenient().when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(
        accountId, PositionStatus.OPEN)).thenReturn(positions);
    lenient().when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of());
    lenient().when(accountSnapshotService.snapshot(accountId))
        .thenReturn(snapshot(accountId));
    for (PositionEntity position : positions) {
      lenient().when(symbolRepository.findBySymbol(position.getSymbol()))
          .thenReturn(Optional.of(symbol(position.getSymbol())));
    }
    lenient().when(quoteService.freshQuote(anyString())).thenAnswer(invocation ->
        quote(invocation.getArgument(0), "95"));

    AtomicInteger prepareIndex = new AtomicInteger();
    when(accountRiskSnapshotService.prepare(eq(accountId), anyMap())).thenAnswer(invocation -> {
      assertThat(insideMutationTransaction.get())
          .as("provider-backed prepare must stay outside mutation locks")
          .isFalse();
      int index = Math.min(prepareIndex.getAndIncrement(), preparedSequence.size() - 1);
      return preparedSequence.get(index);
    });
    AtomicInteger projectionIndex = new AtomicInteger();
    when(accountRiskSnapshotService.project(
        eq(account), anyList(), anyList(), any(PreparedAccountRisk.class)))
        .thenAnswer(invocation -> {
          assertThat(insideMutationTransaction.get())
              .as("projection must validate the locked state")
              .isTrue();
          int index = Math.min(projectionIndex.getAndIncrement(), projectionSequence.size() - 1);
          return projectionSequence.get(index);
        });
  }

  private static PreparedAccountRisk prepared(
      UUID accountId,
      List<PositionEntity> positions,
      String mark,
      String last
  ) {
    Map<String, PreparedSymbolRisk> symbols = new LinkedHashMap<>();
    for (PositionEntity position : positions) {
      symbols.putIfAbsent(position.getSymbol(), new PreparedSymbolRisk(
          position.getSymbol(),
          snapshot(position.getSymbol(), mark, last),
          decimal("0.005"),
          100));
    }
    List<PositionFingerprint> fingerprints = positions.stream()
        .sorted((left, right) -> left.getId().compareTo(right.getId()))
        .map(LiquidationWorkflowTest::fingerprint)
        .toList();
    return new PreparedAccountRisk(accountId, symbols, fingerprints);
  }

  private static PositionFingerprint fingerprint(PositionEntity position) {
    return new PositionFingerprint(
        position.getId(),
        position.getAccountId(),
        position.getVersion(),
        position.getSymbol(),
        position.getProductType(),
        position.getPositionMode(),
        position.getPositionSide(),
        position.getMarginMode(),
        position.getStatus(),
        position.getSide(),
        position.getLots(),
        position.getOpenPrice(),
        position.getMarginHeld(),
        position.getFundingPnl(),
        position.getLeverage());
  }

  private static PositionProjection projected(
      PositionEntity position,
      boolean isolatedLiquidatable,
      String mark
  ) {
    return new PositionProjection(
        position.getId(),
        position.getVersion(),
        decimal(mark),
        risk(isolatedLiquidatable, mark));
  }

  private static AccountRiskProjection projection(
      List<PositionProjection> positions,
      boolean crossLiquidatable
  ) {
    BigDecimal crossEquity = crossLiquidatable ? decimal("0.60000000") : decimal("0.60000001");
    return new AccountRiskProjection(
        crossEquity,
        decimal("10"),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        crossEquity,
        crossEquity.subtract(decimal("10")),
        decimal("0.50000000"),
        decimal("0.10000000"),
        crossLiquidatable,
        positions);
  }

  private static PositionRisk risk(boolean liquidatable, String mark) {
    BigDecimal threshold = decimal("0.60000000");
    BigDecimal isolatedEquity = liquidatable ? threshold : threshold.add(decimal("0.00000001"));
    return new PositionRisk(
        decimal("100"),
        decimal(mark),
        decimal("10"),
        decimal("0.50000000"),
        decimal(mark).subtract(decimal("100")),
        null,
        decimal("0.10000000"),
        decimal("10"),
        BigDecimal.ZERO,
        isolatedEquity,
        threshold,
        decimal("95"),
        liquidatable);
  }

  private static SystemCloseOrderService.CloseResult closeResult(
      TradingAccountEntity account,
      PositionEntity source
  ) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setAccountId(account.getId());
    order.setProductType(ProductType.LINEAR_PERP);
    order.setOrderOrigin(OrderOrigin.LIQUIDATION);
    order.setStatus(OrderStatus.FILLED);
    PositionEntity closed = position(
        source.getId(), source.getAccountId(), source.getSymbol(), source.getMarginMode());
    closed.setStatus(PositionStatus.CLOSED);
    return new SystemCloseOrderService.CloseResult(order, closed, account, false);
  }

  private static TradingAccountEntity account(UUID id) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(id);
    account.setBalance(decimal("100"));
    account.setEquity(decimal("100"));
    account.setUsedMargin(decimal("10"));
    account.setFreeMargin(decimal("90"));
    account.setLeverage(10);
    return account;
  }

  private static PositionEntity position(
      UUID id,
      UUID accountId,
      String symbol,
      MarginMode marginMode
  ) {
    PositionEntity position = new PositionEntity();
    position.setId(id);
    position.setAccountId(accountId);
    position.setSymbol(symbol);
    position.setProductType(ProductType.LINEAR_PERP);
    position.setPositionMode(PositionMode.ONE_WAY);
    position.setPositionSide(PositionSide.BOTH);
    position.setMarginMode(marginMode);
    position.setSide(OrderSide.BUY);
    position.setLots(BigDecimal.ONE);
    position.setOpenPrice(decimal("100"));
    position.setCurrentPrice(decimal("100"));
    position.setMarkPrice(decimal("100"));
    position.setMarginHeld(decimal("10"));
    position.setFundingPnl(BigDecimal.ZERO);
    position.setLeverage(10);
    position.setStatus(PositionStatus.OPEN);
    position.setVersion(0L);
    return position;
  }

  private static SymbolEntity symbol(String code) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(UUID.randomUUID());
    symbol.setSymbol(code);
    symbol.setProductType(ProductType.LINEAR_PERP);
    symbol.setAssetClass("CRYPTO_PERPETUAL");
    symbol.setMaintenanceMarginRate(decimal("0.005"));
    symbol.setLiquidationFeeRate(decimal("0.005"));
    symbol.setSettlementAsset("USDT");
    symbol.setMarginAsset("USDT");
    return symbol;
  }

  private static ExecutableMarketSnapshot snapshot(String symbol, String mark, String last) {
    return new ExecutableMarketSnapshot(
        symbol,
        ProductType.LINEAR_PERP,
        "binance-usdm",
        symbol.replace("-PERP", ""),
        MarketSourceMode.PUBLIC_EXTERNAL,
        decimal(last).subtract(BigDecimal.ONE),
        decimal(last).add(BigDecimal.ONE),
        decimal(last),
        decimal(mark),
        decimal(mark),
        NOW.minusSeconds(1),
        NOW.plusSeconds(30));
  }

  private static AccountSnapshot snapshot(UUID accountId) {
    return new AccountSnapshot(
        accountId,
        decimal("100"),
        BigDecimal.ZERO,
        decimal("100"),
        decimal("10"),
        decimal("0.5"),
        decimal("90"),
        decimal("1000"),
        "USDT");
  }

  private static QuoteResponse quote(String symbol, String mid) {
    return new QuoteResponse(
        "quote",
        symbol,
        decimal(mid).subtract(BigDecimal.ONE),
        decimal(mid).add(BigDecimal.ONE),
        decimal(mid),
        decimal("2"),
        "legacy-test",
        1L);
  }

  private static UUID uuid(long lowBits) {
    return new UUID(0L, lowBits);
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value).setScale(8, RoundingMode.HALF_UP);
  }
}
