package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.execution.ExecutionAdapter;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutionMode;
import com.fxplatform.execution.ExecutionProperties;
import com.fxplatform.execution.ExecutionResult;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.execution.FullFillRequest;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.model.SpotMarketBundle;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.dto.request.UpdateOrderRequest;
import com.fxplatform.trading.dto.response.OrderEventResponse;
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.entity.OrderEventEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.entity.TradeEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

  @Mock
  private OrderRepository orderRepository;

  @Mock
  private TradeRepository tradeRepository;

  @Mock
  private PositionRepository positionRepository;

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private RiskCheckService riskCheckService;

  @Mock
  private ExecutionAdapter executionAdapter;

  @Mock
  private LedgerService ledgerService;

  @Mock
  private WalletService walletService;

  @Mock
  private DemoExecutionGuard demoExecutionGuard;

  @Mock
  private WalletBalanceRepository walletBalanceRepository;

  @Mock
  private SpotPositionService spotPositionService;

  @Mock
  private MarketBundleResolver marketBundleResolver;

  @Mock
  private SymbolRepository symbolRepository;

  @Mock
  private FullFillCoordinator fullFillCoordinator;

  @Mock
  private TradingTransactionExecutor transactionExecutor;

  @BeforeEach
  void lockedAccountLookupUsesTheOwnedAccountFixture() {
    org.mockito.Mockito.lenient()
        .when(accountRepository.findByIdAndUserIdForUpdate(any(UUID.class), any(UUID.class)))
        .thenAnswer(invocation -> accountRepository.findByIdAndUserId(
            invocation.getArgument(0), invocation.getArgument(1)));
    org.mockito.Mockito.lenient()
        .when(transactionExecutor.execute(any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
  }

  @Test
  void p0MarketGuardFailureStopsBeforeBundleResolution() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = p0MarketOrder(accountId, "guard-first");
    TradingAccountEntity account = demoAccount(userId, accountId);

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "guard-first"))
        .thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "guard-first"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    org.mockito.Mockito.doThrow(new BusinessException("SYMBOL_NOT_ALLOWED", "blocked"))
        .when(demoExecutionGuard)
        .requireDemo(account, ProductType.CRYPTO_SPOT, "BTCUSDT");

    assertThatThrownBy(() -> orderService(org.mockito.Mockito.mock(OrderEventService.class))
        .createOrder(principal, request))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("SYMBOL_NOT_ALLOWED"));

    verify(marketBundleResolver, never()).resolveSpot(any(), any());
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(transactionExecutor, never()).execute(any());
  }

  @Test
  void configuredNonP0ProductUsesCatalogTypeAtGuard() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = advancedOrder(
        accountId, "EURUSD", OrderType.MARKET, TimeInForce.GTC, false,
        "non-p0-product");
    TradingAccountEntity account = demoAccount(userId, accountId);
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol("EURUSD");
    symbol.setProductType(ProductType.FX_MARGIN);
    ExecutionProperties properties = new ExecutionProperties();
    properties.setMode(ExecutionMode.DEMO);

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(symbol));

    assertThatThrownBy(() -> orderServiceWithSymbolRepository(
        org.mockito.Mockito.mock(OrderEventService.class),
        new DemoExecutionGuard(properties, symbolRepository)).createOrder(principal, request))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("PRODUCT_NOT_ALLOWED"));

    verify(marketBundleResolver, never()).resolveSpot(any(), any());
    verify(fullFillCoordinator, never()).execute(any(), any());
    verify(transactionExecutor, never()).execute(any());
  }

  @Test
  void nonP0AdvancedContractsRejectAfterReplayLookupWithoutLegacyMutation() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    List<CreateOrderRequest> unsupported = List.of(
        advancedOrder(
            accountId, "EURUSD", OrderType.STOP_LIMIT, TimeInForce.GTC, false,
            "non-p0-stop-limit"),
        advancedOrder(
            accountId, "EURUSD", OrderType.LIMIT, TimeInForce.IOC, false,
            "non-p0-ioc"),
        advancedOrder(
            accountId, "EURUSD", OrderType.LIMIT, TimeInForce.FOK, false,
            "non-p0-fok"),
        advancedOrder(
            accountId, "EURUSD", OrderType.LIMIT, TimeInForce.GTC, true,
            "non-p0-post-only"));
    OrderService service = orderService(org.mockito.Mockito.mock(OrderEventService.class));

    for (CreateOrderRequest request : unsupported) {
      assertThatThrownBy(() -> service.createOrder(principal, request))
          .isInstanceOfSatisfying(
              BusinessException.class,
              exception -> assertThat(exception.getCode()).isEqualTo("INVALID_ORDER_TYPE"));
    }

    verify(accountRepository, never()).findByIdAndUserId(any(), any());
    verify(riskCheckService, never()).checkOrder(any(), any());
    verify(executionAdapter, never()).execute(any());
    verify(orderRepository, never()).save(any());
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verifyNoInteractions(ledgerService, walletService);
  }

  @Test
  void p0SpotAdvancedContractFailsClosedWhenSimpleAuthorityIsUnavailable() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = advancedOrder(
        accountId, "BTCUSDT", OrderType.STOP_LIMIT, TimeInForce.GTC, false,
        "p0-authority-unavailable");

    assertThatThrownBy(() -> orderService(org.mockito.Mockito.mock(OrderEventService.class))
        .createOrder(principal, request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("EXECUTION_UNAVAILABLE"));

    verify(accountRepository, never()).findByIdAndUserId(any(), any());
    verify(riskCheckService, never()).checkOrder(any(), any());
    verify(orderRepository, never()).save(any());
    verifyNoInteractions(ledgerService, walletService);
  }

  @Test
  void createOrderTranslatesStorageFailuresToTheCanonicalExecutionError() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = p0MarketOrder(accountId, "storage-failure");
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, "storage-failure"))
        .thenThrow(new UncategorizedSQLException(
            "create order", "INSERT INTO trading.orders", new SQLException("injected")));

    assertThatThrownBy(() -> orderService(org.mockito.Mockito.mock(OrderEventService.class))
        .createOrder(principal, request))
        .isInstanceOfSatisfying(BusinessException.class, exception -> {
          assertThat(exception.getCode()).isEqualTo("EXECUTION_UNAVAILABLE");
          assertThat(exception.getMessage()).doesNotContain("trading.orders", "injected");
        });
  }

  @Test
  void p0MarketRetryResolvesOutsideTransactionAndWritesOnlyTheFreshAttempt() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = p0MarketOrder(accountId, "stale-retry");
    TradingAccountEntity account = demoAccount(userId, accountId);
    AtomicBoolean insideMutation = new AtomicBoolean();
    AtomicBoolean freshResultGranted = new AtomicBoolean();

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "stale-retry"))
        .thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "stale-retry"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class))).thenReturn(BigDecimal.ZERO);
    when(riskCheckService.resolveEffectiveLeverage(account, request)).thenReturn(1);
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any()))
        .thenAnswer(invocation -> {
          assertThat(insideMutation.get()).isFalse();
          return spotBundle("binance", Instant.now().plusSeconds(2));
        });
    when(fullFillCoordinator.execute(any(FullFillRequest.class), any(ExecutableMarketSnapshot.class)))
        .thenThrow(new BusinessException("MARKET_DATA_STALE", "lock wait expired snapshot"))
        .thenAnswer(invocation -> {
          freshResultGranted.set(true);
          return fullFill("0.10", "100.0100");
        });
    org.mockito.Mockito.doAnswer(invocation -> {
      insideMutation.set(true);
      try {
        return ((Supplier<?>) invocation.getArgument(0)).get();
      } finally {
        insideMutation.set(false);
      }
    }).when(transactionExecutor).execute(any());
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      assertThat(freshResultGranted.get()).isTrue();
      OrderEntity order = invocation.getArgument(0);
      if (order.getId() == null) {
        order.setId(UUID.randomUUID());
      }
      return order;
    });

    OrderResponse response = orderService(org.mockito.Mockito.mock(OrderEventService.class))
        .createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    verify(marketBundleResolver, org.mockito.Mockito.times(2)).resolveSpot(eq("BTCUSDT"), any());
    verify(transactionExecutor, org.mockito.Mockito.times(2)).execute(any());
    verify(fullFillCoordinator, org.mockito.Mockito.times(2)).execute(any(), any());
    verify(tradeRepository, org.mockito.Mockito.times(1)).save(any(TradeEntity.class));
  }

  @Test
  void p0MarketFinalFreshnessFailureAfterEnsureRollsBackAndRefetchesOutsideTransaction() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = p0MarketOrder(accountId, "final-stale-retry");
    TradingAccountEntity account = demoAccount(userId, accountId);
    FullFillResult first = fullFill("0.10", "100.0100");
    FullFillResult second = fullFill("0.10", "100.0200");
    AtomicBoolean insideMutation = new AtomicBoolean();

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, "final-stale-retry")).thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "final-stale-retry"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any())).thenReturn(BigDecimal.ZERO);
    when(riskCheckService.resolveEffectiveLeverage(eq(account), any())).thenReturn(1);
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any()))
        .thenAnswer(invocation -> {
          assertThat(insideMutation.get()).isFalse();
          return spotBundle("binance", Instant.now().plusSeconds(2));
        });
    when(fullFillCoordinator.execute(any(), any())).thenReturn(first, second);
    org.mockito.Mockito.doThrow(new BusinessException("MARKET_DATA_STALE", "expired after ensure"))
        .doNothing()
        .when(fullFillCoordinator).requireFresh(any(FullFillResult.class));
    org.mockito.Mockito.doAnswer(invocation -> {
      insideMutation.set(true);
      try {
        return ((Supplier<?>) invocation.getArgument(0)).get();
      } finally {
        insideMutation.set(false);
      }
    }).when(transactionExecutor).execute(any());
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      if (order.getId() == null) {
        order.setId(UUID.randomUUID());
      }
      return order;
    });

    OrderResponse response = orderService(org.mockito.Mockito.mock(OrderEventService.class))
        .createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    verify(marketBundleResolver, org.mockito.Mockito.times(2)).resolveSpot(eq("BTCUSDT"), any());
    verify(transactionExecutor, org.mockito.Mockito.times(2)).execute(any());
    verify(fullFillCoordinator, org.mockito.Mockito.times(2)).execute(any(), any());
    verify(fullFillCoordinator, org.mockito.Mockito.times(2)).requireFresh(any(FullFillResult.class));
    verify(walletService, org.mockito.Mockito.times(2))
        .lockBalancesInOrder(accountId, List.of("BTC", "USDT"));
    verify(spotPositionService, org.mockito.Mockito.times(2))
        .lockOrCreate(accountId, "BTC", "USDT");
    verify(orderRepository, org.mockito.Mockito.times(2)).save(any(OrderEntity.class));
    verify(tradeRepository).save(any(TradeEntity.class));
  }

  @Test
  void p0MarketReplayReturnsBeforeGuardAndBundleResolution() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = p0MarketOrder(accountId, "existing-p0");
    OrderEntity existing = pendingOrderEntity(userId, accountId, UUID.randomUUID());
    alignExistingWithRequest(existing, request);

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "existing-p0"))
        .thenReturn(Optional.of(existing));

    OrderResponse response = orderService(org.mockito.Mockito.mock(OrderEventService.class))
        .createOrder(principal, request);

    assertThat(response.id()).isEqualTo(existing.getId());
    verify(demoExecutionGuard, never()).requireDemo(any(), any(), any());
    verify(marketBundleResolver, never()).resolveSpot(any(), any());
    verify(transactionExecutor, never()).execute(any());
  }

  @Test
  void p0MarketStopsAfterTwoStaleAttemptsWithoutWriting() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = p0MarketOrder(accountId, "stale-twice");
    TradingAccountEntity account = demoAccount(userId, accountId);

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "stale-twice"))
        .thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "stale-twice"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any())).thenReturn(BigDecimal.ZERO);
    when(riskCheckService.resolveEffectiveLeverage(eq(account), any())).thenReturn(1);
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any()))
        .thenReturn(spotBundle("binance", Instant.now().plusSeconds(2)));
    when(fullFillCoordinator.execute(any(), any()))
        .thenThrow(new BusinessException("MARKET_DATA_STALE", "expired behind lock"));

    assertThatThrownBy(() -> orderService(org.mockito.Mockito.mock(OrderEventService.class))
        .createOrder(principal, request))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("MARKET_DATA_STALE"));

    verify(marketBundleResolver, org.mockito.Mockito.times(2)).resolveSpot(eq("BTCUSDT"), any());
    verify(transactionExecutor, org.mockito.Mockito.times(2)).execute(any());
    verify(orderRepository, never()).save(any());
    verify(tradeRepository, never()).save(any());
  }

  @Test
  void p0MarketDoesNotRetryNonStaleMutationFailure() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = p0MarketOrder(accountId, "non-stale");
    TradingAccountEntity account = demoAccount(userId, accountId);

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "non-stale"))
        .thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "non-stale"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any())).thenReturn(BigDecimal.ZERO);
    when(riskCheckService.resolveEffectiveLeverage(eq(account), any())).thenReturn(1);
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any()))
        .thenReturn(spotBundle("binance", Instant.now().plusSeconds(2)));
    when(fullFillCoordinator.execute(any(), any()))
        .thenThrow(new BusinessException("INSUFFICIENT_MARGIN", "changed while waiting"));

    assertThatThrownBy(() -> orderService(org.mockito.Mockito.mock(OrderEventService.class))
        .createOrder(principal, request))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("INSUFFICIENT_MARGIN"));

    verify(marketBundleResolver, org.mockito.Mockito.times(1)).resolveSpot(eq("BTCUSDT"), any());
    verify(transactionExecutor, org.mockito.Mockito.times(1)).execute(any());
    verify(orderRepository, never()).save(any());
    verify(tradeRepository, never()).save(any());
  }

  @Test
  void marketOrderRecordsMarginLedgerAfterRiskAndExecution() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = marketOrder(accountId, "idem-1");
    TradingAccountEntity account = demoAccount(userId, accountId);
    BigDecimal requiredMargin = new BigDecimal("110.00000000");
    BigDecimal filledMargin = new BigDecimal("110.02000000");
    Instant filledAt = Instant.parse("2026-06-05T12:00:00Z");

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "idem-1")).thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class))).thenReturn(requiredMargin);
    when(accountRepository.reserveMarginIfAvailable(accountId, filledMargin)).thenReturn(1);
    when(executionAdapter.execute(any(CreateOrderRequest.class))).thenReturn(
        fullExecution("1.10020", filledAt, "0.10"));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      order.setId(UUID.randomUUID());
      return order;
    });
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      position.setId(UUID.randomUUID());
      return position;
    });

    OrderService service = orderService(org.mockito.Mockito.mock(OrderEventService.class));

    OrderResponse response = service.createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(response.executionPrice()).isEqualByComparingTo("1.10020");
    assertThat(account.getUsedMargin()).isEqualByComparingTo(filledMargin);
    assertThat(account.getFreeMargin()).isEqualByComparingTo("9889.98000000");

    ArgumentCaptor<OrderEntity> orderCaptor = ArgumentCaptor.forClass(OrderEntity.class);
    verify(orderRepository, org.mockito.Mockito.atLeastOnce()).save(orderCaptor.capture());
    assertThat(orderCaptor.getAllValues().getLast().getStatus()).isEqualTo(OrderStatus.FILLED);

    verify(tradeRepository).save(any(TradeEntity.class));
    ArgumentCaptor<PositionEntity> positionCaptor = ArgumentCaptor.forClass(PositionEntity.class);
    verify(positionRepository).save(positionCaptor.capture());
    assertThat(positionCaptor.getValue().getMarginHeld()).isEqualByComparingTo(filledMargin);
    verify(accountRepository).reserveMarginIfAvailable(accountId, filledMargin);
    verify(ledgerService).recordMarginHold(eq(account), eq(filledMargin), any(UUID.class), eq("Market order margin hold"));
    verify(demoExecutionGuard, org.mockito.Mockito.times(2))
        .requireDemo(account, ProductType.CRYPTO_SPOT, "EURUSD");
    org.mockito.InOrder accountWalletOrder = org.mockito.Mockito.inOrder(
        accountRepository, walletBalanceRepository, orderRepository);
    accountWalletOrder.verify(accountRepository).findByIdAndUserIdForUpdate(accountId, userId);
    accountWalletOrder.verify(walletBalanceRepository).findByAccountIdForUpdate(accountId);
    accountWalletOrder.verify(orderRepository, org.mockito.Mockito.atLeastOnce()).save(any(OrderEntity.class));
  }

  @Test
  void compatibilityConstructorFailsClosedForP0PerpetualCreation() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = new CreateOrderRequest(
        accountId,
        "BTCUSDT-PERP",
        OrderSide.BUY,
        OrderType.MARKET,
        null,
        null,
        null,
        null,
        "idem-perp-lock-order",
        "client-perp-lock-order",
        BigDecimal.ONE,
        null,
        10);
    TradingAccountEntity account = demoAccount(userId, accountId);

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, "client-perp-lock-order")).thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    assertThatThrownBy(() -> orderService(org.mockito.Mockito.mock(OrderEventService.class))
        .createOrder(principal, request))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("EXECUTION_UNAVAILABLE"));

    verify(accountRepository, never()).findByIdAndUserIdForUpdate(any(), any());
    verify(positionRepository, never()).findOpenByAccountIdForUpdate(any());
    verify(marketBundleResolver, never()).resolvePerp(any(), any());
    verify(riskCheckService, never()).checkOrder(any(), any());
    verify(orderRepository, never()).save(any(OrderEntity.class));
  }

  @Test
  void marketOrderStoresRequestedLeverageOnCreatedPosition() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = marketOrder(accountId, "idem-leverage-20", 20);
    TradingAccountEntity account = demoAccount(userId, accountId);
    BigDecimal requiredMargin = new BigDecimal("550.10000000");
    Instant filledAt = Instant.parse("2026-06-05T12:00:00Z");

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "idem-leverage-20")).thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class))).thenReturn(requiredMargin);
    when(riskCheckService.resolveEffectiveLeverage(eq(account), any(CreateOrderRequest.class))).thenReturn(20);
    when(accountRepository.reserveMarginIfAvailable(accountId, requiredMargin)).thenReturn(1);
    when(executionAdapter.execute(any(CreateOrderRequest.class))).thenReturn(
        fullExecution("1.10020", filledAt, "0.10"));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      order.setId(UUID.randomUUID());
      return order;
    });
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      position.setId(UUID.randomUUID());
      return position;
    });

    OrderService service = orderService(org.mockito.Mockito.mock(OrderEventService.class));

    OrderResponse response = service.createOrder(principal, request);

    assertThat(response.leverage()).isEqualTo(20);

    ArgumentCaptor<PositionEntity> positionCaptor = ArgumentCaptor.forClass(PositionEntity.class);
    verify(positionRepository).save(positionCaptor.capture());
    assertThat(positionCaptor.getValue().getLeverage()).isEqualTo(20);
  }

  @Test
  void rejectedRiskCheckDoesNotExecuteOrWriteLedger() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = marketOrder(accountId, "idem-2");
    TradingAccountEntity account = demoAccount(userId, accountId);

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "idem-2")).thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class)))
        .thenThrow(new BusinessException("INSUFFICIENT_MARGIN", "Free margin is not enough"));

    OrderService service = orderService(org.mockito.Mockito.mock(OrderEventService.class));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createOrder(principal, request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Free margin is not enough");

    verify(executionAdapter, never()).execute(any());
    verify(orderRepository, never()).save(any());
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordMarginHold(any(), any(), any(), any());
  }

  @Test
  void createOrderRiskFailureReplayDoesNotPersistExecuteOrWriteLedger() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = marketOrder(accountId, "idem-risk-failure-replay");
    TradingAccountEntity account = demoAccount(userId, accountId);

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "idem-risk-failure-replay"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class)))
        .thenThrow(new BusinessException("INSUFFICIENT_MARGIN", "Free margin is not enough"));

    OrderService service = orderService(org.mockito.Mockito.mock(OrderEventService.class));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createOrder(principal, request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Free margin is not enough");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createOrder(principal, request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Free margin is not enough");

    verify(riskCheckService, org.mockito.Mockito.times(2)).checkOrder(eq(account), any(CreateOrderRequest.class));
    verify(executionAdapter, never()).execute(any());
    verify(orderRepository, never()).save(any());
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordMarginHold(any(), any(), any(), any());
  }

  @Test
  void partialMarketExecutionIsRejectedBeforeAnyMutation() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = marketOrder(accountId, "idem-partial-1");
    TradingAccountEntity account = demoAccount(userId, accountId);
    BigDecimal requiredMargin = new BigDecimal("100.00000000");
    Instant filledAt = Instant.parse("2026-06-05T12:05:00Z");

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "idem-partial-1")).thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class))).thenReturn(requiredMargin);
    when(executionAdapter.execute(any(CreateOrderRequest.class))).thenReturn(new ExecutionResult(
        new BigDecimal("1.10100"),
        filledAt,
        new BigDecimal("0.04"),
        new BigDecimal("0.06"),
        new BigDecimal("0.44"),
        new BigDecimal("0.00010"),
        null,
        null));
    OrderEventService orderEventService = org.mockito.Mockito.mock(OrderEventService.class);
    OrderService service = orderService(orderEventService);

    assertThatThrownBy(() -> service.createOrder(principal, request))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("PARTIAL_FILL_NOT_SUPPORTED"));

    verify(orderRepository, never()).save(any());
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).reserveMarginIfAvailable(any(), any());
    verify(ledgerService, never()).recordMarginHold(any(), any(), any(), any());
  }

  @Test
  void marketExecutionFeeUsesBalanceFallbackOnceWhenEquityIsNull() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = marketOrder(accountId, "idem-fee-null-equity");
    TradingAccountEntity account = demoAccount(userId, accountId);
    account.setEquity(null);
    BigDecimal requiredMargin = new BigDecimal("100.00000000");
    BigDecimal filledMargin = new BigDecimal("110.10000000");
    Instant filledAt = Instant.parse("2026-06-05T12:06:00Z");

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "idem-fee-null-equity")).thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class))).thenReturn(requiredMargin);
    when(accountRepository.reserveMarginIfAvailable(accountId, filledMargin)).thenReturn(1);
    when(executionAdapter.execute(any(CreateOrderRequest.class))).thenReturn(new ExecutionResult(
        new BigDecimal("1.10100"),
        filledAt,
        new BigDecimal("0.10"),
        BigDecimal.ZERO,
        new BigDecimal("0.44"),
        BigDecimal.ZERO,
        null,
        null));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      order.setId(UUID.randomUUID());
      return order;
    });
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      position.setId(UUID.randomUUID());
      return position;
    });

    OrderService service = orderService(org.mockito.Mockito.mock(OrderEventService.class));

    service.createOrder(principal, request);

    assertThat(account.getBalance()).isEqualByComparingTo("9999.56000000");
    assertThat(account.getEquity()).isEqualByComparingTo("9999.56000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("110.10000000");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("9889.46000000");
    verify(ledgerService).recordTradeFeeForTrade(eq(account), eq(new BigDecimal("0.44")), any(UUID.class), eq("Trade fee charged"));
  }

  @Test
  void rejectedExecutionRecordsOrderReasonWithoutTradePositionOrLedger() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = marketOrder(accountId, "idem-reject-1");
    TradingAccountEntity account = demoAccount(userId, accountId);

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "idem-reject-1")).thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class))).thenReturn(new BigDecimal("110.02000000"));
    when(executionAdapter.execute(any(CreateOrderRequest.class))).thenReturn(ExecutionResult.rejected(
        "EXECUTION_REJECTED",
        "Demo execution rejected"));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      order.setId(UUID.randomUUID());
      return order;
    });

    OrderEventService orderEventService = org.mockito.Mockito.mock(OrderEventService.class);
    OrderService service = orderService(orderEventService);

    OrderResponse response = service.createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.REJECTED.name());
    assertThat(response.rejectCode()).isEqualTo("EXECUTION_REJECTED");
    assertThat(response.rejectMessage()).isEqualTo("Demo execution rejected");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("0");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("10000.00000000");
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(ledgerService, never()).recordMarginHold(any(), any(), any(), any());
    verify(ledgerService, never()).recordTradeFee(any(), any(), any(), any());
    verify(orderEventService).record(
        any(UUID.class),
        eq("ORDER_REJECTED"),
        eq(OrderStatus.ACCEPTED),
        eq(OrderStatus.REJECTED),
        eq("EXECUTION_REJECTED"),
        eq("Demo execution rejected"));
  }

  @Test
  void limitOrderUsesPendingStatusAndCompatibleResponseFields() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = new CreateOrderRequest(
        accountId,
        "EURUSD",
        OrderSide.BUY,
        OrderType.LIMIT,
        null,
        null,
        null,
        null,
        null,
        "client-limit-1",
        new BigDecimal("0.10"),
        new BigDecimal("1.08000"));
    TradingAccountEntity account = demoAccount(userId, accountId);
    BigDecimal holdAmount = new BigDecimal("10.80000000");

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "client-limit-1"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class))).thenReturn(holdAmount);
    when(accountRepository.reserveMarginIfAvailable(accountId, holdAmount)).thenReturn(1);
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      order.setId(UUID.randomUUID());
      return order;
    });

    OrderEventService orderEventService = org.mockito.Mockito.mock(OrderEventService.class);
    OrderService service = orderService(orderEventService);

    OrderResponse response = service.createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
    assertThat(response.lots()).isEqualByComparingTo("0.10");
    assertThat(response.quantity()).isEqualByComparingTo("0.10");
    assertThat(response.price()).isEqualByComparingTo("1.08000");
    assertThat(response.filledQuantity()).isEqualByComparingTo("0");
    assertThat(response.remainingQuantity()).isEqualByComparingTo("0.10");
    assertThat(response.holdAmount()).isEqualByComparingTo(holdAmount);
    assertThat(account.getUsedMargin()).isEqualByComparingTo(holdAmount);
    assertThat(account.getFreeMargin()).isEqualByComparingTo("9989.20000000");

    ArgumentCaptor<OrderEntity> orderCaptor = ArgumentCaptor.forClass(OrderEntity.class);
    verify(orderRepository).save(orderCaptor.capture());
    assertThat(orderCaptor.getValue().getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(orderCaptor.getValue().getClientOrderId()).isEqualTo("client-limit-1");
    assertThat(orderCaptor.getValue().getQuantity()).isEqualByComparingTo("0.10");
    assertThat(orderCaptor.getValue().getRemainingQuantity()).isEqualByComparingTo("0.10");
    verify(executionAdapter, never()).execute(any());
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(accountRepository).reserveMarginIfAvailable(accountId, holdAmount);
    verify(ledgerService).recordOrderHold(eq(account), eq(holdAmount), any(UUID.class), eq("Pending order margin reserved"));
    verify(orderEventService).record(
        any(UUID.class),
        eq("ORDER_PENDING"),
        eq(OrderStatus.ACCEPTED),
        eq(OrderStatus.PENDING),
        eq(null),
        eq("Pending order accepted and waiting"));
  }

  @Test
  void spotLimitOrderLocksQuoteWalletInsteadOfMargin() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = new CreateOrderRequest(
        accountId,
        "BTCUSDT",
        OrderSide.BUY,
        OrderType.LIMIT,
        null,
        null,
        null,
        null,
        "idem-spot-limit",
        "client-spot-limit",
        new BigDecimal("0.10"),
        new BigDecimal("50000.00000000"),
        1);
    TradingAccountEntity account = demoAccount(userId, accountId);
    BigDecimal holdAmount = new BigDecimal("5000.00000000");

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "client-spot-limit"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class))).thenReturn(holdAmount);
    when(riskCheckService.resolveEffectiveLeverage(eq(account), any(CreateOrderRequest.class))).thenReturn(1);
    when(riskCheckService.isSpotSymbol("BTCUSDT")).thenReturn(true);
    when(riskCheckService.resolveHoldCurrency(eq(account), any(CreateOrderRequest.class))).thenReturn("USDT");
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      order.setId(UUID.randomUUID());
      return order;
    });

    OrderEventService orderEventService = org.mockito.Mockito.mock(OrderEventService.class);
    OrderService service = orderService(orderEventService);

    OrderResponse response = service.createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
    assertThat(response.holdAmount()).isEqualByComparingTo(holdAmount);
    assertThat(response.holdCurrency()).isEqualTo("USDT");
    org.mockito.InOrder accountWalletOrder = org.mockito.Mockito.inOrder(
        accountRepository, walletService, spotPositionService, orderRepository);
    accountWalletOrder.verify(accountRepository).findByIdAndUserIdForUpdate(accountId, userId);
    accountWalletOrder.verify(walletService).lockBalancesInOrder(accountId, List.of("BTC", "USDT"));
    accountWalletOrder.verify(spotPositionService).lockOrCreate(accountId, "BTC", "USDT");
    accountWalletOrder.verify(orderRepository).save(any(OrderEntity.class));
    assertThat(account.getUsedMargin()).isEqualByComparingTo("0");
    verify(walletService).lockAvailableWithEntryType(
        eq(accountId),
        eq("USDT"),
        eq(holdAmount),
        eq("ORDER"),
        any(UUID.class),
        eq("Pending spot order wallet locked"),
        eq("SPOT_ORDER_LOCK"));
    verify(accountRepository, never()).reserveMarginIfAvailable(eq(accountId), any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), any());
    verify(executionAdapter, never()).execute(any());
    verify(orderEventService).record(
        any(UUID.class),
        eq("ORDER_PENDING"),
        eq(OrderStatus.ACCEPTED),
        eq(OrderStatus.PENDING),
        eq(null),
        eq("Pending order accepted and waiting"));
  }

  @Test
  void spotSellLimitOrderLocksBaseWalletInsteadOfMargin() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = new CreateOrderRequest(
        accountId,
        "BTCUSDT",
        OrderSide.SELL,
        OrderType.LIMIT,
        null,
        null,
        null,
        null,
        "idem-spot-sell-limit",
        "client-spot-sell-limit",
        new BigDecimal("0.10"),
        new BigDecimal("55000.00000000"),
        1);
    TradingAccountEntity account = demoAccount(userId, accountId);
    BigDecimal holdAmount = new BigDecimal("0.10000000");

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "client-spot-sell-limit"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class))).thenReturn(holdAmount);
    when(riskCheckService.resolveEffectiveLeverage(eq(account), any(CreateOrderRequest.class))).thenReturn(1);
    when(riskCheckService.isSpotSymbol("BTCUSDT")).thenReturn(true);
    when(riskCheckService.resolveHoldCurrency(eq(account), any(CreateOrderRequest.class))).thenReturn("BTC");
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      order.setId(UUID.randomUUID());
      return order;
    });

    OrderEventService orderEventService = org.mockito.Mockito.mock(OrderEventService.class);
    OrderService service = orderService(orderEventService);

    OrderResponse response = service.createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
    assertThat(response.holdAmount()).isEqualByComparingTo(holdAmount);
    assertThat(response.holdCurrency()).isEqualTo("BTC");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("0");
    verify(walletService).lockAvailableWithEntryType(
        eq(accountId),
        eq("BTC"),
        eq(holdAmount),
        eq("ORDER"),
        any(UUID.class),
        eq("Pending spot order wallet locked"),
        eq("SPOT_ORDER_LOCK"));
    verify(accountRepository, never()).reserveMarginIfAvailable(eq(accountId), any());
    verify(ledgerService, never()).recordOrderHold(any(), any(), any(), any());
    verify(executionAdapter, never()).execute(any());
  }

  @Test
  void pendingOrderWithoutRequestedPriceIsRejectedBeforeRiskCheck() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = new CreateOrderRequest(
        accountId,
        "EURUSD",
        OrderSide.BUY,
        OrderType.LIMIT,
        new BigDecimal("0.10"),
        null,
        null,
        null,
        "idem-limit-without-price",
        null,
        null,
        null);
    TradingAccountEntity account = demoAccount(userId, accountId);

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "idem-limit-without-price"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));

    OrderService service = orderService(org.mockito.Mockito.mock(OrderEventService.class));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createOrder(principal, request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("requested price");

    verify(riskCheckService, never()).checkOrder(any(), any());
    verify(executionAdapter, never()).execute(any());
    verify(orderRepository, never()).save(any());
  }

  @Test
  void createOrderReturnsExistingOrderWhenUniqueConstraintWinsRace() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID existingOrderId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = marketOrder(accountId, "idem-race");
    TradingAccountEntity account = demoAccount(userId, accountId);
    OrderEntity existing = pendingOrderEntity(userId, accountId, existingOrderId);
    alignExistingWithRequest(existing, request);
    existing.setStatus(OrderStatus.PENDING);

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "idem-race"))
        .thenReturn(Optional.empty(), Optional.of(existing));
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "idem-race")).thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class))).thenReturn(new BigDecimal("110.02000000"));
    when(orderRepository.save(any(OrderEntity.class))).thenThrow(new DataIntegrityViolationException("duplicate order"));

    OrderService service = orderService(org.mockito.Mockito.mock(OrderEventService.class));

    OrderResponse response = service.createOrder(principal, request);

    assertThat(response.id()).isEqualTo(existingOrderId);
    assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
    verify(executionAdapter).execute(any());
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verify(ledgerService, never()).recordMarginHold(any(), any(), any(), any());
  }

  @Test
  void createOrderReplayReturnsExistingOrderWithoutSecondExecutionOrLedger() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = marketOrder(accountId, "idem-replay");
    TradingAccountEntity account = demoAccount(userId, accountId);
    BigDecimal requiredMargin = new BigDecimal("110.02000000");
    AtomicReference<OrderEntity> storedOrder = new AtomicReference<>();

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "idem-replay"))
        .thenAnswer(invocation -> Optional.ofNullable(storedOrder.get()));
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "idem-replay"))
        .thenAnswer(invocation -> Optional.ofNullable(storedOrder.get()));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class))).thenReturn(requiredMargin);
    when(accountRepository.reserveMarginIfAvailable(accountId, requiredMargin)).thenReturn(1);
    when(executionAdapter.execute(any(CreateOrderRequest.class)))
        .thenReturn(fullExecution(
            "1.10020", Instant.parse("2026-06-05T12:00:00Z"), "0.10"));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      if (order.getId() == null) {
        order.setId(orderId);
      }
      storedOrder.set(order);
      return order;
    });
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      position.setId(UUID.randomUUID());
      return position;
    });

    OrderEventService replayEvents = org.mockito.Mockito.mock(OrderEventService.class);
    OrderService service = orderService(replayEvents);

    OrderResponse first = service.createOrder(principal, request);
    OrderResponse replay = service.createOrder(principal, request);

    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(replay.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(org.springframework.test.util.ReflectionTestUtils.getField(
        storedOrder.get(), "requestFingerprint"))
        .as("new orders persist the original request fingerprint")
        .isNotNull();
    verify(executionAdapter).execute(any(CreateOrderRequest.class));
    verify(tradeRepository).save(any(TradeEntity.class));
    verify(positionRepository).save(any(PositionEntity.class));
    verify(ledgerService).recordMarginHold(eq(account), eq(requiredMargin), any(UUID.class), eq("Market order margin hold"));
    verify(replayEvents, org.mockito.Mockito.times(1)).record(
        any(), any(), any(), any(), any(), any());
  }

  @Test
  void createOrderAllowsTerminalClientOrderIdReuseWithNewIdempotencyKey() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID completedOrderId = UUID.randomUUID();
    UUID newOrderId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest completedRequest = limitOrderWithKeys(
        accountId, "idem-completed", "client-reusable");
    CreateOrderRequest newRequest = limitOrderWithKeys(
        accountId, "idem-new", "client-reusable");
    OrderEntity completed = pendingOrderEntity(userId, accountId, completedOrderId);
    alignExistingWithRequest(completed, completedRequest);
    completed.setStatus(OrderStatus.FILLED);
    completed.setFilledQuantity(completedRequest.quantity());
    completed.setRemainingQuantity(BigDecimal.ZERO);
    completed.setRequestFingerprint(OrderRequestFingerprint.calculate(completedRequest));
    TradingAccountEntity account = demoAccount(userId, accountId);
    BigDecimal holdAmount = new BigDecimal("10.80000000");

    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "idem-new"))
        .thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, "client-reusable")).thenReturn(Optional.of(completed));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(eq(account), any(CreateOrderRequest.class)))
        .thenReturn(holdAmount);
    when(accountRepository.reserveMarginIfAvailable(accountId, holdAmount)).thenReturn(1);
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      order.setId(newOrderId);
      return order;
    });

    OrderResponse response = orderService(org.mockito.Mockito.mock(OrderEventService.class))
        .createOrder(principal, newRequest);

    assertThat(response.id()).isEqualTo(newOrderId);
    assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
    ArgumentCaptor<OrderEntity> saved = ArgumentCaptor.forClass(OrderEntity.class);
    verify(orderRepository).save(saved.capture());
    assertThat(saved.getValue().getClientOrderId()).isEqualTo("client-reusable");
    assertThat(saved.getValue().getIdempotencyKey()).isEqualTo("idem-new");
  }

  @Test
  void createOrderReplaysTerminalOrderByIdempotencyWithoutClientOrderLookup() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID replayOrderId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest replayRequest = limitOrderWithKeys(
        accountId, "idem-original", "client-reused-after-fill");
    OrderEntity terminalReplay = pendingOrderEntity(userId, accountId, replayOrderId);
    alignExistingWithRequest(terminalReplay, replayRequest);
    terminalReplay.setStatus(OrderStatus.FILLED);
    terminalReplay.setFilledQuantity(replayRequest.quantity());
    terminalReplay.setRemainingQuantity(BigDecimal.ZERO);
    terminalReplay.setRequestFingerprint(OrderRequestFingerprint.calculate(replayRequest));

    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "idem-original"))
        .thenReturn(Optional.of(terminalReplay));

    OrderResponse response = orderService(org.mockito.Mockito.mock(OrderEventService.class))
        .createOrder(principal, replayRequest);

    assertThat(response.id()).isEqualTo(replayOrderId);
    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    verify(accountRepository, never()).findByIdAndUserId(any(), any());
    verify(orderRepository, never()).findByUserIdAndAccountIdAndClientOrderId(
        any(), any(), any());
    verify(orderRepository, never()).save(any());
  }

  @Test
  void createOrderRejectsDifferentPayloadForTerminalIdempotencyReplay() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest original = limitOrderWithKeys(
        accountId, "idem-terminal", "client-terminal");
    CreateOrderRequest conflict = new CreateOrderRequest(
        accountId,
        "EURUSD",
        OrderSide.BUY,
        OrderType.LIMIT,
        null,
        null,
        null,
        null,
        "idem-terminal",
        "client-terminal",
        new BigDecimal("0.20"),
        new BigDecimal("1.08000"));
    OrderEntity terminal = pendingOrderEntity(userId, accountId, UUID.randomUUID());
    alignExistingWithRequest(terminal, original);
    terminal.setStatus(OrderStatus.CANCELED);
    terminal.setRequestFingerprint(OrderRequestFingerprint.calculate(original));

    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "idem-terminal"))
        .thenReturn(Optional.of(terminal));

    assertThatThrownBy(() -> orderService(org.mockito.Mockito.mock(OrderEventService.class))
        .createOrder(principal, conflict))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(com.fxplatform.common.exception.ErrorCode.DUPLICATE_CLIENT_ORDER_ID));

    verify(accountRepository, never()).findByIdAndUserId(any(), any());
    verify(orderRepository, never()).save(any());
  }

  @Test
  void createOrderRejectsClientOrderIdReplayWhenRequestFingerprintDiffers() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = marketOrder(accountId, "idem-conflict");
    OrderEntity existing = pendingOrderEntity(userId, accountId, orderId);
    alignExistingWithRequest(existing, request);
    existing.setQuantity(new BigDecimal("0.20"));
    existing.setOriginalQuantity(new BigDecimal("0.20"));
    existing.setBaseQuantity(new BigDecimal("0.20"));
    existing.setLots(new BigDecimal("0.20"));
    existing.setRemainingQuantity(new BigDecimal("0.20"));

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, "idem-conflict")).thenReturn(Optional.of(existing));

    OrderEventService orderEventService = org.mockito.Mockito.mock(OrderEventService.class);
    OrderService service = orderService(orderEventService);

    assertThatThrownBy(() -> service.createOrder(principal, request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(com.fxplatform.common.exception.ErrorCode.DUPLICATE_CLIENT_ORDER_ID));

    assertThat(existing.getId()).isEqualTo(orderId);
    assertThat(existing.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(existing.getOriginalQuantity()).isEqualByComparingTo("0.20");
    assertThat(existing.getBaseQuantity()).isEqualByComparingTo("0.20");
    assertThat(existing.getFilledQuantity()).isEqualByComparingTo("0");
    assertThat(existing.getRemainingQuantity()).isEqualByComparingTo("0.20");
    verify(accountRepository, never()).findByIdAndUserId(any(), any());
    verify(riskCheckService, never()).checkOrder(any(), any());
    verify(executionAdapter, never()).execute(any());
    verify(orderRepository, never()).save(any());
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
    verifyNoInteractions(ledgerService);
    verifyNoInteractions(orderEventService);
  }

  @Test
  void createOrderRejectsMalformedTrailingReplayBeforeExistingOrderLookup() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest request = new CreateOrderRequest(
        accountId,
        "EURUSD",
        OrderSide.BUY,
        OrderType.TRAILING_STOP_MARKET,
        new BigDecimal("0.10"),
        new BigDecimal("1.08000"),
        null,
        null,
        "advanced-replay",
        null,
        null,
        null,
        null,
        PositionSide.BOTH,
        QuantityUnit.BASE,
        MarginMode.CROSS,
        new BigDecimal("1.07000"),
        null,
        false,
        List.of(),
        TimeInForce.GTC,
        false,
        null,
        new BigDecimal("0.00100"),
        null);
    OrderEntity existing = pendingOrderEntity(userId, accountId, UUID.randomUUID());
    alignExistingWithRequest(existing, request);
    existing.setRequestFingerprint(OrderRequestFingerprint.calculate(request));

    org.mockito.Mockito.lenient()
        .when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
            userId, accountId, "advanced-replay"))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> orderService(org.mockito.Mockito.mock(OrderEventService.class))
        .createOrder(principal, request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("INVALID_PERPETUAL_ORDER_FIELDS"));

    verify(orderRepository, never()).findByUserIdAndAccountIdAndClientOrderId(
        any(), any(), any());
    verify(orderRepository, never()).findByUserIdAndIdempotencyKey(any(), any());
  }

  @Test
  void createOrderRejectsReplayWhenExplicitIdempotencyKeyDiffers() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    CreateOrderRequest original = marketOrderWithKeys(
        accountId, "idem-original", "client-shared");
    CreateOrderRequest conflict = marketOrderWithKeys(
        accountId, "idem-conflict", "client-shared");
    OrderEntity existing = pendingOrderEntity(userId, accountId, orderId);
    alignExistingWithRequest(existing, original);
    existing.setRequestFingerprint(OrderRequestFingerprint.calculate(original));

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, "client-shared")).thenReturn(Optional.of(existing));
    OrderEventService orderEventService = org.mockito.Mockito.mock(OrderEventService.class);
    OrderService service = orderService(orderEventService);

    assertThatThrownBy(() -> service.createOrder(principal, conflict))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(com.fxplatform.common.exception.ErrorCode.DUPLICATE_CLIENT_ORDER_ID));

    assertThat(existing.getId()).isEqualTo(orderId);
    assertThat(existing.getIdempotencyKey()).isEqualTo("idem-original");
    verify(accountRepository, never()).findByIdAndUserId(any(), any());
    verify(executionAdapter, never()).execute(any());
    verify(orderRepository, never()).save(any());
    verifyNoInteractions(ledgerService);
    verifyNoInteractions(orderEventService);
  }

  @Test
  void orderEventsRequireOwnedOrderAndReturnTimeline() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    OrderEntity order = pendingOrderEntity(userId, accountId, orderId);
    Instant createdAt = Instant.parse("2026-06-05T12:30:00Z");
    OrderEventResponse event = new OrderEventResponse(
        UUID.randomUUID(),
        orderId,
        "ORDER_PENDING",
        OrderStatus.ACCEPTED.name(),
        OrderStatus.PENDING.name(),
        null,
        "Pending order accepted and waiting",
        createdAt);
    OrderEventService orderEventService = org.mockito.Mockito.mock(OrderEventService.class);

    when(orderRepository.findByUserIdAndId(userId, orderId)).thenReturn(Optional.of(order));
    when(orderEventService.events(orderId)).thenReturn(List.of(event));

    OrderService service = orderService(orderEventService);

    List<OrderEventResponse> events = service.orderEvents(principal, orderId);

    assertThat(events).containsExactly(event);
  }

  @Test
  void cancelPendingOrderReleasesHoldAndRecordsEvent() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    TradingAccountEntity account = demoAccount(userId, accountId);
    account.setUsedMargin(new BigDecimal("10.80000000"));
    account.setFreeMargin(new BigDecimal("9989.20000000"));
    OrderEntity order = pendingOrderEntity(userId, accountId, orderId);
    order.setHoldAmount(new BigDecimal("10.80000000"));
    order.setHoldCurrency("USD");

    when(orderRepository.findByUserIdAndId(userId, orderId)).thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(orderRepository.cancelPending(any(OrderEntity.class))).thenReturn(1);
    OrderEventService orderEventService = org.mockito.Mockito.mock(OrderEventService.class);
    OrderService service = orderService(orderEventService);

    OrderResponse response = service.cancelOrder(principal, orderId);

    assertThat(response.status()).isEqualTo(OrderStatus.CANCELED.name());
    assertThat(response.canceledAt()).isNotNull();
    assertThat(account.getUsedMargin()).isEqualByComparingTo("0");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("10000.00000000");
    verify(orderRepository).cancelPending(order);
    verify(accountRepository).save(account);
    verify(ledgerService).recordOrderRelease(eq(account), eq(new BigDecimal("10.80000000")), eq(orderId), eq("Pending order canceled"));
    verify(orderEventService).record(
        eq(orderId),
        eq("ORDER_CANCELED"),
        eq(OrderStatus.PENDING),
        eq(OrderStatus.CANCELED),
        eq(null),
        eq("Pending order canceled"));
  }

  @Test
  void cancelSpotPendingOrderReleasesWalletHoldWithoutMarginLedger() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    TradingAccountEntity account = demoAccount(userId, accountId);
    OrderEntity order = pendingOrderEntity(userId, accountId, orderId);
    order.setSymbol("BTCUSDT");
    order.setHoldAmount(new BigDecimal("5000.00000000"));
    order.setHoldCurrency("USDT");

    when(orderRepository.findByUserIdAndId(userId, orderId)).thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(orderRepository.cancelPending(any(OrderEntity.class))).thenReturn(1);
    when(riskCheckService.isSpotSymbol("BTCUSDT")).thenReturn(true);
    OrderEventService orderEventService = org.mockito.Mockito.mock(OrderEventService.class);
    OrderService service = orderService(orderEventService);

    OrderResponse response = service.cancelOrder(principal, orderId);

    assertThat(response.status()).isEqualTo(OrderStatus.CANCELED.name());
    assertThat(response.holdAmount()).isEqualByComparingTo("0");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("0");
    org.mockito.InOrder accountWalletOrder = org.mockito.Mockito.inOrder(
        accountRepository, walletService, spotPositionService, orderRepository);
    accountWalletOrder.verify(accountRepository).findByIdAndUserIdForUpdate(accountId, userId);
    accountWalletOrder.verify(walletService).lockBalancesInOrder(accountId, List.of("BTC", "USDT"));
    accountWalletOrder.verify(spotPositionService).lockOrCreate(accountId, "BTC", "USDT");
    accountWalletOrder.verify(orderRepository).findByIdForUpdate(orderId);
    verify(walletService).releaseLockedWithEntryType(
        eq(accountId),
        eq("USDT"),
        eq(new BigDecimal("5000.00000000")),
        eq("ORDER"),
        eq(orderId),
        eq("Pending spot order canceled"),
        eq("SPOT_ORDER_RELEASE"));
    verify(accountRepository, never()).save(account);
    verify(ledgerService, never()).recordOrderRelease(any(), any(), any(), any());
    verify(orderEventService).record(
        eq(orderId),
        eq("ORDER_CANCELED"),
        eq(OrderStatus.PENDING),
        eq(OrderStatus.CANCELED),
        eq(null),
        eq("Pending order canceled"));
  }

  @Test
  void cancelFilledOrderIsRejected() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    OrderEntity order = pendingOrderEntity(userId, accountId, orderId);
    order.setStatus(OrderStatus.FILLED);

    when(orderRepository.findByUserIdAndId(userId, orderId)).thenReturn(Optional.of(order));

    OrderService service = orderService(org.mockito.Mockito.mock(OrderEventService.class));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.cancelOrder(principal, orderId))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Only pending, partially filled, or awaiting-activation orders can be canceled");

    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordOrderRelease(any(), any(), any(), any());
  }

  @Test
  void cancelPendingOrderReplayIsRejectedWithoutSecondRelease() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    TradingAccountEntity account = demoAccount(userId, accountId);
    account.setUsedMargin(new BigDecimal("10.80000000"));
    account.setFreeMargin(new BigDecimal("9989.20000000"));
    OrderEntity order = pendingOrderEntity(userId, accountId, orderId);
    order.setHoldAmount(new BigDecimal("10.80000000"));
    order.setHoldCurrency("USD");

    when(orderRepository.findByUserIdAndId(userId, orderId)).thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(orderRepository.cancelPending(any(OrderEntity.class))).thenReturn(1);
    OrderEventService orderEventService = org.mockito.Mockito.mock(OrderEventService.class);
    OrderService service = orderService(orderEventService);

    OrderResponse first = service.cancelOrder(principal, orderId);

    assertThat(first.status()).isEqualTo(OrderStatus.CANCELED.name());
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.cancelOrder(principal, orderId))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Only pending, partially filled, or awaiting-activation orders can be canceled");
    verify(ledgerService).recordOrderRelease(eq(account), eq(new BigDecimal("10.80000000")), eq(orderId), eq("Pending order canceled"));
    verify(orderEventService).record(
        eq(orderId),
        eq("ORDER_CANCELED"),
        eq(OrderStatus.PENDING),
        eq(OrderStatus.CANCELED),
        eq(null),
        eq("Pending order canceled"));
    assertThat(account.getUsedMargin()).isEqualByComparingTo("0");
  }

  @Test
  void modifyPendingOrderAdjustsHoldDeltaAndRecordsEvent() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    TradingAccountEntity account = demoAccount(userId, accountId);
    account.setUsedMargin(new BigDecimal("10.00000000"));
    account.setFreeMargin(new BigDecimal("9990.00000000"));
    OrderEntity order = pendingOrderEntity(userId, accountId, orderId);
    order.setHoldAmount(new BigDecimal("10.00000000"));
    order.setOriginalQuantity(new BigDecimal("0.10"));
    order.setBaseQuantity(new BigDecimal("0.10"));
    order.setQuantityUnit(com.fxplatform.trading.enums.QuantityUnit.BASE);
    UpdateOrderRequest update = new UpdateOrderRequest(
        new BigDecimal("0.20"),
        new BigDecimal("1.07900"),
        new BigDecimal("1.07000"),
        new BigDecimal("1.09000"));

    when(orderRepository.findByUserIdAndId(userId, orderId)).thenReturn(Optional.of(order));
    when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(riskCheckService.checkOrder(any(TradingAccountEntity.class), any(CreateOrderRequest.class)))
        .thenReturn(new BigDecimal("20.00000000"));
    when(accountRepository.reserveMarginIfAvailable(accountId, new BigDecimal("10.00000000"))).thenReturn(1);
    OrderEventService orderEventService = org.mockito.Mockito.mock(OrderEventService.class);
    OrderService service = orderService(orderEventService);

    OrderResponse response = service.modifyOrder(principal, orderId, update);

    assertThat(response.status()).isEqualTo(OrderStatus.PENDING.name());
    assertThat(response.quantity()).isEqualByComparingTo("0.20");
    assertThat(response.originalQuantity()).isEqualByComparingTo("0.20");
    assertThat(response.baseQuantity()).isEqualByComparingTo("0.20");
    assertThat(response.price()).isEqualByComparingTo("1.07900");
    assertThat(order.getStopLoss()).isEqualByComparingTo("1.07000");
    assertThat(order.getTakeProfit()).isEqualByComparingTo("1.09000");
    assertThat(order.getHoldAmount()).isEqualByComparingTo("20.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("20.00000000");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("9980.00000000");
    org.mockito.InOrder accountWalletOrder = org.mockito.Mockito.inOrder(
        accountRepository, walletBalanceRepository, orderRepository);
    accountWalletOrder.verify(accountRepository).findByIdAndUserIdForUpdate(accountId, userId);
    accountWalletOrder.verify(walletBalanceRepository).findByAccountIdForUpdate(accountId);
    accountWalletOrder.verify(orderRepository).findByIdForUpdate(orderId);
    verify(orderRepository).save(order);
    verify(accountRepository).reserveMarginIfAvailable(accountId, new BigDecimal("10.00000000"));
    verify(ledgerService).recordOrderHold(eq(account), eq(new BigDecimal("10.00000000")), eq(orderId), eq("Pending order margin increased"));
    verify(orderEventService).record(
        eq(orderId),
        eq("ORDER_MODIFIED"),
        eq(OrderStatus.PENDING),
        eq(OrderStatus.PENDING),
        eq(null),
        eq("Pending order modified"));
  }

  private static CreateOrderRequest marketOrder(UUID accountId, String idempotencyKey) {
    return marketOrder(accountId, idempotencyKey, null);
  }

  private static CreateOrderRequest marketOrder(UUID accountId, String idempotencyKey, Integer leverage) {
    return new CreateOrderRequest(
        accountId,
        "EURUSD",
        OrderSide.BUY,
        OrderType.MARKET,
        new BigDecimal("0.10"),
        null,
        null,
        null,
        idempotencyKey,
        null,
        null,
        null,
        leverage);
  }

  private static CreateOrderRequest marketOrderWithKeys(
      UUID accountId,
      String idempotencyKey,
      String clientOrderId
  ) {
    return new CreateOrderRequest(
        accountId,
        "EURUSD",
        OrderSide.BUY,
        OrderType.MARKET,
        new BigDecimal("0.10"),
        null,
        null,
        null,
        idempotencyKey,
        clientOrderId,
        null,
        null,
        null);
  }

  private static CreateOrderRequest limitOrderWithKeys(
      UUID accountId,
      String idempotencyKey,
      String clientOrderId
  ) {
    return new CreateOrderRequest(
        accountId,
        "EURUSD",
        OrderSide.BUY,
        OrderType.LIMIT,
        null,
        null,
        null,
        null,
        idempotencyKey,
        clientOrderId,
        new BigDecimal("0.10"),
        new BigDecimal("1.08000"));
  }

  private static TradingAccountEntity demoAccount(UUID userId, UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setBalance(new BigDecimal("10000.00000000"));
    account.setEquity(new BigDecimal("10000.00000000"));
    account.setUsedMargin(BigDecimal.ZERO);
    account.setFreeMargin(new BigDecimal("10000.00000000"));
    account.setLeverage(100);
    return account;
  }

  private static OrderEntity pendingOrderEntity(UUID userId, UUID accountId, UUID orderId) {
    OrderEntity order = new OrderEntity();
    order.setId(orderId);
    order.setUserId(userId);
    order.setAccountId(accountId);
    order.setSymbol("EURUSD");
    order.setSide(OrderSide.BUY);
    order.setOrderType(OrderType.LIMIT);
    order.setStatus(OrderStatus.PENDING);
    order.setLots(new BigDecimal("0.10"));
    order.setQuantity(new BigDecimal("0.10"));
    order.setRequestedPrice(new BigDecimal("1.08000"));
    order.setPrice(new BigDecimal("1.08000"));
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(new BigDecimal("0.10"));
    order.setClientOrderId("client-pending-1");
    order.setIdempotencyKey("client-pending-1");
    return order;
  }

  private static void alignExistingWithRequest(
      OrderEntity existing,
      CreateOrderRequest request
  ) {
    existing.setAccountId(request.accountId());
    existing.setSymbol(com.fxplatform.common.market.SymbolNormalizer.normalize(request.symbol()));
    existing.setSide(request.side());
    existing.setOrderType(request.orderType());
    existing.setLots(request.quantity());
    existing.setQuantity(request.quantity());
    existing.setOriginalQuantity(request.quantity());
    existing.setBaseQuantity(request.quantity());
    existing.setQuantityUnit(request.quantityUnit());
    existing.setRequestedPrice(request.price());
    existing.setPrice(request.price());
    existing.setStopLoss(request.stopLoss());
    existing.setTakeProfit(request.takeProfit());
    existing.setTriggerPrice(request.triggerPrice());
    existing.setTriggerPriceType(request.triggerPriceType());
    existing.setPositionSide(request.positionSide());
    existing.setMarginMode(request.marginMode());
    existing.setReduceOnly(request.reduceOnly());
    existing.setLeverage(request.leverage());
    existing.setClientOrderId(request.clientOrderId());
    existing.setIdempotencyKey(request.idempotencyKey() == null
        ? request.clientOrderId()
        : request.idempotencyKey());
    existing.setRemainingQuantity(request.quantity());
  }

  private OrderService orderService(OrderEventService orderEventService) {
    return new OrderService(
        orderRepository,
        accountRepository,
        riskCheckService,
        executionAdapter,
        new OrderFillService(orderRepository, tradeRepository, positionRepository, accountRepository, ledgerService),
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
        transactionExecutor);
  }

  private OrderService orderServiceWithSymbolRepository(
      OrderEventService orderEventService,
      DemoExecutionGuard guard
  ) {
    return new OrderService(
        orderRepository,
        accountRepository,
        riskCheckService,
        executionAdapter,
        new OrderFillService(orderRepository, tradeRepository, positionRepository, accountRepository, ledgerService),
        ledgerService,
        walletService,
        orderEventService,
        new OrderCommandFactory(),
        new OrderEntityFactory(),
        new OrderResponseMapper(),
        new OrderStatusPolicy(),
        guard,
        walletBalanceRepository,
        positionRepository,
        spotPositionService,
        marketBundleResolver,
        fullFillCoordinator,
        transactionExecutor,
        symbolRepository,
        null,
        null,
        null);
  }

  @Test
  void cancelProtectionCarrierDelegatesToProtectionStateAuthority() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    TradingAccountEntity account = demoAccount(userId, accountId);
    OrderEntity order = pendingOrderEntity(userId, accountId, orderId);
    order.setSymbol("BTCUSDT-PERP");
    order.setProductType(ProductType.LINEAR_PERP);
    order.setMarginMode(MarginMode.CROSS);
    order.setProtectionType(ProtectionType.TAKE_PROFIT);
    order.setParentPositionId(UUID.randomUUID());
    order.setHoldAmount(new BigDecimal("10.00000000"));
    when(orderRepository.findByUserIdAndId(userId, orderId)).thenReturn(Optional.of(order));
    ProtectionOrderService protectionOrderService =
        org.mockito.Mockito.mock(ProtectionOrderService.class);
    order.setStatus(OrderStatus.CANCELED);
    when(protectionOrderService.cancel(userId, orderId))
        .thenReturn(new OrderResponseMapper().toResponse(order));
    OrderService service = orderService(org.mockito.Mockito.mock(OrderEventService.class));
    service.setProtectionOrderService(protectionOrderService);

    OrderResponse response = service.cancelOrder(principal, orderId);

    assertThat(response.status()).isEqualTo(OrderStatus.CANCELED.name());
    verify(protectionOrderService).cancel(userId, orderId);
    verify(accountRepository, never()).findByIdAndUserIdForUpdate(any(), any());
    verify(ledgerService, never()).recordOrderRelease(any(), any(), any(), any());
  }

  @Test
  void compatibilityConstructorFailsClosedWhenPerpetualModifyAuthorityIsUnavailable() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    OrderEntity order = pendingOrderEntity(userId, accountId, orderId);
    order.setSymbol("BTCUSDT-PERP");
    order.setProductType(ProductType.LINEAR_PERP);
    order.setRequestedPrice(new BigDecimal("100"));
    order.setPrice(new BigDecimal("100"));
    when(orderRepository.findByUserIdAndId(userId, orderId)).thenReturn(Optional.of(order));

    assertThatThrownBy(() -> orderService(org.mockito.Mockito.mock(OrderEventService.class))
        .modifyOrder(principal, orderId, new UpdateOrderRequest(
            new BigDecimal("0.20"),
            new BigDecimal("99"),
            null,
            null)))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(com.fxplatform.common.exception.ErrorCode.EXECUTION_UNAVAILABLE));

    verify(orderRepository, never()).findByIdForUpdate(orderId);
    verify(accountRepository, never()).findByIdAndUserIdForUpdate(any(), any());
    verify(riskCheckService, never()).checkOrder(any(), any());
    verify(orderRepository, never()).save(any(OrderEntity.class));
  }

  @Test
  void compatibilityConstructorFailsClosedWhenSpotModifyAuthorityIsUnavailable() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    OrderEntity order = pendingOrderEntity(userId, accountId, orderId);
    order.setSymbol("BTCUSDT");
    order.setProductType(ProductType.CRYPTO_SPOT);
    order.setMarginMode(MarginMode.CASH);
    order.setOrderType(OrderType.STOP_LIMIT);
    order.setStatus(OrderStatus.PENDING_ACTIVATION);
    order.setRequestedPrice(new BigDecimal("100"));
    order.setPrice(new BigDecimal("100"));
    order.setTriggerPrice(new BigDecimal("110"));
    order.setTriggerPriceType(TriggerPriceType.LAST_PRICE);
    when(orderRepository.findByUserIdAndId(userId, orderId)).thenReturn(Optional.of(order));

    assertThatThrownBy(() -> orderService(org.mockito.Mockito.mock(OrderEventService.class))
        .modifyOrder(principal, orderId, new UpdateOrderRequest(
            new BigDecimal("0.20"),
            new BigDecimal("99"),
            null,
            null)))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(com.fxplatform.common.exception.ErrorCode.EXECUTION_UNAVAILABLE));

    verify(orderRepository, never()).findByIdForUpdate(orderId);
    verify(accountRepository, never()).findByIdAndUserIdForUpdate(any(), any());
    verify(riskCheckService, never()).checkOrder(any(), any());
    verify(orderRepository, never()).save(any(OrderEntity.class));
  }

  private static CreateOrderRequest p0MarketOrder(UUID accountId, String clientOrderId) {
    return new CreateOrderRequest(
        accountId,
        "BTCUSDT",
        OrderSide.BUY,
        OrderType.MARKET,
        new BigDecimal("0.10"),
        null,
        null,
        null,
        clientOrderId,
        clientOrderId,
        new BigDecimal("0.10"),
        null,
        1);
  }

  private static CreateOrderRequest advancedOrder(
      UUID accountId,
      String symbol,
      OrderType orderType,
      TimeInForce timeInForce,
      boolean postOnly,
      String key
  ) {
    boolean stopLimit = orderType == OrderType.STOP_LIMIT;
    return new CreateOrderRequest(
        accountId,
        symbol,
        OrderSide.BUY,
        orderType,
        null,
        null,
        null,
        null,
        key,
        key,
        new BigDecimal("0.10"),
        new BigDecimal("1.08000"),
        1,
        PositionSide.BOTH,
        QuantityUnit.BASE,
        symbol.equals("BTCUSDT") ? MarginMode.CASH : MarginMode.CROSS,
        stopLimit ? new BigDecimal("1.07000") : null,
        stopLimit ? TriggerPriceType.LAST_PRICE : null,
        false,
        List.of(),
        timeInForce,
        postOnly,
        null,
        null,
        null);
  }

  private static SpotMarketBundle spotBundle(String providerCode, Instant expiresAt) {
    return new SpotMarketBundle(
        "BTCUSDT",
        "BTCUSDT",
        providerCode,
        MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("99"),
        new BigDecimal("100"),
        new BigDecimal("99.5"),
        null,
        List.of(),
        List.of(),
        expiresAt.minusSeconds(1),
        expiresAt);
  }

  private static FullFillResult fullFill(String quantity, String price) {
    Instant now = Instant.now();
    BigDecimal baseQuantity = new BigDecimal(quantity);
    BigDecimal filledPrice = new BigDecimal(price);
    return new FullFillResult(
        filledPrice,
        now,
        baseQuantity,
        BigDecimal.ZERO,
        new BigDecimal("0.0005"),
        baseQuantity.multiply(new BigDecimal("0.0005"))
            .setScale(8, java.math.RoundingMode.HALF_UP),
        "BTC",
        com.fxplatform.trading.enums.LiquidityRole.TAKER,
        new BigDecimal("0.0100"),
        MarketSourceMode.PUBLIC_EXTERNAL,
        "binance",
        "BTCUSDT",
        now.minusSeconds(1),
        now.plusSeconds(2));
  }

  private static PerpetualMarketBundle perpBundle(Instant expiresAt) {
    return new PerpetualMarketBundle(
        "BTCUSDT-PERP",
        "BTCUSDT",
        "binance-usdm",
        MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("99"),
        new BigDecimal("100"),
        new BigDecimal("99.5"),
        new BigDecimal("99.6"),
        new BigDecimal("99.4"),
        null,
        List.of(),
        List.of(),
        expiresAt.minusSeconds(1),
        expiresAt);
  }

  private static ExecutionResult fullExecution(String price, Instant filledAt, String quantity) {
    return new ExecutionResult(
        new BigDecimal(price),
        filledAt,
        new BigDecimal(quantity),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        null,
        BigDecimal.ZERO,
        null,
        null);
  }
}
