package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import com.fxplatform.risk.service.PerpMarginCalculator;
import com.fxplatform.risk.service.PerpetualRiskService;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.risk.service.TradingInstrumentClassifier;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** RED coverage for binding every Perpetual DEPTH level to platform instrument authority. */
@ExtendWith(MockitoExtension.class)
class DepthPerpetualInstrumentAuthorityRoutingTest {

  private static final Instant NOW = Instant.parse("2026-07-17T10:00:00Z");
  private static final String SYMBOL = "BTCUSDT-PERP";

  @Mock private OrderRepository orderRepository;
  @Mock private TradeRepository tradeRepository;
  @Mock private TradingAccountRepository accountRepository;
  @Mock private RiskCheckService riskCheckService;
  @Mock private ExecutionAdapter executionAdapter;
  @Mock private LedgerService ledgerService;
  @Mock private WalletService walletService;
  @Mock private OrderEventService orderEventService;
  @Mock private DemoExecutionGuard demoExecutionGuard;
  @Mock private WalletBalanceRepository walletBalanceRepository;
  @Mock private PositionRepository positionRepository;
  @Mock private SpotPositionService spotPositionService;
  @Mock private MarketBundleResolver marketBundleResolver;
  @Mock private TradingTransactionExecutor transactionExecutor;
  @Mock private SymbolRepository symbolRepository;
  @Mock private AccountSymbolSettingRepository accountSymbolSettingRepository;
  @Mock private ProtectionOrderService protectionOrderService;

  @Test
  void multiLevelFokRejectsAnOffTickSecondFillBeforeMutation() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(level("100", "1"), level("100.05", "1")),
        null);

    BusinessException failure = submit(
        policy,
        TimeInForce.FOK,
        rules("10000"),
        "depth-perp-off-tick");

    assertThat(failure.getCode()).isIn(
        ErrorCode.PRICE_TICK_MISMATCH,
        "INVALID_INSTRUMENT_RULES");
    assertNoMutationStarted();
  }

  @Test
  void multiLevelFokRejectsActualFillNotionalAboveInstrumentMaximumBeforeMutation() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(level("100", "1"), level("200", "1")),
        null);

    BusinessException failure = submit(
        policy,
        TimeInForce.FOK,
        rules("250"),
        "depth-perp-actual-max-notional");

    assertThat(failure.getCode()).isEqualTo("ORDER_NOTIONAL_TOO_LARGE");
    assertNoMutationStarted();
  }

  @Test
  void cappedGtcRejectsConservativeTailNotionalAboveInstrumentMaximumBeforeMutation() {
    DemoExecutionPolicy policy = depthPolicy(
        List.of(level("100", "1"), level("200", "1")),
        new BigDecimal("1"));

    BusinessException failure = submit(
        policy,
        TimeInForce.GTC,
        rules("150"),
        "depth-perp-tail-max-notional");

    assertThat(failure.getCode()).isEqualTo("ORDER_NOTIONAL_TOO_LARGE");
    assertNoMutationStarted();
  }

  private BusinessException submit(
      DemoExecutionPolicy policy,
      TimeInForce timeInForce,
      InstrumentRules rules,
      String key
  ) {
    UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000461");
    UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000462");
    TradingAccountEntity account = account(userId, accountId);
    SymbolEntity symbol = symbol();

    when(orderRepository.findByUserIdAndIdempotencyKey(userId, key))
        .thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, key))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId))
        .thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol(SYMBOL)).thenReturn(Optional.of(symbol));
    when(marketBundleResolver.resolvePerp(eq(SYMBOL), any())).thenReturn(bundle());
    InstrumentRulesEngine instrumentRulesEngine = spy(new InstrumentRulesEngine(
        symbolRepository,
        null,
        null,
        null,
        null,
        new ObjectMapper(),
        new TradingInstrumentClassifier()));
    doReturn(rules).when(instrumentRulesEngine).rules(symbol);

    FullFillCoordinator fullFillCoordinator = new FullFillCoordinator(
        executionAdapter,
        Clock.fixed(NOW, ZoneOffset.UTC));
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
    DemoExecutionPolicyProvider stablePolicy = () -> policy;
    DepthOrderExecutionService depthExecution = new DepthOrderExecutionService(
        stablePolicy,
        tradeRepository,
        orderFillService,
        orderEventService,
        Clock.fixed(NOW, ZoneOffset.UTC));
    PerpetualAccountRiskSnapshotService accountRisk =
        new PerpetualAccountRiskSnapshotService(
            positionRepository,
            symbolRepository,
            marketBundleResolver,
            fullFillCoordinator,
            new PerpetualRiskService(new PerpMarginCalculator()));
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
        new OrderHoldCalculator(fullFillCoordinator),
        accountSymbolSettingRepository,
        new PerpetualOrderRiskService(fullFillCoordinator),
        accountRisk);
    service.setProtectionOrderService(protectionOrderService);
    service.setDepthOrderExecutionService(depthExecution);

    return catchThrowableOfType(
        () -> service.createOrder(
            new UserPrincipal(userId, "depth-perp-rules@example.com", "TRADER"),
            request(accountId, key, timeInForce)),
        BusinessException.class);
  }

  private void assertNoMutationStarted() {
    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any(OrderEntity.class));
    verify(tradeRepository, never()).save(any());
    verifyNoInteractions(ledgerService, orderEventService);
  }

  private static CreateOrderRequest request(
      UUID accountId,
      String key,
      TimeInForce timeInForce
  ) {
    return new CreateOrderRequest(
        accountId,
        SYMBOL,
        OrderSide.BUY,
        OrderType.MARKET,
        null,
        null,
        null,
        null,
        key,
        key,
        new BigDecimal("2.0000"),
        null,
        10,
        PositionSide.BOTH,
        QuantityUnit.BASE,
        MarginMode.CROSS,
        null,
        null,
        false,
        List.of(),
        timeInForce,
        false,
        null,
        null,
        null);
  }

  private static DemoExecutionPolicy depthPolicy(
      List<DemoBookLevel> asks,
      BigDecimal maxFillQuantityPerTick
  ) {
    return new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.001"),
        new BigDecimal("0.002"),
        new BigDecimal("0.001"),
        new BigDecimal("0.0001"),
        List.of(level("99", "5")),
        asks,
        maxFillQuantityPerTick);
  }

  private static DemoBookLevel level(String price, String quantity) {
    return new DemoBookLevel(new BigDecimal(price), new BigDecimal(quantity));
  }

  private static TradingAccountEntity account(UUID userId, UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    account.setBaseCurrency("USDT");
    account.setBalance(new BigDecimal("50000"));
    account.setEquity(new BigDecimal("50000"));
    account.setUsedMargin(BigDecimal.ZERO);
    account.setFreeMargin(new BigDecimal("50000"));
    account.setPositionMode(PositionMode.ONE_WAY);
    return account;
  }

  private static SymbolEntity symbol() {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(UUID.fromString("00000000-0000-0000-0000-000000000463"));
    symbol.setSymbol(SYMBOL);
    symbol.setProductType(ProductType.LINEAR_PERP);
    symbol.setBaseCurrency("BTC");
    symbol.setQuoteCurrency("USDT");
    symbol.setSettlementAsset("USDT");
    symbol.setMarginAsset("USDT");
    symbol.setContractSize(BigDecimal.ONE);
    symbol.setContractMultiplier(BigDecimal.ONE);
    symbol.setMaintenanceMarginRate(new BigDecimal("0.005"));
    symbol.setLeverage(100);
    return symbol;
  }

  private static InstrumentRules rules(String maxNotional) {
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
        new BigDecimal("0.1"),
        new BigDecimal("0.0001"),
        new BigDecimal("0.0001"),
        new BigDecimal("1000"),
        new BigDecimal("5"),
        new BigDecimal(maxNotional),
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
        SYMBOL,
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
