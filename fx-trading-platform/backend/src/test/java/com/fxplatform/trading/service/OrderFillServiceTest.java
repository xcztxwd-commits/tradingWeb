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
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.DemoFillIdentity;
import com.fxplatform.execution.DemoMatchFill;
import com.fxplatform.execution.DemoMatchingMode;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.ExecutionResult;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.entity.SpotPositionEntity;
import com.fxplatform.trading.entity.TradeEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.event.TradingAccountMutationEvent;
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
import java.math.RoundingMode;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

  @Mock
  private ApplicationEventPublisher accountMutationPublisher;

  @Mock
  private ProtectionOrderService protectionOrderService;

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
  void linearPerpetualDepthPartialFillConsumesOnlyItsHoldSlice() {
    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setUserId(userId);
    account.setPositionMode(PositionMode.ONE_WAY);
    account.setUsedMargin(new BigDecimal("50.50000000"));
    account.setFreeMargin(new BigDecimal("19949.50000000"));
    OrderEntity order = depthPerpetualOrder(
        accountId, OrderSide.BUY, "10", "50.50000000");
    order.setUserId(userId);
    Instant asOf = Instant.parse("2026-07-17T08:00:00Z");
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(perpSymbol(
        "BTCUSDT", "LINEAR_PERPETUAL", "BTC", "USDT", "1", "1", "0.005",
        "USDT", "USDT")));
    when(tradeRepository.findByOrderIdAndFillIdentity(order.getId(), "perp-depth-fill-1"))
        .thenReturn(Optional.empty());
    when(tradeRepository.save(any(TradeEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(orderRepository.save(any(OrderEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.save(any(TradingAccountEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, "BTCUSDT", PositionMode.ONE_WAY, PositionSide.BOTH))
        .thenReturn(Optional.empty());
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      if (position.getId() == null) {
        position.setId(UUID.randomUUID());
      }
      return position;
    });
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository);
    service.setAccountMutationPublisher(accountMutationPublisher);

    OrderEntity result = service.applyFill(
        order,
        order,
        account,
        takerFill("2", "90"),
        "perp-depth-fill-1",
        perpetualSnapshot(asOf, "100"),
        depthPolicy(),
        new BigDecimal("40.40000000"));

    assertThat(result).isSameAs(order);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    assertThat(order.getFilledQuantity()).isEqualByComparingTo("2.00000000");
    assertThat(order.getRemainingQuantity()).isEqualByComparingTo("8.00000000");
    assertThat(order.getAvgFillPrice()).isEqualByComparingTo("90.00000000");
    assertThat(order.getFee()).isEqualByComparingTo("0.09000000");
    assertThat(order.getFeeAsset()).isEqualTo("USDT");
    assertThat(order.getHoldAmount()).isEqualByComparingTo("40.40000000");
    assertThat(order.getFilledAt()).isNull();

    ArgumentCaptor<PositionEntity> positionCaptor = ArgumentCaptor.forClass(PositionEntity.class);
    verify(positionRepository).save(positionCaptor.capture());
    PositionEntity position = positionCaptor.getValue();
    assertThat(position.getSide()).isEqualTo(OrderSide.BUY);
    assertThat(position.getLots()).isEqualByComparingTo("2.00000000");
    assertThat(position.getOpenPrice()).isEqualByComparingTo("90.00000000");
    assertThat(position.getMarkPrice()).isEqualByComparingTo("100.00000000");
    assertThat(position.getNotional()).isEqualByComparingTo("200.00000000");
    assertThat(position.getInitialMargin()).isEqualByComparingTo("9.00000000");
    assertThat(position.getMarginHeld()).isEqualByComparingTo("9.00000000");
    assertThat(position.getMaintenanceMargin()).isEqualByComparingTo("1.00000000");
    assertThat(position.getFloatingPnl()).isEqualByComparingTo("20.00000000");

    assertThat(account.getUsedMargin()).isEqualByComparingTo("49.40000000");
    assertThat(account.getBalance()).isEqualByComparingTo("19999.91000000");
    assertThat(account.getEquity()).isEqualByComparingTo("20019.91000000");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("19970.51000000");

    ArgumentCaptor<TradeEntity> tradeCaptor = ArgumentCaptor.forClass(TradeEntity.class);
    verify(tradeRepository).save(tradeCaptor.capture());
    TradeEntity trade = tradeCaptor.getValue();
    assertThat(trade.getId()).isEqualTo(
        DemoFillIdentity.tradeId(order.getId(), "perp-depth-fill-1"));
    assertThat(trade.getCanonicalFullFill()).isFalse();
    assertThat(trade.getFillIdentity()).isEqualTo("perp-depth-fill-1");
    assertThat(trade.getProductType()).isEqualTo(ProductType.LINEAR_PERP);
    assertThat(trade.getLots()).isEqualByComparingTo("2.00000000");
    assertThat(trade.getPrice()).isEqualByComparingTo("90.00000000");
    assertThat(trade.getFee()).isEqualByComparingTo("0.09000000");
    assertThat(trade.getFeeAsset()).isEqualTo("USDT");
    assertThat(trade.getRealizedPnl()).isEqualByComparingTo("0.00000000");

    verify(ledgerService).recordOrderRelease(
        account, new BigDecimal("10.10000000"), order.getId(),
        "Perpetual order hold consumed");
    verify(ledgerService).recordMarginHold(
        account, new BigDecimal("9.00000000"), position.getId(),
        "Position margin held from order hold");
    verify(ledgerService).recordTradeFeeForTrade(
        account, new BigDecimal("0.09000000"), trade.getId(), "Trade fee charged");
    verify(orderRepository).save(order);
    ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
    verify(accountMutationPublisher, times(3)).publishEvent(events.capture());
    assertThat(events.getAllValues().stream()
        .map(TradingAccountMutationEvent.class::cast)
        .map(TradingAccountMutationEvent::type))
        .containsExactly("TRADE_CREATED", "BALANCE_UPDATED", "POSITION_UPDATED");
  }

  @Test
  void linearPerpetualDepthFeeRoundsOnlyAfterQuantityPriceAndRateAreMultiplied() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setPositionMode(PositionMode.ONE_WAY);
    account.setUsedMargin(new BigDecimal("0.00000050"));
    account.setFreeMargin(new BigDecimal("19999.99999950"));
    OrderEntity order = depthPerpetualOrder(
        accountId, OrderSide.BUY, "0.0001", "0.00000050");
    Instant asOf = Instant.parse("2026-07-17T08:00:30Z");
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(perpSymbol(
        "BTCUSDT", "LINEAR_PERPETUAL", "BTC", "USDT", "1", "1", "0.005",
        "USDT", "USDT")));
    when(tradeRepository.findByOrderIdAndFillIdentity(order.getId(), "perp-fee-rounding"))
        .thenReturn(Optional.empty());
    when(tradeRepository.save(any(TradeEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(orderRepository.save(any(OrderEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.save(any(TradingAccountEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, "BTCUSDT", PositionMode.ONE_WAY, PositionSide.BOTH))
        .thenReturn(Optional.empty());
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      position.setId(UUID.randomUUID());
      return position;
    });
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository);

    service.applyFill(
        order,
        order,
        account,
        takerFill("0.0001", "0.0999500000"),
        "perp-fee-rounding",
        perpetualSnapshot(asOf, "0.1000000000"),
        depthPolicy(),
        BigDecimal.ZERO);

    assertThat(order.getFee()).isEqualByComparingTo("0.00000000");
    ArgumentCaptor<TradeEntity> trade = ArgumentCaptor.forClass(TradeEntity.class);
    verify(tradeRepository).save(trade.capture());
    assertThat(trade.getValue().getFee()).isEqualByComparingTo("0.00000000");
    verify(ledgerService, never()).recordTradeFeeForTrade(any(), any(), any(), any());
  }

  @Test
  void linearPerpetualDepthIocPartialMarksParentTerminalOnLastAppliedFill() {
    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setUserId(userId);
    account.setPositionMode(PositionMode.ONE_WAY);
    account.setUsedMargin(new BigDecimal("10.10000000"));
    account.setFreeMargin(new BigDecimal("19989.90000000"));
    OrderEntity order = depthPerpetualOrder(
        accountId, OrderSide.BUY, "2", "10.10000000");
    order.setUserId(userId);
    AtomicReference<PositionEntity> openSlot = new AtomicReference<>();
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(perpSymbol(
        "BTCUSDT", "LINEAR_PERPETUAL", "BTC", "USDT", "1", "1", "0.005",
        "USDT", "USDT")));
    when(tradeRepository.save(any(TradeEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(orderRepository.save(any(OrderEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.save(any(TradingAccountEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, "BTCUSDT", PositionMode.ONE_WAY, PositionSide.BOTH))
        .thenAnswer(invocation -> Optional.ofNullable(openSlot.get()));
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      if (position.getId() == null) {
        position.setId(UUID.randomUUID());
      }
      openSlot.set(position);
      return position;
    });
    PositionEngine positionEngine = new PositionEngine(
        positionRepository,
        accountRepository,
        ledgerService,
        new com.fxplatform.risk.service.MarginCalculator(),
        new com.fxplatform.risk.service.PnLCalculator());
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository, null, positionEngine, null, protectionOrderService);

    service.applyFill(
        order,
        order,
        account,
        takerFill("1", "100"),
        "perp-ioc-terminal-partial",
        perpetualSnapshot(Instant.parse("2026-07-17T08:00:30Z"), "100"),
        depthPolicy(),
        new BigDecimal("5.05000000"),
        true);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    assertThat(order.getRemainingQuantity()).isEqualByComparingTo("1");
    verify(protectionOrderService).afterPerpetualFillLocked(
        eq(order), any(PositionEngine.PositionUpdateResult.class),
        eq(new BigDecimal("100")), eq(true));
  }

  @Test
  void linearPerpetualDepthFillsAccumulateIntoOnePositionUntilFinalAndReplayNoOps() {
    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setUserId(userId);
    account.setPositionMode(PositionMode.ONE_WAY);
    account.setUsedMargin(new BigDecimal("50.50000000"));
    account.setFreeMargin(new BigDecimal("19949.50000000"));
    OrderEntity order = depthPerpetualOrder(
        accountId, OrderSide.BUY, "10", "50.50000000");
    order.setUserId(userId);
    AtomicReference<PositionEntity> openSlot = new AtomicReference<>();
    Map<String, TradeEntity> trades = new HashMap<>();
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(perpSymbol(
        "BTCUSDT", "LINEAR_PERPETUAL", "BTC", "USDT", "1", "1", "0.005",
        "USDT", "USDT")));
    when(tradeRepository.findByOrderIdAndFillIdentity(eq(order.getId()), any()))
        .thenAnswer(invocation -> Optional.ofNullable(trades.get(invocation.getArgument(1))));
    when(tradeRepository.save(any(TradeEntity.class))).thenAnswer(invocation -> {
      TradeEntity trade = invocation.getArgument(0);
      trades.put(trade.getFillIdentity(), trade);
      return trade;
    });
    when(orderRepository.save(any(OrderEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.save(any(TradingAccountEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, "BTCUSDT", PositionMode.ONE_WAY, PositionSide.BOTH))
        .thenAnswer(invocation -> Optional.ofNullable(openSlot.get()));
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      if (position.getId() == null) {
        position.setId(UUID.randomUUID());
      }
      openSlot.set(position);
      return position;
    });
    PositionEngine positionEngine = new PositionEngine(
        positionRepository,
        accountRepository,
        ledgerService,
        new com.fxplatform.risk.service.MarginCalculator(),
        new com.fxplatform.risk.service.PnLCalculator());
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository, null, positionEngine, null, protectionOrderService);
    service.setAccountMutationPublisher(accountMutationPublisher);
    Instant firstAt = Instant.parse("2026-07-17T08:01:00Z");

    service.applyFill(
        order, order, account, takerFill("2", "90"), "perp-sequence-1",
        perpetualSnapshot(firstAt, "100"), depthPolicy(),
        new BigDecimal("40.40000000"));
    UUID positionId = openSlot.get().getId();
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    assertThat(order.getFilledQuantity()).isEqualByComparingTo("2.00000000");
    assertThat(order.getRemainingQuantity()).isEqualByComparingTo("8.00000000");
    assertThat(order.getAvgFillPrice()).isEqualByComparingTo("90.00000000");
    assertThat(order.getFee()).isEqualByComparingTo("0.09000000");
    assertThat(order.getHoldAmount()).isEqualByComparingTo("40.40000000");
    assertThat(order.getFilledAt()).isNull();

    service.applyFill(
        order, order, account, takerFill("3", "100"), "perp-sequence-2",
        perpetualSnapshot(firstAt.plusSeconds(1), "100"), depthPolicy(),
        new BigDecimal("25.25000000"));
    assertThat(openSlot.get().getId()).isEqualTo(positionId);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    assertThat(order.getFilledQuantity()).isEqualByComparingTo("5.00000000");
    assertThat(order.getRemainingQuantity()).isEqualByComparingTo("5.00000000");
    assertThat(order.getAvgFillPrice()).isEqualByComparingTo("96.00000000");
    assertThat(order.getFee()).isEqualByComparingTo("0.24000000");
    assertThat(order.getHoldAmount()).isEqualByComparingTo("25.25000000");
    assertThat(order.getFilledAt()).isNull();

    Instant finalAt = firstAt.plusSeconds(2);
    service.applyFill(
        order, order, account, takerFill("5", "100"), "perp-sequence-3",
        perpetualSnapshot(finalAt, "100"), depthPolicy(), BigDecimal.ZERO);

    PositionEntity position = openSlot.get();
    assertThat(position.getId()).isEqualTo(positionId);
    assertThat(position.getLots()).isEqualByComparingTo("10.00000000");
    assertThat(position.getOpenPrice()).isEqualByComparingTo("98.00000000");
    assertThat(position.getMarginHeld()).isEqualByComparingTo("49.00000000");
    assertThat(position.getInitialMargin()).isEqualByComparingTo("49.00000000");
    assertThat(position.getNotional()).isEqualByComparingTo("1000.00000000");
    assertThat(position.getMaintenanceMargin()).isEqualByComparingTo("5.00000000");
    assertThat(position.getFloatingPnl()).isEqualByComparingTo("20.00000000");
    assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
    assertThat(order.getFilledQuantity()).isEqualByComparingTo("10.00000000");
    assertThat(order.getRemainingQuantity()).isEqualByComparingTo("0.00000000");
    assertThat(order.getAvgFillPrice()).isEqualByComparingTo("98.00000000");
    assertThat(order.getFee()).isEqualByComparingTo("0.49000000");
    assertThat(order.getHoldAmount()).isEqualByComparingTo("0.00000000");
    assertThat(order.getFilledAt()).isEqualTo(finalAt);
    assertThat(account.getUsedMargin()).isEqualByComparingTo("49.00000000");
    assertThat(account.getBalance()).isEqualByComparingTo("19999.51000000");
    assertThat(account.getEquity()).isEqualByComparingTo("20019.51000000");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("19970.51000000");
    assertThat(trades).hasSize(3);
    assertThat(trades.values())
        .allSatisfy(trade -> assertThat(trade.getCanonicalFullFill()).isFalse());
    verify(positionRepository, times(3)).save(any(PositionEntity.class));
    verify(orderRepository, times(3)).save(order);
    verify(ledgerService).recordOrderRelease(
        account, new BigDecimal("10.10000000"), order.getId(),
        "Perpetual order hold consumed");
    verify(ledgerService).recordOrderRelease(
        account, new BigDecimal("15.15000000"), order.getId(),
        "Perpetual order hold consumed");
    verify(ledgerService).recordOrderRelease(
        account, new BigDecimal("25.25000000"), order.getId(),
        "Perpetual order hold consumed");
    verify(ledgerService).recordMarginHold(
        account, new BigDecimal("9.00000000"), positionId,
        "Position margin held from order hold");
    verify(ledgerService).recordMarginHold(
        account, new BigDecimal("15.00000000"), positionId,
        "Position margin held from order hold");
    verify(ledgerService).recordMarginHold(
        account, new BigDecimal("25.00000000"), positionId,
        "Position margin held from order hold");
    verify(ledgerService, never()).recordTradePnl(any(), any(), any(), any());
    ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
    verify(accountMutationPublisher, times(9)).publishEvent(events.capture());
    assertThat(events.getAllValues().stream()
        .map(TradingAccountMutationEvent.class::cast)
        .map(TradingAccountMutationEvent::type))
        .containsExactly(
            "TRADE_CREATED", "BALANCE_UPDATED", "POSITION_UPDATED",
            "TRADE_CREATED", "BALANCE_UPDATED", "POSITION_UPDATED",
            "TRADE_CREATED", "BALANCE_UPDATED", "POSITION_UPDATED");
    verify(protectionOrderService, times(2)).afterPerpetualFillLocked(
        eq(order), any(PositionEngine.PositionUpdateResult.class),
        eq(new BigDecimal("100")), eq(false));
    verify(protectionOrderService).afterPerpetualFillLocked(
        eq(order), any(PositionEngine.PositionUpdateResult.class),
        eq(new BigDecimal("100")), eq(true));

    org.mockito.Mockito.clearInvocations(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository, accountMutationPublisher, protectionOrderService);
    service.applyFill(
        order, order, account, takerFill("5", "100"), "perp-sequence-3",
        perpetualSnapshot(finalAt, "100"), depthPolicy(), BigDecimal.ZERO);
    assertThatThrownBy(() -> service.applyFill(
        order, order, account, takerFill("5", "101"), "perp-sequence-3",
        perpetualSnapshot(finalAt, "100"), depthPolicy(), BigDecimal.ZERO))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("FILL_IDENTITY_CONFLICT"));
    verify(tradeRepository, times(2))
        .findByOrderIdAndFillIdentity(order.getId(), "perp-sequence-3");
    verify(tradeRepository, never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(
        orderRepository, positionRepository, accountRepository, ledgerService,
        symbolRepository, accountMutationPublisher, protectionOrderService);
  }

  @Test
  void linearPerpetualDepthPartialReduceKeepsEntryAndRealizesOnlyClosedQuantity() {
    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setUserId(userId);
    account.setPositionMode(PositionMode.ONE_WAY);
    account.setEquity(new BigDecimal("20025.00000000"));
    account.setUsedMargin(new BigDecimal("25.22000000"));
    account.setFreeMargin(new BigDecimal("19999.78000000"));
    OrderEntity order = depthPerpetualOrder(
        accountId, OrderSide.SELL, "4", "0.22000000");
    order.setUserId(userId);
    order.setReduceOnly(true);
    PositionEntity position = openLinearPosition(
        accountId, OrderSide.BUY, "5", "100", "105", "25", "25");
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(perpSymbol(
        "BTCUSDT", "LINEAR_PERPETUAL", "BTC", "USDT", "1", "1", "0.005",
        "USDT", "USDT")));
    when(tradeRepository.findByOrderIdAndFillIdentity(order.getId(), "perp-reduce-1"))
        .thenReturn(Optional.empty());
    when(tradeRepository.save(any(TradeEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(orderRepository.save(any(OrderEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.save(any(TradingAccountEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, "BTCUSDT", PositionMode.ONE_WAY, PositionSide.BOTH))
        .thenReturn(Optional.of(position));
    when(positionRepository.save(any(PositionEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository);
    service.setAccountMutationPublisher(accountMutationPublisher);

    service.applyFill(
        order,
        order,
        account,
        takerFill("2", "110"),
        "perp-reduce-1",
        perpetualSnapshot(Instant.parse("2026-07-17T08:02:00Z"), "105"),
        depthPolicy(),
        new BigDecimal("0.11000000"));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    assertThat(order.getFilledQuantity()).isEqualByComparingTo("2.00000000");
    assertThat(order.getRemainingQuantity()).isEqualByComparingTo("2.00000000");
    assertThat(order.getAvgFillPrice()).isEqualByComparingTo("110.00000000");
    assertThat(order.getFee()).isEqualByComparingTo("0.11000000");
    assertThat(order.getHoldAmount()).isEqualByComparingTo("0.11000000");
    assertThat(position.getStatus()).isEqualTo(PositionStatus.OPEN);
    assertThat(position.getSide()).isEqualTo(OrderSide.BUY);
    assertThat(position.getLots()).isEqualByComparingTo("3.00000000");
    assertThat(position.getOpenPrice()).isEqualByComparingTo("100.00000000");
    assertThat(position.getMarkPrice()).isEqualByComparingTo("105.00000000");
    assertThat(position.getMarginHeld()).isEqualByComparingTo("15.00000000");
    assertThat(position.getInitialMargin()).isEqualByComparingTo("15.00000000");
    assertThat(position.getNotional()).isEqualByComparingTo("315.00000000");
    assertThat(position.getMaintenanceMargin()).isEqualByComparingTo("1.57500000");
    assertThat(position.getFloatingPnl()).isEqualByComparingTo("15.00000000");
    assertThat(position.getRealizedPnl()).isEqualByComparingTo("20.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("15.11000000");
    assertThat(account.getBalance()).isEqualByComparingTo("20019.89000000");
    assertThat(account.getEquity()).isEqualByComparingTo("20034.89000000");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("20019.78000000");

    ArgumentCaptor<TradeEntity> trade = ArgumentCaptor.forClass(TradeEntity.class);
    verify(tradeRepository).save(trade.capture());
    assertThat(trade.getValue().getRealizedPnl()).isEqualByComparingTo("20.00000000");
    assertThat(trade.getValue().getFee()).isEqualByComparingTo("0.11000000");
    verify(ledgerService).recordOrderRelease(
        account, new BigDecimal("0.11000000"), order.getId(),
        "Perpetual order hold consumed");
    verify(ledgerService).recordMarginRelease(
        account, new BigDecimal("10.00000000"), position.getId(),
        "Position margin released");
    verify(ledgerService).recordTradePnl(
        account, new BigDecimal("20.00000000"), position.getId(),
        "Position realized PnL");
    verify(ledgerService).recordTradeFeeForTrade(
        account, new BigDecimal("0.11000000"), trade.getValue().getId(),
        "Trade fee charged");
    ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
    verify(accountMutationPublisher, times(3)).publishEvent(events.capture());
    assertThat(events.getAllValues().stream()
        .map(TradingAccountMutationEvent.class::cast)
        .map(TradingAccountMutationEvent::type))
        .containsExactly("TRADE_CREATED", "BALANCE_UPDATED", "POSITION_UPDATED");
  }

  @Test
  void linearPerpetualDepthOneWayFlipClosesOldSideThenOpensOnlyRemainder() {
    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setUserId(userId);
    account.setPositionMode(PositionMode.ONE_WAY);
    account.setUsedMargin(new BigDecimal("24.63500000"));
    account.setFreeMargin(new BigDecimal("19975.36500000"));
    OrderEntity order = depthPerpetualOrder(
        accountId, OrderSide.SELL, "3", "14.63500000");
    order.setUserId(userId);
    PositionEntity existing = openLinearPosition(
        accountId, OrderSide.BUY, "2", "100", "100", "10", "0");
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(perpSymbol(
        "BTCUSDT", "LINEAR_PERPETUAL", "BTC", "USDT", "1", "1", "0.005",
        "USDT", "USDT")));
    when(tradeRepository.findByOrderIdAndFillIdentity(order.getId(), "perp-flip-1"))
        .thenReturn(Optional.empty());
    when(tradeRepository.save(any(TradeEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(orderRepository.save(any(OrderEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.save(any(TradingAccountEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, "BTCUSDT", PositionMode.ONE_WAY, PositionSide.BOTH))
        .thenReturn(Optional.of(existing));
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      if (position.getId() == null) {
        position.setId(UUID.randomUUID());
      }
      return position;
    });
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository);
    service.setAccountMutationPublisher(accountMutationPublisher);

    service.applyFill(
        order,
        order,
        account,
        takerFill("3", "90"),
        "perp-flip-1",
        perpetualSnapshot(Instant.parse("2026-07-17T08:02:15Z"), "95"),
        depthPolicy(),
        BigDecimal.ZERO);

    ArgumentCaptor<PositionEntity> positions = ArgumentCaptor.forClass(PositionEntity.class);
    verify(positionRepository, times(2)).save(positions.capture());
    PositionEntity closed = positions.getAllValues().get(0);
    PositionEntity opened = positions.getAllValues().get(1);
    assertThat(closed).isSameAs(existing);
    assertThat(closed.getStatus()).isEqualTo(PositionStatus.CLOSED);
    assertThat(closed.getRealizedPnl()).isEqualByComparingTo("-20.00000000");
    assertThat(closed.getMarginHeld()).isEqualByComparingTo("0.00000000");
    assertThat(opened.getId()).isNotEqualTo(closed.getId());
    assertThat(opened.getStatus()).isEqualTo(PositionStatus.OPEN);
    assertThat(opened.getSide()).isEqualTo(OrderSide.SELL);
    assertThat(opened.getLots()).isEqualByComparingTo("1.00000000");
    assertThat(opened.getOpenPrice()).isEqualByComparingTo("90.00000000");
    assertThat(opened.getMarkPrice()).isEqualByComparingTo("95.00000000");
    assertThat(opened.getMarginHeld()).isEqualByComparingTo("4.50000000");
    assertThat(opened.getNotional()).isEqualByComparingTo("95.00000000");
    assertThat(opened.getMaintenanceMargin()).isEqualByComparingTo("0.47500000");
    assertThat(opened.getFloatingPnl()).isEqualByComparingTo("-5.00000000");
    assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
    assertThat(order.getFilledQuantity()).isEqualByComparingTo("3.00000000");
    assertThat(order.getRemainingQuantity()).isEqualByComparingTo("0.00000000");
    assertThat(order.getHoldAmount()).isEqualByComparingTo("0.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("4.50000000");
    assertThat(account.getBalance()).isEqualByComparingTo("19979.86500000");
    assertThat(account.getEquity()).isEqualByComparingTo("19974.86500000");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("19970.36500000");

    ArgumentCaptor<TradeEntity> trade = ArgumentCaptor.forClass(TradeEntity.class);
    verify(tradeRepository).save(trade.capture());
    assertThat(trade.getValue().getRealizedPnl()).isEqualByComparingTo("-20.00000000");
    verify(ledgerService).recordOrderRelease(
        account, new BigDecimal("14.63500000"), order.getId(),
        "Perpetual order hold consumed");
    verify(ledgerService).recordMarginRelease(
        account, new BigDecimal("10"), existing.getId(),
        "Position margin released");
    verify(ledgerService).recordMarginHold(
        account, new BigDecimal("4.50000000"), opened.getId(),
        "Reversed position margin held");
    verify(ledgerService).recordTradePnl(
        account, new BigDecimal("-20.00000000"), existing.getId(),
        "Position realized PnL");
    ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
    verify(accountMutationPublisher, times(3)).publishEvent(events.capture());
    assertThat(events.getAllValues().stream()
        .map(TradingAccountMutationEvent.class::cast)
        .map(TradingAccountMutationEvent::type))
        .containsExactly("TRADE_CREATED", "BALANCE_UPDATED", "POSITION_CLOSED");
  }

  @Test
  void linearPerpetualDepthReduceOnlyCannotCrossOneWayPosition() {
    assertPerpetualDepthCrossingRejected(
        PositionMode.ONE_WAY, PositionSide.BOTH, true, "perp-reduce-cross");
  }

  @Test
  void linearPerpetualDepthHedgeCloseCannotCrossItsLongSlot() {
    assertPerpetualDepthCrossingRejected(
        PositionMode.HEDGE, PositionSide.LONG, false, "perp-hedge-cross");
  }

  @Test
  void linearPerpetualDepthRejectsIncoherentUsedMarginBeforeSavingPosition() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setPositionMode(PositionMode.ONE_WAY);
    account.setUsedMargin(new BigDecimal("10.00000000"));
    account.setFreeMargin(new BigDecimal("19990.00000000"));
    OrderEntity order = depthPerpetualOrder(
        accountId, OrderSide.BUY, "2", "20.00000000");
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(perpSymbol(
        "BTCUSDT", "LINEAR_PERPETUAL", "BTC", "USDT", "1", "1", "0.005",
        "USDT", "USDT")));
    when(tradeRepository.findByOrderIdAndFillIdentity(order.getId(), "perp-bad-used"))
        .thenReturn(Optional.empty());
    when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, "BTCUSDT", PositionMode.ONE_WAY, PositionSide.BOTH))
        .thenReturn(Optional.empty());
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository);

    assertThatThrownBy(() -> service.applyFill(
        order, order, account, takerFill("2", "90"), "perp-bad-used",
        perpetualSnapshot(Instant.parse("2026-07-17T08:02:30Z"), "100"),
        depthPolicy(), BigDecimal.ZERO))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("ORDER_HOLD_INVALID"));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(order.getHoldAmount()).isEqualByComparingTo("20.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("10.00000000");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("19990.00000000");
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(orderRepository, never()).save(any());
    verify(tradeRepository, never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(ledgerService, accountMutationPublisher);
  }

  @Test
  void linearPerpetualDepthRejectsDerivedMarkNotionalOverflowBeforeAnySave() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setPositionMode(PositionMode.ONE_WAY);
    account.setBalance(new BigDecimal("100000000.00000000"));
    account.setEquity(new BigDecimal("100000000.00000000"));
    account.setUsedMargin(new BigDecimal("5049999.99999500"));
    account.setFreeMargin(new BigDecimal("94950000.00000500"));
    OrderEntity order = depthPerpetualOrder(
        accountId, OrderSide.BUY, "99999999.9999", "5049999.99999500");
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(perpSymbol(
        "BTCUSDT", "LINEAR_PERPETUAL", "BTC", "USDT", "1", "1", "0.005",
        "USDT", "USDT")));
    when(tradeRepository.findByOrderIdAndFillIdentity(order.getId(), "perp-derived-overflow"))
        .thenReturn(Optional.empty());
    when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, "BTCUSDT", PositionMode.ONE_WAY, PositionSide.BOTH))
        .thenReturn(Optional.empty());
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository);

    assertThatThrownBy(() -> service.applyFill(
        order,
        order,
        account,
        takerFill("99999999.9999", "1.0000000000"),
        "perp-derived-overflow",
        perpetualSnapshot(
            Instant.parse("2026-07-17T08:03:00Z"),
            "99999999999999.9999999999"),
        depthPolicy(),
        BigDecimal.ZERO))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("INVALID_DEPTH_FILL"));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(account.getBalance()).isEqualByComparingTo("100000000.00000000");
    assertThat(account.getEquity()).isEqualByComparingTo("100000000.00000000");
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(orderRepository, never()).save(any());
    verify(tradeRepository, never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(ledgerService, accountMutationPublisher);
  }

  @Test
  void linearPerpetualDepthRejectsPostFeeAccountOverflowBeforeAnySave() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setPositionMode(PositionMode.ONE_WAY);
    account.setBalance(new BigDecimal("-9999999999999999.99999999"));
    account.setEquity(new BigDecimal("-9999999999999999.99999999"));
    account.setUsedMargin(new BigDecimal("0.00000101"));
    account.setFreeMargin(new BigDecimal("-9999999999999999.99999999"));
    OrderEntity order = depthPerpetualOrder(
        accountId, OrderSide.BUY, "0.0001", "0.00000101");
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(perpSymbol(
        "BTCUSDT", "LINEAR_PERPETUAL", "BTC", "USDT", "1", "1", "0.005",
        "USDT", "USDT")));
    when(tradeRepository.findByOrderIdAndFillIdentity(order.getId(), "perp-post-fee-overflow"))
        .thenReturn(Optional.empty());
    when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, "BTCUSDT", PositionMode.ONE_WAY, PositionSide.BOTH))
        .thenReturn(Optional.empty());
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository);

    assertThatThrownBy(() -> service.applyFill(
        order,
        order,
        account,
        takerFill("0.0001", "0.2000000000"),
        "perp-post-fee-overflow",
        perpetualSnapshot(Instant.parse("2026-07-17T08:03:15Z"), "0.2000000000"),
        depthPolicy(),
        BigDecimal.ZERO))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("INVALID_DEPTH_FILL"));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(account.getBalance())
        .isEqualByComparingTo("-9999999999999999.99999999");
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(orderRepository, never()).save(any());
    verify(tradeRepository, never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(ledgerService, accountMutationPublisher);
  }

  @Test
  void linearPerpetualDepthRejectsUnderfundedOpeningSliceBeforeAnySave() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setPositionMode(PositionMode.ONE_WAY);
    account.setUsedMargin(new BigDecimal("9.00000000"));
    account.setFreeMargin(new BigDecimal("19991.00000000"));
    OrderEntity order = depthPerpetualOrder(
        accountId, OrderSide.BUY, "2", "9.00000000");
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(perpSymbol(
        "BTCUSDT", "LINEAR_PERPETUAL", "BTC", "USDT", "1", "1", "0.005",
        "USDT", "USDT")));
    when(tradeRepository.findByOrderIdAndFillIdentity(order.getId(), "perp-underfunded"))
        .thenReturn(Optional.empty());
    when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, "BTCUSDT", PositionMode.ONE_WAY, PositionSide.BOTH))
        .thenReturn(Optional.empty());
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository);

    assertThatThrownBy(() -> service.applyFill(
        order, order, account, takerFill("2", "90"), "perp-underfunded",
        perpetualSnapshot(Instant.parse("2026-07-17T08:03:30Z"), "100"),
        depthPolicy(), BigDecimal.ZERO))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("ORDER_HOLD_INVALID"));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(order.getHoldAmount()).isEqualByComparingTo("9.00000000");
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(orderRepository, never()).save(any());
    verify(tradeRepository, never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(ledgerService, accountMutationPublisher);
  }

  @Test
  void linearPerpetualDepthRejectsNonUsdtPerpetualSymbolBeforePositionMutation() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setPositionMode(PositionMode.ONE_WAY);
    account.setUsedMargin(new BigDecimal("10.10000000"));
    account.setFreeMargin(new BigDecimal("19989.90000000"));
    OrderEntity order = depthPerpetualOrder(
        accountId, OrderSide.BUY, "2", "10.10000000");
    SymbolEntity symbol = perpSymbol(
        "BTCUSDT", "LINEAR_PERPETUAL", "BTC", "USDT", "1", "1", "0.005",
        "USDT", "USDC");
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol));
    when(tradeRepository.findByOrderIdAndFillIdentity(order.getId(), "perp-wrong-assets"))
        .thenReturn(Optional.empty());
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository);

    assertThatThrownBy(() -> service.applyFill(
        order, order, account, takerFill("2", "90"), "perp-wrong-assets",
        perpetualSnapshot(Instant.parse("2026-07-17T08:04:00Z"), "100"),
        depthPolicy(), BigDecimal.ZERO))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("SYMBOL_NOT_TRADABLE"));

    verify(positionRepository, never()).findOpenPerpetualSlotForUpdate(
        any(), any(), any(), any());
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(orderRepository, never()).save(any());
    verify(tradeRepository, never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(ledgerService, accountMutationPublisher);
  }

  @Test
  void linearPerpetualDepthRejectsUnknownSymbolBeforePositionMutation() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setPositionMode(PositionMode.ONE_WAY);
    account.setUsedMargin(new BigDecimal("10.10000000"));
    account.setFreeMargin(new BigDecimal("19989.90000000"));
    OrderEntity order = depthPerpetualOrder(
        accountId, OrderSide.BUY, "2", "10.10000000");
    when(tradeRepository.findByOrderIdAndFillIdentity(order.getId(), "perp-unknown-symbol"))
        .thenReturn(Optional.empty());
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.empty());
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository);

    assertThatThrownBy(() -> service.applyFill(
        order, order, account, takerFill("2", "90"), "perp-unknown-symbol",
        perpetualSnapshot(Instant.parse("2026-07-17T08:04:15Z"), "100"),
        depthPolicy(), BigDecimal.ZERO))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("SYMBOL_NOT_TRADABLE"));

    verify(positionRepository, never()).findOpenPerpetualSlotForUpdate(
        any(), any(), any(), any());
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(orderRepository, never()).save(any());
    verify(tradeRepository, never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(ledgerService, accountMutationPublisher);
  }

  @Test
  void linearPerpetualDepthRejectsNonRepresentableProjectedHoldBeforeMutation() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setPositionMode(PositionMode.ONE_WAY);
    account.setUsedMargin(new BigDecimal("50.50000000"));
    account.setFreeMargin(new BigDecimal("19949.50000000"));
    OrderEntity order = depthPerpetualOrder(
        accountId, OrderSide.BUY, "10", "50.50000000");
    OrderFillService service = executablePerpetualDepthService(accountId);

    assertThatThrownBy(() -> service.applyFill(
        order, order, account, takerFill("2", "90"), "perp-nonrepresentable-h1",
        perpetualSnapshot(Instant.parse("2026-07-17T08:04:30Z"), "100"),
        depthPolicy(), new BigDecimal("40.400000001")))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("INVALID_DEPTH_FILL"));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(order.getHoldAmount()).isEqualByComparingTo("50.50000000");
    verify(tradeRepository).findByOrderIdAndFillIdentity(
        order.getId(), "perp-nonrepresentable-h1");
    verify(symbolRepository, never()).findBySymbol(any());
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(orderRepository, never()).save(any());
    verify(tradeRepository, never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(ledgerService, accountMutationPublisher);
  }

  @Test
  void linearPerpetualDepthRejectsOversizedProviderBeforeIdentityQuery() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setPositionMode(PositionMode.ONE_WAY);
    account.setUsedMargin(new BigDecimal("10.10000000"));
    account.setFreeMargin(new BigDecimal("19989.90000000"));
    OrderEntity order = depthPerpetualOrder(
        accountId, OrderSide.BUY, "2", "10.10000000");
    OrderFillService service = executablePerpetualDepthService(accountId);
    Instant asOf = Instant.parse("2026-07-17T08:05:00Z");
    ExecutableMarketSnapshot snapshot = perpetualSnapshot(
        asOf, "100", "p".repeat(33));

    assertThatThrownBy(() -> service.applyFill(
        order, order, account, takerFill("2", "90"), "perp-provider-overflow",
        snapshot, depthPolicy(), BigDecimal.ZERO))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("INVALID_DEPTH_FILL"));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(order.getHoldAmount()).isEqualByComparingTo("10.10000000");
    org.mockito.Mockito.verifyNoInteractions(
        tradeRepository, symbolRepository, positionRepository, accountRepository,
        orderRepository, ledgerService, accountMutationPublisher);
  }

  @Test
  void linearPerpetualDepthRejectsPerpSpecificInvalidInputsBeforeMutation() {
    record RejectionCase(
        String name,
        DemoMatchFill fill,
        DemoExecutionPolicy policy,
        ProductType snapshotProduct,
        String mark,
        int leverage,
        String orderQuantity
    ) {
    }
    List<RejectionCase> cases = List.of(
        new RejectionCase(
            "non-DEPTH policy", takerFill("2", "90"), simplePolicy(),
            ProductType.LINEAR_PERP, "100", 20, "2"),
        new RejectionCase(
            "fee mismatch",
            new DemoMatchFill(
                new BigDecimal("2"), new BigDecimal("90"), LiquidityRole.TAKER,
                new BigDecimal("0.0002")),
            depthPolicy(), ProductType.LINEAR_PERP, "100", 20, "2"),
        new RejectionCase(
            "wrong snapshot product", takerFill("2", "90"), depthPolicy(),
            ProductType.CRYPTO_SPOT, "100", 20, "2"),
        new RejectionCase(
            "zero mark", takerFill("2", "90"), depthPolicy(),
            ProductType.LINEAR_PERP, "0", 20, "2"),
        new RejectionCase(
            "zero leverage", takerFill("2", "90"), depthPolicy(),
            ProductType.LINEAR_PERP, "100", 0, "2"),
        new RejectionCase(
            "overfill", takerFill("3", "90"), depthPolicy(),
            ProductType.LINEAR_PERP, "100", 20, "2"),
        new RejectionCase(
            "raw quantity scale",
            takerFill("0.00001", "90"), depthPolicy(),
            ProductType.LINEAR_PERP, "100", 20, "2"),
        new RejectionCase(
            "raw price scale",
            takerFill("2", "90.00000000001"), depthPolicy(),
            ProductType.LINEAR_PERP, "100", 20, "2"));
    Instant asOf = Instant.parse("2026-07-17T08:06:00Z");

    for (int index = 0; index < cases.size(); index++) {
      int caseIndex = index;
      RejectionCase rejection = cases.get(index);
      UUID accountId = UUID.randomUUID();
      TradingAccountEntity account = account(accountId);
      account.setPositionMode(PositionMode.ONE_WAY);
      account.setUsedMargin(new BigDecimal("10.10000000"));
      account.setFreeMargin(new BigDecimal("19989.90000000"));
      OrderEntity order = depthPerpetualOrder(
          accountId, OrderSide.BUY, rejection.orderQuantity(), "10.10000000");
      order.setLeverage(rejection.leverage());
      OrderFillService service = executablePerpetualDepthService(accountId);
      ExecutableMarketSnapshot snapshot = perpetualSnapshot(
          asOf.plusSeconds(index), rejection.mark(), "local-perp", rejection.snapshotProduct());
      org.mockito.Mockito.clearInvocations(
          orderRepository, tradeRepository, positionRepository, accountRepository,
          ledgerService, symbolRepository, accountMutationPublisher);

      assertThatThrownBy(() -> service.applyFill(
          order, order, account, rejection.fill(), "perp-invalid-" + caseIndex,
          snapshot, rejection.policy(), BigDecimal.ZERO))
          .as(rejection.name())
          .isInstanceOfSatisfying(BusinessException.class,
              exception -> assertThat(exception.getCode()).isEqualTo("INVALID_DEPTH_FILL"));

      assertThat(order.getStatus()).as(rejection.name()).isEqualTo(OrderStatus.PENDING);
      assertThat(order.getHoldAmount()).as(rejection.name())
          .isEqualByComparingTo("10.10000000");
      verify(positionRepository, never()).save(any());
      verify(accountRepository, never()).save(any());
      verify(orderRepository, never()).save(any());
      verify(tradeRepository, never()).save(any());
      org.mockito.Mockito.verifyNoInteractions(ledgerService, accountMutationPublisher);
    }
  }

  @Test
  void spotLimitBuyDepthPartialFillPreservesFutureHoldAndReleasesOnlyImprovement() {
    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setUserId(userId);
    OrderEntity order = depthSpotOrder(
        accountId, OrderSide.BUY, "10", "1001.00000000", "USDT");
    order.setUserId(userId);
    Map<String, WalletBalanceEntity> balances = new HashMap<>();
    List<AssetLedgerEntryEntity> assetEntries = new ArrayList<>();
    WalletBalanceEntity quote = wallet(
        accountId, "USDT", "1101.00000000", "100.00000000", "1001.00000000");
    balances.put("USDT", quote);
    SpotPositionService spotPositionService = org.mockito.Mockito.mock(SpotPositionService.class);
    SpotPositionEntity savedSpotPosition = new SpotPositionEntity();
    savedSpotPosition.setId(UUID.randomUUID());
    when(spotPositionService.applyBuy(any(), any(), any(), any(), any(), any()))
        .thenReturn(savedSpotPosition);
    when(symbolRepository.findBySymbol("BTCUSDT"))
        .thenReturn(Optional.of(symbol("BTCUSDT", "SPOT", "BTC", "USDT")));
    when(walletBalanceRepository.findByAccountIdAndWalletTypeAndAssetForUpdate(
        eq(accountId), eq(WalletType.SPOT.code()), any(String.class)))
        .thenAnswer(invocation -> Optional.ofNullable(balances.get(invocation.getArgument(2))));
    when(walletBalanceRepository.save(any(WalletBalanceEntity.class))).thenAnswer(invocation -> {
      WalletBalanceEntity balance = invocation.getArgument(0);
      if (balance.getId() == null) {
        balance.setId(UUID.randomUUID());
      }
      balances.put(balance.getAsset(), balance);
      return balance;
    });
    when(assetLedgerEntryRepository.save(any(AssetLedgerEntryEntity.class)))
        .thenAnswer(invocation -> {
          AssetLedgerEntryEntity entry = invocation.getArgument(0);
          assetEntries.add(entry);
          return entry;
        });
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(tradeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    WalletService walletService = new WalletService(
        walletBalanceRepository, assetLedgerEntryRepository);
    SpotSettlementService settlement = new SpotSettlementService(walletService, spotPositionService);
    settlement.setAccountMutationPublisher(accountMutationPublisher);
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository, settlement, walletService);
    service.setAccountMutationPublisher(accountMutationPublisher);
    Instant asOf = Instant.parse("2026-07-17T04:00:00Z");
    DemoMatchFill fill = new DemoMatchFill(
        new BigDecimal("2"),
        new BigDecimal("90"),
        LiquidityRole.TAKER,
        new BigDecimal("0.0005"));
    String fillIdentity = "depth-buy-fill-0";

    OrderEntity result = service.applyFill(
        order,
        order,
        account,
        fill,
        fillIdentity,
        spotSnapshot(asOf),
        depthPolicy(),
        new BigDecimal("800.80000000"));

    assertThat(result).isSameAs(order);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    assertThat(order.getExecutionPrice()).isEqualByComparingTo("90.00000000");
    assertThat(order.getAvgFillPrice()).isEqualByComparingTo("90.00000000");
    assertThat(order.getFilledQuantity()).isEqualByComparingTo("2.00000000");
    assertThat(order.getRemainingQuantity()).isEqualByComparingTo("8.00000000");
    assertThat(order.getFee()).isEqualByComparingTo("0.09000000");
    assertThat(order.getFeeAsset()).isEqualTo("USDT");
    assertThat(order.getLiquidityRole()).isEqualTo(LiquidityRole.TAKER);
    assertThat(order.getHoldAmount()).isEqualByComparingTo("800.80000000");
    assertThat(order.getFilledAt()).isNull();
    assertThat(quote.getTotal()).isEqualByComparingTo("920.91000000");
    assertThat(quote.getAvailable()).isEqualByComparingTo("120.11000000");
    assertThat(quote.getLocked()).isEqualByComparingTo("800.80000000");
    assertThat(balances.get("BTC").getAvailable()).isEqualByComparingTo("2.00000000");

    UUID tradeId = com.fxplatform.execution.DemoFillIdentity.tradeId(order.getId(), fillIdentity);
    assertThat(assetEntries)
        .extracting(AssetLedgerEntryEntity::getEntryType)
        .containsExactly("SPOT_BUY_DEBIT", "TRADE_FEE", "SPOT_BUY_CREDIT", "SPOT_ORDER_RELEASE");
    assertThat(assetEntries)
        .extracting(AssetLedgerEntryEntity::getAmount)
        .usingElementComparator(BigDecimal::compareTo)
        .containsExactly(
            new BigDecimal("-180.00000000"),
            new BigDecimal("-0.09000000"),
            new BigDecimal("2.00000000"),
            new BigDecimal("20.11000000"));
    assertThat(assetEntries).allSatisfy(entry -> {
      assertThat(entry.getReferenceType()).isEqualTo("TRADE");
      assertThat(entry.getReferenceId()).isEqualTo(tradeId);
    });
    verify(spotPositionService).applyBuy(
        accountId,
        "BTC",
        "USDT",
        new BigDecimal("2.00000000"),
        new BigDecimal("180.00000000"),
        new BigDecimal("0.09000000"));
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordTradeFeeForTrade(any(), any(), any(), any());
    ArgumentCaptor<com.fxplatform.trading.entity.TradeEntity> tradeCaptor =
        ArgumentCaptor.forClass(com.fxplatform.trading.entity.TradeEntity.class);
    verify(tradeRepository).save(tradeCaptor.capture());
    var trade = tradeCaptor.getValue();
    assertThat(trade.getId()).isEqualTo(tradeId);
    assertThat(trade.getFillIdentity()).isEqualTo(fillIdentity);
    assertThat(trade.getCanonicalFullFill()).isFalse();
    assertThat(trade.getOrderId()).isEqualTo(order.getId());
    assertThat(trade.getAccountId()).isEqualTo(accountId);
    assertThat(trade.getProductType()).isEqualTo(ProductType.CRYPTO_SPOT);
    assertThat(trade.getLots()).isEqualByComparingTo("2.00000000");
    assertThat(trade.getPrice()).isEqualByComparingTo("90.00000000");
    assertThat(trade.getFee()).isEqualByComparingTo("0.09000000");
    assertThat(trade.getFeeAsset()).isEqualTo("USDT");
    assertThat(trade.getLiquidityRole()).isEqualTo(LiquidityRole.TAKER);
    assertThat(trade.getSourceMode()).isEqualTo("LOCAL_SIMULATED");
    assertThat(trade.getProviderCode()).isEqualTo("local-spot");
    assertThat(trade.getExecutedAt()).isEqualTo(asOf);
    verify(orderRepository).save(order);

    ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
    verify(accountMutationPublisher, times(3)).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getAllValues().stream()
        .map(TradingAccountMutationEvent.class::cast)
        .map(TradingAccountMutationEvent::type))
        .containsExactly("POSITION_UPDATED", "TRADE_CREATED", "BALANCE_UPDATED");
    assertThat(eventCaptor.getAllValues().stream()
        .map(TradingAccountMutationEvent.class::cast)
        .filter(event -> "POSITION_UPDATED".equals(event.type())))
        .singleElement()
        .satisfies(event -> assertThat(event.resourceId()).isEqualTo(savedSpotPosition.getId()));

    when(tradeRepository.findByOrderIdAndFillIdentity(order.getId(), fillIdentity))
        .thenReturn(Optional.of(trade));
    service.applyFill(
        order,
        order,
        account,
        fill,
        fillIdentity,
        spotSnapshot(asOf),
        depthPolicy(),
        new BigDecimal("800.80000000"));

    org.mockito.Mockito.verifyNoMoreInteractions(accountMutationPublisher);
    verify(tradeRepository).save(any(TradeEntity.class));
    verify(orderRepository).save(order);
  }

  @Test
  void highPrecisionDepthFillUsesRawPriceForGrossFeeTradeAndLatestExecution() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    OrderEntity order = depthSpotOrder(
        accountId, OrderSide.BUY, "10000", "10005.00004902", "USDT");
    SpotSettlementService settlement = org.mockito.Mockito.mock(SpotSettlementService.class);
    WalletService walletService = org.mockito.Mockito.mock(WalletService.class);
    when(symbolRepository.findBySymbol("BTCUSDT"))
        .thenReturn(Optional.of(persistedSpotSymbol("BTCUSDT")));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(tradeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository, settlement, walletService);
    BigDecimal rawPrice = new BigDecimal("1.0000000049");

    service.applyFill(
        order,
        order,
        account,
        takerFill("10000", rawPrice.toPlainString()),
        "high-precision-fill",
        spotSnapshot(Instant.parse("2026-07-17T04:10:00Z")),
        depthPolicy(),
        BigDecimal.ZERO);

    ArgumentCaptor<ExecutionResult> executionCaptor = ArgumentCaptor.forClass(ExecutionResult.class);
    verify(settlement).settleBuyPartialFill(
        eq(order), eq(order), executionCaptor.capture(), any(SymbolEntity.class),
        eq(account), any(UUID.class));
    ExecutionResult execution = executionCaptor.getValue();
    assertThat(execution.filledQuantity()).isEqualByComparingTo("10000.00000000");
    assertThat(execution.filledPrice()).isEqualByComparingTo(rawPrice);
    assertThat(execution.filledQuantity().multiply(execution.filledPrice())
        .setScale(8, RoundingMode.HALF_UP))
        .isEqualByComparingTo("10000.00004900");
    assertThat(execution.fee()).isEqualByComparingTo("5.00000002");
    assertThat(order.getExecutionPrice()).isEqualByComparingTo(rawPrice);
    assertThat(order.getAvgFillPrice()).isEqualByComparingTo("1.00000000");
    assertThat(order.getFee()).isEqualByComparingTo("5.00000002");
    assertThat(order.getHoldAmount()).isEqualByComparingTo("0.00000000");
    ArgumentCaptor<TradeEntity> tradeCaptor = ArgumentCaptor.forClass(TradeEntity.class);
    verify(tradeRepository).save(tradeCaptor.capture());
    assertThat(tradeCaptor.getValue().getPrice()).isEqualByComparingTo(rawPrice);
    assertThat(tradeCaptor.getValue().getFee()).isEqualByComparingTo("5.00000002");
    verify(walletService, never()).releaseLockedWithEntryType(
        any(UUID.class), any(String.class), any(BigDecimal.class), any(String.class),
        any(UUID.class), any(String.class), any(String.class));
  }

  @Test
  void nonWalletRepresentableDepthQuantityFailsBeforeMutation() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    OrderEntity order = depthSpotOrder(
        accountId, OrderSide.BUY, "2", "2.00100000", "USDT");
    SpotSettlementService settlement = org.mockito.Mockito.mock(SpotSettlementService.class);
    WalletService walletService = org.mockito.Mockito.mock(WalletService.class);
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository, settlement, walletService);

    assertThatThrownBy(() -> service.applyFill(
        order, order, account, takerFill("1.000000001", "1"), "quantity-scale-fill",
        spotSnapshot(Instant.parse("2026-07-17T04:15:00Z")), depthPolicy(),
        new BigDecimal("1.00050000")))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("INVALID_DEPTH_FILL"));

    verify(tradeRepository, never()).save(any());
    verify(orderRepository, never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(
        settlement, walletService, symbolRepository, positionRepository,
        accountRepository, ledgerService);
  }

  @Test
  void quantityOutsideTradesLotsNumericScaleFailsBeforeAnyInteraction() {
    assertSqlNumericDepthFillRejectedBeforeInteractions(
        takerFill("0.12345", "100"),
        "1",
        "100.05000000",
        "87.69882750");
  }

  @Test
  void priceOutsideTradePriceNumericScaleFailsBeforeAnyInteraction() {
    assertSqlNumericDepthFillRejectedBeforeInteractions(
        takerFill("1", "1.00000000009"),
        "1",
        "1.00050000",
        "0");
  }

  @Test
  void grossQuoteOutsideWalletNumericPrecisionFailsBeforeAnyInteraction() {
    assertSqlNumericDepthFillRejectedBeforeInteractions(
        takerFill("99999999.9999", "1000000000.0000000000"),
        "99999999.9999",
        "9999999999999999.99999999",
        "0");
  }

  @Test
  void fillIdentityLongerThanTradesColumnFailsBeforeAnyInteraction() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    OrderEntity order = depthSpotOrder(
        accountId, OrderSide.BUY, "1", "100.05000000", "USDT");
    SpotSettlementService settlement = org.mockito.Mockito.mock(SpotSettlementService.class);
    WalletService walletService = org.mockito.Mockito.mock(WalletService.class);
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository, settlement, walletService);

    assertThatThrownBy(() -> service.applyFill(
        order, order, account, takerFill("1", "100"), "x".repeat(65),
        spotSnapshot(Instant.parse("2026-07-17T07:22:00Z")), depthPolicy(),
        BigDecimal.ZERO))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("INVALID_DEPTH_FILL"));

    org.mockito.Mockito.verifyNoInteractions(
        tradeRepository, symbolRepository, orderRepository, settlement, walletService,
        positionRepository, accountRepository, ledgerService);
  }

  @Test
  void weightedAverageOutsideOrdersNumericPrecisionFailsBeforeMutation() {
    assertStateDerivedNumericDepthFillRejectedBeforeMutation(
        takerFill("1", "99999999999999.9999999999"),
        "1",
        "100050000000000.00000000",
        "0",
        order -> {
        });
  }

  @Test
  void cumulativeFeeOutsideOrdersNumericPrecisionFailsBeforeMutation() {
    assertStateDerivedNumericDepthFillRejectedBeforeMutation(
        takerFill("1", "100"),
        "1",
        "100.05000000",
        "0",
        order -> order.setFee(new BigDecimal("9999999999999999.99999999")));
  }

  @Test
  void replayWithSameIdentityAndDistinctRawPriceConflicts() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    OrderEntity order = depthSpotOrder(
        accountId, OrderSide.BUY, "2", "2.00100000", "USDT");
    AtomicReference<TradeEntity> storedTrade = new AtomicReference<>();
    when(symbolRepository.findBySymbol("BTCUSDT"))
        .thenReturn(Optional.of(persistedSpotSymbol("BTCUSDT")));
    when(tradeRepository.findByOrderIdAndFillIdentity(order.getId(), "precise-replay"))
        .thenAnswer(invocation -> Optional.ofNullable(storedTrade.get()));
    when(tradeRepository.save(any(TradeEntity.class))).thenAnswer(invocation -> {
      TradeEntity trade = invocation.getArgument(0);
      storedTrade.set(trade);
      return trade;
    });
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    SpotSettlementService settlement = org.mockito.Mockito.mock(SpotSettlementService.class);
    WalletService walletService = org.mockito.Mockito.mock(WalletService.class);
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository, settlement, walletService);
    Instant asOf = Instant.parse("2026-07-17T04:20:00Z");

    service.applyFill(
        order, order, account, takerFill("1", "1.0000000010"), "precise-replay",
        spotSnapshot(asOf), depthPolicy(), new BigDecimal("1.00050000"));

    assertThatThrownBy(() -> service.applyFill(
        order, order, account, takerFill("1", "1.0000000020"), "precise-replay",
        spotSnapshot(asOf), depthPolicy(), new BigDecimal("1.00050000")))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("FILL_IDENTITY_CONFLICT"));

    verify(tradeRepository).save(any(TradeEntity.class));
    verify(orderRepository).save(order);
    verify(settlement).settleBuyPartialFill(
        eq(order), eq(order), any(ExecutionResult.class), any(SymbolEntity.class),
        eq(account), any(UUID.class));
  }

  @Test
  void unknownDepthSymbolFailsBeforeMutation() {
    assertDepthSymbolRejectedBeforeMutation(Optional.empty());
  }

  @Test
  void disabledDepthSymbolFailsBeforeMutation() {
    SymbolEntity symbol = persistedSpotSymbol("BTCUSDT");
    symbol.setEnabled(false);
    assertDepthSymbolRejectedBeforeMutation(Optional.of(symbol));
  }

  @Test
  void nonTradableDepthSymbolFailsBeforeMutation() {
    SymbolEntity symbol = persistedSpotSymbol("BTCUSDT");
    symbol.setTradable(false);
    assertDepthSymbolRejectedBeforeMutation(Optional.of(symbol));
  }

  @Test
  void wrongProductDepthSymbolFailsBeforeMutation() {
    SymbolEntity symbol = persistedSpotSymbol("BTCUSDT");
    symbol.setProductType(ProductType.LINEAR_PERP);
    assertDepthSymbolRejectedBeforeMutation(Optional.of(symbol));
  }

  @Test
  void depthApplyFillRequiresAnExistingTransaction() throws Exception {
    Method applyFill = OrderFillService.class.getMethod(
        "applyFill",
        OrderEntity.class,
        OrderEntity.class,
        TradingAccountEntity.class,
        DemoMatchFill.class,
        String.class,
        ExecutableMarketSnapshot.class,
        DemoExecutionPolicy.class,
        BigDecimal.class);

    assertThat(applyFill.getAnnotation(Transactional.class))
        .isNotNull()
        .extracting(Transactional::propagation)
        .isEqualTo(Propagation.MANDATORY);

    Method terminalApplyFill = OrderFillService.class.getMethod(
        "applyFill",
        OrderEntity.class,
        OrderEntity.class,
        TradingAccountEntity.class,
        DemoMatchFill.class,
        String.class,
        ExecutableMarketSnapshot.class,
        DemoExecutionPolicy.class,
        BigDecimal.class,
        boolean.class);
    assertThat(terminalApplyFill.getAnnotation(Transactional.class))
        .isNotNull()
        .extracting(Transactional::propagation)
        .isEqualTo(Propagation.MANDATORY);
  }

  @Test
  void twoDepthPartialsThenFinalPreserveWeightedAverageRoundedFeeSumAndExactHold() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    OrderEntity order = depthSpotOrder(
        accountId, OrderSide.BUY, "3", "300.15000000", "USDT");
    SpotSettlementService settlement = org.mockito.Mockito.mock(SpotSettlementService.class);
    when(symbolRepository.findBySymbol("BTCUSDT"))
        .thenReturn(Optional.of(symbol("BTCUSDT", "SPOT", "BTC", "USDT")));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(tradeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository, settlement, org.mockito.Mockito.mock(WalletService.class));
    Instant firstAsOf = Instant.parse("2026-07-17T05:00:00Z");

    service.applyFill(
        order, order, account, takerFill("1", "100"), "weighted-fill-0",
        spotSnapshot(firstAsOf), depthPolicy(), new BigDecimal("200.10000000"));
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    assertThat(order.getAvgFillPrice()).isEqualByComparingTo("100.00000000");
    assertThat(order.getFee()).isEqualByComparingTo("0.05000000");
    assertThat(order.getHoldAmount()).isEqualByComparingTo("200.10000000");

    service.applyFill(
        order, order, account, takerFill("1", "110"), "weighted-fill-1",
        spotSnapshot(firstAsOf.plusSeconds(10)), depthPolicy(), new BigDecimal("90.04500000"));
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    assertThat(order.getAvgFillPrice()).isEqualByComparingTo("105.00000000");
    assertThat(order.getFee()).isEqualByComparingTo("0.10500000");
    assertThat(order.getHoldAmount()).isEqualByComparingTo("90.04500000");

    service.applyFill(
        order, order, account, takerFill("1", "90"), "weighted-fill-2",
        spotSnapshot(firstAsOf.plusSeconds(20)), depthPolicy(), BigDecimal.ZERO);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
    assertThat(order.getExecutionPrice()).isEqualByComparingTo("90.00000000");
    assertThat(order.getAvgFillPrice()).isEqualByComparingTo("100.00000000");
    assertThat(order.getFilledQuantity()).isEqualByComparingTo("3.00000000");
    assertThat(order.getRemainingQuantity()).isEqualByComparingTo("0.00000000");
    assertThat(order.getFee()).isEqualByComparingTo("0.15000000");
    assertThat(order.getHoldAmount()).isEqualByComparingTo("0.00000000");
    assertThat(order.getFilledAt()).isEqualTo(firstAsOf.plusSeconds(20));
    verify(tradeRepository, times(3)).save(any(TradeEntity.class));
    verify(orderRepository, times(3)).save(order);
    verify(settlement, times(3)).settleBuyPartialFill(
        eq(order), eq(order), any(ExecutionResult.class), any(SymbolEntity.class),
        eq(account), any(UUID.class));
  }

  @Test
  void spotSellDepthPartialFillPreservesEightBaseHoldWithoutExtraRelease() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    OrderEntity order = depthSpotOrder(
        accountId, OrderSide.SELL, "10", "10.00000000", "BTC");
    SpotSettlementService settlement = org.mockito.Mockito.mock(SpotSettlementService.class);
    WalletService walletService = org.mockito.Mockito.mock(WalletService.class);
    when(symbolRepository.findBySymbol("BTCUSDT"))
        .thenReturn(Optional.of(symbol("BTCUSDT", "SPOT", "BTC", "USDT")));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(tradeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository, settlement, walletService);
    String identity = "depth-sell-fill-0";

    service.applyFill(
        order, order, account, takerFill("2", "90"), identity,
        spotSnapshot(Instant.parse("2026-07-17T05:30:00Z")), depthPolicy(),
        new BigDecimal("8.00000000"));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    assertThat(order.getFilledQuantity()).isEqualByComparingTo("2.00000000");
    assertThat(order.getRemainingQuantity()).isEqualByComparingTo("8.00000000");
    assertThat(order.getAvgFillPrice()).isEqualByComparingTo("90.00000000");
    assertThat(order.getFee()).isEqualByComparingTo("0.09000000");
    assertThat(order.getHoldAmount()).isEqualByComparingTo("8.00000000");
    UUID tradeId = DemoFillIdentity.tradeId(order.getId(), identity);
    verify(settlement).settleSellPartialFill(
        eq(order), eq(order), any(ExecutionResult.class), any(SymbolEntity.class),
        eq(account), eq(tradeId));
    verify(walletService, never()).releaseLockedWithEntryType(
        any(UUID.class), any(String.class), any(BigDecimal.class), any(String.class),
        any(UUID.class), any(String.class), any(String.class));
    org.mockito.Mockito.verifyNoInteractions(accountRepository, ledgerService, positionRepository);
  }

  @Test
  void identicalDepthFillReplayIsTotalNoOpAndConflictingPayloadFailsBeforeMutation() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    OrderEntity order = depthSpotOrder(
        accountId, OrderSide.BUY, "10", "800.80000000", "USDT");
    order.setFilledQuantity(new BigDecimal("2.00000000"));
    order.setRemainingQuantity(new BigDecimal("8.00000000"));
    order.setAvgFillPrice(new BigDecimal("90.00000000"));
    order.setFee(new BigDecimal("0.09000000"));
    String identity = "replayed-depth-fill";
    TradeEntity existing = depthTrade(
        order, accountId, identity, "2.00000000", "90.00000000", "0.09000000");
    when(tradeRepository.findByOrderIdAndFillIdentity(order.getId(), identity))
        .thenReturn(Optional.of(existing));
    SpotSettlementService settlement = org.mockito.Mockito.mock(SpotSettlementService.class);
    WalletService walletService = org.mockito.Mockito.mock(WalletService.class);
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository, settlement, walletService);
    ExecutableMarketSnapshot incoherentReplaySnapshot = new ExecutableMarketSnapshot(
        "BTCUSDT", ProductType.CRYPTO_SPOT, "", "", MarketSourceMode.LOCAL_SIMULATED,
        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, null, null, null, null);

    OrderEntity replay = service.applyFill(
        order, order, account, takerFill("2", "90"), identity,
        incoherentReplaySnapshot, depthPolicy(), new BigDecimal("800.80000000"));

    assertThat(replay).isSameAs(order);
    assertThat(order.getFilledQuantity()).isEqualByComparingTo("2.00000000");
    assertThat(order.getRemainingQuantity()).isEqualByComparingTo("8.00000000");
    assertThat(order.getHoldAmount()).isEqualByComparingTo("800.80000000");
    verify(tradeRepository, never()).save(any());
    verify(orderRepository, never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(settlement, walletService, accountMutationPublisher);

    assertThatThrownBy(() -> service.applyFill(
        order, order, account, takerFill("2", "91"), identity,
        spotSnapshot(Instant.parse("2026-07-17T06:00:00Z")), depthPolicy(),
        new BigDecimal("800.80000000")))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("FILL_IDENTITY_CONFLICT"));

    verify(tradeRepository, never()).save(any());
    verify(orderRepository, never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(settlement, walletService, accountMutationPublisher);
  }

  @Test
  void finalDepthFillReplayIgnoresMutableZeroRemainingAndZeroHoldState() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    OrderEntity order = depthSpotOrder(
        accountId, OrderSide.BUY, "1", "0.00000000", "USDT");
    order.setStatus(OrderStatus.FILLED);
    order.setFilledQuantity(new BigDecimal("1.00000000"));
    order.setRemainingQuantity(BigDecimal.ZERO.setScale(8));
    order.setAvgFillPrice(new BigDecimal("100.00000000"));
    order.setFee(new BigDecimal("0.05000000"));
    String identity = "final-depth-replay";
    when(tradeRepository.findByOrderIdAndFillIdentity(order.getId(), identity))
        .thenReturn(Optional.of(depthTrade(
            order, accountId, identity, "1.00000000", "100.00000000", "0.05000000")));
    SpotSettlementService settlement = org.mockito.Mockito.mock(SpotSettlementService.class);
    WalletService walletService = org.mockito.Mockito.mock(WalletService.class);
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository, settlement, walletService);

    OrderEntity replay = service.applyFill(
        order, order, account, takerFill("1", "100"), identity,
        spotSnapshot(Instant.parse("2026-07-17T06:30:00Z")), depthPolicy(), BigDecimal.ZERO);

    assertThat(replay).isSameAs(order);
    verify(tradeRepository, never()).save(any());
    verify(orderRepository, never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(settlement, walletService);
  }

  @Test
  void liveAccountCannotUseTheDemoDepthFillPrimitive() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setAccountType(AccountType.LIVE);
    OrderEntity order = depthSpotOrder(
        accountId, OrderSide.BUY, "10", "1001.00000000", "USDT");
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository, org.mockito.Mockito.mock(SpotSettlementService.class),
        org.mockito.Mockito.mock(WalletService.class));

    assertThatThrownBy(() -> service.applyFill(
        order, order, account, takerFill("2", "90"), "live-depth-fill",
        spotSnapshot(Instant.parse("2026-07-17T06:40:00Z")), depthPolicy(),
        new BigDecimal("800.80000000")))
        .isInstanceOf(BusinessException.class);

    verify(tradeRepository, never()).findByOrderIdAndFillIdentity(any(), any());
    verify(tradeRepository, never()).save(any());
    verify(orderRepository, never()).save(any());
  }

  @Test
  void mismatchedOrderAndAccountUsersFailBeforeIdentityQueryOrMutation() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setUserId(UUID.randomUUID());
    OrderEntity order = depthSpotOrder(
        accountId, OrderSide.BUY, "10", "1001.00000000", "USDT");
    order.setUserId(UUID.randomUUID());
    SpotSettlementService settlement = org.mockito.Mockito.mock(SpotSettlementService.class);
    WalletService walletService = org.mockito.Mockito.mock(WalletService.class);
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository, settlement, walletService);

    assertThatThrownBy(() -> service.applyFill(
        order, order, account, takerFill("2", "90"), "wrong-user-fill",
        spotSnapshot(Instant.parse("2026-07-17T06:50:00Z")), depthPolicy(),
        new BigDecimal("800.80000000")))
        .isInstanceOf(BusinessException.class);

    verify(tradeRepository, never()).findByOrderIdAndFillIdentity(any(), any());
    verify(tradeRepository, never()).save(any());
    verify(orderRepository, never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(settlement, walletService);
  }

  @Test
  void invalidDepthFillContractsFailBeforeTradeFinancialOrOrderMutation() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    SpotSettlementService settlement = org.mockito.Mockito.mock(SpotSettlementService.class);
    WalletService walletService = org.mockito.Mockito.mock(WalletService.class);
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository, settlement, walletService);
    Instant asOf = Instant.parse("2026-07-17T07:00:00Z");
    OrderEntity underfunded = depthSpotOrder(
        accountId, OrderSide.BUY, "10", "100.00000000", "USDT");
    OrderEntity overfill = depthSpotOrder(
        accountId, OrderSide.BUY, "10", "1001.00000000", "USDT");
    OrderEntity wrongCurrency = depthSpotOrder(
        accountId, OrderSide.BUY, "10", "1001.00000000", "BTC");
    OrderEntity wrongOwner = depthSpotOrder(
        accountId, OrderSide.BUY, "10", "1001.00000000", "USDT");
    OrderEntity unrelatedOwner = depthSpotOrder(
        accountId, OrderSide.BUY, "10", "1001.00000000", "USDT");
    ExecutableMarketSnapshot incoherent = new ExecutableMarketSnapshot(
        "BTCUSDT", ProductType.CRYPTO_SPOT, "local-spot", "BTCUSDT",
        MarketSourceMode.LOCAL_SIMULATED, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
        null, null, asOf, asOf);

    assertThatThrownBy(() -> service.applyFill(
        underfunded, underfunded, account, takerFill("2", "90"), "underfunded",
        spotSnapshot(asOf), depthPolicy(), new BigDecimal("1.00000000")))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.applyFill(
        overfill, overfill, account, takerFill("2", "90"), "missing-next-hold",
        spotSnapshot(asOf), depthPolicy(), null))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.applyFill(
        overfill, overfill, account, takerFill("11", "90"), "overfill",
        spotSnapshot(asOf), depthPolicy(), BigDecimal.ONE))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.applyFill(
        overfill, overfill, account,
        new DemoMatchFill(new BigDecimal("2"), new BigDecimal("90"),
            LiquidityRole.TAKER, new BigDecimal("0.0002")),
        "wrong-fee", spotSnapshot(asOf), depthPolicy(), new BigDecimal("800.80000000")))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.applyFill(
        overfill, overfill, account, takerFill("2", "90"), "simple-policy",
        spotSnapshot(asOf), DemoExecutionPolicy.defaults(), new BigDecimal("800.80000000")))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.applyFill(
        overfill, overfill, account, takerFill("2", "90"), "bad-snapshot",
        incoherent, depthPolicy(), new BigDecimal("800.80000000")))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.applyFill(
        wrongCurrency, wrongCurrency, account, takerFill("2", "90"), "bad-currency",
        spotSnapshot(asOf), depthPolicy(), new BigDecimal("800.80000000")))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.applyFill(
        wrongOwner, unrelatedOwner, account, takerFill("2", "90"), "bad-owner",
        spotSnapshot(asOf), depthPolicy(), new BigDecimal("800.80000000")))
        .isInstanceOf(BusinessException.class);

    verify(tradeRepository, never()).save(any());
    verify(orderRepository, never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(settlement, walletService);
  }

  @Test
  void completedSpotFillPublishesOneTradeAndOneBalanceRefreshHint() {
    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setUserId(userId);
    OrderEntity order = order(accountId);
    order.setUserId(userId);
    order.setVersion(4L);
    SpotSettlementService spotSettlementService = org.mockito.Mockito.mock(SpotSettlementService.class);
    OrderFillService service = new OrderFillService(
        orderRepository,
        tradeRepository,
        positionRepository,
        accountRepository,
        ledgerService,
        symbolRepository,
        spotSettlementService);
    service.setAccountMutationPublisher(accountMutationPublisher);

    service.fill(
        order,
        account,
        new ExecutionResult(
            new BigDecimal("50000.00000000"),
            Instant.parse("2026-07-13T01:00:00Z"),
            new BigDecimal("0.20"),
            BigDecimal.ZERO,
            new BigDecimal("10.00000000"),
            BigDecimal.ZERO,
            null,
            null),
        null,
        "Spot fill");

    ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
    verify(accountMutationPublisher, times(2)).publishEvent(events.capture());
    assertThat(events.getAllValues())
        .allSatisfy(event -> assertThat(event).isInstanceOf(TradingAccountMutationEvent.class));
    assertThat(events.getAllValues().stream()
        .map(TradingAccountMutationEvent.class::cast)
        .map(TradingAccountMutationEvent::type))
        .containsExactly("TRADE_CREATED", "BALANCE_UPDATED");
    assertThat(events.getAllValues().stream()
        .map(TradingAccountMutationEvent.class::cast))
        .allSatisfy(event -> {
          assertThat(event.userId()).isEqualTo(userId);
          assertThat(event.accountId()).isEqualTo(accountId);
          assertThat(event.version()).isEqualTo(4L);
        });
  }

  @Test
  void canonicalPerpetualPositionUpdatePublishesExactlyOneUpdatedHint() {
    PositionEntity position = new PositionEntity();
    position.setId(UUID.randomUUID());
    position.setStatus(PositionStatus.OPEN);
    position.setVersion(6L);

    List<TradingAccountMutationEvent> events = fillCanonicalPerpetual(
        new PositionEngine.PositionUpdateResult(position));

    assertThat(events.stream().map(TradingAccountMutationEvent::type))
        .containsExactly("TRADE_CREATED", "BALANCE_UPDATED", "POSITION_UPDATED");
    assertThat(events.stream().filter(event -> "POSITION_UPDATED".equals(event.type())))
        .singleElement()
        .satisfies(event -> {
          assertThat(event.resourceId()).isEqualTo(position.getId());
          assertThat(event.version()).isEqualTo(6L);
        });
  }

  @Test
  void canonicalPerpetualPositionClosePublishesExactlyOneClosedHint() {
    PositionEntity position = new PositionEntity();
    position.setId(UUID.randomUUID());
    position.setStatus(PositionStatus.CLOSED);
    position.setVersion(7L);
    PositionEngine.PositionUpdateResult closed = new PositionEngine.PositionUpdateResult(position)
        .withReduction(position.getId(), new BigDecimal("0.20"), BigDecimal.ZERO);

    List<TradingAccountMutationEvent> events = fillCanonicalPerpetual(closed);

    assertThat(events.stream().map(TradingAccountMutationEvent::type))
        .containsExactly("TRADE_CREATED", "BALANCE_UPDATED", "POSITION_CLOSED");
    assertThat(events.stream().filter(event -> "POSITION_CLOSED".equals(event.type())))
        .singleElement()
        .satisfies(event -> {
          assertThat(event.resourceId()).isEqualTo(position.getId());
          assertThat(event.version()).isEqualTo(7L);
        });
  }

  @Test
  void canonicalFullFillMarksOnlyDemoP0TradeAndCopiesExecutionMetadata() {
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
        new BigDecimal("5.00050000"),
        "USDT",
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
    TradingAccountEntity liveAccount = account(UUID.randomUUID());
    liveAccount.setAccountType(AccountType.LIVE);
    OrderEntity liveOrder = order(liveAccount.getId());
    liveOrder.setProductType(ProductType.CRYPTO_SPOT);
    liveOrder.setBaseQuantity(new BigDecimal("0.20"));
    service.fill(liveOrder, liveAccount, fill, BigDecimal.ZERO, "legacy live fill");

    assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
    assertThat(order.getFee()).isEqualByComparingTo(fill.fee());
    assertThat(order.getFeeAsset()).isEqualTo("USDT");
    assertThat(order.getLiquidityRole()).isEqualTo(LiquidityRole.TAKER);
    ArgumentCaptor<com.fxplatform.trading.entity.TradeEntity> tradeCaptor =
        ArgumentCaptor.forClass(com.fxplatform.trading.entity.TradeEntity.class);
    verify(tradeRepository, times(2)).save(tradeCaptor.capture());
    var demoTrade = tradeCaptor.getAllValues().get(0);
    var legacyLiveTrade = tradeCaptor.getAllValues().get(1);
    assertThat(demoTrade.getFee()).isEqualByComparingTo(fill.fee());
    assertThat(demoTrade.getFeeAsset()).isEqualTo("USDT");
    assertThat(demoTrade.getLiquidityRole()).isEqualTo(LiquidityRole.TAKER);
    assertThat(demoTrade.getProductType()).isEqualTo(ProductType.CRYPTO_SPOT);
    assertThat(demoTrade.getCanonicalFullFill()).isTrue();
    assertThat(demoTrade.getSourceMode()).isEqualTo("LOCAL_SIMULATED");
    assertThat(demoTrade.getProviderCode()).isEqualTo("local-spot");
    assertThat(legacyLiveTrade.getCanonicalFullFill()).isFalse();
  }

  @Test
  void nonOwnerOcoFillPassesSharedHoldOwnerToSettlementAndClearsOnlyOwnerHold() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    OrderEntity order = order(accountId);
    order.setProductType(ProductType.CRYPTO_SPOT);
    order.setBaseQuantity(new BigDecimal("0.20"));
    order.setHoldAmount(BigDecimal.ZERO);
    OrderEntity holdOwner = order(accountId);
    holdOwner.setHoldAmount(new BigDecimal("10010.00000000"));
    holdOwner.setHoldCurrency("USDT");
    order.setHoldOwnerOrderId(holdOwner.getId());
    SpotSettlementService settlement = org.mockito.Mockito.mock(SpotSettlementService.class);
    when(symbolRepository.findBySymbol("BTCUSDT"))
        .thenReturn(Optional.of(symbol("BTCUSDT", "SPOT", "BTC", "USDT")));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(tradeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    Instant filledAt = Instant.parse("2026-07-12T02:00:00Z");
    FullFillResult fill = new FullFillResult(
        new BigDecimal("50005"), filledAt, new BigDecimal("0.20"), BigDecimal.ZERO,
        new BigDecimal("0.0005"), new BigDecimal("5.00050000"), "USDT",
        LiquidityRole.TAKER, new BigDecimal("5"), MarketSourceMode.LOCAL_SIMULATED,
        "local-spot", "BTCUSDT", filledAt.minusSeconds(1), filledAt.plusSeconds(2));
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository, settlement);

    service.fill(order, holdOwner, account, fill, holdOwner.getHoldAmount(), "OCO fill");

    verify(settlement).settleBuyFill(
        eq(order), eq(holdOwner), any(ExecutionResult.class), any(SymbolEntity.class),
        eq(account), any(UUID.class));
    assertThat(order.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(holdOwner.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
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

  private List<TradingAccountMutationEvent> fillCanonicalPerpetual(
      PositionEngine.PositionUpdateResult positionUpdate
  ) {
    UUID accountId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setUserId(userId);
    OrderEntity order = order(accountId);
    order.setUserId(userId);
    order.setProductType(ProductType.LINEAR_PERP);
    order.setVersion(4L);
    PositionEngine positionEngine = org.mockito.Mockito.mock(PositionEngine.class);
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(perpSymbol(
        "BTCUSDT",
        "LINEAR_PERPETUAL",
        "BTC",
        "USDT",
        "1",
        "1",
        "0.005",
        "USDT",
        "USDT")));
    when(positionEngine.applyPerpetualFill(
        any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
        .thenReturn(positionUpdate);
    OrderFillService service = new OrderFillService(
        orderRepository,
        tradeRepository,
        positionRepository,
        accountRepository,
        ledgerService,
        symbolRepository,
        null,
        positionEngine);
    service.setAccountMutationPublisher(accountMutationPublisher);
    Instant filledAt = Instant.parse("2026-07-13T02:00:00Z");

    service.fillPerpetual(
        order,
        account,
        new FullFillResult(
            new BigDecimal("50000.00000000"),
            filledAt,
            new BigDecimal("0.20"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "USDT",
            LiquidityRole.TAKER,
            BigDecimal.ZERO,
            MarketSourceMode.LOCAL_SIMULATED,
            "local-perp",
            "BTCUSDT",
            filledAt.minusSeconds(1),
            filledAt),
        new BigDecimal("50000.00000000"),
        20,
        "Canonical perpetual fill");

    ArgumentCaptor<Object> captured = ArgumentCaptor.forClass(Object.class);
    verify(accountMutationPublisher, times(3)).publishEvent(captured.capture());
    return captured.getAllValues().stream()
        .map(TradingAccountMutationEvent.class::cast)
        .toList();
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

  private static OrderEntity depthSpotOrder(
      UUID accountId,
      OrderSide side,
      String quantity,
      String holdAmount,
      String holdCurrency
  ) {
    OrderEntity order = order(accountId);
    order.setProductType(ProductType.CRYPTO_SPOT);
    order.setSide(side);
    order.setStatus(OrderStatus.PENDING);
    order.setLots(new BigDecimal(quantity));
    order.setQuantity(new BigDecimal(quantity));
    order.setBaseQuantity(new BigDecimal(quantity));
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(new BigDecimal(quantity));
    order.setHoldAmount(new BigDecimal(holdAmount));
    order.setHoldCurrency(holdCurrency);
    return order;
  }

  private static OrderEntity depthPerpetualOrder(
      UUID accountId,
      OrderSide side,
      String quantity,
      String holdAmount
  ) {
    OrderEntity order = order(accountId);
    order.setProductType(ProductType.LINEAR_PERP);
    order.setSide(side);
    order.setStatus(OrderStatus.PENDING);
    order.setLots(new BigDecimal(quantity));
    order.setQuantity(new BigDecimal(quantity));
    order.setBaseQuantity(new BigDecimal(quantity));
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(new BigDecimal(quantity));
    order.setHoldAmount(new BigDecimal(holdAmount));
    order.setHoldCurrency("USDT");
    order.setPositionMode(PositionMode.ONE_WAY);
    order.setPositionSide(PositionSide.BOTH);
    order.setMarginMode(MarginMode.CROSS);
    order.setReduceOnly(false);
    order.setLeverage(20);
    return order;
  }

  private static PositionEntity openLinearPosition(
      UUID accountId,
      OrderSide side,
      String quantity,
      String entry,
      String mark,
      String marginHeld,
      String floatingPnl
  ) {
    BigDecimal lots = new BigDecimal(quantity);
    BigDecimal markPrice = new BigDecimal(mark);
    PositionEntity position = new PositionEntity();
    position.setId(UUID.randomUUID());
    position.setAccountId(accountId);
    position.setSymbol("BTCUSDT");
    position.setProductType(ProductType.LINEAR_PERP);
    position.setSide(side);
    position.setLots(lots);
    position.setOpenPrice(new BigDecimal(entry));
    position.setCurrentPrice(markPrice);
    position.setMarkPrice(markPrice);
    position.setNotional(lots.multiply(markPrice));
    position.setInitialMargin(new BigDecimal(marginHeld));
    position.setMaintenanceMargin(lots.multiply(markPrice).multiply(new BigDecimal("0.005")));
    position.setMarginHeld(new BigDecimal(marginHeld));
    position.setFloatingPnl(new BigDecimal(floatingPnl));
    position.setRealizedPnl(BigDecimal.ZERO);
    position.setFundingPnl(BigDecimal.ZERO);
    position.setSettlementAsset("USDT");
    position.setMarginAsset("USDT");
    position.setPositionMode(PositionMode.ONE_WAY);
    position.setPositionSide(PositionSide.BOTH);
    position.setMarginMode(MarginMode.CROSS);
    position.setLeverage(20);
    position.setStatus(PositionStatus.OPEN);
    position.setOpenedAt(Instant.parse("2026-07-17T07:00:00Z"));
    return position;
  }

  private void assertPerpetualDepthCrossingRejected(
      PositionMode positionMode,
      PositionSide positionSide,
      boolean reduceOnly,
      String fillIdentity
  ) {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    account.setPositionMode(positionMode);
    account.setUsedMargin(new BigDecimal("24.63500000"));
    account.setFreeMargin(new BigDecimal("19975.36500000"));
    OrderEntity order = depthPerpetualOrder(
        accountId, OrderSide.SELL, "3", "14.63500000");
    order.setPositionMode(positionMode);
    order.setPositionSide(positionSide);
    order.setReduceOnly(reduceOnly);
    PositionEntity existing = openLinearPosition(
        accountId, OrderSide.BUY, "2", "100", "100", "10", "0");
    existing.setPositionMode(positionMode);
    existing.setPositionSide(positionSide);
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(perpSymbol(
        "BTCUSDT", "LINEAR_PERPETUAL", "BTC", "USDT", "1", "1", "0.005",
        "USDT", "USDT")));
    when(tradeRepository.findByOrderIdAndFillIdentity(order.getId(), fillIdentity))
        .thenReturn(Optional.empty());
    when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, "BTCUSDT", positionMode, positionSide))
        .thenReturn(Optional.of(existing));
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository);

    assertThatThrownBy(() -> service.applyFill(
        order, order, account, takerFill("3", "90"), fillIdentity,
        perpetualSnapshot(Instant.parse("2026-07-17T08:02:20Z"), "95"),
        depthPolicy(), BigDecimal.ZERO))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("REDUCE_ONLY_EXCEEDS_POSITION"));

    assertThat(existing.getStatus()).isEqualTo(PositionStatus.OPEN);
    assertThat(existing.getLots()).isEqualByComparingTo("2.00000000");
    assertThat(existing.getOpenPrice()).isEqualByComparingTo("100.00000000");
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    assertThat(order.getHoldAmount()).isEqualByComparingTo("14.63500000");
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(orderRepository, never()).save(any());
    verify(tradeRepository, never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(ledgerService, accountMutationPublisher);
  }

  private void assertDepthSymbolRejectedBeforeMutation(Optional<SymbolEntity> persistedSymbol) {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    OrderEntity order = depthSpotOrder(
        accountId, OrderSide.BUY, "10", "1001.00000000", "USDT");
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(persistedSymbol);
    SpotSettlementService settlement = org.mockito.Mockito.mock(SpotSettlementService.class);
    WalletService walletService = org.mockito.Mockito.mock(WalletService.class);
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository, settlement, walletService);

    assertThatThrownBy(() -> service.applyFill(
        order, order, account, takerFill("2", "90"), "strict-symbol-fill",
        spotSnapshot(Instant.parse("2026-07-17T07:10:00Z")), depthPolicy(),
        new BigDecimal("800.80000000")))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("SYMBOL_NOT_TRADABLE"));

    verify(tradeRepository, never()).save(any());
    verify(orderRepository, never()).save(any());
    org.mockito.Mockito.verifyNoInteractions(settlement, walletService, positionRepository,
        accountRepository, ledgerService);
  }

  private void assertSqlNumericDepthFillRejectedBeforeInteractions(
      DemoMatchFill fill,
      String orderQuantity,
      String holdBefore,
      String holdAfter
  ) {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    OrderEntity order = depthSpotOrder(
        accountId, OrderSide.BUY, orderQuantity, holdBefore, "USDT");
    org.mockito.Mockito.lenient().when(symbolRepository.findBySymbol("BTCUSDT"))
        .thenReturn(Optional.of(persistedSpotSymbol("BTCUSDT")));
    org.mockito.Mockito.lenient().when(
        tradeRepository.findByOrderIdAndFillIdentity(order.getId(), "numeric-shape-fill"))
        .thenReturn(Optional.empty());
    org.mockito.Mockito.lenient().when(tradeRepository.save(any(TradeEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    org.mockito.Mockito.lenient().when(orderRepository.save(any(OrderEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    SpotSettlementService settlement = org.mockito.Mockito.mock(SpotSettlementService.class);
    WalletService walletService = org.mockito.Mockito.mock(WalletService.class);
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository, settlement, walletService);

    assertThatThrownBy(() -> service.applyFill(
        order, order, account, fill, "numeric-shape-fill",
        spotSnapshot(Instant.parse("2026-07-17T07:20:00Z")), depthPolicy(),
        new BigDecimal(holdAfter)))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("INVALID_DEPTH_FILL"));

    org.mockito.Mockito.verifyNoInteractions(
        tradeRepository, symbolRepository, orderRepository, settlement, walletService,
        positionRepository, accountRepository, ledgerService);
  }

  private void assertStateDerivedNumericDepthFillRejectedBeforeMutation(
      DemoMatchFill fill,
      String orderQuantity,
      String holdBefore,
      String holdAfter,
      java.util.function.Consumer<OrderEntity> customizeOrder
  ) {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId);
    OrderEntity order = depthSpotOrder(
        accountId, OrderSide.BUY, orderQuantity, holdBefore, "USDT");
    customizeOrder.accept(order);
    when(tradeRepository.findByOrderIdAndFillIdentity(order.getId(), "derived-numeric-fill"))
        .thenReturn(Optional.empty());
    SpotSettlementService settlement = org.mockito.Mockito.mock(SpotSettlementService.class);
    WalletService walletService = org.mockito.Mockito.mock(WalletService.class);
    OrderFillService service = new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository, settlement, walletService);

    assertThatThrownBy(() -> service.applyFill(
        order, order, account, fill, "derived-numeric-fill",
        spotSnapshot(Instant.parse("2026-07-17T07:24:00Z")), depthPolicy(),
        new BigDecimal(holdAfter)))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("INVALID_DEPTH_FILL"));

    verify(tradeRepository).findByOrderIdAndFillIdentity(order.getId(), "derived-numeric-fill");
    verify(tradeRepository, never()).save(any());
    org.mockito.Mockito.verifyNoMoreInteractions(tradeRepository);
    org.mockito.Mockito.verifyNoInteractions(
        symbolRepository, orderRepository, settlement, walletService,
        positionRepository, accountRepository, ledgerService);
  }

  private OrderFillService executablePerpetualDepthService(UUID accountId) {
    lenient().when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(perpSymbol(
        "BTCUSDT", "LINEAR_PERPETUAL", "BTC", "USDT", "1", "1", "0.005",
        "USDT", "USDT")));
    lenient().when(tradeRepository.findByOrderIdAndFillIdentity(any(UUID.class), any(String.class)))
        .thenReturn(Optional.empty());
    lenient().when(tradeRepository.save(any(TradeEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient().when(orderRepository.save(any(OrderEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient().when(accountRepository.save(any(TradingAccountEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient().when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, "BTCUSDT", PositionMode.ONE_WAY, PositionSide.BOTH))
        .thenReturn(Optional.empty());
    lenient().when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      if (position.getId() == null) {
        position.setId(UUID.randomUUID());
      }
      return position;
    });
    return new OrderFillService(
        orderRepository, tradeRepository, positionRepository, accountRepository,
        ledgerService, symbolRepository);
  }

  private static DemoExecutionPolicy depthPolicy() {
    return new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        new BigDecimal("0.0002"),
        new BigDecimal("0.0005"),
        new BigDecimal("0.001"),
        new BigDecimal("0.0001"),
        List.of(),
        List.of(),
        null);
  }

  private static DemoExecutionPolicy simplePolicy() {
    return new DemoExecutionPolicy(
        DemoMatchingMode.SIMPLE,
        new BigDecimal("0.0002"),
        new BigDecimal("0.0005"),
        new BigDecimal("0.001"),
        new BigDecimal("0.0001"),
        List.of(),
        List.of(),
        null);
  }

  private static DemoMatchFill takerFill(String quantity, String price) {
    return new DemoMatchFill(
        new BigDecimal(quantity),
        new BigDecimal(price),
        LiquidityRole.TAKER,
        new BigDecimal("0.0005"));
  }

  private static TradeEntity depthTrade(
      OrderEntity order,
      UUID accountId,
      String identity,
      String quantity,
      String price,
      String fee
  ) {
    TradeEntity trade = new TradeEntity();
    trade.setId(DemoFillIdentity.tradeId(order.getId(), identity));
    trade.setOrderId(order.getId());
    trade.setAccountId(accountId);
    trade.setSymbol(order.getSymbol());
    trade.setProductType(ProductType.CRYPTO_SPOT);
    trade.setLots(new BigDecimal(quantity));
    trade.setPrice(new BigDecimal(price));
    trade.setFee(new BigDecimal(fee));
    trade.setFeeAsset("USDT");
    trade.setLiquidityRole(LiquidityRole.TAKER);
    trade.setFillIdentity(identity);
    return trade;
  }

  private static ExecutableMarketSnapshot spotSnapshot(Instant asOf) {
    return new ExecutableMarketSnapshot(
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        "local-spot",
        "BTCUSDT",
        MarketSourceMode.LOCAL_SIMULATED,
        new BigDecimal("89.00000000"),
        new BigDecimal("90.00000000"),
        new BigDecimal("89.50000000"),
        null,
        null,
        asOf,
        asOf.plusSeconds(5));
  }

  private static ExecutableMarketSnapshot perpetualSnapshot(Instant asOf, String mark) {
    return perpetualSnapshot(asOf, mark, "local-perp");
  }

  private static ExecutableMarketSnapshot perpetualSnapshot(
      Instant asOf,
      String mark,
      String providerCode
  ) {
    return perpetualSnapshot(asOf, mark, providerCode, ProductType.LINEAR_PERP);
  }

  private static ExecutableMarketSnapshot perpetualSnapshot(
      Instant asOf,
      String mark,
      String providerCode,
      ProductType productType
  ) {
    return new ExecutableMarketSnapshot(
        "BTCUSDT",
        productType,
        providerCode,
        "BTCUSDT",
        MarketSourceMode.LOCAL_SIMULATED,
        new BigDecimal("89.00000000"),
        new BigDecimal("90.00000000"),
        new BigDecimal("89.50000000"),
        new BigDecimal(mark),
        new BigDecimal(mark),
        asOf,
        asOf.plusSeconds(5));
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
    if ("SPOT".equals(assetClass)) {
      symbol.setProductType(ProductType.CRYPTO_SPOT);
    }
    return symbol;
  }

  private static SymbolEntity persistedSpotSymbol(String code) {
    return symbol(code, "SPOT", "BTC", "USDT");
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
    symbol.setProductType("INVERSE_PERPETUAL".equals(assetClass)
        ? ProductType.INVERSE_PERP
        : ProductType.LINEAR_PERP);
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
