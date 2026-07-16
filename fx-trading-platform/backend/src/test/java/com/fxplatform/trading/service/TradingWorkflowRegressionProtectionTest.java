package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.execution.ExecutionAdapter;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutionResult;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.service.MarginCalculator;
import com.fxplatform.risk.service.PnLCalculator;
import com.fxplatform.risk.service.TradingAlgorithmEngine;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.dto.response.PositionResponse;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.entity.TradeEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TradingWorkflowRegressionProtectionTest {

  @Mock
  private OrderRepository orderRepository;

  @Mock
  private TradeRepository tradeRepository;

  @Mock
  private PositionRepository positionRepository;

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private ExecutionAdapter executionAdapter;

  @Mock
  private LedgerService ledgerService;

  @Mock
  private OrderEventService orderEventService;

  @Mock
  private QuoteService quoteService;

  @Mock
  private SymbolRepository symbolRepository;

  @Mock
  private WalletService walletService;

  @Mock
  private SpotSettlementService spotSettlementService;

  @Mock
  private SpotPositionService spotPositionService;

  @Mock
  private DemoExecutionGuard demoExecutionGuard;

  @Mock
  private WalletBalanceRepository walletBalanceRepository;

  @BeforeEach
  void rowLockQueriesReturnTheSameFixtureRows() {
    org.mockito.Mockito.lenient()
        .when(accountRepository.findByIdAndUserIdForUpdate(any(UUID.class), any(UUID.class)))
        .thenAnswer(invocation -> accountRepository.findByIdAndUserId(
            invocation.getArgument(0), invocation.getArgument(1)));
    org.mockito.Mockito.lenient().when(accountRepository.findByIdForUpdate(any(UUID.class)))
        .thenAnswer(invocation -> accountRepository.findById(invocation.getArgument(0)));
    org.mockito.Mockito.lenient().when(positionRepository.findByIdForUpdate(any(UUID.class)))
        .thenAnswer(invocation -> positionRepository.findById(invocation.getArgument(0)));
  }

  @Test
  void forexMarketBuyCreatesFilledOrderOpenPositionAndMarginLedgerEntries() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    TradingAccountEntity account = account(userId, accountId, new BigDecimal("10000.00000000"), BigDecimal.ZERO, 100);
    CreateOrderRequest request = marketOrder(accountId, "EURUSD", OrderSide.BUY, new BigDecimal("0.10"), "forex-open", 100);
    BigDecimal expectedMargin = new BigDecimal("110.02000000");
    BigDecimal expectedFee = new BigDecimal("1.10000000");
    Instant filledAt = Instant.parse("2026-06-16T01:00:00Z");

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "forex-open"))
        .thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "forex-open")).thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(forexSymbol()));
    when(quoteService.freshQuote("EURUSD")).thenReturn(quote("EURUSD", "1.10000", "1.10020"));
    when(accountRepository.reserveMarginIfAvailable(accountId, expectedMargin)).thenReturn(1);
    when(executionAdapter.execute(any(CreateOrderRequest.class))).thenReturn(new ExecutionResult(
        new BigDecimal("1.10020"),
        filledAt,
        new BigDecimal("0.10"),
        BigDecimal.ZERO,
        expectedFee,
        BigDecimal.ZERO,
        null,
        null));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> withOrderId(invocation.getArgument(0)));
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> withPositionId(invocation.getArgument(0)));

    OrderResponse response = orderService().createOrder(principal, request);

    assertThat(response.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(response.executionPrice()).isEqualByComparingTo("1.10020");
    assertThat(account.getUsedMargin()).isEqualByComparingTo(expectedMargin);
    assertThat(account.getFreeMargin()).isLessThan(new BigDecimal("10000.00000000"));

    ArgumentCaptor<OrderEntity> orderCaptor = ArgumentCaptor.forClass(OrderEntity.class);
    verify(orderRepository, atLeastOnce()).save(orderCaptor.capture());
    assertThat(orderCaptor.getAllValues().getLast().getStatus()).isEqualTo(OrderStatus.FILLED);

    ArgumentCaptor<PositionEntity> positionCaptor = ArgumentCaptor.forClass(PositionEntity.class);
    verify(positionRepository).save(positionCaptor.capture());
    PositionEntity position = positionCaptor.getValue();
    assertThat(position.getStatus()).isEqualTo(PositionStatus.OPEN);
    assertThat(position.getMarginHeld()).isEqualByComparingTo(expectedMargin);
    verify(tradeRepository).save(any(TradeEntity.class));
    verify(ledgerService).recordMarginHold(eq(account), eq(expectedMargin), eq(position.getId()), eq("Market order margin hold"));
    verify(ledgerService).recordTradeFeeForTrade(
        eq(account),
        eq(expectedFee),
        any(UUID.class),
        eq("Trade fee charged"));
  }

  @Test
  void forexPositionCloseMarksClosedReleasesMarginAndRecordsPnlLedger() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    TradingAccountEntity account = account(userId, accountId, new BigDecimal("10000.00000000"), new BigDecimal("300.00000000"), 100);
    PositionEntity position = openForexPosition(accountId, positionId);
    BigDecimal marginHeld = new BigDecimal("110.02000000");

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(forexSymbol()));
    when(quoteService.freshQuote("EURUSD")).thenReturn(quote("EURUSD", "1.10120", "1.10124"));
    when(positionRepository.closeIfOpen(position)).thenReturn(1);

    PositionResponse response = positionService().closePosition(userId, accountId, positionId);

    assertThat(response.status()).isEqualTo(PositionStatus.CLOSED.name());
    assertThat(response.marginHeld()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(response.realizedPnl()).isEqualByComparingTo("10.00000000");
    assertThat(position.getStatus()).isEqualTo(PositionStatus.CLOSED);
    assertThat(position.getMarginHeld()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(account.getUsedMargin()).isEqualByComparingTo("189.98000000");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("9820.02000000");
    verify(ledgerService).recordMarginRelease(eq(account), eq(marginHeld), eq(positionId), eq("Position margin released"));
    verify(ledgerService).recordTradePnl(eq(account), eq(new BigDecimal("10.00000000")), eq(positionId), eq("Position closed"));
  }

  @Test
  void spotCryptoMarketBuyUsesWalletSettlementWithoutCreatingMarginPosition() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(userId, "trader@example.com", "TRADER");
    TradingAccountEntity account = account(userId, accountId, new BigDecimal("20000.00000000"), BigDecimal.ZERO, 20);
    CreateOrderRequest request = marketOrder(accountId, "BTCUSDT", OrderSide.BUY, new BigDecimal("0.20"), "spot-open", 20);

    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, "spot-open"))
        .thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, "spot-open")).thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(spotSymbol()));
    when(quoteService.freshQuote("BTCUSDT")).thenReturn(quote("BTCUSDT", "49999.00000000", "50000.00000000"));
    when(walletService.getBalance(accountId, "USDT"))
        .thenReturn(Optional.of(wallet(accountId, "USDT", "20000.00000000")));
    when(executionAdapter.execute(any(CreateOrderRequest.class))).thenReturn(new ExecutionResult(
        new BigDecimal("50000.00000000"),
        Instant.parse("2026-06-16T01:05:00Z"),
        new BigDecimal("0.20"),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        null,
        BigDecimal.ZERO,
        null,
        null));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> withOrderId(invocation.getArgument(0)));

    OrderResponse order = orderService().createOrder(principal, request);

    assertThat(order.status()).isEqualTo(OrderStatus.FILLED.name());
    assertThat(order.leverage()).isEqualTo(1);
    assertThat(account.getUsedMargin()).isEqualByComparingTo(BigDecimal.ZERO);
    verify(accountRepository, never()).reserveMarginIfAvailable(eq(accountId), any(BigDecimal.class));
    verify(spotSettlementService).settleBuyFill(
        any(OrderEntity.class),
        any(ExecutionResult.class),
        any(SymbolEntity.class),
        eq(account),
        any(UUID.class));
    verify(positionRepository, never()).save(any(PositionEntity.class));
  }

  @Test
  void crossFlowMutationsPreserveGlobalLockOrder() throws IOException {
    assertTokensInOrder(
        "src/main/java/com/fxplatform/account/service/AccountTransferService.java",
        "private AccountTransferResponse transferLocked",
        "accountRepository.findByIdAndUserIdForUpdate",
        "walletService.lockBalancesInOrder",
        "positionRepository.findOpenLinearPerpByAccountIdForUpdate",
        "orderRepository.findActiveLinearPerpByAccountIdForUpdate",
        "ledgerService.recordTransfer");
    assertTokensInOrder(
        "src/main/java/com/fxplatform/trading/service/PendingOrderExecutionProcessor.java",
        "private boolean processLocked",
        "accountRepository.findByIdForUpdate",
        "walletBalanceRepository.findByAccountIdForUpdate",
        "spotPositionService.lockExisting",
        "orderRepository.findByIdForUpdate");
    assertTokensInOrder(
        "src/main/java/com/fxplatform/trading/service/CancelAllOrderService.java",
        "private BatchActionResponse cancelLocked",
        "accountRepository.findByIdForUpdate",
        "walletBalanceRepository.findByAccountIdForUpdate",
        "positionRepository.findOpenByAccountIdForUpdate",
        "orderRepository.findActive");
    assertTokensInOrder(
        "src/main/java/com/fxplatform/trading/service/FundingService.java",
        "public FundingSettlementOutcome settleFundingForPositionOutcome",
        "accountRepository.findByIdForUpdate",
        "positionRepository.findByIdForUpdate",
        "settleLockedPosition");
    assertTokensInOrder(
        "src/main/java/com/fxplatform/trading/service/LiquidationService.java",
        "private LockedRisk projectFresh",
        "accountRepository.findByIdForUpdate",
        "positionRepository.findOpenLinearPerpByAccountIdForUpdate",
        "orderRepository.findActiveLinearPerpByAccountIdForUpdate");
    assertTokensInOrder(
        "src/main/java/com/fxplatform/trading/service/SystemCloseOrderService.java",
        "private CloseResult persist(CloseIntent intent, PreparedClose prepared)",
        "accountRepository.findByIdForUpdate",
        "settingRepository",
        "findByAccountIdAndSymbolForUpdate",
        "positionRepository.findOpenLinearPerpBySymbolForUpdate",
        "orderRepository.findActiveLinearPerpBySymbolForUpdate");
    assertTokensInOrder(
        "src/main/java/com/fxplatform/trading/service/OcoOrderService.java",
        "private OcoOrderGroupResponse cancelLocked",
        "requireOwnedAccount(userId, accountId, true)",
        "lockExistingState(accountId)",
        "orderRepository.findByContingencyGroupIdForUpdate",
        "walletService.releaseLockedWithEntryType",
        "orderRepository.save",
        "orderEventService.record");
    assertTokensInOrder(
        "src/main/java/com/fxplatform/trading/service/OcoOrderService.java",
        "private void lockExistingState",
        "walletBalanceRepository.findByAccountIdForUpdate",
        "spotPositionService.lockExisting");
    assertTokensInOrder(
        "src/main/java/com/fxplatform/trading/service/ProtectionOrderService.java",
        "private OrderResponse updateLocked",
        "lockAccount",
        "lockSetting",
        "lockPosition",
        "orderRepository.findActiveLinearPerpBySymbolForUpdate",
        "orderRepository.updateById",
        "orderEventService.record");
    assertTokensInOrder(
        "src/main/java/com/fxplatform/trading/service/OrderService.java",
        "private OrderResponse persistP0Perpetual",
        "accountRepository.findByIdAndUserIdForUpdate",
        "accountSymbolSettingRepository",
        "findByAccountIdAndSymbolForUpdate",
        "positionRepository",
        "findOpenLinearPerpByAccountIdForUpdate",
        "orderRepository",
        "findActiveLinearPerpByAccountIdForUpdate",
        "ledgerService.recordOrderHold",
        "orderFillService.fillPerpetual",
        "orderEventService.record");
    assertTokensInOrder(
        "src/main/java/com/fxplatform/trading/service/TradingSettingsService.java",
        "private TradingSettingsResponse updateSymbolSettingsLocked",
        "requireOwnedAccountForUpdate",
        "settingRepository.findByAccountIdAndSymbolForUpdate",
        "positionRepository.findOpenLinearPerpByAccountIdForUpdate",
        "orderRepository.findActiveLinearPerpByAccountIdForUpdate");
  }

  private OrderService orderService() {
    RiskCheckService riskCheckService = new RiskCheckService(
        quoteService,
        symbolRepository,
        new MarginCalculator(new TradingAlgorithmEngine()),
        null,
        walletService);
    return new OrderService(
        orderRepository,
        accountRepository,
        riskCheckService,
        executionAdapter,
        new OrderFillService(
            orderRepository,
            tradeRepository,
            positionRepository,
            accountRepository,
            ledgerService,
            symbolRepository,
            spotSettlementService),
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
        spotPositionService);
  }

  private PositionService positionService() {
    TradingAlgorithmEngine engine = new TradingAlgorithmEngine();
    return new PositionService(
        positionRepository,
        accountRepository,
        quoteService,
        new PnLCalculator(engine),
        ledgerService,
        symbolRepository,
        demoExecutionGuard);
  }

  private static CreateOrderRequest marketOrder(
      UUID accountId,
      String symbol,
      OrderSide side,
      BigDecimal quantity,
      String clientOrderId,
      Integer leverage
  ) {
    return new CreateOrderRequest(
        accountId,
        symbol,
        side,
        OrderType.MARKET,
        quantity,
        null,
        null,
        null,
        clientOrderId,
        null,
        null,
        null,
        leverage);
  }

  private static TradingAccountEntity account(
      UUID userId,
      UUID accountId,
      BigDecimal balance,
      BigDecimal usedMargin,
      Integer leverage
  ) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setBaseCurrency("USD");
    account.setBalance(balance);
    account.setEquity(balance);
    account.setUsedMargin(usedMargin);
    account.setFreeMargin(balance.subtract(usedMargin));
    account.setLeverage(leverage);
    return account;
  }

  private static PositionEntity openForexPosition(UUID accountId, UUID positionId) {
    PositionEntity position = new PositionEntity();
    position.setId(positionId);
    position.setAccountId(accountId);
    position.setSymbol("EURUSD");
    position.setSide(OrderSide.BUY);
    position.setLots(new BigDecimal("0.10"));
    position.setOpenPrice(new BigDecimal("1.10020"));
    position.setCurrentPrice(new BigDecimal("1.10020"));
    position.setMarginHeld(new BigDecimal("110.02000000"));
    position.setStatus(PositionStatus.OPEN);
    position.setLeverage(100);
    return position;
  }

  private static SymbolEntity forexSymbol() {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol("EURUSD");
    symbol.setProductType(ProductType.FX_MARGIN);
    symbol.setAssetClass("FOREX");
    symbol.setBaseCurrency("EUR");
    symbol.setQuoteCurrency("USD");
    symbol.setLotSize(new BigDecimal("100000"));
    symbol.setLeverage(100);
    return symbol;
  }

  private static SymbolEntity spotSymbol() {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol("BTCUSDT");
    symbol.setProductType(ProductType.CRYPTO_SPOT);
    symbol.setAssetClass("SPOT");
    symbol.setBaseCurrency("BTC");
    symbol.setQuoteCurrency("USDT");
    symbol.setLotSize(BigDecimal.ONE);
    symbol.setLeverage(20);
    return symbol;
  }

  private static QuoteResponse quote(String symbol, String bid, String ask) {
    BigDecimal bidPrice = new BigDecimal(bid);
    BigDecimal askPrice = new BigDecimal(ask);
    return new QuoteResponse(
        "quote",
        symbol,
        bidPrice,
        askPrice,
        bidPrice.add(askPrice).divide(new BigDecimal("2")),
        askPrice.subtract(bidPrice),
        "test",
        1781667600000L);
  }

  private static WalletBalanceEntity wallet(UUID accountId, String asset, String available) {
    WalletBalanceEntity balance = new WalletBalanceEntity();
    balance.setAccountId(accountId);
    balance.setAsset(asset);
    balance.setTotal(new BigDecimal(available));
    balance.setAvailable(new BigDecimal(available));
    balance.setLocked(BigDecimal.ZERO);
    return balance;
  }

  private static OrderEntity withOrderId(OrderEntity order) {
    if (order.getId() == null) {
      order.setId(UUID.randomUUID());
    }
    return order;
  }

  private static PositionEntity withPositionId(PositionEntity position) {
    if (position.getId() == null) {
      position.setId(UUID.randomUUID());
    }
    return position;
  }

  private static void assertTokensInOrder(String sourcePath, String... tokens) throws IOException {
    String source = Files.readString(Path.of(sourcePath));
    int methodStart = source.indexOf(tokens[0]);
    assertThat(methodStart)
        .as("%s should contain target method %s", sourcePath, tokens[0])
        .isGreaterThanOrEqualTo(0);
    int bodyStart = source.indexOf('{', methodStart);
    assertThat(bodyStart).as("%s target method should have a body", sourcePath).isPositive();
    int bodyEnd = matchingBrace(source, bodyStart);
    String methodBody = source.substring(methodStart, bodyEnd + 1);
    int cursor = 0;
    for (String token : tokens) {
      int next = methodBody.indexOf(token, cursor);
      assertThat(next)
          .as("%s should contain %s after the preceding lock-order token", sourcePath, token)
          .isGreaterThanOrEqualTo(cursor);
      cursor = next + token.length();
    }
  }

  private static int matchingBrace(String source, int openingBrace) {
    int depth = 0;
    for (int index = openingBrace; index < source.length(); index++) {
      char character = source.charAt(index);
      if (character == '{') {
        depth++;
      } else if (character == '}' && --depth == 0) {
        return index;
      }
    }
    throw new AssertionError("Target method body has unbalanced braces");
  }
}
