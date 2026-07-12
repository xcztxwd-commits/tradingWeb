package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.ExecutionResult;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import com.fxplatform.wallet.entity.AssetLedgerEntryEntity;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.enums.WalletType;
import com.fxplatform.wallet.repository.AssetLedgerEntryRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderFillServiceTest {

  @Mock
  private OrderRepository orderRepository;

  @Mock
  private TradeRepository tradeRepository;

  @Mock
  private PositionRepository positionRepository;

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private LedgerService ledgerService;

  @Mock
  private SymbolRepository symbolRepository;

  @Mock
  private WalletBalanceRepository walletBalanceRepository;

  @Mock
  private AssetLedgerEntryRepository assetLedgerEntryRepository;

  @BeforeEach
  void setUpWalletRepositories() {
    lenient().when(walletBalanceRepository.save(any(WalletBalanceEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient().when(assetLedgerEntryRepository.save(any(AssetLedgerEntryEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void forexFillsThroughOrderFillServiceMergeIntoExistingNetPosition() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setLeverage(100);
    AtomicReference<PositionEntity> openPosition = new AtomicReference<>();

    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(symbol("EURUSD", "FOREX", "EUR", "USD")));
    when(positionRepository.findOpenNetPosition(accountId, "EURUSD"))
        .thenAnswer(invocation -> Optional.ofNullable(openPosition.get()));
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      if (position.getId() == null) {
        position.setId(UUID.randomUUID());
      }
      openPosition.set(position);
      return position;
    });
    when(accountRepository.reserveMarginIfAvailable(eq(accountId), any(BigDecimal.class))).thenReturn(1);

    OrderFillService service = new OrderFillService(
        orderRepository,
        tradeRepository,
        positionRepository,
        accountRepository,
        ledgerService,
        symbolRepository,
        null);

    service.fill(
        forexOrder(accountId, OrderSide.BUY, new BigDecimal("0.10"), 100),
        account,
        new ExecutionResult(new BigDecimal("1.10000"), Instant.parse("2026-06-16T01:00:00Z"), new BigDecimal("0.10"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null),
        null,
        "Market order margin hold");
    UUID firstPositionId = openPosition.get().getId();

    service.fill(
        forexOrder(accountId, OrderSide.BUY, new BigDecimal("0.10"), 100),
        account,
        new ExecutionResult(new BigDecimal("1.20000"), Instant.parse("2026-06-16T01:01:00Z"), new BigDecimal("0.10"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null),
        null,
        "Market order margin hold");

    PositionEntity merged = openPosition.get();
    assertThat(merged.getId()).isEqualTo(firstPositionId);
    assertThat(merged.getSymbol()).isEqualTo("EURUSD");
    assertThat(merged.getLots()).isEqualByComparingTo("0.20");
    assertThat(merged.getOpenPrice()).isEqualByComparingTo("1.15000000");
    assertThat(merged.getMarginHeld()).isEqualByComparingTo("230.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("230.00000000");
    verify(positionRepository, times(2)).findOpenNetPosition(accountId, "EURUSD");
    verify(positionRepository, times(2)).save(any(PositionEntity.class));
  }

  @Test
  void spotCryptoBuyFillUsesWalletSettlementAndDoesNotCreateMarginPosition() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    OrderEntity order = order(accountId);

    SpotSettlementService spotSettlementService = org.mockito.Mockito.mock(SpotSettlementService.class);

    OrderFillService service = new OrderFillService(
        orderRepository,
        tradeRepository,
        positionRepository,
        accountRepository,
        ledgerService,
        symbolRepository,
        spotSettlementService);

    service.fill(
        order,
        account,
        new ExecutionResult(new BigDecimal("50000.00000000"), Instant.parse("2026-06-16T01:00:00Z"), new BigDecimal("0.20"), BigDecimal.ZERO, new BigDecimal("10.00000000"), BigDecimal.ZERO, null, null),
        null,
        "Spot fill");

    verify(positionRepository, never()).save(any(PositionEntity.class));
    assertThat(account.getUsedMargin()).isEqualByComparingTo(BigDecimal.ZERO);
    verify(accountRepository, never()).reserveMarginIfAvailable(eq(accountId), any());
    verify(ledgerService, never()).recordMarginHold(eq(account), any(), any(), any());
    verify(ledgerService, never()).recordTradeFee(eq(account), any(), any(), any());
    verify(spotSettlementService).settleBuyFill(
        eq(order),
        any(ExecutionResult.class),
        any(SymbolEntity.class),
        eq(account),
        any(UUID.class));
  }

  @Test
  void spotCryptoSellFillUsesWalletSettlementAndDoesNotCreateSellPosition() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    OrderEntity order = order(accountId);
    order.setSide(OrderSide.SELL);

    SpotSettlementService spotSettlementService = org.mockito.Mockito.mock(SpotSettlementService.class);

    OrderFillService service = new OrderFillService(
        orderRepository,
        tradeRepository,
        positionRepository,
        accountRepository,
        ledgerService,
        symbolRepository,
        spotSettlementService);

    service.fill(
        order,
        account,
        new ExecutionResult(new BigDecimal("55000.00000000"), Instant.parse("2026-06-16T01:00:00Z"), new BigDecimal("0.20"), BigDecimal.ZERO, new BigDecimal("11.00000000"), BigDecimal.ZERO, null, null),
        null,
        "Spot fill");

    verify(positionRepository, never()).save(any(PositionEntity.class));
    assertThat(account.getUsedMargin()).isEqualByComparingTo(BigDecimal.ZERO);
    verify(accountRepository, never()).reserveMarginIfAvailable(eq(accountId), any());
    verify(spotSettlementService).settleSellFill(
        eq(order),
        any(ExecutionResult.class),
        any(SymbolEntity.class),
        eq(account),
        any(UUID.class));
  }

  @Test
  void spotPartialPendingFillIsRejectedBeforeAnyMutation() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    OrderEntity order = order(accountId);
    order.setStatus(OrderStatus.PENDING);
    order.setLots(new BigDecimal("0.10"));
    order.setQuantity(new BigDecimal("0.10"));
    order.setBaseQuantity(new BigDecimal("0.10"));
    order.setRemainingQuantity(new BigDecimal("0.10"));
    order.setHoldAmount(new BigDecimal("5000.00000000"));
    order.setHoldCurrency("USDT");

    SpotSettlementService spotSettlementService = org.mockito.Mockito.mock(SpotSettlementService.class);

    OrderFillService service = new OrderFillService(
        orderRepository,
        tradeRepository,
        positionRepository,
        accountRepository,
        ledgerService,
        symbolRepository,
        spotSettlementService);

    assertThatThrownBy(() -> service.fill(
        order,
        account,
        new ExecutionResult(
            new BigDecimal("49000.00000000"),
            Instant.parse("2026-06-16T01:00:00Z"),
            new BigDecimal("0.04"),
            new BigDecimal("0.06"),
            new BigDecimal("1.96000000"),
            BigDecimal.ZERO,
            null,
            null),
        order.getHoldAmount(),
        "Spot pending wallet hold"))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("PARTIAL_FILL_NOT_SUPPORTED"));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(order.getRemainingQuantity()).isEqualByComparingTo("0.10");
    assertThat(order.getHoldAmount()).isEqualByComparingTo("5000.00000000");
    verify(orderRepository, never()).save(any(OrderEntity.class));
    verify(tradeRepository, never()).save(any());
    verify(spotSettlementService, never()).settleBuyFill(
        any(), any(), any(), any(), any());
    verify(positionRepository, never()).save(any(PositionEntity.class));
    verify(accountRepository, never()).reserveMarginIfAvailable(eq(accountId), any());
  }

  @Test
  void canonicalFullFillCopiesFeeRoleAndSourceMetadataToOneTrade() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    OrderEntity order = order(accountId);
    order.setProductType(ProductType.CRYPTO_SPOT);
    order.setBaseQuantity(new BigDecimal("0.20"));
    SpotSettlementService spotSettlementService = org.mockito.Mockito.mock(SpotSettlementService.class);
    when(symbolRepository.findBySymbol("BTCUSDT"))
        .thenReturn(Optional.of(symbol("BTCUSDT", "SPOT", "BTC", "USDT")));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(tradeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    Instant filledAt = Instant.parse("2026-07-12T02:00:00Z");
    FullFillResult fill = new FullFillResult(
        new BigDecimal("50005.00000000"),
        filledAt,
        new BigDecimal("0.20"),
        BigDecimal.ZERO,
        new BigDecimal("0.0005"),
        new BigDecimal("0.00010000"),
        "BTC",
        LiquidityRole.TAKER,
        new BigDecimal("5.00000000"),
        MarketSourceMode.LOCAL_SIMULATED,
        "local-spot",
        "BTCUSDT",
        filledAt.minusSeconds(1),
        filledAt.plusSeconds(2));
    OrderFillService service = new OrderFillService(
        orderRepository,
        tradeRepository,
        positionRepository,
        accountRepository,
        ledgerService,
        symbolRepository,
        spotSettlementService);

    service.fill(order, account, fill, BigDecimal.ZERO, "canonical fill");

    assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
    assertThat(order.getFee()).isEqualByComparingTo(fill.fee());
    assertThat(order.getFeeAsset()).isEqualTo("BTC");
    assertThat(order.getLiquidityRole()).isEqualTo(LiquidityRole.TAKER);
    ArgumentCaptor<com.fxplatform.trading.entity.TradeEntity> tradeCaptor =
        ArgumentCaptor.forClass(com.fxplatform.trading.entity.TradeEntity.class);
    verify(tradeRepository, times(1)).save(tradeCaptor.capture());
    assertThat(tradeCaptor.getValue().getFee()).isEqualByComparingTo(fill.fee());
    assertThat(tradeCaptor.getValue().getFeeAsset()).isEqualTo("BTC");
    assertThat(tradeCaptor.getValue().getLiquidityRole()).isEqualTo(LiquidityRole.TAKER);
    assertThat(tradeCaptor.getValue().getProductType()).isEqualTo(ProductType.CRYPTO_SPOT);
    assertThat(tradeCaptor.getValue().getSourceMode()).isEqualTo("LOCAL_SIMULATED");
    assertThat(tradeCaptor.getValue().getProviderCode()).isEqualTo("local-spot");
  }

  @Test
  void finalFillBoundaryRejectsLowerGreaterNullAndNonZeroRemainingQuantities() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    OrderFillService service = new OrderFillService(
        orderRepository,
        tradeRepository,
        positionRepository,
        accountRepository,
        ledgerService);
    Instant filledAt = Instant.parse("2026-07-12T02:00:00Z");
    List<ExecutionResult> invalid = List.of(
        execution("0.10", "0"),
        execution("0.30", "0"),
        new ExecutionResult(
            new BigDecimal("100"), filledAt, null, BigDecimal.ZERO,
            BigDecimal.ZERO, null, BigDecimal.ZERO, null, null),
        execution("0.20", "0.01"));

    for (ExecutionResult execution : invalid) {
      OrderEntity order = order(accountId);
      order.setBaseQuantity(new BigDecimal("0.20"));
      assertThatThrownBy(() -> service.fill(order, account, execution, BigDecimal.ZERO, "invalid"))
          .isInstanceOfSatisfying(BusinessException.class,
              exception -> assertThat(exception.getCode()).isEqualTo("PARTIAL_FILL_NOT_SUPPORTED"));
    }

    verify(orderRepository, never()).save(any());
    verify(tradeRepository, never()).save(any());
    verify(positionRepository, never()).save(any());
  }

  private static ExecutionResult execution(String filled, String remaining) {
    return new ExecutionResult(
        new BigDecimal("100"),
        Instant.parse("2026-07-12T02:00:00Z"),
        new BigDecimal(filled),
        new BigDecimal(remaining),
        BigDecimal.ZERO,
        null,
        BigDecimal.ZERO,
        null,
        null);
  }

  @Test
  void inversePerpetualFillWritesCoinSettledMarginSnapshot() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setLeverage(10);
    AtomicReference<PositionEntity> savedPosition = new AtomicReference<>();

    when(symbolRepository.findBySymbol("BTCUSD")).thenReturn(Optional.of(perpSymbol(
        "BTCUSD",
        "INVERSE_PERPETUAL",
        "BTC",
        "USD",
        "100",
        "1",
        "0.005",
        "BTC",
        "BTC")));
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      position.setId(UUID.randomUUID());
      savedPosition.set(position);
      return position;
    });
    when(accountRepository.reserveMarginIfAvailable(eq(accountId), any(BigDecimal.class))).thenReturn(1);

    OrderFillService service = new OrderFillService(
        orderRepository,
        tradeRepository,
        positionRepository,
        accountRepository,
        ledgerService,
        symbolRepository,
        null);

    service.fill(
        inverseOrder(accountId),
        account,
        new ExecutionResult(new BigDecimal("50000.00000000"), Instant.parse("2026-06-16T01:00:00Z"), new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null),
        null,
        "Market order margin hold");

    PositionEntity position = savedPosition.get();
    assertThat(position.getSymbol()).isEqualTo("BTCUSD");
    assertThat(position.getMarkPrice()).isEqualByComparingTo("50000.00000000");
    assertThat(position.getNotional()).isEqualByComparingTo("10000.00000000");
    assertThat(position.getInitialMargin()).isEqualByComparingTo("0.02000000");
    assertThat(position.getMaintenanceMargin()).isEqualByComparingTo("0.00100000");
    assertThat(position.getSettlementAsset()).isEqualTo("BTC");
    assertThat(position.getMarginAsset()).isEqualTo("BTC");
    assertThat(position.getMarginHeld()).isEqualByComparingTo(position.getInitialMargin());
  }

  @Test
  void inversePerpetualFillDebitsFeeAssetWalletAndWritesAssetLedgerEntry() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setBaseCurrency("BTC");
    account.setBalance(new BigDecimal("1.00000000"));
    account.setEquity(new BigDecimal("1.00000000"));
    account.setFreeMargin(new BigDecimal("1.00000000"));
    account.setLeverage(10);
    WalletBalanceEntity btcBalance = wallet(accountId, "BTC", "1.00000000", "1.00000000", "0");
    AtomicReference<PositionEntity> savedPosition = new AtomicReference<>();

    when(symbolRepository.findBySymbol("BTCUSD")).thenReturn(Optional.of(perpSymbol(
        "BTCUSD",
        "INVERSE_PERPETUAL",
        "BTC",
        "USD",
        "100",
        "1",
        "0.005",
        "BTC",
        "BTC")));
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      position.setId(UUID.randomUUID());
      savedPosition.set(position);
      return position;
    });
    when(accountRepository.reserveMarginIfAvailable(eq(accountId), any(BigDecimal.class))).thenReturn(1);
    when(walletBalanceRepository.findByAccountIdAndWalletTypeAndAssetForUpdate(
        accountId, WalletType.SPOT.code(), "BTC"))
        .thenReturn(Optional.of(btcBalance));

    OrderFillService service = new OrderFillService(
        orderRepository,
        tradeRepository,
        positionRepository,
        accountRepository,
        ledgerService,
        symbolRepository,
        null,
        new WalletService(walletBalanceRepository, assetLedgerEntryRepository));

    service.fill(
        inverseOrder(accountId),
        account,
        new ExecutionResult(
            new BigDecimal("50000.00000000"),
            Instant.parse("2026-06-16T01:00:00Z"),
            new BigDecimal("100"),
            BigDecimal.ZERO,
            new BigDecimal("0.00020000"),
            "BTC",
            BigDecimal.ZERO,
            null,
            null),
        null,
        "Market order margin hold");

    assertThat(btcBalance.getAvailable()).isEqualByComparingTo("0.99980000");
    assertThat(account.getBalance()).isEqualByComparingTo("0.99980000");
    verify(ledgerService).recordTradeFeeForTrade(
        eq(account),
        eq(new BigDecimal("0.00020000")),
        any(UUID.class),
        eq("Trade fee charged"));
    ArgumentCaptor<AssetLedgerEntryEntity> assetEntry = ArgumentCaptor.forClass(AssetLedgerEntryEntity.class);
    verify(assetLedgerEntryRepository).save(assetEntry.capture());
    assertThat(assetEntry.getValue().getAsset()).isEqualTo("BTC");
    assertThat(assetEntry.getValue().getAmount()).isEqualByComparingTo("-0.00020000");
    assertThat(assetEntry.getValue().getBalanceAfter()).isEqualByComparingTo("0.99980000");
    assertThat(assetEntry.getValue().getEntryType()).isEqualTo("TRADE_FEE");
    assertThat(assetEntry.getValue().getReferenceType()).isEqualTo("TRADE");
    assertThat(assetEntry.getValue().getReferenceId()).isNotNull();
  }

  private static TradingAccountEntity account(UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setBalance(new BigDecimal("20000.00000000"));
    account.setEquity(new BigDecimal("20000.00000000"));
    account.setUsedMargin(BigDecimal.ZERO);
    account.setFreeMargin(new BigDecimal("20000.00000000"));
    account.setLeverage(20);
    return account;
  }

  private static OrderEntity order(UUID accountId) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setAccountId(accountId);
    order.setSymbol("BTCUSDT");
    order.setSide(OrderSide.BUY);
    order.setStatus(OrderStatus.ACCEPTED);
    order.setLots(new BigDecimal("0.20"));
    order.setQuantity(new BigDecimal("0.20"));
    order.setLeverage(1);
    return order;
  }

  private static OrderEntity forexOrder(UUID accountId, OrderSide side, BigDecimal quantity, int leverage) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setAccountId(accountId);
    order.setSymbol("EURUSD");
    order.setSide(side);
    order.setStatus(OrderStatus.ACCEPTED);
    order.setLots(quantity);
    order.setQuantity(quantity);
    order.setLeverage(leverage);
    return order;
  }

  private static OrderEntity inverseOrder(UUID accountId) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setAccountId(accountId);
    order.setSymbol("BTCUSD");
    order.setSide(OrderSide.BUY);
    order.setStatus(OrderStatus.ACCEPTED);
    order.setLots(new BigDecimal("100"));
    order.setQuantity(new BigDecimal("100"));
    order.setLeverage(10);
    return order;
  }

  private static SymbolEntity symbol(String code, String assetClass, String baseCurrency, String quoteCurrency) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol(code);
    symbol.setAssetClass(assetClass);
    symbol.setBaseCurrency(baseCurrency);
    symbol.setQuoteCurrency(quoteCurrency);
    symbol.setLotSize("FOREX".equals(assetClass) ? new BigDecimal("100000") : BigDecimal.ONE);
    symbol.setLeverage(20);
    return symbol;
  }

  private static SymbolEntity perpSymbol(
      String code,
      String assetClass,
      String baseCurrency,
      String quoteCurrency,
      String contractSize,
      String contractMultiplier,
      String maintenanceMarginRate,
      String settlementAsset,
      String marginAsset
  ) {
    SymbolEntity symbol = symbol(code, assetClass, baseCurrency, quoteCurrency);
    symbol.setLotSize(new BigDecimal(contractSize));
    symbol.setContractSize(new BigDecimal(contractSize));
    symbol.setContractMultiplier(new BigDecimal(contractMultiplier));
    symbol.setMaintenanceMarginRate(new BigDecimal(maintenanceMarginRate));
    symbol.setSettlementAsset(settlementAsset);
    symbol.setMarginAsset(marginAsset);
    return symbol;
  }

  private static WalletBalanceEntity wallet(
      UUID accountId,
      String asset,
      String total,
      String available,
      String locked
  ) {
    WalletBalanceEntity balance = new WalletBalanceEntity();
    balance.setId(UUID.randomUUID());
    balance.setAccountId(accountId);
    balance.setWalletType(WalletType.SPOT.code());
    balance.setAsset(asset);
    balance.setTotal(new BigDecimal(total));
    balance.setAvailable(new BigDecimal(available));
    balance.setLocked(new BigDecimal(locked));
    return balance;
  }
}
