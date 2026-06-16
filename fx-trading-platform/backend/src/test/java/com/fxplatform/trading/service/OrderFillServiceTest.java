package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.execution.ExecutionResult;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import com.fxplatform.wallet.entity.AssetLedgerEntryEntity;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.repository.AssetLedgerEntryRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
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

    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol("BTCUSDT", "SPOT", "BTC", "USDT")));
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
    verify(spotSettlementService).settleBuyFill(eq(order), any(ExecutionResult.class), any(SymbolEntity.class), eq(account));
  }

  @Test
  void spotCryptoSellFillUsesWalletSettlementAndDoesNotCreateSellPosition() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    OrderEntity order = order(accountId);
    order.setSide(OrderSide.SELL);

    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol("BTCUSDT", "SPOT", "BTC", "USDT")));
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
    verify(spotSettlementService).settleSellFill(eq(order), any(ExecutionResult.class), any(SymbolEntity.class), eq(account));
  }

  @Test
  void spotPartialPendingFillClearsOrderHoldAfterSettlementReleasesRemainder() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    OrderEntity order = order(accountId);
    order.setStatus(OrderStatus.PENDING);
    order.setHoldAmount(new BigDecimal("5000.00000000"));
    order.setHoldCurrency("USDT");

    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol("BTCUSDT", "SPOT", "BTC", "USDT")));
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
        "Spot pending wallet hold");

    assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    assertThat(order.getRemainingQuantity()).isEqualByComparingTo("0.06");
    assertThat(order.getHoldAmount()).isEqualByComparingTo("0");
    verify(spotSettlementService).settleBuyFill(eq(order), any(ExecutionResult.class), any(SymbolEntity.class), eq(account));
    verify(positionRepository, never()).save(any(PositionEntity.class));
    verify(accountRepository, never()).reserveMarginIfAvailable(eq(accountId), any());
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
    when(walletBalanceRepository.findByAccountIdAndAsset(accountId, "BTC")).thenReturn(Optional.of(btcBalance));

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
    verify(ledgerService).recordTradeFee(eq(account), eq(new BigDecimal("0.00020000")), eq(savedPosition.get().getId()), eq("Trade fee charged"));
    ArgumentCaptor<AssetLedgerEntryEntity> assetEntry = ArgumentCaptor.forClass(AssetLedgerEntryEntity.class);
    verify(assetLedgerEntryRepository).save(assetEntry.capture());
    assertThat(assetEntry.getValue().getAsset()).isEqualTo("BTC");
    assertThat(assetEntry.getValue().getAmount()).isEqualByComparingTo("-0.00020000");
    assertThat(assetEntry.getValue().getBalanceAfter()).isEqualByComparingTo("0.99980000");
    assertThat(assetEntry.getValue().getEntryType()).isEqualTo("INVERSE_PERP_FEE");
    assertThat(assetEntry.getValue().getReferenceType()).isEqualTo("POSITION");
    assertThat(assetEntry.getValue().getReferenceId()).isEqualTo(savedPosition.get().getId());
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
    balance.setAsset(asset);
    balance.setTotal(new BigDecimal(total));
    balance.setAvailable(new BigDecimal(available));
    balance.setLocked(new BigDecimal(locked));
    return balance;
  }
}
