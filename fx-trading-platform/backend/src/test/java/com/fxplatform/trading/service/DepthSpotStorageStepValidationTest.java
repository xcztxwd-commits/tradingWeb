package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.execution.DemoBookLevel;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.DemoExecutionPolicy;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DepthSpotStorageStepValidationTest {

  private static final BigDecimal EFFECTIVE_STORAGE_STEP = new BigDecimal("0.0002");

  @Test
  void baseRouteRejectsFillAndRemainderThatDoNotAlignWithEffectiveStorageStep() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(new DemoBookLevel(new BigDecimal("100"), new BigDecimal("0.0003"))),
        null);
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    String identity = "depth-storage-step-fill";
    RouteFixture fixture = routeFixture(policy, userId, accountId, identity);
    CreateOrderRequest request = request(
        accountId,
        OrderType.LIMIT,
        QuantityUnit.BASE,
        "0.0004",
        "105",
        identity);

    assertThatThrownBy(() -> fixture.service().createOrder(
        new UserPrincipal(userId, "trader@example.com", "TRADER"),
        request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("INVALID_INSTRUMENT_RULES"));
  }

  @Test
  void marketBuyRejectsNonAlignedCapWithStableInstrumentRulesError() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(new DemoBookLevel(new BigDecimal("100"), BigDecimal.ONE)),
        new BigDecimal("0.0003"));
    DepthOrderExecutionService service = depthService(policy);

    assertThatThrownBy(() -> service.convertSpotMarketBuy(
        new BigDecimal("100"), EFFECTIVE_STORAGE_STEP, policy))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("INVALID_INSTRUMENT_RULES"));
  }

  private static RouteFixture routeFixture(
      DemoExecutionPolicy policy,
      UUID userId,
      UUID accountId,
      String identity
  ) {
    OrderRepository orderRepository = mock(OrderRepository.class);
    TradingAccountRepository accountRepository = mock(TradingAccountRepository.class);
    RiskCheckService riskCheckService = mock(RiskCheckService.class);
    ExecutionAdapter executionAdapter = mock(ExecutionAdapter.class);
    OrderFillService orderFillService = mock(OrderFillService.class);
    LedgerService ledgerService = mock(LedgerService.class);
    WalletService walletService = mock(WalletService.class);
    OrderEventService orderEventService = mock(OrderEventService.class);
    DemoExecutionGuard demoExecutionGuard = mock(DemoExecutionGuard.class);
    WalletBalanceRepository walletBalanceRepository = mock(WalletBalanceRepository.class);
    PositionRepository positionRepository = mock(PositionRepository.class);
    SpotPositionService spotPositionService = mock(SpotPositionService.class);
    MarketBundleResolver marketBundleResolver = mock(MarketBundleResolver.class);
    FullFillCoordinator fullFillCoordinator = mock(FullFillCoordinator.class);
    TradingTransactionExecutor transactionExecutor = mock(TradingTransactionExecutor.class);
    SymbolRepository symbolRepository = mock(SymbolRepository.class);
    InstrumentRulesEngine instrumentRulesEngine = mock(InstrumentRulesEngine.class);
    OrderHoldCalculator orderHoldCalculator = mock(OrderHoldCalculator.class);
    TradeRepository tradeRepository = mock(TradeRepository.class);

    TradingAccountEntity account = account(userId, accountId);
    SymbolEntity symbol = symbol();
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, identity)).thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, identity))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId))
        .thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol));
    when(instrumentRulesEngine.rules(symbol)).thenReturn(rules());
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any())).thenReturn(bundle());

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
        () -> policy,
        tradeRepository,
        orderFillService,
        orderEventService));
    return new RouteFixture(service);
  }

  private static DepthOrderExecutionService depthService(DemoExecutionPolicy policy) {
    return new DepthOrderExecutionService(
        () -> policy,
        mock(TradeRepository.class),
        mock(OrderFillService.class),
        mock(OrderEventService.class));
  }

  private static DemoExecutionPolicy depthPolicy(
      List<DemoBookLevel> asks,
      BigDecimal maxFillQuantityPerTick
  ) {
    return new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.0002"),
        new BigDecimal("0.0005"),
        new BigDecimal("0.001"),
        new BigDecimal("0.0001"),
        List.of(),
        asks,
        maxFillQuantityPerTick);
  }

  private static CreateOrderRequest request(
      UUID accountId,
      OrderType orderType,
      QuantityUnit quantityUnit,
      String quantity,
      String price,
      String identity
  ) {
    return new CreateOrderRequest(
        accountId,
        "BTCUSDT",
        OrderSide.BUY,
        orderType,
        null,
        null,
        null,
        null,
        identity,
        identity,
        new BigDecimal(quantity),
        price == null ? null : new BigDecimal(price),
        1,
        PositionSide.BOTH,
        quantityUnit,
        MarginMode.CASH,
        null,
        null,
        false,
        List.of());
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
    symbol.setMinLot(EFFECTIVE_STORAGE_STEP);
    symbol.setMaxLot(new BigDecimal("100"));
    symbol.setLeverage(1);
    return symbol;
  }

  private static InstrumentRules rules() {
    return new InstrumentRules(
        "BTCUSDT",
        true,
        true,
        true,
        true,
        true,
        true,
        true,
        ProductType.CRYPTO_SPOT,
        new BigDecimal("0.1"),
        EFFECTIVE_STORAGE_STEP,
        EFFECTIVE_STORAGE_STEP,
        new BigDecimal("100"),
        BigDecimal.ZERO,
        null,
        new BigDecimal("0.0001"),
        new BigDecimal("100"),
        1,
        1,
        "USDT",
        "USDT",
        BigDecimal.ONE,
        BigDecimal.ONE,
        "DEFAULT",
        "ALWAYS",
        "NONE",
        "NORMAL");
  }

  private static SpotMarketBundle bundle() {
    Instant now = Instant.now();
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
        now.minusSeconds(1),
        now.plusSeconds(30));
  }

  private record RouteFixture(OrderService service) {
  }
}
