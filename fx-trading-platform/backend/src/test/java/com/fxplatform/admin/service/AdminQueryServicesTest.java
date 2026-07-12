package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.ledger.entity.LedgerEntryEntity;
import com.fxplatform.ledger.enums.LedgerEntryType;
import com.fxplatform.ledger.repository.LedgerEntryRepository;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.PriceAdjustmentRepository;
import com.fxplatform.market.repository.SymbolCategoryRepository;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.entity.TradeEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.FundingRateRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminQueryServicesTest {

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private OrderRepository orderRepository;

  @Mock
  private PositionRepository positionRepository;

  @Mock
  private TradeRepository tradeRepository;

  @Mock
  private LedgerEntryRepository ledgerEntryRepository;

  @Mock
  private SymbolRepository symbolRepository;

  @Mock
  private SymbolCategoryRepository symbolCategoryRepository;

  @Mock
  private PriceAdjustmentRepository priceAdjustmentRepository;

  @Mock
  private FundingRateRepository fundingRateRepository;

  @Test
  void accountsReturnPagedAdminDto() {
    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setBaseCurrency("USD");
    account.setBalance(new BigDecimal("1000.00"));
    account.setEquity(new BigDecimal("990.00"));
    account.setUsedMargin(new BigDecimal("10.00"));
    account.setFreeMargin(new BigDecimal("980.00"));
    account.setMarginLevel(new BigDecimal("99.00"));
    account.setLeverage(100);
    account.setStatus(AccountStatus.ACTIVE);
    account.setCreatedAt(Instant.parse("2026-06-08T00:00:00Z"));
    account.setUpdatedAt(Instant.parse("2026-06-08T00:01:00Z"));
    when(accountRepository.findAll(any(), eq(Map.of("createdAt", "created_at")), eq("createdAt"), eq(false)))
        .thenReturn(page(account));

    var page = new AdminAccountQueryService(accountRepository).accounts(0, 20);

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).id()).isEqualTo(accountId);
    assertThat(page.items().get(0).userId()).isEqualTo(userId);
    assertThat(page.items().get(0).balance()).isEqualByComparingTo("1000.00");
    assertThat(page.items().get(0).status()).isEqualTo("ACTIVE");
  }

  @Test
  void tradingQueriesReturnOrderAndPositionDtos() {
    UUID orderId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    OrderEntity order = new OrderEntity();
    order.setId(orderId);
    order.setAccountId(accountId);
    order.setUserId(UUID.randomUUID());
    order.setSymbol("EURUSD");
    order.setSide(OrderSide.BUY);
    order.setOrderType(OrderType.MARKET);
    order.setStatus(OrderStatus.FILLED);
    order.setLots(new BigDecimal("0.10"));
    order.setExecutionPrice(new BigDecimal("1.09000"));
    order.setCreatedAt(Instant.parse("2026-06-08T01:00:00Z"));
    order.setUpdatedAt(Instant.parse("2026-06-08T01:00:01Z"));

    UUID positionId = UUID.randomUUID();
    PositionEntity position = new PositionEntity();
    position.setId(positionId);
    position.setAccountId(accountId);
    position.setSymbol("EURUSD");
    position.setSide(OrderSide.BUY);
    position.setLots(new BigDecimal("0.10"));
    position.setOpenPrice(new BigDecimal("1.09000"));
    position.setCurrentPrice(new BigDecimal("1.09100"));
    position.setFloatingPnl(new BigDecimal("1.00"));
    position.setRealizedPnl(BigDecimal.ZERO);
    position.setMarginHeld(new BigDecimal("10.00"));
    position.setStatus(PositionStatus.OPEN);
    position.setOpenedAt(Instant.parse("2026-06-08T01:00:00Z"));

    TradeEntity trade = new TradeEntity();
    trade.setId(UUID.randomUUID());
    trade.setOrderId(orderId);
    trade.setAccountId(accountId);
    trade.setSymbol("EURUSD");
    trade.setSide(OrderSide.BUY);
    trade.setLots(new BigDecimal("0.10"));
    trade.setPrice(new BigDecimal("1.09000"));
    trade.setExecutedAt(Instant.parse("2026-06-08T01:00:02Z"));

    when(orderRepository.findAll(any(), eq(Map.of("createdAt", "created_at")), eq("createdAt"), eq(false)))
        .thenReturn(page(order));
    when(positionRepository.findAll(any(), eq(Map.of("openedAt", "opened_at")), eq("openedAt"), eq(false)))
        .thenReturn(page(position));
    when(tradeRepository.findAll(any(), eq(Map.of("executedAt", "executed_at")), eq("executedAt"), eq(false)))
        .thenReturn(page(trade));

    AdminTradingQueryService service = new AdminTradingQueryService(orderRepository, positionRepository, tradeRepository);
    var orders = service.orders(0, 20);
    var positions = service.positions(0, 20);
    var trades = service.trades(0, 20);

    assertThat(orders.items().get(0).id()).isEqualTo(orderId);
    assertThat(orders.items().get(0).executionPrice()).isEqualByComparingTo("1.09000");
    assertThat(positions.items().get(0).id()).isEqualTo(positionId);
    assertThat(positions.items().get(0).floatingPnl()).isEqualByComparingTo("1.00");
    assertThat(trades.items().get(0).orderId()).isEqualTo(orderId);
    assertThat(trades.items().get(0).price()).isEqualByComparingTo("1.09000");
  }

  @Test
  void financeAndMarketQueriesReturnPagedDtos() {
    LedgerEntryEntity ledger = new LedgerEntryEntity();
    ledger.setId(UUID.randomUUID());
    ledger.setAccountId(UUID.randomUUID());
    ledger.setEntryType(LedgerEntryType.DEMO_DEPOSIT);
    ledger.setAmount(new BigDecimal("250.00"));
    ledger.setBalanceAfter(new BigDecimal("1250.00"));
    ledger.setCurrency("USD");
    ledger.setReferenceType("DEPOSIT");
    ledger.setDescription("Deposit approved");
    ledger.setCreatedAt(Instant.parse("2026-06-08T02:00:00Z"));

    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(UUID.randomUUID());
    symbol.setSymbol("EURUSD");
    symbol.setDisplayName("Euro / US Dollar");
    symbol.setProvider("massive");
    symbol.setProviderSymbol("EURUSD");
    symbol.setAssetClass("FOREX");
    symbol.setBaseCurrency("EUR");
    symbol.setQuoteCurrency("USD");
    symbol.setPipSize(new BigDecimal("0.0001"));
    symbol.setTickSize(new BigDecimal("0.00001"));
    symbol.setLotSize(new BigDecimal("100000"));
    symbol.setMinLot(new BigDecimal("0.01"));
    symbol.setMaxLot(new BigDecimal("100"));
    symbol.setLeverage(100);
    symbol.setSpreadMarkup(new BigDecimal("0.00002"));
    symbol.setEnabled(true);

    when(ledgerEntryRepository.findAll(any(), eq(Map.of("createdAt", "created_at")), eq("createdAt"), eq(false)))
        .thenReturn(page(ledger));
    when(symbolRepository.findAll(any(), eq(Map.of("symbol", "symbol")), eq("symbol"), eq(true)))
        .thenReturn(page(symbol));

    var ledgerPage = new AdminFinanceQueryService(ledgerEntryRepository).ledger(0, 20);
    var symbolPage = new AdminMarketQueryService(
        symbolRepository,
        symbolCategoryRepository,
        priceAdjustmentRepository,
        fundingRateRepository).symbols(0, 20);

    assertThat(ledgerPage.items().get(0).entryType()).isEqualTo("DEMO_DEPOSIT");
    assertThat(ledgerPage.items().get(0).amount()).isEqualByComparingTo("250.00");
    assertThat(symbolPage.items().get(0).symbol()).isEqualTo("EURUSD");
    assertThat(symbolPage.items().get(0).enabled()).isTrue();
  }

  private static <T> Page<T> page(T item) {
    Page<T> page = Page.of(1, 20);
    page.setRecords(List.of(item));
    page.setTotal(1);
    return page;
  }
}
