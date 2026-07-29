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
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.model.SpotMarketBundle;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.risk.service.TradingInstrumentClassifier;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** RED coverage for binding every Spot DEPTH level to platform instrument authority. */
@ExtendWith(MockitoExtension.class)
class DepthSpotInstrumentAuthorityRoutingTest {

  private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");
  private static final String SYMBOL = "BTCUSDT";

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
  @Mock private OrderHoldCalculator orderHoldCalculator;

  @Test
  void multiLevelLimitRejectsAnOffTickSecondFillBeforeTransactionOrFinancialWrite() {
    BusinessException failure = submit(
        depthPolicy(List.of(level("100.0", "1"), level("100.05", "1"))),
        request(OrderType.LIMIT, QuantityUnit.BASE, "2.0000", "101.0", "spot-limit-off-tick"),
        rules("10000"));

    assertTickAuthorityFailure(failure);
    assertNoMutationStarted();
  }

  @Test
  void multiLevelMarketRejectsAnOffTickSecondFillBeforeTransactionOrFinancialWrite() {
    BusinessException failure = submit(
        depthPolicy(List.of(level("100.0", "1"), level("100.05", "1"))),
        request(OrderType.MARKET, QuantityUnit.QUOTE, "201.00000000", null,
            "spot-market-off-tick"),
        rules("10000"));

    assertTickAuthorityFailure(failure);
    assertNoMutationStarted();
  }

  @Test
  void currentTickActualMultiLevelNotionalAboveMaximumFailsBeforeTransactionOrFinancialWrite() {
    BusinessException failure = submit(
        depthPolicy(List.of(level("100.0", "1"), level("200.0", "1"))),
        request(OrderType.MARKET, QuantityUnit.QUOTE, "301.00000000", null,
            "spot-actual-max-notional"),
        rules("250"));

    assertThat(failure).isNotNull();
    assertThat(failure.getCode()).isEqualTo("ORDER_NOTIONAL_TOO_LARGE");
    assertNoMutationStarted();
  }

  private BusinessException submit(
      DemoExecutionPolicy policy,
      CreateOrderRequest request,
      InstrumentRules rules
  ) {
    UUID userId = namedId("user-" + request.idempotencyKey());
    UUID accountId = request.accountId();
    TradingAccountEntity account = account(userId, accountId);
    SymbolEntity symbol = symbol();

    when(orderRepository.findByUserIdAndIdempotencyKey(userId, request.idempotencyKey()))
        .thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, request.clientOrderId()))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId))
        .thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol(SYMBOL)).thenReturn(Optional.of(symbol));
    when(marketBundleResolver.resolveSpot(eq(SYMBOL), any())).thenReturn(bundle());

    InstrumentRulesEngine instrumentRulesEngine = spy(new InstrumentRulesEngine(
        symbolRepository,
        null,
        null,
        null,
        null,
        new ObjectMapper(),
        new TradingInstrumentClassifier()));
    doReturn(rules).when(instrumentRulesEngine).rules(symbol);

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
    FullFillCoordinator fullFillCoordinator = new FullFillCoordinator(
        executionAdapter,
        Clock.fixed(NOW, ZoneOffset.UTC));
    DemoExecutionPolicyProvider stablePolicy = () -> policy;
    DepthOrderExecutionService depthExecution = new DepthOrderExecutionService(
        stablePolicy,
        tradeRepository,
        orderFillService,
        orderEventService,
        Clock.fixed(NOW, ZoneOffset.UTC));
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
    service.setDepthOrderExecutionService(depthExecution);

    return catchThrowableOfType(
        () -> service.createOrder(
            new UserPrincipal(userId, "depth-spot-rules@example.com", "TRADER"),
            request),
        BusinessException.class);
  }

  private void assertTickAuthorityFailure(BusinessException failure) {
    assertThat(failure).isNotNull();
    assertThat(failure.getCode()).isIn(
        ErrorCode.PRICE_TICK_MISMATCH,
        "INVALID_INSTRUMENT_RULES");
  }

  private void assertNoMutationStarted() {
    verify(transactionExecutor, never()).execute(any());
    verify(accountRepository, never()).save(any());
    verify(orderRepository, never()).save(any(OrderEntity.class));
    verify(tradeRepository, never()).save(any());
    verifyNoInteractions(ledgerService, walletService, spotPositionService, orderEventService);
  }

  private static CreateOrderRequest request(
      OrderType orderType,
      QuantityUnit quantityUnit,
      String quantity,
      String price,
      String key
  ) {
    return new CreateOrderRequest(
        namedId("account-" + key),
        SYMBOL,
        OrderSide.BUY,
        orderType,
        null,
        null,
        null,
        null,
        key,
        key,
        new BigDecimal(quantity),
        price == null ? null : new BigDecimal(price),
        1,
        PositionSide.BOTH,
        quantityUnit,
        MarginMode.CASH,
        null,
        null,
        false,
        List.of(),
        TimeInForce.FOK,
        false,
        null,
        null,
        null);
  }

  private static DemoExecutionPolicy depthPolicy(List<DemoBookLevel> asks) {
    return new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.001"),
        new BigDecimal("0.002"),
        new BigDecimal("0.001"),
        new BigDecimal("0.0001"),
        List.of(level("99.0", "5")),
        asks,
        null);
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
    account.setLeverage(1);
    account.setBalance(new BigDecimal("50000"));
    account.setEquity(new BigDecimal("50000"));
    account.setUsedMargin(BigDecimal.ZERO);
    account.setFreeMargin(new BigDecimal("50000"));
    return account;
  }

  private static SymbolEntity symbol() {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(namedId("symbol-" + SYMBOL));
    symbol.setSymbol(SYMBOL);
    symbol.setProductType(ProductType.CRYPTO_SPOT);
    symbol.setAssetClass("CRYPTO");
    symbol.setBaseCurrency("BTC");
    symbol.setQuoteCurrency("USDT");
    symbol.setLotSize(BigDecimal.ONE);
    symbol.setMinLot(new BigDecimal("0.0001"));
    symbol.setMaxLot(new BigDecimal("100"));
    symbol.setLeverage(1);
    symbol.setEnabled(true);
    symbol.setTradable(true);
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
        ProductType.CRYPTO_SPOT,
        new BigDecimal("0.1"),
        new BigDecimal("0.0001"),
        new BigDecimal("0.0001"),
        new BigDecimal("100"),
        new BigDecimal("5"),
        new BigDecimal(maxNotional),
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
    return new SpotMarketBundle(
        SYMBOL,
        SYMBOL,
        "binance",
        MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("99"),
        new BigDecimal("100"),
        new BigDecimal("99.5"),
        null,
        List.of(),
        List.of(),
        NOW.minusSeconds(1),
        NOW.plusSeconds(30));
  }

  private static UUID namedId(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }
}
