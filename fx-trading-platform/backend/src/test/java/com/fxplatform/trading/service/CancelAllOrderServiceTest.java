package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.dto.response.BatchActionResponse;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CancelAllOrderServiceTest {

  private static final UUID ORDER_1 = id(1);
  private static final UUID ORDER_2 = id(2);
  private static final UUID ORDER_3 = id(3);
  private static final UUID ORDER_4 = id(4);
  private static final UUID ORDER_5 = id(5);

  @Mock private TradingAccountRepository accountRepository;
  @Mock private WalletBalanceRepository walletBalanceRepository;
  @Mock private PositionRepository positionRepository;
  @Mock private OrderRepository orderRepository;
  @Mock private WalletService walletService;
  @Mock private LedgerService ledgerService;
  @Mock private OrderEventService orderEventService;
  @Mock private DemoExecutionGuard demoExecutionGuard;
  @Mock private TradingTransactionExecutor transactionExecutor;
  @Mock private BatchActionRequestService batchActionRequestService;

  private UUID userId;
  private UUID accountId;
  private TradingAccountEntity account;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    accountId = UUID.randomUUID();
    account = demoAccount(userId, accountId);

    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId))
        .thenReturn(Optional.of(account));
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(walletBalanceRepository.findByAccountIdForUpdate(accountId)).thenReturn(List.of());
    when(positionRepository.findOpenByAccountIdForUpdate(accountId)).thenReturn(List.of());
    when(orderRepository.findActiveByAccountIdForUpdate(accountId)).thenReturn(List.of());
    when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of());
    when(orderRepository.save(any(OrderEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.save(any(TradingAccountEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(transactionExecutor.execute(any())).thenAnswer(
        invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    when(batchActionRequestService.beginCurrent(
        any(), anyString(), anyString(), anyString(), any()))
        .thenAnswer(invocation -> new BatchActionRequestService.Execution(
            UUID.randomUUID(),
            invocation.getArgument(0),
            invocation.getArgument(1),
            invocation.getArgument(2),
            invocation.getArgument(4),
            null));
  }

  @Test
  void cancelUserRejectsWrongOwnerBeforeDemoGuardLocksOrMutation() {
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().cancelUser(userId, accountId, "cancel-scope-1"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));

    verify(demoExecutionGuard, never()).requireDemoAccount(any());
    verify(walletBalanceRepository, never()).findByAccountIdForUpdate(any());
    verify(positionRepository, never()).findOpenByAccountIdForUpdate(any());
    verify(orderRepository, never()).findActiveByAccountIdForUpdate(any());
    verify(orderRepository, never()).save(any());
    verify(orderEventService, never()).record(any(), anyString(), any(), any(), any(), anyString());
  }

  @Test
  void cancelUserRejectsNonDemoAccountBeforeScopeLocksOrMutation() {
    doThrow(new BusinessException(ErrorCode.DEMO_ACCOUNT_REQUIRED, "Demo account required"))
        .when(demoExecutionGuard)
        .requireDemoAccount(account);

    assertThatThrownBy(() -> service().cancelUser(userId, accountId, "cancel-scope-2"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.DEMO_ACCOUNT_REQUIRED));

    verify(walletBalanceRepository, never()).findByAccountIdForUpdate(any());
    verify(positionRepository, never()).findOpenByAccountIdForUpdate(any());
    verify(orderRepository, never()).findActiveByAccountIdForUpdate(any());
    verify(orderRepository, never()).save(any());
  }

  @Test
  void liquidationCancellationStopsWhenAdminCleanupOwnsTheAccountGate() {
    account.setStatus(AccountStatus.RISK_REDUCTION_PENDING);

    assertThatThrownBy(() -> service().cancelActivePerpetual(accountId))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("ACCOUNT_CLEANUP_PENDING"));

    verify(walletBalanceRepository, never()).findByAccountIdForUpdate(any());
    verify(positionRepository, never()).findOpenByAccountIdForUpdate(any());
    verify(orderRepository, never()).findActiveLinearPerpByAccountIdForUpdate(any());
  }

  @Test
  void cancelUserUsesStableClientOrderSequenceInsteadOfRandomUuidOrder() {
    OrderEntity first = order(
        id(200), ProductType.LINEAR_PERP, MarginMode.CROSS,
        BigDecimal.ZERO, "USDT");
    first.setClientOrderId("scenario-client-1");
    OrderEntity second = order(
        id(100), ProductType.LINEAR_PERP, MarginMode.CROSS,
        BigDecimal.ZERO, "USDT");
    second.setClientOrderId("scenario-client-2");
    when(orderRepository.findActiveByAccountIdForUpdate(accountId))
        .thenReturn(List.of(second, first));

    BatchActionResponse response = service().cancelUser(
        userId, accountId, "stable-business-order");

    assertThat(response.items())
        .extracting(BatchActionResponse.Item::orderId)
        .containsExactly(first.getId(), second.getId());
    InOrder writes = inOrder(orderRepository);
    writes.verify(orderRepository).findActiveByAccountIdForUpdate(accountId);
    writes.verify(orderRepository).save(first);
    writes.verify(orderRepository).save(second);
  }

  @Test
  void cancelUserRejectsTheSameRequestIdWhenExpectedOrderIdsChange() {
    OrderEntity pending = order(
        ORDER_1, ProductType.CRYPTO_SPOT, MarginMode.CASH,
        BigDecimal.ZERO, "USDT");
    when(orderRepository.findActiveByAccountIdForUpdate(accountId))
        .thenReturn(List.of(pending), List.of());
    AtomicReference<String> fingerprintMaterial = new AtomicReference<>();
    when(batchActionRequestService.beginCurrent(
        eq(accountId), eq("CANCEL_ALL"), eq("cancel-fingerprint"), anyString(), any()))
        .thenAnswer(invocation -> {
          String material = invocation.getArgument(3);
          if (!fingerprintMaterial.compareAndSet(null, material)
              && !fingerprintMaterial.get().equals(material)) {
            throw new BusinessException(
                "BATCH_REQUEST_CONFLICT",
                "Batch request fingerprint conflicts with the original request");
          }
          @SuppressWarnings("unchecked")
          List<UUID> scope = invocation.getArgument(4);
          return new BatchActionRequestService.Execution(
              id(902), accountId, "CANCEL_ALL", "cancel-fingerprint", scope, null);
        });

    service().cancelUser(
        userId, accountId, "cancel-fingerprint", List.of(ORDER_1));

    assertThatThrownBy(() -> service().cancelUser(
        userId, accountId, "cancel-fingerprint", List.of(ORDER_2)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("BATCH_REQUEST_CONFLICT"));
    verify(orderRepository, times(1)).save(pending);
    verify(orderEventService, times(1)).record(
        eq(ORDER_1), eq("ORDER_CANCELED"), any(), eq(OrderStatus.CANCELED),
        eq(null), anyString());
  }

  @Test
  void accountScopeLocksInOrderAndReleasesEveryAuthorityHoldExactlyOnce() {
    OrderEntity spot = order(
        ORDER_1, ProductType.CRYPTO_SPOT, MarginMode.CASH,
        new BigDecimal("12.00000000"), "USDT");
    OrderEntity ocoPeer = order(
        ORDER_2, ProductType.CRYPTO_SPOT, MarginMode.CASH,
        BigDecimal.ZERO, "BTC");
    OrderEntity ocoOwner = order(
        ORDER_3, ProductType.CRYPTO_SPOT, MarginMode.CASH,
        new BigDecimal("0.40000000"), "BTC");
    UUID groupId = UUID.randomUUID();
    ocoPeer.setOrderOrigin(OrderOrigin.OCO);
    ocoPeer.setContingencyGroupId(groupId);
    ocoPeer.setHoldOwnerOrderId(ORDER_3);
    ocoOwner.setOrderOrigin(OrderOrigin.OCO);
    ocoOwner.setContingencyGroupId(groupId);
    ocoOwner.setHoldOwnerOrderId(ORDER_3);

    OrderEntity cross = order(
        ORDER_4, ProductType.LINEAR_PERP, MarginMode.CROSS,
        new BigDecimal("20.00000000"), "USDT");
    OrderEntity isolatedProtection = order(
        ORDER_5, ProductType.LINEAR_PERP, MarginMode.ISOLATED,
        new BigDecimal("15.00000000"), "USDT");
    isolatedProtection.setOrderOrigin(OrderOrigin.PROTECTIVE);
    isolatedProtection.setProtectionType(ProtectionType.TAKE_PROFIT);
    isolatedProtection.setParentPositionId(UUID.randomUUID());
    isolatedProtection.setReduceOnly(true);

    List<OrderEntity> unsorted = List.of(
        isolatedProtection, ocoOwner, spot, cross, ocoPeer);
    OrderEntity laterOrder = order(
        id(99), ProductType.LINEAR_PERP, MarginMode.CROSS,
        new BigDecimal("7.00000000"), "USDT");
    AtomicReference<BatchActionResponse> persisted = new AtomicReference<>();
    AtomicReference<List<UUID>> frozenScope = new AtomicReference<>();
    AtomicInteger begins = new AtomicInteger();
    when(batchActionRequestService.beginCurrent(
        eq(accountId), eq("CANCEL_ALL"), eq("cancel-all-replay-key"), anyString(), any()))
        .thenAnswer(invocation -> {
          @SuppressWarnings("unchecked")
          List<UUID> candidateScope = invocation.getArgument(4);
          if (begins.getAndIncrement() == 0) {
            frozenScope.set(candidateScope);
            return new BatchActionRequestService.Execution(
                id(901), accountId, "CANCEL_ALL", "cancel-all-replay-key",
                frozenScope.get(), null);
          }
          return new BatchActionRequestService.Execution(
              id(901), accountId, "CANCEL_ALL", "cancel-all-replay-key",
              frozenScope.get(), persisted.get());
        });
    org.mockito.Mockito.doAnswer(invocation -> {
      persisted.set(invocation.getArgument(1));
      return null;
    }).when(batchActionRequestService).completeCurrent(any(), any());
    AtomicBoolean insideTransaction = new AtomicBoolean();
    org.mockito.Mockito.doAnswer(invocation -> {
      insideTransaction.set(true);
      try {
        return ((Supplier<?>) invocation.getArgument(0)).get();
      } finally {
        insideTransaction.set(false);
      }
    }).when(transactionExecutor).execute(any());
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenAnswer(invocation -> {
      assertThat(insideTransaction.get()).isTrue();
      return Optional.of(account);
    });
    when(walletBalanceRepository.findByAccountIdForUpdate(accountId)).thenAnswer(invocation -> {
      assertThat(insideTransaction.get()).isTrue();
      return List.of();
    });
    when(positionRepository.findOpenByAccountIdForUpdate(accountId)).thenAnswer(invocation -> {
      assertThat(insideTransaction.get()).isTrue();
      return List.of();
    });
    when(orderRepository.findActiveByAccountIdForUpdate(accountId))
        .thenAnswer(invocation -> {
          assertThat(insideTransaction.get()).isTrue();
          return unsorted;
        })
        .thenAnswer(invocation -> {
          assertThat(insideTransaction.get()).isTrue();
          return List.of(laterOrder);
        });

    BatchActionResponse first = service().cancelUser(
        userId, accountId, "cancel-all-replay-key");
    BatchActionResponse replay = service().cancelUser(
        userId, accountId, "cancel-all-replay-key");

    assertThat(first.accountId()).isEqualTo(accountId);
    assertThat(first.requestId()).isEqualTo("cancel-all-replay-key");
    assertThat(first.items())
        .extracting(BatchActionResponse.Item::orderId)
        .containsExactly(ORDER_1, ORDER_2, ORDER_3, ORDER_4, ORDER_5);
    assertThat(first.items())
        .extracting(BatchActionResponse.Item::positionId)
        .containsOnlyNulls();
    assertThat(first.items())
        .extracting(BatchActionResponse.Item::status)
        .containsOnly(OrderStatus.CANCELED.name());
    assertThat(first.items())
        .extracting(BatchActionResponse.Item::errorCode)
        .containsOnlyNulls();
    assertThat(replay).isEqualTo(first);
    assertThat(laterOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(laterOrder.getHoldAmount()).isEqualByComparingTo("7.00000000");

    assertThat(unsorted).allSatisfy(order -> {
      assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
      assertThat(order.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
      assertThat(order.getRemainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    });
    assertThat(account.getUsedMargin()).isEqualByComparingTo("65.00000000");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("920.00000000");

    verify(walletService, times(1)).releaseLockedWithEntryType(
        eq(accountId), eq("USDT"), eq(new BigDecimal("12.00000000")),
        eq("ORDER"), eq(ORDER_1), anyString(), eq("SPOT_ORDER_RELEASE"));
    verify(walletService, times(1)).releaseLockedWithEntryType(
        eq(accountId), eq("BTC"), eq(new BigDecimal("0.40000000")),
        eq("ORDER"), eq(ORDER_3), anyString(), eq("SPOT_ORDER_RELEASE"));
    verify(ledgerService, times(1)).recordOrderRelease(
        eq(account), eq(new BigDecimal("20.00000000")), eq(ORDER_4), anyString());
    verify(ledgerService, times(1)).recordOrderRelease(
        eq(account), eq(new BigDecimal("15.00000000")), eq(ORDER_5), anyString());
    verify(orderRepository, times(5)).save(any(OrderEntity.class));
    verify(orderEventService, times(5)).record(
        any(), eq("ORDER_CANCELED"), any(), eq(OrderStatus.CANCELED),
        eq(null), anyString());
    verify(transactionExecutor, times(2)).execute(any());

    InOrder locks = inOrder(
        accountRepository, walletBalanceRepository, positionRepository, orderRepository);
    locks.verify(accountRepository).findByIdAndUserIdForUpdate(accountId, userId);
    locks.verify(walletBalanceRepository).findByAccountIdForUpdate(accountId);
    locks.verify(positionRepository).findOpenByAccountIdForUpdate(accountId);
    locks.verify(orderRepository).findActiveByAccountIdForUpdate(accountId);

    InOrder writes = inOrder(orderRepository);
    writes.verify(orderRepository).findActiveByAccountIdForUpdate(accountId);
    writes.verify(orderRepository).save(spot);
    writes.verify(orderRepository).save(ocoPeer);
    writes.verify(orderRepository).save(ocoOwner);
    writes.verify(orderRepository).save(cross);
    writes.verify(orderRepository).save(isolatedProtection);
  }

  @Test
  void injectedSecondWriteFailureRollsBackTheWholeAccountScope() {
    OrderEntity first = order(
        ORDER_1, ProductType.CRYPTO_SPOT, MarginMode.CASH, BigDecimal.ZERO, "USDT");
    OrderEntity second = order(
        ORDER_2, ProductType.CRYPTO_SPOT, MarginMode.CASH, BigDecimal.ZERO, "USDT");
    List<OrderEntity> orders = List.of(second, first);
    when(orderRepository.findActiveByAccountIdForUpdate(accountId)).thenReturn(orders);
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      if (ORDER_2.equals(order.getId())) {
        throw new IllegalStateException("injected database failure");
      }
      return order;
    });
    installRollbackTransaction(account, orders);

    assertThatThrownBy(() -> service().cancelUser(userId, accountId, "atomic-cancel"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("injected database failure");

    assertThat(orders)
        .extracting(OrderEntity::getStatus)
        .containsOnly(OrderStatus.PENDING);
    assertThat(orders)
        .extracting(OrderEntity::getRemainingQuantity)
        .containsOnly(new BigDecimal("1.00000000"));
    assertThat(orders)
        .extracting(OrderEntity::getCanceledAt)
        .containsOnlyNulls();
    verify(transactionExecutor, times(1)).execute(any());
  }

  @Test
  void cancelActiveSlotRemovesEveryOrderThatCouldSurviveAnIsolatedLiquidation() {
    UUID positionId = id(700);
    PositionEntity target = new PositionEntity();
    target.setId(positionId);
    target.setAccountId(accountId);
    target.setSymbol("BTCUSDT-PERP");
    target.setProductType(ProductType.LINEAR_PERP);
    target.setMarginMode(MarginMode.ISOLATED);
    target.setPositionMode(PositionMode.ONE_WAY);
    target.setPositionSide(PositionSide.BOTH);
    target.setSide(OrderSide.BUY);
    target.setStatus(PositionStatus.OPEN);
    target.setLots(new BigDecimal("1.00000000"));

    OrderEntity opening = order(
        ORDER_1, ProductType.LINEAR_PERP, MarginMode.ISOLATED, BigDecimal.ZERO, "USDT");
    opening.setSide(OrderSide.BUY);
    OrderEntity oppositeNonReduceOnly = order(
        ORDER_2, ProductType.LINEAR_PERP, MarginMode.ISOLATED, BigDecimal.ZERO, "USDT");
    oppositeNonReduceOnly.setSide(OrderSide.SELL);
    OrderEntity reduceOnly = order(
        ORDER_3, ProductType.LINEAR_PERP, MarginMode.ISOLATED, BigDecimal.ZERO, "USDT");
    reduceOnly.setSide(OrderSide.SELL);
    reduceOnly.setReduceOnly(true);
    reduceOnly.setParentPositionId(positionId);
    OrderEntity otherSlot = order(
        ORDER_4, ProductType.LINEAR_PERP, MarginMode.ISOLATED, BigDecimal.ZERO, "USDT");
    otherSlot.setSymbol("ETHUSDT-PERP");

    when(positionRepository.findOpenByAccountIdForUpdate(accountId)).thenReturn(List.of(target));
    when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of(otherSlot, reduceOnly, oppositeNonReduceOnly, opening));

    service().cancelActiveSlot(accountId, positionId);

    assertThat(List.of(opening, oppositeNonReduceOnly, reduceOnly))
        .allSatisfy(order -> assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED));
    assertThat(otherSlot.getStatus()).isEqualTo(OrderStatus.PENDING);
    verify(orderRepository, times(3)).save(any(OrderEntity.class));
  }

  @Test
  void repositoriesFreezeDeterministicAccountWalletPositionOrderLockQueries() throws Exception {
    String walletSql = sql(
        WalletBalanceRepository.class,
        "findByAccountIdForUpdate",
        UUID.class);
    String positionSql = sql(
        PositionRepository.class,
        "findOpenByAccountIdForUpdate",
        UUID.class);
    String orderSql = sql(
        OrderRepository.class,
        "findActiveByAccountIdForUpdate",
        UUID.class);

    assertThat(walletSql).contains("ORDER BY wallet_type, asset", "FOR UPDATE");
    assertThat(positionSql).contains("ORDER BY symbol, position_side, id", "FOR UPDATE");
    assertThat(orderSql).contains("ORDER BY id", "FOR UPDATE");
  }

  private CancelAllOrderService service() {
    return new CancelAllOrderService(
        accountRepository,
        walletBalanceRepository,
        positionRepository,
        orderRepository,
        walletService,
        ledgerService,
        orderEventService,
        demoExecutionGuard,
        transactionExecutor,
        batchActionRequestService);
  }

  private void installRollbackTransaction(
      TradingAccountEntity targetAccount,
      List<OrderEntity> orders
  ) {
    org.mockito.Mockito.doAnswer(invocation -> {
      List<OrderSnapshot> snapshots = orders.stream().map(OrderSnapshot::capture).toList();
      BigDecimal usedMargin = targetAccount.getUsedMargin();
      BigDecimal freeMargin = targetAccount.getFreeMargin();
      try {
        return ((Supplier<?>) invocation.getArgument(0)).get();
      } catch (RuntimeException exception) {
        snapshots.forEach(OrderSnapshot::restore);
        targetAccount.setUsedMargin(usedMargin);
        targetAccount.setFreeMargin(freeMargin);
        throw exception;
      }
    }).when(transactionExecutor).execute(any());
  }

  private static String sql(
      Class<?> repository,
      String method,
      Class<?>... parameterTypes
  ) throws Exception {
    Select annotation = repository.getMethod(method, parameterTypes).getAnnotation(Select.class);
    assertThat(annotation).isNotNull();
    return String.join(" ", annotation.value()).replaceAll("\\s+", " ").trim();
  }

  private static TradingAccountEntity demoAccount(UUID userId, UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    account.setBaseCurrency("USDT");
    account.setBalance(new BigDecimal("1000.00000000"));
    account.setEquity(new BigDecimal("1000.00000000"));
    account.setUsedMargin(new BigDecimal("100.00000000"));
    account.setFreeMargin(new BigDecimal("900.00000000"));
    return account;
  }

  private OrderEntity order(
      UUID id,
      ProductType productType,
      MarginMode marginMode,
      BigDecimal holdAmount,
      String holdCurrency
  ) {
    OrderEntity order = new OrderEntity();
    order.setId(id);
    order.setUserId(userId);
    order.setAccountId(accountId);
    order.setSymbol(productType == ProductType.CRYPTO_SPOT ? "BTCUSDT" : "BTCUSDT-PERP");
    order.setProductType(productType);
    order.setMarginMode(marginMode);
    order.setSide(OrderSide.BUY);
    order.setOrderType(OrderType.LIMIT);
    order.setStatus(OrderStatus.PENDING);
    order.setQuantity(new BigDecimal("1.00000000"));
    order.setBaseQuantity(new BigDecimal("1.00000000"));
    order.setRemainingQuantity(new BigDecimal("1.00000000"));
    order.setHoldAmount(holdAmount);
    order.setHoldCurrency(holdCurrency);
    return order;
  }

  private static UUID id(long suffix) {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
  }

  private record OrderSnapshot(
      OrderEntity order,
      OrderStatus status,
      Instant canceledAt,
      BigDecimal remainingQuantity,
      BigDecimal holdAmount
  ) {

    private static OrderSnapshot capture(OrderEntity order) {
      return new OrderSnapshot(
          order,
          order.getStatus(),
          order.getCanceledAt(),
          order.getRemainingQuantity(),
          order.getHoldAmount());
    }

    private void restore() {
      order.setStatus(status);
      order.setCanceledAt(canceledAt);
      order.setRemainingQuantity(remainingQuantity);
      order.setHoldAmount(holdAmount);
    }
  }
}
