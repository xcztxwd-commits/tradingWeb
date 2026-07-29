package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.execution.FullFillExecutionPath;
import com.fxplatform.execution.FullFillRequest;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class PendingOrderExecutionProcessorTest {

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

  @BeforeEach
  void inlineTransactionExecutors() {
    org.mockito.Mockito.lenient().when(transactionExecutor.execute(any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    org.mockito.Mockito.lenient().when(transactionExecutor.executeJoined(any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
  }

  @Test
  void locksGroupInGlobalOrderThenClaimsWinnerCancelsPeerAndFillsAgainstOwner() {
    UUID accountId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    OrderEntity owner = leg(accountId, groupId, ownerId, OrderType.LIMIT);
    owner.setId(ownerId);
    owner.setPrice(new BigDecimal("49000"));
    owner.setRequestedPrice(new BigDecimal("49000"));
    owner.setHoldAmount(new BigDecimal("5103.06025500"));
    OrderEntity winner = leg(accountId, groupId, ownerId, OrderType.STOP_MARKET);
    winner.setTriggerPrice(new BigDecimal("51000"));
    List<OrderEntity> ordered = List.of(owner, winner).stream()
        .sorted(Comparator.comparing(OrderEntity::getId)).toList();
    TradingAccountEntity account = account(accountId);
    ExecutableMarketSnapshot snapshot = snapshot("52000");
    FullFillResult fill = fill("0.1", "52005.2");

    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(orderRepository.findByContingencyGroupIdForUpdate(groupId)).thenReturn(ordered);
    when(fullFillCoordinator.execute(any(FullFillRequest.class), eq(snapshot))).thenReturn(fill);
    when(orderRepository.claimPending(winner.getId())).thenReturn(1);
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

    boolean executed = processor().process(winner, snapshot);

    assertThat(executed).isTrue();
    assertThat(winner.getStatus()).isEqualTo(OrderStatus.WORKING);
    assertThat(owner.getStatus()).isEqualTo(OrderStatus.CANCELED);
    org.mockito.InOrder locks = org.mockito.Mockito.inOrder(
        accountRepository, walletBalanceRepository, spotPositionService, orderRepository);
    locks.verify(accountRepository).findByIdForUpdate(accountId);
    locks.verify(walletBalanceRepository).findByAccountIdForUpdate(accountId);
    locks.verify(spotPositionService).lockExisting(accountId);
    locks.verify(orderRepository).findByContingencyGroupIdForUpdate(groupId);
    verify(orderRepository).claimPending(winner.getId());
    verify(orderFillService).fill(
        winner, owner, account, fill, new BigDecimal("5203.12026000"),
        "Pending OCO order hold");
    verify(walletService).lockAvailableWithEntryType(
        accountId,
        "USDT",
        new BigDecimal("100.06000500"),
        "ORDER_TRIGGER",
        ownerId,
        "Pending Spot BUY trigger hold increased",
        "SPOT_ORDER_LOCK");
    ArgumentCaptor<FullFillRequest> request = ArgumentCaptor.forClass(FullFillRequest.class);
    verify(fullFillCoordinator).execute(request.capture(), eq(snapshot));
    assertThat(request.getValue().executionIntent().quantityUnit()).isEqualTo(QuantityUnit.BASE);
    assertThat(request.getValue().requestedBaseQuantity()).isEqualByComparingTo("0.1");
  }

  @Test
  void postLockMixedPeerStateIsRejectedWithoutFillOrMutation() {
    UUID accountId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    OrderEntity owner = leg(accountId, groupId, ownerId, OrderType.LIMIT);
    owner.setId(ownerId);
    owner.setStatus(OrderStatus.CANCELED);
    OrderEntity candidate = leg(accountId, groupId, ownerId, OrderType.STOP_MARKET);
    candidate.setTriggerPrice(new BigDecimal("51000"));
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account(accountId)));
    when(orderRepository.findByContingencyGroupIdForUpdate(groupId)).thenReturn(List.of(owner, candidate));

    assertThatThrownBy(() -> processor().processStrict(candidate, snapshot("52000")))
        .isInstanceOfSatisfying(com.fxplatform.common.exception.BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("OCO_GROUP_INCOMPLETE"));
    verify(transactionExecutor).executeJoined(any());
    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).claimPending(any());
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(orderFillService, never()).fill(any(), any(), any(), any(FullFillResult.class), any(), any());
  }

  @Test
  void strictProcessorRequiresTheOwningTransactionWithoutChangingTheWorkerEntry() throws Exception {
    assertThat(PendingOrderExecutionProcessor.class.getAnnotation(Transactional.class)).isNull();
    assertThat(PendingOrderExecutionProcessor.class
        .getMethod("process", OrderEntity.class, ExecutableMarketSnapshot.class)
        .getAnnotation(Transactional.class)).isNull();
    Transactional strict = PendingOrderExecutionProcessor.class
        .getMethod("processStrict", OrderEntity.class, ExecutableMarketSnapshot.class)
        .getAnnotation(Transactional.class);
    assertThat(strict).isNotNull();
    assertThat(strict.propagation()).isEqualTo(Propagation.MANDATORY);
  }

  @Test
  void dualTriggeredOcoCandidatesProduceOneWinnerAndTheSecondCandidateLoses() {
    UUID accountId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    OrderEntity limit = leg(accountId, groupId, ownerId, OrderType.LIMIT);
    limit.setId(ownerId);
    limit.setPrice(new BigDecimal("53000"));
    limit.setRequestedPrice(new BigDecimal("53000"));
    limit.setHoldAmount(new BigDecimal("5302.65000000"));
    OrderEntity stop = leg(accountId, groupId, ownerId, OrderType.STOP_MARKET);
    stop.setTriggerPrice(new BigDecimal("51000"));
    List<OrderEntity> ordered = List.of(limit, stop).stream()
        .sorted(Comparator.comparing(OrderEntity::getId)).toList();
    TradingAccountEntity account = account(accountId);
    ExecutableMarketSnapshot snapshot = snapshot("52000");
    FullFillResult fill = fill("0.1", "52005.2");
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(orderRepository.findByContingencyGroupIdForUpdate(groupId)).thenReturn(ordered);
    when(fullFillCoordinator.execute(any(FullFillRequest.class), eq(snapshot))).thenReturn(fill);
    when(orderRepository.claimPending(stop.getId())).thenReturn(1);
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    org.mockito.Mockito.doAnswer(invocation -> {
      ((OrderEntity) invocation.getArgument(0)).setStatus(OrderStatus.FILLED);
      limit.setHoldAmount(BigDecimal.ZERO);
      return null;
    }).when(orderFillService).fill(
        eq(stop), eq(limit), eq(account), eq(fill), any(BigDecimal.class), any(String.class));

    PendingOrderExecutionProcessor processor = processor();
    assertThat(processor.process(stop, snapshot)).isTrue();
    assertThat(processor.process(limit, snapshot)).isFalse();

    assertThat(stop.getStatus()).isEqualTo(OrderStatus.FILLED);
    assertThat(limit.getStatus()).isEqualTo(OrderStatus.CANCELED);
    verify(orderRepository, org.mockito.Mockito.times(1)).claimPending(stop.getId());
    verify(fullFillCoordinator, org.mockito.Mockito.times(1)).execute(any(), eq(snapshot));
    verify(orderFillService, org.mockito.Mockito.times(1)).fill(
        stop, limit, account, fill, new BigDecimal("5302.65000000"),
        "Pending OCO order hold");
  }

  @Test
  void injectedFillFailurePropagatesFromTheSingleExecutorAttempt() {
    UUID accountId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    OrderEntity owner = leg(accountId, groupId, ownerId, OrderType.LIMIT);
    owner.setId(ownerId);
    owner.setPrice(new BigDecimal("49000"));
    owner.setRequestedPrice(new BigDecimal("49000"));
    owner.setHoldAmount(new BigDecimal("5103.06025500"));
    OrderEntity winner = leg(accountId, groupId, ownerId, OrderType.STOP_MARKET);
    winner.setTriggerPrice(new BigDecimal("51000"));
    List<OrderEntity> ordered = List.of(owner, winner).stream()
        .sorted(Comparator.comparing(OrderEntity::getId)).toList();
    TradingAccountEntity account = account(accountId);
    ExecutableMarketSnapshot snapshot = snapshot("52000");
    FullFillResult fill = fill("0.1", "52005.2");
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(orderRepository.findByContingencyGroupIdForUpdate(groupId)).thenReturn(ordered);
    when(fullFillCoordinator.execute(any(FullFillRequest.class), eq(snapshot))).thenReturn(fill);
    when(orderRepository.claimPending(winner.getId())).thenReturn(1);
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    org.mockito.Mockito.doThrow(new IllegalStateException("injected settlement failure"))
        .when(orderFillService).fill(
            winner, owner, account, fill, new BigDecimal("5203.12026000"),
            "Pending OCO order hold");

    assertThatThrownBy(() -> processor().process(winner, snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("injected settlement failure");

    verify(transactionExecutor, org.mockito.Mockito.times(1)).execute(any());
    verify(orderRepository).claimPending(winner.getId());
    verify(orderEventService).record(
        owner.getId(), "ORDER_CANCELED", OrderStatus.PENDING, OrderStatus.CANCELED,
        null, "OCO peer canceled by winning leg");
    verify(orderEventService, never()).record(
        winner.getId(), "ORDER_FILLED", OrderStatus.WORKING, OrderStatus.FILLED,
        null, "OCO winning leg filled");
  }

  @Test
  void mixedAccountGroupIsRejectedBeforePricingClaimOrFill() {
    UUID accountId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    OrderEntity owner = leg(accountId, groupId, ownerId, OrderType.LIMIT);
    owner.setId(ownerId);
    owner.setPrice(new BigDecimal("49000"));
    owner.setRequestedPrice(new BigDecimal("49000"));
    owner.setHoldAmount(new BigDecimal("5103.06025500"));
    OrderEntity candidate = leg(accountId, groupId, ownerId, OrderType.STOP_MARKET);
    candidate.setTriggerPrice(new BigDecimal("51000"));
    owner.setAccountId(UUID.randomUUID());
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account(accountId)));
    when(orderRepository.findByContingencyGroupIdForUpdate(groupId))
        .thenReturn(List.of(owner, candidate));

    assertThatThrownBy(() -> processor().process(candidate, snapshot("52000")))
        .isInstanceOfSatisfying(com.fxplatform.common.exception.BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("OCO_GROUP_INCOMPLETE"));

    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(orderRepository, never()).claimPending(any());
    verify(orderFillService, never()).fill(
        any(), any(), any(), any(FullFillResult.class), any(), any());
  }

  @Test
  void ordinaryP0PendingOrderRequiresPositivePersistedCanonicalBaseQuantity() {
    UUID accountId = UUID.randomUUID();
    OrderEntity candidate = leg(accountId, null, null, OrderType.LIMIT);
    candidate.setOrderOrigin(com.fxplatform.trading.enums.OrderOrigin.USER);
    candidate.setHoldOwnerOrderId(null);
    candidate.setPrice(new BigDecimal("53000"));
    candidate.setRequestedPrice(new BigDecimal("53000"));
    candidate.setBaseQuantity(null);
    candidate.setQuantity(new BigDecimal("0.1000"));
    candidate.setLots(new BigDecimal("0.1000"));
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account(accountId)));
    when(orderRepository.findByIdForUpdate(candidate.getId())).thenReturn(Optional.of(candidate));

    assertThatThrownBy(() -> processor().process(candidate, snapshot("52000")))
        .isInstanceOfSatisfying(com.fxplatform.common.exception.BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("BAD_QUANTITY"));

    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(orderRepository, never()).claimPending(any());
    verify(orderFillService, never()).fill(
        any(), any(), any(), any(FullFillResult.class), any(), any());
  }

  @ParameterizedTest(name = "{0}: last={1}, hold={2}, fill={3}, fee-inclusive topUp={4}")
  @CsvSource({
      "exact,51000,5100.00000000,51000,2.55000000",
      "cross,52000,5100.00000000,52000,102.60000000",
      "gap,60000,5100.00000000,60000,903.00000000"
  })
  void buyStopMarketLocksActualQuoteSpendAndUsdtFeeBeforeClaimAndFill(
      String scenario,
      String last,
      String initialHold,
      String fillPrice,
      String expectedTopUp
  ) {
    UUID accountId = UUID.randomUUID();
    OrderEntity candidate = ordinaryBuyStopMarket(accountId, initialHold);
    TradingAccountEntity account = account(accountId);
    ExecutableMarketSnapshot snapshot = snapshot(last);
    FullFillResult fill = fill("0.1", fillPrice);
    BigDecimal topUp = new BigDecimal(expectedTopUp);
    BigDecimal expectedHold = new BigDecimal(initialHold).add(topUp).setScale(8);

    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(orderRepository.findByIdForUpdate(candidate.getId())).thenReturn(Optional.of(candidate));
    when(fullFillCoordinator.execute(any(FullFillRequest.class), eq(snapshot))).thenReturn(fill);
    when(orderRepository.claimPending(candidate.getId())).thenReturn(1);

    assertThat(processor().process(candidate, snapshot))
        .as(scenario)
        .isTrue();

    if (topUp.signum() == 0) {
      verify(walletService, never()).lockAvailableWithEntryType(
          any(), any(), any(), any(), any(), any(), any(String.class));
    } else {
      verify(walletService).lockAvailableWithEntryType(
          eq(accountId),
          eq("USDT"),
          eq(topUp),
          eq("ORDER_TRIGGER"),
          eq(candidate.getId()),
          any(String.class),
          eq("SPOT_ORDER_LOCK"));
      org.mockito.InOrder topUpBeforeClaim = org.mockito.Mockito.inOrder(
          fullFillCoordinator, walletService, orderRepository);
      topUpBeforeClaim.verify(fullFillCoordinator).requireFresh(fill);
      topUpBeforeClaim.verify(walletService).lockAvailableWithEntryType(
          eq(accountId),
          eq("USDT"),
          eq(topUp),
          eq("ORDER_TRIGGER"),
          eq(candidate.getId()),
          any(String.class),
          eq("SPOT_ORDER_LOCK"));
      topUpBeforeClaim.verify(orderRepository).claimPending(candidate.getId());
    }
    assertThat(candidate.getHoldAmount()).isEqualByComparingTo(expectedHold);
    verify(orderFillService).fill(
        candidate, candidate, account, fill, expectedHold, "Pending order hold");
  }

  @Test
  void insufficientBuyStopMarketTopUpLeavesOrderAndHoldUntouchedBeforeClaim() {
    UUID accountId = UUID.randomUUID();
    OrderEntity candidate = ordinaryBuyStopMarket(accountId, "5100.00000000");
    TradingAccountEntity account = account(accountId);
    ExecutableMarketSnapshot snapshot = snapshot("52000");
    FullFillResult fill = fill("0.1", "52000");
    BusinessException insufficient = new BusinessException(
        "INSUFFICIENT_BALANCE",
        "Available balance is insufficient for the trigger gap");

    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(orderRepository.findByIdForUpdate(candidate.getId())).thenReturn(Optional.of(candidate));
    when(fullFillCoordinator.execute(any(FullFillRequest.class), eq(snapshot))).thenReturn(fill);
    org.mockito.Mockito.doThrow(insufficient).when(walletService).lockAvailableWithEntryType(
        accountId,
        "USDT",
        new BigDecimal("102.60000000"),
        "ORDER_TRIGGER",
        candidate.getId(),
        "Pending Spot BUY trigger hold increased",
        "SPOT_ORDER_LOCK");

    assertThatThrownBy(() -> processor().process(candidate, snapshot))
        .isSameAs(insufficient);

    assertThat(candidate.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(candidate.getHoldAmount()).isEqualByComparingTo("5100.00000000");
    verify(orderRepository, never()).claimPending(any());
    verify(orderRepository, never()).save(any());
    verify(orderFillService, never()).fill(
        any(), any(), any(), any(FullFillResult.class), any(), any());
    verify(orderEventService, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void claimLossAfterBuyStopMarketTopUpRollsBackWalletLedgerAndOwnerHold() {
    UUID accountId = UUID.randomUUID();
    OrderEntity candidate = ordinaryBuyStopMarket(accountId, "5100.00000000");
    TradingAccountEntity account = account(accountId);
    ExecutableMarketSnapshot snapshot = snapshot("52000");
    FullFillResult fill = fill("0.1", "52000");
    AtomicReference<BigDecimal> available = new AtomicReference<>(
        new BigDecimal("1000.00000000"));
    AtomicReference<BigDecimal> locked = new AtomicReference<>(
        new BigDecimal("5100.00000000"));
    AtomicInteger triggerLedgerEntries = new AtomicInteger();

    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(orderRepository.findByIdForUpdate(candidate.getId())).thenReturn(Optional.of(candidate));
    when(fullFillCoordinator.execute(any(FullFillRequest.class), eq(snapshot))).thenReturn(fill);
    when(orderRepository.claimPending(candidate.getId())).thenReturn(0);
    org.mockito.Mockito.doAnswer(invocation -> {
      BigDecimal amount = invocation.getArgument(2);
      available.set(available.get().subtract(amount));
      locked.set(locked.get().add(amount));
      triggerLedgerEntries.incrementAndGet();
      return null;
    }).when(walletService).lockAvailableWithEntryType(
        accountId,
        "USDT",
        new BigDecimal("102.60000000"),
        "ORDER_TRIGGER",
        candidate.getId(),
        "Pending Spot BUY trigger hold increased",
        "SPOT_ORDER_LOCK");
    org.mockito.Mockito.doAnswer(invocation -> {
      BigDecimal availableBefore = available.get();
      BigDecimal lockedBefore = locked.get();
      int ledgerBefore = triggerLedgerEntries.get();
      BigDecimal holdBefore = candidate.getHoldAmount();
      try {
        return ((Supplier<?>) invocation.getArgument(0)).get();
      } catch (RuntimeException exception) {
        available.set(availableBefore);
        locked.set(lockedBefore);
        triggerLedgerEntries.set(ledgerBefore);
        candidate.setHoldAmount(holdBefore);
        throw exception;
      }
    }).when(transactionExecutor).execute(any());

    assertThat(processor().process(candidate, snapshot)).isFalse();

    assertThat(available.get()).isEqualByComparingTo("1000.00000000");
    assertThat(locked.get()).isEqualByComparingTo("5100.00000000");
    assertThat(triggerLedgerEntries).hasValue(0);
    assertThat(candidate.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(candidate.getHoldAmount()).isEqualByComparingTo("5100.00000000");
    verify(walletService).lockAvailableWithEntryType(
        accountId,
        "USDT",
        new BigDecimal("102.60000000"),
        "ORDER_TRIGGER",
        candidate.getId(),
        "Pending Spot BUY trigger hold increased",
        "SPOT_ORDER_LOCK");
    verify(orderRepository, never()).save(any());
    verify(orderFillService, never()).fill(
        any(), any(), any(), any(FullFillResult.class), any(), any());
    verify(orderEventService, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void lockedAccountUserMustMatchCandidateAndLockedGroupBeforePricing() {
    UUID accountId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    OrderEntity owner = leg(accountId, groupId, ownerId, OrderType.LIMIT);
    owner.setId(ownerId);
    owner.setPrice(new BigDecimal("49000"));
    owner.setRequestedPrice(new BigDecimal("49000"));
    owner.setHoldAmount(new BigDecimal("5103.06025500"));
    OrderEntity candidate = leg(accountId, groupId, ownerId, OrderType.STOP_MARKET);
    candidate.setTriggerPrice(new BigDecimal("51000"));
    TradingAccountEntity wrongOwnerAccount = account(accountId);
    wrongOwnerAccount.setUserId(UUID.randomUUID());
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(wrongOwnerAccount));

    assertThatThrownBy(() -> processor().process(candidate, snapshot("52000")))
        .isInstanceOfSatisfying(com.fxplatform.common.exception.AuthorizationException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("ACCOUNT_NOT_FOUND"));

    verify(walletBalanceRepository, never()).findByAccountIdForUpdate(any());
    verify(orderRepository, never()).findByContingencyGroupIdForUpdate(any());
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(orderRepository, never()).claimPending(any());
  }

  @Test
  void triggeredRestingSpotStopLimitActivatesOnceWithoutFill() {
    UUID accountId = UUID.randomUUID();
    OrderEntity candidate = stopLimit(
        accountId, OrderStatus.PENDING_ACTIVATION, OrderSide.BUY, "51990", "52000");
    ExecutableMarketSnapshot snapshot = snapshot("52000");
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account(accountId)));
    when(orderRepository.findByIdForUpdate(candidate.getId())).thenReturn(Optional.of(candidate));
    when(orderRepository.activateStopLimitPending(candidate.getId())).thenReturn(1);

    assertThat(processor().process(candidate, snapshot)).isFalse();

    assertThat(candidate.getStatus()).isEqualTo(OrderStatus.PENDING);
    verify(orderRepository).activateStopLimitPending(candidate.getId());
    verify(orderEventService).record(
        candidate.getId(), "ORDER_TRIGGERED", OrderStatus.PENDING_ACTIVATION,
        OrderStatus.PENDING, null, "Spot STOP_LIMIT activated and resting");
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(orderFillService, never()).fill(
        any(), any(), any(), any(FullFillResult.class), any(), any());
  }

  @Test
  void scanActivationStatusDriftCannotExecuteLockedRestingStopLimitWithOldSnapshot() {
    UUID accountId = UUID.randomUUID();
    OrderEntity scanned = stopLimit(
        accountId, OrderStatus.PENDING_ACTIVATION, OrderSide.BUY, "52000", "52000");
    OrderEntity locked = stopLimit(
        accountId, OrderStatus.PENDING, OrderSide.BUY, "52000", "52000");
    locked.setId(scanned.getId());
    ExecutableMarketSnapshot snapshot = snapshot("52000");
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account(accountId)));
    when(orderRepository.findByIdForUpdate(scanned.getId())).thenReturn(Optional.of(locked));

    assertThat(processor().process(scanned, snapshot)).isFalse();

    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(orderRepository, never()).claimPending(any());
    verify(orderRepository, never()).activateStopLimitPending(any());
    verify(orderRepository, never()).activateStopLimitWorking(any());
    verify(orderFillService, never()).fill(
        any(), any(), any(), any(FullFillResult.class), any(), any());
    verify(orderEventService, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void sameStatusStopLimitModificationCannotUseSnapshotForScannedExecutionContract() {
    UUID accountId = UUID.randomUUID();
    OrderEntity scanned = stopLimit(
        accountId, OrderStatus.PENDING_ACTIVATION, OrderSide.BUY, "51990", "52000");
    OrderEntity locked = stopLimit(
        accountId, OrderStatus.PENDING_ACTIVATION, OrderSide.BUY, "52000", "52000");
    locked.setId(scanned.getId());
    ExecutableMarketSnapshot snapshot = snapshot("52000");
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account(accountId)));
    when(orderRepository.findByIdForUpdate(scanned.getId())).thenReturn(Optional.of(locked));

    assertThat(processor().process(scanned, snapshot)).isFalse();

    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(orderRepository, never()).claimPending(any());
    verify(orderRepository, never()).activateStopLimitPending(any());
    verify(orderRepository, never()).activateStopLimitWorking(any());
    verify(orderFillService, never()).fill(
        any(), any(), any(), any(FullFillResult.class), any(), any());
    verify(orderEventService, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void triggeredMarketableSpotStopLimitUsesLimitIntentAndImmediateTakerPath() {
    UUID accountId = UUID.randomUUID();
    OrderEntity candidate = stopLimit(
        accountId, OrderStatus.PENDING_ACTIVATION, OrderSide.BUY, "52000", "52000");
    TradingAccountEntity account = account(accountId);
    ExecutableMarketSnapshot snapshot = snapshot("52000");
    FullFillResult fill = fill("0.1", "52000");
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(orderRepository.findByIdForUpdate(candidate.getId())).thenReturn(Optional.of(candidate));
    when(fullFillCoordinator.execute(any(FullFillRequest.class), eq(snapshot))).thenReturn(fill);
    when(orderRepository.activateStopLimitWorking(candidate.getId())).thenReturn(1);

    assertThat(processor().process(candidate, snapshot)).isTrue();

    ArgumentCaptor<FullFillRequest> request = ArgumentCaptor.forClass(FullFillRequest.class);
    verify(fullFillCoordinator).execute(request.capture(), eq(snapshot));
    assertThat(request.getValue().executionPath()).isEqualTo(FullFillExecutionPath.IMMEDIATE_LIMIT);
    assertThat(request.getValue().executionIntent().orderType()).isEqualTo(OrderType.LIMIT);
    assertThat(request.getValue().limitPrice()).isEqualByComparingTo("52000");
    assertThat(candidate.getOrderType()).isEqualTo(OrderType.STOP_LIMIT);
    verify(orderRepository).activateStopLimitWorking(candidate.getId());
    verify(orderEventService).record(
        candidate.getId(), "ORDER_TRIGGERED", OrderStatus.PENDING_ACTIVATION,
        OrderStatus.WORKING, null, "Spot STOP_LIMIT activated for immediate execution");
    verify(orderFillService).fill(
        candidate, candidate, account, fill, candidate.getHoldAmount(),
        "Pending order hold");
  }

  @Test
  void sellStopLimitTriggersAndTakesWhenLastAndBidEqualItsBoundaries() {
    UUID accountId = UUID.randomUUID();
    OrderEntity candidate = stopLimit(
        accountId, OrderStatus.PENDING_ACTIVATION, OrderSide.SELL, "51990", "52000");
    TradingAccountEntity account = account(accountId);
    ExecutableMarketSnapshot snapshot = snapshot("52000");
    FullFillResult fill = fill("0.1", "51990");
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(orderRepository.findByIdForUpdate(candidate.getId())).thenReturn(Optional.of(candidate));
    when(fullFillCoordinator.execute(any(FullFillRequest.class), eq(snapshot))).thenReturn(fill);
    when(orderRepository.activateStopLimitWorking(candidate.getId())).thenReturn(1);

    assertThat(processor().process(candidate, snapshot)).isTrue();

    ArgumentCaptor<FullFillRequest> request = ArgumentCaptor.forClass(FullFillRequest.class);
    verify(fullFillCoordinator).execute(request.capture(), eq(snapshot));
    assertThat(request.getValue().executionPath()).isEqualTo(FullFillExecutionPath.IMMEDIATE_LIMIT);
    assertThat(request.getValue().executionIntent().orderType()).isEqualTo(OrderType.LIMIT);
    assertThat(request.getValue().limitPrice()).isEqualByComparingTo("51990");
    verify(orderRepository).activateStopLimitWorking(candidate.getId());
    verify(orderFillService).fill(
        candidate, candidate, account, fill, candidate.getHoldAmount(), "Pending order hold");
  }

  @Test
  void sellStopLimitAtTriggerRestsWhenLimitIsAboveBid() {
    UUID accountId = UUID.randomUUID();
    OrderEntity candidate = stopLimit(
        accountId, OrderStatus.PENDING_ACTIVATION, OrderSide.SELL, "52000", "52000");
    ExecutableMarketSnapshot snapshot = snapshot("52000");
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account(accountId)));
    when(orderRepository.findByIdForUpdate(candidate.getId())).thenReturn(Optional.of(candidate));
    when(orderRepository.activateStopLimitPending(candidate.getId())).thenReturn(1);

    assertThat(processor().process(candidate, snapshot)).isFalse();

    assertThat(candidate.getStatus()).isEqualTo(OrderStatus.PENDING);
    verify(orderRepository).activateStopLimitPending(candidate.getId());
    verify(fullFillCoordinator).requireFresh(snapshot);
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(orderFillService, never()).fill(
        any(), any(), any(), any(FullFillResult.class), any(), any());
  }

  @Test
  void activatedSpotStopLimitLaterFillsAsRestingMakerIntent() {
    UUID accountId = UUID.randomUUID();
    OrderEntity candidate = stopLimit(
        accountId, OrderStatus.PENDING, OrderSide.BUY, "52000", "52000");
    TradingAccountEntity account = account(accountId);
    ExecutableMarketSnapshot snapshot = snapshot("53000");
    FullFillResult fill = fill("0.1", "52000");
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(orderRepository.findByIdForUpdate(candidate.getId())).thenReturn(Optional.of(candidate));
    when(fullFillCoordinator.execute(any(FullFillRequest.class), eq(snapshot))).thenReturn(fill);
    when(orderRepository.claimPending(candidate.getId())).thenReturn(1);

    assertThat(processor().process(candidate, snapshot)).isTrue();

    ArgumentCaptor<FullFillRequest> request = ArgumentCaptor.forClass(FullFillRequest.class);
    verify(fullFillCoordinator).execute(request.capture(), eq(snapshot));
    assertThat(request.getValue().executionPath()).isEqualTo(FullFillExecutionPath.RESTING_LIMIT);
    assertThat(request.getValue().executionIntent().orderType()).isEqualTo(OrderType.LIMIT);
    verify(orderRepository).claimPending(candidate.getId());
    verify(orderEventService, never()).record(
        eq(candidate.getId()), eq("ORDER_TRIGGERED"), any(), any(), any(), any());
    verify(orderFillService).fill(
        candidate, candidate, account, fill, candidate.getHoldAmount(), "Pending order hold");
  }

  @Test
  void restingStopLimitActivationCasLossLeavesActivationStateWithoutEventOrFill() {
    UUID accountId = UUID.randomUUID();
    OrderEntity candidate = stopLimit(
        accountId, OrderStatus.PENDING_ACTIVATION, OrderSide.BUY, "51990", "52000");
    ExecutableMarketSnapshot snapshot = snapshot("52000");
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account(accountId)));
    when(orderRepository.findByIdForUpdate(candidate.getId())).thenReturn(Optional.of(candidate));
    when(orderRepository.activateStopLimitPending(candidate.getId())).thenReturn(0);

    assertThat(processor().process(candidate, snapshot)).isFalse();

    assertThat(candidate.getStatus()).isEqualTo(OrderStatus.PENDING_ACTIVATION);
    verify(orderRepository).activateStopLimitPending(candidate.getId());
    verify(orderEventService, never()).record(any(), any(), any(), any(), any(), any());
    verify(orderFillService, never()).fill(
        any(), any(), any(), any(FullFillResult.class), any(), any());
  }

  @Test
  void immediateStopLimitActivationCasLossNeverFillsTheLosingWorker() {
    UUID accountId = UUID.randomUUID();
    OrderEntity candidate = stopLimit(
        accountId, OrderStatus.PENDING_ACTIVATION, OrderSide.BUY, "52000", "52000");
    ExecutableMarketSnapshot snapshot = snapshot("52000");
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account(accountId)));
    when(orderRepository.findByIdForUpdate(candidate.getId())).thenReturn(Optional.of(candidate));
    when(fullFillCoordinator.execute(any(FullFillRequest.class), eq(snapshot)))
        .thenReturn(fill("0.1", "52000"));
    when(orderRepository.activateStopLimitWorking(candidate.getId())).thenReturn(0);

    assertThat(processor().process(candidate, snapshot)).isFalse();

    assertThat(candidate.getStatus()).isEqualTo(OrderStatus.PENDING_ACTIVATION);
    verify(orderRepository).activateStopLimitWorking(candidate.getId());
    verify(orderEventService, never()).record(any(), any(), any(), any(), any(), any());
    verify(orderFillService, never()).fill(
        any(), any(), any(), any(FullFillResult.class), any(), any());
  }

  @Test
  void activatedStopLimitFillClaimLossNeverFillsTheLosingWorker() {
    UUID accountId = UUID.randomUUID();
    OrderEntity candidate = stopLimit(
        accountId, OrderStatus.PENDING, OrderSide.BUY, "52000", "52000");
    ExecutableMarketSnapshot snapshot = snapshot("53000");
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account(accountId)));
    when(orderRepository.findByIdForUpdate(candidate.getId())).thenReturn(Optional.of(candidate));
    when(fullFillCoordinator.execute(any(FullFillRequest.class), eq(snapshot)))
        .thenReturn(fill("0.1", "52000"));
    when(orderRepository.claimPending(candidate.getId())).thenReturn(0);

    assertThat(processor().process(candidate, snapshot)).isFalse();

    assertThat(candidate.getStatus()).isEqualTo(OrderStatus.PENDING);
    verify(orderRepository).claimPending(candidate.getId());
    verify(orderFillService, never()).fill(
        any(), any(), any(), any(FullFillResult.class), any(), any());
  }

  @Test
  void immediateSpotStopLimitFillFailureRestoresActualActivationSourceState() {
    UUID accountId = UUID.randomUUID();
    OrderEntity candidate = stopLimit(
        accountId, OrderStatus.PENDING_ACTIVATION, OrderSide.BUY, "52000", "52000");
    TradingAccountEntity account = account(accountId);
    ExecutableMarketSnapshot snapshot = snapshot("52000");
    FullFillResult fill = fill("0.1", "52000");
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(orderRepository.findByIdForUpdate(candidate.getId())).thenReturn(Optional.of(candidate));
    when(fullFillCoordinator.execute(any(FullFillRequest.class), eq(snapshot))).thenReturn(fill);
    when(orderRepository.activateStopLimitWorking(candidate.getId())).thenReturn(1);
    org.mockito.Mockito.doThrow(new IllegalStateException("fill persistence failed"))
        .when(orderFillService).fill(
            candidate, candidate, account, fill, candidate.getHoldAmount(), "Pending order hold");

    assertThatThrownBy(() -> processor().process(candidate, snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("fill persistence failed");

    assertThat(candidate.getStatus()).isEqualTo(OrderStatus.PENDING_ACTIVATION);
    verify(orderRepository).activateStopLimitWorking(candidate.getId());
  }

  @Test
  void untriggeredSpotStopLimitLeavesActivationStateWithoutClaimPricingOrEvent() {
    UUID accountId = UUID.randomUUID();
    OrderEntity candidate = stopLimit(
        accountId, OrderStatus.PENDING_ACTIVATION, OrderSide.BUY, "52000", "52000");
    ExecutableMarketSnapshot snapshot = snapshot("51999");
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account(accountId)));
    when(orderRepository.findByIdForUpdate(candidate.getId())).thenReturn(Optional.of(candidate));

    assertThat(processor().process(candidate, snapshot)).isFalse();

    assertThat(candidate.getStatus()).isEqualTo(OrderStatus.PENDING_ACTIVATION);
    assertThat(candidate.getHoldAmount()).isEqualByComparingTo("5202.60000000");
    verify(orderRepository, never()).activateStopLimitPending(any());
    verify(orderRepository, never()).activateStopLimitWorking(any());
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(orderEventService, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void staleRestingActivationSnapshotLeavesStopLimitAndHoldUntouched() {
    UUID accountId = UUID.randomUUID();
    OrderEntity candidate = stopLimit(
        accountId, OrderStatus.PENDING_ACTIVATION, OrderSide.BUY, "51990", "52000");
    ExecutableMarketSnapshot snapshot = snapshot("52000");
    BusinessException stale = new BusinessException("MARKET_DATA_STALE", "activation expired");
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account(accountId)));
    when(orderRepository.findByIdForUpdate(candidate.getId())).thenReturn(Optional.of(candidate));
    org.mockito.Mockito.doThrow(stale).when(fullFillCoordinator).requireFresh(snapshot);

    assertThatThrownBy(() -> processor().process(candidate, snapshot)).isSameAs(stale);

    assertThat(candidate.getStatus()).isEqualTo(OrderStatus.PENDING_ACTIVATION);
    assertThat(candidate.getHoldAmount()).isEqualByComparingTo("5202.60000000");
    verify(orderRepository, never()).activateStopLimitPending(any());
    verify(orderEventService, never()).record(any(), any(), any(), any(), any(), any());
    verify(orderFillService, never()).fill(
        any(), any(), any(), any(FullFillResult.class), any(), any());
  }

  @Test
  void protectiveStopLimitCannotEnterTheUserActivationStateMachine() {
    UUID accountId = UUID.randomUUID();
    OrderEntity candidate = stopLimit(
        accountId, OrderStatus.PENDING_ACTIVATION, OrderSide.BUY, "52000", "52000");
    candidate.setOrderOrigin(OrderOrigin.PROTECTIVE);
    candidate.setProtectionType(com.fxplatform.trading.enums.ProtectionType.STOP_LOSS);
    candidate.setParentPositionId(UUID.randomUUID());
    ExecutableMarketSnapshot snapshot = snapshot("52000");
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account(accountId)));
    when(orderRepository.findByIdForUpdate(candidate.getId())).thenReturn(Optional.of(candidate));

    assertThat(processor().process(candidate, snapshot)).isFalse();

    verify(orderRepository, never()).activateStopLimitPending(any());
    verify(orderRepository, never()).activateStopLimitWorking(any());
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(orderEventService, never()).record(any(), any(), any(), any(), any(), any());
  }

  private PendingOrderExecutionProcessor processor() {
    return new PendingOrderExecutionProcessor(
        orderRepository, accountRepository, demoExecutionGuard, walletBalanceRepository,
        walletService, spotPositionService, fullFillCoordinator, orderFillService,
        orderEventService, transactionExecutor);
  }

  private static OrderEntity leg(UUID accountId, UUID groupId, UUID ownerId, OrderType type) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setUserId(accountId);
    order.setAccountId(accountId);
    order.setSymbol("BTCUSDT");
    order.setProductType(ProductType.CRYPTO_SPOT);
    order.setSide(OrderSide.BUY);
    order.setOrderType(type);
    if (type == OrderType.LIMIT) {
      order.setPrice(new BigDecimal("49000"));
      order.setRequestedPrice(new BigDecimal("49000"));
    } else {
      order.setTriggerPrice(new BigDecimal("51000"));
      order.setTriggerPriceType(TriggerPriceType.LAST_PRICE);
      order.setTriggerExecutionType(TriggerExecutionType.MARKET);
    }
    order.setStatus(OrderStatus.PENDING);
    order.setLots(new BigDecimal("0.1"));
    order.setQuantity(new BigDecimal("0.1"));
    order.setOriginalQuantity(new BigDecimal("0.1"));
    order.setBaseQuantity(new BigDecimal("0.1"));
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setMarginMode(MarginMode.CASH);
    order.setPositionSide(PositionSide.BOTH);
    order.setOrderOrigin(OrderOrigin.OCO);
    order.setTimeInForce(TimeInForce.GTC);
    order.setReduceOnly(false);
    order.setContingencyGroupId(groupId);
    order.setHoldOwnerOrderId(ownerId);
    order.setHoldAmount(BigDecimal.ZERO);
    order.setHoldCurrency("USDT");
    return order;
  }

  private static OrderEntity ordinaryBuyStopMarket(UUID accountId, String holdAmount) {
    OrderEntity order = leg(accountId, null, null, OrderType.STOP_MARKET);
    order.setOrderOrigin(OrderOrigin.USER);
    order.setHoldOwnerOrderId(null);
    order.setHoldAmount(new BigDecimal(holdAmount));
    return order;
  }

  private static OrderEntity stopLimit(
      UUID accountId,
      OrderStatus status,
      OrderSide side,
      String price,
      String trigger
  ) {
    OrderEntity order = leg(accountId, null, null, OrderType.LIMIT);
    order.setOrderType(OrderType.STOP_LIMIT);
    order.setOrderOrigin(OrderOrigin.USER);
    order.setSide(side);
    order.setStatus(status);
    order.setPrice(new BigDecimal(price));
    order.setRequestedPrice(new BigDecimal(price));
    order.setTriggerPrice(new BigDecimal(trigger));
    order.setTriggerPriceType(TriggerPriceType.LAST_PRICE);
    order.setTriggerExecutionType(TriggerExecutionType.LIMIT);
    order.setHoldOwnerOrderId(null);
    order.setHoldAmount(new BigDecimal("5202.60000000"));
    return order;
  }

  private static TradingAccountEntity account(UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(accountId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    return account;
  }

  private static ExecutableMarketSnapshot snapshot(String last) {
    Instant now = Instant.now();
    return new ExecutableMarketSnapshot(
        "BTCUSDT", ProductType.CRYPTO_SPOT, "binance", "BTCUSDT",
        MarketSourceMode.PUBLIC_EXTERNAL, new BigDecimal("51990"),
        new BigDecimal("52000"), new BigDecimal(last), null, null,
        now.minusSeconds(1), now.plusSeconds(30));
  }

  private static FullFillResult fill(String quantity, String price) {
    Instant now = Instant.now();
    BigDecimal filledQuantity = new BigDecimal(quantity);
    BigDecimal filledPrice = new BigDecimal(price);
    BigDecimal fee = filledQuantity.multiply(filledPrice)
        .multiply(new BigDecimal("0.0005"))
        .setScale(8, java.math.RoundingMode.HALF_UP);
    return new FullFillResult(
        filledPrice, now, filledQuantity, BigDecimal.ZERO,
        new BigDecimal("0.0005"), fee, "USDT",
        LiquidityRole.TAKER, new BigDecimal("5.2"), MarketSourceMode.PUBLIC_EXTERNAL,
        "binance", "BTCUSDT", now.minusSeconds(1), now.plusSeconds(30));
  }
}
