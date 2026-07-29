package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.execution.DemoBookLevel;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.DemoExecutionPolicyProvider;
import com.fxplatform.execution.DemoFillIdentity;
import com.fxplatform.execution.DemoMatchingMode;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.model.SpotMarketBundle;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.SpotPositionEntity;
import com.fxplatform.trading.entity.TradeEntity;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.SpotPositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import com.fxplatform.wallet.entity.AssetLedgerEntryEntity;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.enums.WalletType;
import com.fxplatform.wallet.repository.AssetLedgerEntryRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * RED contracts for ordinary resting Spot orders in DEPTH mode.
 *
 * <p>The fill, wallet, settlement, and position services are real. Repository doubles retain
 * every row so each virtual Tick is audited as financial state, not merely collaborator traffic.
 */
class DepthSpotPendingLifecycleTest {

  private static final String SYMBOL = "BTCUSDT";
  private static final Instant NOW = Instant.parse("2026-07-18T03:00:00Z");

  @Test
  void restingLimitAdvancesPartialPartialFilledAcrossThreeUniqueDepthTicksWithExactMakerState() {
    Harness harness = new Harness(restingBuy("1", "101", "101.20200000", false));

    harness.execute(tick(1, "98", "100"));
    assertOrder(harness.order, OrderStatus.PARTIALLY_FILLED,
        "0.40000000", "0.60000000", "60.72120000", "0.04000000", "100.00000000");
    harness.assertBalance("USDT", "959.96000000", "899.23880000", "60.72120000");
    harness.assertBalance("BTC", "0.40000000", "0.40000000", "0.00000000");
    harness.assertPosition("0.40000000", "100.00000000", "0.04000000");

    harness.execute(tick(2, "98", "100"));
    assertOrder(harness.order, OrderStatus.PARTIALLY_FILLED,
        "0.80000000", "0.20000000", "20.24040000", "0.08000000", "100.00000000");
    harness.assertBalance("USDT", "919.92000000", "899.67960000", "20.24040000");
    harness.assertBalance("BTC", "0.80000000", "0.80000000", "0.00000000");
    harness.assertPosition("0.80000000", "100.00000000", "0.08000000");

    harness.execute(tick(3, "98", "100"));
    assertOrder(harness.order, OrderStatus.FILLED,
        "1.00000000", "0.00000000", "0.00000000", "0.10000000", "100.00000000");
    harness.assertBalance("USDT", "899.90000000", "899.90000000", "0.00000000");
    harness.assertBalance("BTC", "1.00000000", "1.00000000", "0.00000000");
    harness.assertPosition("1.00000000", "100.00000000", "0.10000000");

    assertThat(harness.trades)
        .extracting(TradeEntity::getLots)
        .usingElementComparator(BigDecimal::compareTo)
        .containsExactly(decimal("0.4"), decimal("0.4"), decimal("0.2"));
    assertThat(harness.trades)
        .extracting(TradeEntity::getLiquidityRole)
        .containsOnly(LiquidityRole.MAKER);
    assertThat(harness.trades)
        .extracting(TradeEntity::getExecutedAt)
        .containsExactly(tickTime(1), tickTime(2), tickTime(3));
    harness.assertLedger(
        entry("USDT", "SPOT_BUY_DEBIT", "-40.00000000"),
        entry("USDT", "TRADE_FEE", "-0.04000000"),
        entry("BTC", "SPOT_BUY_CREDIT", "0.40000000"),
        entry("USDT", "SPOT_ORDER_RELEASE", "0.44080000"),
        entry("USDT", "SPOT_BUY_DEBIT", "-40.00000000"),
        entry("USDT", "TRADE_FEE", "-0.04000000"),
        entry("BTC", "SPOT_BUY_CREDIT", "0.40000000"),
        entry("USDT", "SPOT_ORDER_RELEASE", "0.44080000"),
        entry("USDT", "SPOT_BUY_DEBIT", "-20.00000000"),
        entry("USDT", "TRADE_FEE", "-0.02000000"),
        entry("BTC", "SPOT_BUY_CREDIT", "0.20000000"),
        entry("USDT", "SPOT_ORDER_RELEASE", "0.22040000"));

    ArgumentCaptor<String> eventTypes = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<OrderStatus> fromStatuses = ArgumentCaptor.forClass(OrderStatus.class);
    ArgumentCaptor<OrderStatus> toStatuses = ArgumentCaptor.forClass(OrderStatus.class);
    verify(harness.orderEventService, times(3)).record(
        eq(harness.order.getId()), eventTypes.capture(), fromStatuses.capture(),
        toStatuses.capture(), eq(null), anyString());
    assertThat(eventTypes.getAllValues())
        .containsExactly("ORDER_PARTIALLY_FILLED", "ORDER_PARTIALLY_FILLED", "ORDER_FILLED");
    assertThat(fromStatuses.getAllValues())
        .containsExactly(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED,
            OrderStatus.PARTIALLY_FILLED);
    assertThat(toStatuses.getAllValues())
        .containsExactly(OrderStatus.PARTIALLY_FILLED, OrderStatus.PARTIALLY_FILLED,
            OrderStatus.FILLED);
    verify(harness.fullFillCoordinator, never()).execute(any(), any());
  }

  @Test
  void replayingSameSnapshotTickIsNoOpBeforeMatchingTopUpLedgerAndEvents() {
    Harness harness = new Harness(restingBuy("1", "101", "101.20200000", false));
    SpotMarketBundle firstTick = tick(1, "98", "100");
    harness.execute(firstTick);
    FinancialState committed = harness.financialState();
    String ordinalZero = DemoFillIdentity.forSnapshot(
        harness.order.getId(), com.fxplatform.execution.ExecutableMarketSnapshot.from(firstTick), 0);

    clearInvocations(
        harness.depthOrderExecutionService,
        harness.orderFillService,
        harness.walletService,
        harness.ledgerService,
        harness.orderEventService,
        harness.orderRepository,
        harness.tradeRepository,
        harness.fullFillCoordinator);

    harness.execute(firstTick);

    assertThat(harness.financialState()).isEqualTo(committed);
    assertOrder(harness.order, OrderStatus.PARTIALLY_FILLED,
        "0.40000000", "0.60000000", "60.72120000", "0.04000000", "100.00000000");
    verify(harness.tradeRepository).findByOrderIdAndFillIdentity(harness.order.getId(), ordinalZero);
    verify(harness.orderRepository, never()).save(any(OrderEntity.class));
    verify(harness.walletService, never()).lockAvailableWithEntryType(
        any(UUID.class), anyString(), any(BigDecimal.class), anyString(), any(UUID.class),
        anyString(), anyString());
    verifyNoInteractions(
        harness.orderFillService,
        harness.ledgerService,
        harness.orderEventService);
    assertThat(mockingDetails(harness.depthOrderExecutionService).getInvocations())
        .noneMatch(invocation -> invocation.getMethod().getName().equals("prepare"));
    verify(harness.fullFillCoordinator, never()).execute(any(), any());
  }

  @Test
  void storedPostOnlyIgnoresPlacementGateAndUsesDepthBookAsMakerWhenTopOfBookDisagrees() {
    OrderEntity order = restingBuy("0.4", "100", "40.08000000", true);
    Harness harness = new Harness(order);

    // Snapshot ask says the order is not marketable; the configured DEPTH ask is exactly 100.
    // A stored Post Only flag must not be replayed as a placement-time would-take gate.
    harness.execute(tick(1, "99", "101"));

    assertOrder(order, OrderStatus.FILLED,
        "0.40000000", "0.00000000", "0.00000000", "0.04000000", "100.00000000");
    assertThat(harness.trades).singleElement().satisfies(trade -> {
      assertThat(trade.getLots()).isEqualByComparingTo("0.4");
      assertThat(trade.getPrice()).isEqualByComparingTo("100");
      assertThat(trade.getLiquidityRole()).isEqualTo(LiquidityRole.MAKER);
    });
    harness.assertBalance("USDT", "959.96000000", "959.96000000", "0.00000000");
    harness.assertBalance("BTC", "0.40000000", "0.40000000", "0.00000000");
    verify(harness.fullFillCoordinator, never()).execute(any(), any());
  }

  private static final class Harness {

    private final OrderEntity order;
    private final TradingAccountEntity account;
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final TradingAccountRepository accountRepository =
        mock(TradingAccountRepository.class);
    private final WalletBalanceRepository walletBalanceRepository =
        mock(WalletBalanceRepository.class);
    private final AssetLedgerEntryRepository assetLedgerEntryRepository =
        mock(AssetLedgerEntryRepository.class);
    private final SpotPositionRepository spotPositionRepository =
        mock(SpotPositionRepository.class);
    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final PositionRepository positionRepository = mock(PositionRepository.class);
    private final SymbolRepository symbolRepository = mock(SymbolRepository.class);
    private final InstrumentRulesEngine instrumentRulesEngine = mock(InstrumentRulesEngine.class);
    private final InstrumentRules instrumentRules = mock(InstrumentRules.class);
    private final LedgerService ledgerService = mock(LedgerService.class);
    private final OrderEventService orderEventService = mock(OrderEventService.class);
    private final DemoExecutionGuard demoExecutionGuard = mock(DemoExecutionGuard.class);
    private final FullFillCoordinator fullFillCoordinator = mock(FullFillCoordinator.class);
    private final TradingTransactionExecutor transactionExecutor =
        mock(TradingTransactionExecutor.class);
    private final MarketBundleResolver marketBundleResolver = mock(MarketBundleResolver.class);
    private final DemoExecutionPolicyProvider policyProvider =
        mock(DemoExecutionPolicyProvider.class);

    private final Map<String, WalletBalanceEntity> balances = new HashMap<>();
    private final Map<String, SpotPositionEntity> positions = new HashMap<>();
    private final List<AssetLedgerEntryEntity> assetLedger = new ArrayList<>();
    private final List<TradeEntity> trades = new ArrayList<>();
    private final AtomicReference<SpotMarketBundle> currentBundle = new AtomicReference<>();

    private final WalletService walletService;
    private final SpotPositionService spotPositionService;
    private final OrderFillService orderFillService;
    private final DepthOrderExecutionService depthOrderExecutionService;
    private final PendingOrderExecutionService scanner;

    private Harness(OrderEntity order) {
      this.order = order;
      this.account = account(order.getAccountId(), order.getUserId());
      configureRepositories();

      walletService = spy(new WalletService(
          walletBalanceRepository, assetLedgerEntryRepository));
      spotPositionService = new SpotPositionService(spotPositionRepository);
      SpotSettlementService settlementService =
          new SpotSettlementService(walletService, spotPositionService);
      orderFillService = spy(new OrderFillService(
          orderRepository,
          tradeRepository,
          positionRepository,
          accountRepository,
          ledgerService,
          symbolRepository,
          settlementService,
          walletService));
      DemoExecutionPolicy policy = policy();
      when(policyProvider.current()).thenReturn(policy);
      depthOrderExecutionService = spy(new DepthOrderExecutionService(
          policyProvider,
          tradeRepository,
          orderFillService,
          orderEventService,
          Clock.fixed(NOW, ZoneOffset.UTC)));

      PendingOrderExecutionProcessor processor = new PendingOrderExecutionProcessor(
          orderRepository,
          accountRepository,
          demoExecutionGuard,
          walletBalanceRepository,
          walletService,
          spotPositionService,
          fullFillCoordinator,
          orderFillService,
          orderEventService,
          transactionExecutor);
      installDepthAuthority(processor, depthOrderExecutionService);
      processor.setSymbolRepository(symbolRepository);
      processor.setInstrumentRulesEngine(instrumentRulesEngine);

      scanner = new PendingOrderExecutionService(
          orderRepository,
          accountRepository,
          mock(QuoteService.class),
          mock(RiskCheckService.class),
          orderFillService,
          orderEventService,
          demoExecutionGuard,
          walletBalanceRepository,
          walletService,
          spotPositionService,
          positionRepository,
          transactionExecutor,
          marketBundleResolver,
          fullFillCoordinator);
      scanner.setPendingOrderExecutionProcessor(processor);

      putBalance("USDT", "1000", decimal("1000").subtract(order.getHoldAmount()).toPlainString(),
          order.getHoldAmount().toPlainString());
      putBalance("BTC", "0", "0", "0");
      putPosition();
    }

    private void configureRepositories() {
      when(transactionExecutor.execute(any())).thenAnswer(invocation ->
          ((Supplier<?>) invocation.getArgument(0)).get());
      when(accountRepository.findById(order.getAccountId())).thenReturn(Optional.of(account));
      when(accountRepository.findByIdForUpdate(order.getAccountId()))
          .thenReturn(Optional.of(account));
      when(orderRepository.findByStatus(any(OrderStatus.class))).thenAnswer(invocation -> {
        OrderStatus requested = invocation.getArgument(0);
        return order.getStatus() == requested ? List.of(order) : List.of();
      });
      when(orderRepository.findUserStopLimitsAwaitingActivation()).thenReturn(List.of());
      when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
      when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation ->
          invocation.getArgument(0));

      when(marketBundleResolver.resolveSpot(eq(SYMBOL), any())).thenAnswer(invocation -> {
        SpotMarketBundle bundle = currentBundle.get();
        if (bundle == null) {
          throw new AssertionError("A virtual DEPTH Tick must be installed before scanning");
        }
        return bundle;
      });
      when(symbolRepository.findBySymbol(SYMBOL)).thenReturn(Optional.of(symbol()));
      when(instrumentRulesEngine.rules(any(SymbolEntity.class))).thenReturn(instrumentRules);
      when(instrumentRules.stepSize()).thenReturn(decimal("0.0001"));
      when(instrumentRules.productType()).thenReturn(ProductType.CRYPTO_SPOT);

      when(walletBalanceRepository.findByAccountIdForUpdate(order.getAccountId()))
          .thenAnswer(invocation -> balances.values().stream().toList());
      when(walletBalanceRepository.findByAccountIdAndWalletTypeAndAssetForUpdate(
          any(UUID.class), anyString(), anyString())).thenAnswer(invocation -> Optional.ofNullable(
              balances.get(walletKey(
                  invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)))));
      when(walletBalanceRepository.save(any(WalletBalanceEntity.class))).thenAnswer(invocation -> {
        WalletBalanceEntity balance = invocation.getArgument(0);
        if (balance.getId() == null) {
          balance.setId(UUID.randomUUID());
        }
        balances.put(walletKey(
            balance.getAccountId(), balance.getWalletType(), balance.getAsset()), balance);
        return balance;
      });
      when(assetLedgerEntryRepository.findByBusinessOperation(
          any(UUID.class), anyString(), anyString(), anyString(), any(UUID.class), anyString()))
          .thenAnswer(invocation -> assetLedger.stream()
              .filter(entry -> entry.getAccountId().equals(invocation.getArgument(0)))
              .filter(entry -> entry.getWalletType().equals(invocation.getArgument(1)))
              .filter(entry -> entry.getAsset().equals(invocation.getArgument(2)))
              .filter(entry -> entry.getReferenceType().equals(invocation.getArgument(3)))
              .filter(entry -> entry.getReferenceId().equals(invocation.getArgument(4)))
              .filter(entry -> entry.getOperationType().equals(invocation.getArgument(5)))
              .findFirst().orElse(null));
      when(assetLedgerEntryRepository.save(any(AssetLedgerEntryEntity.class)))
          .thenAnswer(invocation -> {
            AssetLedgerEntryEntity entry = invocation.getArgument(0);
            if (entry.getId() == null) {
              entry.setId(UUID.randomUUID());
            }
            assetLedger.add(entry);
            return entry;
          });

      when(spotPositionRepository.findByAccountIdForUpdate(order.getAccountId()))
          .thenAnswer(invocation -> positions.values().stream().toList());
      when(spotPositionRepository.findBySlotForUpdate(
          any(UUID.class), anyString(), anyString(), anyString())).thenAnswer(invocation ->
              Optional.ofNullable(positions.get(positionKey(
                  invocation.getArgument(0), invocation.getArgument(1),
                  invocation.getArgument(2), invocation.getArgument(3)))));
      when(spotPositionRepository.save(any(SpotPositionEntity.class))).thenAnswer(invocation -> {
        SpotPositionEntity position = invocation.getArgument(0);
        if (position.getId() == null) {
          position.setId(UUID.randomUUID());
        }
        positions.put(positionKey(
            position.getAccountId(), position.getWalletType(),
            position.getAsset(), position.getCostAsset()), position);
        return position;
      });

      when(tradeRepository.findByOrderIdAndFillIdentity(any(UUID.class), anyString()))
          .thenAnswer(invocation -> trades.stream()
              .filter(trade -> trade.getOrderId().equals(invocation.getArgument(0)))
              .filter(trade -> trade.getFillIdentity().equals(invocation.getArgument(1)))
              .findFirst());
      when(tradeRepository.save(any(TradeEntity.class))).thenAnswer(invocation -> {
        TradeEntity trade = invocation.getArgument(0);
        trades.add(trade);
        return trade;
      });
    }

    private void execute(SpotMarketBundle bundle) {
      currentBundle.set(bundle);
      scanner.executePendingOrders();
    }

    private void putBalance(String asset, String total, String available, String locked) {
      WalletBalanceEntity balance = new WalletBalanceEntity();
      balance.setId(UUID.randomUUID());
      balance.setAccountId(order.getAccountId());
      balance.setWalletType(WalletType.SPOT.code());
      balance.setAsset(asset);
      balance.setTotal(decimal(total));
      balance.setAvailable(decimal(available));
      balance.setLocked(decimal(locked));
      balances.put(walletKey(order.getAccountId(), WalletType.SPOT.code(), asset), balance);
    }

    private void putPosition() {
      SpotPositionEntity position = new SpotPositionEntity();
      position.setId(UUID.randomUUID());
      position.setAccountId(order.getAccountId());
      position.setWalletType(WalletType.SPOT.code());
      position.setAsset("BTC");
      position.setCostAsset("USDT");
      position.setQuantity(decimal("0"));
      position.setAverageCost(decimal("0"));
      position.setRealizedPnl(decimal("0"));
      position.setUnrealizedPnl(decimal("0"));
      position.setFeeCost(decimal("0"));
      positions.put(positionKey(
          order.getAccountId(), WalletType.SPOT.code(), "BTC", "USDT"), position);
    }

    private void assertBalance(String asset, String total, String available, String locked) {
      WalletBalanceEntity balance = balances.get(
          walletKey(order.getAccountId(), WalletType.SPOT.code(), asset));
      assertThat(balance).isNotNull();
      assertThat(balance.getTotal()).isEqualByComparingTo(total);
      assertThat(balance.getAvailable()).isEqualByComparingTo(available);
      assertThat(balance.getLocked()).isEqualByComparingTo(locked);
      assertThat(balance.getTotal()).isEqualByComparingTo(
          balance.getAvailable().add(balance.getLocked()));
    }

    private void assertPosition(String quantity, String averageCost, String feeCost) {
      SpotPositionEntity position = positions.get(positionKey(
          order.getAccountId(), WalletType.SPOT.code(), "BTC", "USDT"));
      assertThat(position).isNotNull();
      assertThat(position.getQuantity()).isEqualByComparingTo(quantity);
      assertThat(position.getAverageCost()).isEqualByComparingTo(averageCost);
      assertThat(position.getFeeCost()).isEqualByComparingTo(feeCost);
      assertThat(position.getRealizedPnl()).isEqualByComparingTo("0");
    }

    private void assertLedger(ExpectedLedger... expected) {
      assertThat(assetLedger).hasSize(expected.length);
      for (int index = 0; index < expected.length; index++) {
        AssetLedgerEntryEntity actual = assetLedger.get(index);
        assertThat(actual.getAsset()).isEqualTo(expected[index].asset());
        assertThat(actual.getEntryType()).isEqualTo(expected[index].entryType());
        assertThat(actual.getAmount()).isEqualByComparingTo(expected[index].amount());
      }
    }

    private FinancialState financialState() {
      WalletBalanceEntity usdt = balances.get(
          walletKey(order.getAccountId(), WalletType.SPOT.code(), "USDT"));
      WalletBalanceEntity btc = balances.get(
          walletKey(order.getAccountId(), WalletType.SPOT.code(), "BTC"));
      SpotPositionEntity position = positions.get(positionKey(
          order.getAccountId(), WalletType.SPOT.code(), "BTC", "USDT"));
      return new FinancialState(
          value(order.getFilledQuantity()),
          value(order.getRemainingQuantity()),
          value(order.getHoldAmount()),
          value(order.getFee()),
          value(order.getAvgFillPrice()),
          order.getStatus(),
          value(usdt.getTotal()), value(usdt.getAvailable()), value(usdt.getLocked()),
          value(btc.getTotal()), value(btc.getAvailable()), value(btc.getLocked()),
          value(position.getQuantity()), value(position.getAverageCost()), value(position.getFeeCost()),
          trades.size(), assetLedger.size());
    }
  }

  private static void installDepthAuthority(
      PendingOrderExecutionProcessor processor,
      DepthOrderExecutionService depthOrderExecutionService
  ) {
    Optional<Method> setter = Arrays.stream(PendingOrderExecutionProcessor.class.getDeclaredMethods())
        .filter(method -> method.getParameterCount() == 1)
        .filter(method -> method.getParameterTypes()[0] == DepthOrderExecutionService.class)
        .findFirst();
    try {
      if (setter.isPresent()) {
        setter.get().setAccessible(true);
        setter.get().invoke(processor, depthOrderExecutionService);
        return;
      }
      Optional<Field> field = Arrays.stream(PendingOrderExecutionProcessor.class.getDeclaredFields())
          .filter(candidate -> candidate.getType() == DepthOrderExecutionService.class)
          .findFirst();
      if (field.isPresent()) {
        field.get().setAccessible(true);
        field.get().set(processor, depthOrderExecutionService);
        return;
      }
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError("Pending DEPTH execution authority could not be injected", exception);
    }
    throw new AssertionError(
        "PendingOrderExecutionProcessor has no DepthOrderExecutionService injection point");
  }

  private static OrderEntity restingBuy(
      String quantity,
      String limitPrice,
      String hold,
      boolean postOnly
  ) {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setUserId(userId);
    order.setAccountId(accountId);
    order.setSymbol(SYMBOL);
    order.setProductType(ProductType.CRYPTO_SPOT);
    order.setPositionSide(PositionSide.BOTH);
    order.setMarginMode(MarginMode.CASH);
    order.setSide(OrderSide.BUY);
    order.setOrderType(OrderType.LIMIT);
    order.setStatus(OrderStatus.PENDING);
    order.setOrderOrigin(OrderOrigin.USER);
    order.setLots(decimal(quantity));
    order.setQuantity(decimal(quantity));
    order.setOriginalQuantity(decimal(quantity));
    order.setBaseQuantity(decimal(quantity));
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setFilledQuantity(decimal("0"));
    order.setRemainingQuantity(decimal(quantity));
    order.setRequestedPrice(decimal(limitPrice));
    order.setPrice(decimal(limitPrice));
    order.setTimeInForce(TimeInForce.GTC);
    order.setPostOnly(postOnly);
    order.setReduceOnly(false);
    order.setHoldAmount(decimal(hold));
    order.setHoldCurrency("USDT");
    order.setFee(decimal("0"));
    order.setClientOrderId("depth-pending-" + order.getId());
    order.setIdempotencyKey(order.getClientOrderId());
    order.setCreatedAt(NOW.minusSeconds(60));
    return order;
  }

  private static TradingAccountEntity account(UUID accountId, UUID userId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    account.setBaseCurrency("USDT");
    account.setLeverage(1);
    account.setBalance(decimal("10000"));
    account.setEquity(decimal("10000"));
    account.setUsedMargin(decimal("0"));
    account.setFreeMargin(decimal("10000"));
    return account;
  }

  private static DemoExecutionPolicy policy() {
    return new DemoExecutionPolicy(
        DemoMatchingMode.DEPTH,
        decimal("0.001"),
        decimal("0.002"),
        decimal("0.001"),
        decimal("0.0001"),
        List.of(new DemoBookLevel(decimal("99"), decimal("10"))),
        List.of(new DemoBookLevel(decimal("100"), decimal("10"))),
        decimal("0.4"));
  }

  private static SymbolEntity symbol() {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(UUID.randomUUID());
    symbol.setSymbol(SYMBOL);
    symbol.setProductType(ProductType.CRYPTO_SPOT);
    symbol.setBaseCurrency("BTC");
    symbol.setQuoteCurrency("USDT");
    symbol.setEnabled(true);
    symbol.setTradable(true);
    return symbol;
  }

  private static SpotMarketBundle tick(int ordinal, String bid, String ask) {
    Instant asOf = tickTime(ordinal);
    return new SpotMarketBundle(
        SYMBOL,
        SYMBOL,
        "binance",
        MarketSourceMode.PUBLIC_EXTERNAL,
        decimal(bid),
        decimal(ask),
        decimal("100"),
        null,
        List.of(),
        List.of(),
        asOf,
        NOW.plusSeconds(30));
  }

  private static Instant tickTime(int ordinal) {
    return NOW.minusSeconds(4L - ordinal);
  }

  private static void assertOrder(
      OrderEntity order,
      OrderStatus status,
      String filled,
      String remaining,
      String hold,
      String fee,
      String average
  ) {
    assertThat(order.getStatus()).isEqualTo(status);
    assertThat(order.getFilledQuantity()).isEqualByComparingTo(filled);
    assertThat(order.getRemainingQuantity()).isEqualByComparingTo(remaining);
    assertThat(order.getHoldAmount()).isEqualByComparingTo(hold);
    assertThat(order.getFee()).isEqualByComparingTo(fee);
    assertThat(order.getAvgFillPrice()).isEqualByComparingTo(average);
    assertThat(order.getLiquidityRole()).isEqualTo(LiquidityRole.MAKER);
  }

  private static ExpectedLedger entry(String asset, String entryType, String amount) {
    return new ExpectedLedger(asset, entryType, amount);
  }

  private static String walletKey(UUID accountId, String walletType, String asset) {
    return accountId + "|" + walletType + "|" + asset;
  }

  private static String positionKey(
      UUID accountId,
      String walletType,
      String asset,
      String costAsset
  ) {
    return accountId + "|" + walletType + "|" + asset + "|" + costAsset;
  }

  private static String value(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros().toPlainString();
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }

  private record ExpectedLedger(String asset, String entryType, String amount) {
  }

  private record FinancialState(
      String filled,
      String remaining,
      String hold,
      String fee,
      String average,
      OrderStatus status,
      String usdtTotal,
      String usdtAvailable,
      String usdtLocked,
      String btcTotal,
      String btcAvailable,
      String btcLocked,
      String positionQuantity,
      String positionAverage,
      String positionFee,
      int trades,
      int ledgerEntries
  ) {
  }
}
