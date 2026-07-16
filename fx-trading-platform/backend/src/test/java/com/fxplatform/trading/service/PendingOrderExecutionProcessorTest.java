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
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
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
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
  void inlineRequiresNewExecutor() {
    org.mockito.Mockito.lenient().when(transactionExecutor.execute(any()))
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
        winner, owner, account, fill, owner.getHoldAmount(), "Pending OCO order hold");
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

    assertThatThrownBy(() -> processor().process(candidate, snapshot("52000")))
        .isInstanceOfSatisfying(com.fxplatform.common.exception.BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("OCO_GROUP_INCOMPLETE"));
    verify(orderRepository, never()).claimPending(any());
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(orderFillService, never()).fill(any(), any(), any(), any(FullFillResult.class), any(), any());
  }

  @Test
  void processorDeclaresNoTransactionBoundaryOfItsOwn() {
    assertThat(PendingOrderExecutionProcessor.class.getAnnotation(Transactional.class)).isNull();
    assertThat(List.of(PendingOrderExecutionProcessor.class.getDeclaredMethods()))
        .allSatisfy(method -> assertThat(method.getAnnotation(Transactional.class)).isNull());
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
            winner, owner, account, fill, owner.getHoldAmount(), "Pending OCO order hold");

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
    return new FullFillResult(
        new BigDecimal(price), now, new BigDecimal(quantity), BigDecimal.ZERO,
        new BigDecimal("0.0005"), new BigDecimal("0.00005"), "BTC",
        LiquidityRole.TAKER, new BigDecimal("5.2"), MarketSourceMode.PUBLIC_EXTERNAL,
        "binance", "BTCUSDT", now.minusSeconds(1), now.plusSeconds(30));
  }
}
