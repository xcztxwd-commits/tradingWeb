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
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.execution.DemoBookLevel;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.DemoExecutionPolicyProvider;
import com.fxplatform.execution.DemoMatchingMode;
import com.fxplatform.execution.ExecutionAdapter;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.model.SpotMarketBundle;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DepthSpotFinalAuthorityGateTest {

  @Mock private OrderRepository orderRepository;
  @Mock private TradingAccountRepository accountRepository;
  @Mock private RiskCheckService riskCheckService;
  @Mock private ExecutionAdapter executionAdapter;
  @Mock private TradeRepository tradeRepository;
  @Mock private PositionRepository positionRepository;
  @Mock private LedgerService ledgerService;
  @Mock private WalletService walletService;
  @Mock private OrderEventService orderEventService;
  @Mock private DemoExecutionGuard demoExecutionGuard;
  @Mock private WalletBalanceRepository walletBalanceRepository;
  @Mock private SpotPositionService spotPositionService;
  @Mock private MarketBundleResolver marketBundleResolver;
  @Mock private FullFillCoordinator fullFillCoordinator;
  @Mock private TradingTransactionExecutor transactionExecutor;
  @Mock private SymbolRepository symbolRepository;
  @Mock private InstrumentRulesEngine instrumentRulesEngine;
  @Mock private OrderHoldCalculator orderHoldCalculator;
  @Mock private DemoExecutionPolicyProvider policyProvider;

  @BeforeEach
  void runTransactionsInline() {
    when(transactionExecutor.execute(any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
  }

  @Test
  void policyRotationDuringExistingWalletSelectFailsBeforeAnyMaterializationOrWrite() {
    Fixture fixture = fixture("wallet-policy-rotation", Clock.systemUTC(), freshBundle());
    AtomicReference<DemoExecutionPolicy> current =
        new AtomicReference<>(depthPolicy());
    when(policyProvider.current()).thenAnswer(invocation -> current.get());
    when(walletBalanceRepository.findByAccountIdForUpdate(fixture.accountId()))
        .thenAnswer(invocation -> {
          current.set(depthPolicy());
          return List.of();
        });

    assertMarketDataStale(fixture);

    verify(accountRepository, org.mockito.Mockito.times(2))
        .findByIdAndUserIdForUpdate(fixture.accountId(), fixture.userId());
    verify(walletBalanceRepository, org.mockito.Mockito.times(2))
        .findByAccountIdForUpdate(fixture.accountId());
    verify(spotPositionService, org.mockito.Mockito.times(2)).lockExisting(fixture.accountId());
    assertNoMaterializationOrWrite();
  }

  @Test
  void snapshotExpiryDuringExistingSpotLockFailsBeforeAnyMaterializationOrWrite() {
    Instant now = Instant.parse("2026-07-18T00:00:00Z");
    MutableClock clock = new MutableClock(now, ZoneId.of("UTC"));
    Fixture fixture = fixture(
        "spot-lock-expiry",
        clock,
        bundle(now.minusSeconds(1), now.plusSeconds(1)));
    DemoExecutionPolicy policy = depthPolicy();
    when(policyProvider.current()).thenReturn(policy);
    org.mockito.Mockito.doAnswer(invocation -> {
      clock.set(now.plusSeconds(2));
      return null;
    }).when(spotPositionService).lockExisting(fixture.accountId());

    assertMarketDataStale(fixture);

    verify(accountRepository).findByIdAndUserIdForUpdate(fixture.accountId(), fixture.userId());
    verify(walletBalanceRepository).findByAccountIdForUpdate(fixture.accountId());
    verify(spotPositionService).lockExisting(fixture.accountId());
    assertNoMaterializationOrWrite();
  }

  private void assertMarketDataStale(Fixture fixture) {
    assertThatThrownBy(() -> fixture.service().createOrder(fixture.principal(), fixture.request()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.MARKET_DATA_STALE));
  }

  private void assertNoMaterializationOrWrite() {
    verify(walletService, never()).lockBalancesInOrder(any(), any());
    verify(spotPositionService, never()).lockOrCreate(any(), any(), any());
    verify(orderRepository, never()).save(any(OrderEntity.class));
    verifyNoInteractions(ledgerService, orderEventService);
  }

  private Fixture fixture(String key, Clock clock, SpotMarketBundle bundle) {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(userId, accountId);
    SymbolEntity symbol = symbol();
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, key))
        .thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, key))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId))
        .thenReturn(Optional.of(account));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId))
        .thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol));
    when(instrumentRulesEngine.rules(symbol)).thenReturn(rules());
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any())).thenReturn(bundle);
    return new Fixture(
        userId,
        accountId,
        new UserPrincipal(userId, "trader@example.com", "TRADER"),
        request(accountId, key),
        service(clock));
  }

  private OrderService service(Clock clock) {
    SpotSettlementService settlementService =
        new SpotSettlementService(walletService, spotPositionService);
    OrderFillService orderFillService = new OrderFillService(
        orderRepository,
        tradeRepository,
        positionRepository,
        accountRepository,
        ledgerService,
        symbolRepository,
        settlementService,
        walletService);
    OrderService service = new OrderService(
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
        orderHoldCalculator);
    service.setDepthOrderExecutionService(new DepthOrderExecutionService(
        policyProvider,
        tradeRepository,
        orderFillService,
        orderEventService,
        clock));
    return service;
  }

  private static CreateOrderRequest request(UUID accountId, String key) {
    return new CreateOrderRequest(
        accountId,
        "BTCUSDT",
        OrderSide.BUY,
        OrderType.LIMIT,
        null,
        null,
        null,
        null,
        key,
        key,
        new BigDecimal("1.0000"),
        new BigDecimal("105"),
        1,
        PositionSide.BOTH,
        QuantityUnit.BASE,
        MarginMode.CASH,
        null,
        null,
        false,
        List.of());
  }

  private static DemoExecutionPolicy depthPolicy() {
    return new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.001"),
        new BigDecimal("0.002"),
        new BigDecimal("0.001"),
        new BigDecimal("0.0001"),
        List.of(new DemoBookLevel(new BigDecimal("99"), BigDecimal.ONE)),
        List.of(new DemoBookLevel(new BigDecimal("110"), BigDecimal.ONE)),
        null);
  }

  private static TradingAccountEntity account(UUID userId, UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    account.setBaseCurrency("USDT");
    account.setLeverage(1);
    account.setUsedMargin(BigDecimal.ZERO);
    return account;
  }

  private static SymbolEntity symbol() {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(UUID.randomUUID());
    symbol.setSymbol("BTCUSDT");
    symbol.setProductType(ProductType.CRYPTO_SPOT);
    symbol.setAssetClass("CRYPTO");
    symbol.setBaseCurrency("BTC");
    symbol.setQuoteCurrency("USDT");
    symbol.setLotSize(BigDecimal.ONE);
    symbol.setMinLot(new BigDecimal("0.0001"));
    symbol.setMaxLot(new BigDecimal("100"));
    symbol.setLeverage(1);
    return symbol;
  }

  private static InstrumentRules rules() {
    return new InstrumentRules(
        "BTCUSDT", true, true, true, true, true, true, true,
        ProductType.CRYPTO_SPOT,
        new BigDecimal("0.1"), new BigDecimal("0.0001"),
        new BigDecimal("0.0001"), new BigDecimal("100"),
        new BigDecimal("5"), null,
        new BigDecimal("0.0001"), new BigDecimal("100"),
        1, 1, "USDT", "USDT", BigDecimal.ONE, BigDecimal.ONE,
        "DEFAULT", "ALWAYS", "NONE", "NORMAL");
  }

  private static SpotMarketBundle freshBundle() {
    Instant now = Instant.now();
    return bundle(now.minusSeconds(1), now.plusSeconds(300));
  }

  private static SpotMarketBundle bundle(Instant asOf, Instant expiresAt) {
    return new SpotMarketBundle(
        "BTCUSDT",
        "BTCUSDT",
        "binance",
        MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("99"),
        new BigDecimal("100"),
        new BigDecimal("99.5"),
        null,
        List.of(),
        List.of(),
        asOf,
        expiresAt);
  }

  private record Fixture(
      UUID userId,
      UUID accountId,
      UserPrincipal principal,
      CreateOrderRequest request,
      OrderService service
  ) {
  }

  private static final class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    private MutableClock(Instant instant, ZoneId zone) {
      this.instant = instant;
      this.zone = zone;
    }

    private void set(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
