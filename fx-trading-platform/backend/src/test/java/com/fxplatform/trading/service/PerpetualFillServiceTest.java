package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.service.MarginCalculator;
import com.fxplatform.risk.service.PerpMarginCalculator;
import com.fxplatform.risk.service.PnLCalculator;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.entity.TradeEntity;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PerpetualFillServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-12T08:00:00Z");
  private static final String SYMBOL = "BTCUSDT-PERP";

  @Mock private OrderRepository orderRepository;
  @Mock private TradeRepository tradeRepository;
  @Mock private PositionRepository positionRepository;
  @Mock private TradingAccountRepository accountRepository;
  @Mock private LedgerService ledgerService;
  @Mock private SymbolRepository symbolRepository;

  @Test
  void openingFillUsesActualEntryAndAuthorityMarkThenConsumesWorstPriceHold() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "10.25100000");
    // The fill authority is the submitted order snapshot, not a later account-mode value.
    account.setPositionMode(PositionMode.HEDGE);
    OrderEntity order = order(accountId, "10.25100000");
    // Canonical execution uses the locked executionLeverage argument and must not touch legacy
    // order/account leverage or contract-size margin calculation.
    order.setLeverage(null);
    account.setLeverage(null);
    SymbolEntity symbol = linearSymbol();
    AtomicReference<PositionEntity> savedPosition = new AtomicReference<>();

    when(symbolRepository.findBySymbol(SYMBOL)).thenReturn(Optional.of(symbol));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(tradeRepository.save(any(TradeEntity.class))).thenAnswer(invocation -> {
      TradeEntity trade = invocation.getArgument(0);
      if (trade.getId() == null) {
        trade.setId(UUID.randomUUID());
      }
      return trade;
    });
    when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, SYMBOL, PositionMode.ONE_WAY, PositionSide.BOTH))
        .thenReturn(Optional.empty());
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      if (position.getId() == null) {
        position.setId(UUID.randomUUID());
      }
      savedPosition.set(position);
      return position;
    });
    when(accountRepository.save(any(TradingAccountEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service().fillPerpetual(
        order,
        account,
        fill("101", "1.0000"),
        new BigDecimal("100"),
        10,
        "Perpetual position margin held");

    PositionEntity position = savedPosition.get();
    assertAll(
        () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED),
        () -> assertThat(order.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO),
        () -> assertThat(position.getOpenPrice()).isEqualByComparingTo("101"),
        () -> assertThat(position.getPositionMode()).isEqualTo(PositionMode.ONE_WAY),
        () -> assertThat(position.getCurrentPrice()).isEqualByComparingTo("100"),
        () -> assertThat(position.getMarkPrice()).isEqualByComparingTo("100"),
        () -> assertThat(position.getNotional()).isEqualByComparingTo("100.00000000"),
        () -> assertThat(position.getInitialMargin()).isEqualByComparingTo("10.10000000"),
        () -> assertThat(position.getMarginHeld()).isEqualByComparingTo("10.10000000"),
        () -> assertThat(position.getMaintenanceMargin()).isEqualByComparingTo("0.50000000"),
        () -> assertThat(position.getFloatingPnl()).isEqualByComparingTo("-1.00000000"),
        () -> assertThat(position.getVersion()).isZero(),
        () -> assertThat(account.getUsedMargin()).isEqualByComparingTo("10.10000000"),
        () -> assertThat(account.getBalance()).isEqualByComparingTo("49999.94950000"),
        () -> assertThat(account.getEquity()).isEqualByComparingTo("49998.94950000"),
        () -> assertThat(account.getFreeMargin()).isEqualByComparingTo("49988.84950000"));
    verify(accountRepository, never()).reserveMarginIfAvailable(eq(accountId), any());
    InOrder persistenceOrder = inOrder(
        orderRepository,
        positionRepository,
        accountRepository,
        tradeRepository,
        ledgerService);
    persistenceOrder.verify(orderRepository).save(order);
    persistenceOrder.verify(positionRepository).save(position);
    persistenceOrder.verify(accountRepository).save(account);
    persistenceOrder.verify(tradeRepository).save(any(TradeEntity.class));
    persistenceOrder.verify(orderRepository).save(order);
    persistenceOrder.verify(accountRepository).save(account);
    persistenceOrder.verify(ledgerService).recordOrderRelease(
        account,
        new BigDecimal("10.25100000"),
        order.getId(),
        "Perpetual order hold consumed");
    InOrder ledgerOrder = inOrder(ledgerService);
    ledgerOrder.verify(ledgerService).recordOrderRelease(
        account,
        new BigDecimal("10.25100000"),
        order.getId(),
        "Perpetual order hold consumed");
    ledgerOrder.verify(ledgerService).recordMarginHold(
        account,
        new BigDecimal("10.10000000"),
        position.getId(),
        "Position margin held from order hold");
  }

  @Test
  void deepExistingLossNeedsOnlyMarkToFillAdverseHoldForLegalClose() {
    UUID accountId = UUID.randomUUID();
    BigDecimal hold = new BigDecimal("1.04950000");
    TradingAccountEntity account = account(accountId, "21.04950000");
    account.setEquity(new BigDecimal("49900.00000000"));
    account.setFreeMargin(new BigDecimal("49878.95050000"));
    OrderEntity order = order(accountId, hold.toPlainString());
    order.setSide(OrderSide.SELL);
    order.setReduceOnly(true);
    order.setQuantity(new BigDecimal("1.0000"));
    order.setOriginalQuantity(new BigDecimal("1.0000"));
    order.setQuantityUnit(QuantityUnit.BASE);
    PositionEntity existing = openLong(accountId);
    order.setParentPositionId(existing.getId());
    order.setProtectionType(ProtectionType.TAKE_PROFIT);

    when(symbolRepository.findBySymbol(SYMBOL)).thenReturn(Optional.of(linearSymbol()));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(tradeRepository.save(any(TradeEntity.class))).thenAnswer(invocation -> {
      TradeEntity trade = invocation.getArgument(0);
      trade.setId(UUID.randomUUID());
      return trade;
    });
    when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, SYMBOL, PositionMode.ONE_WAY, PositionSide.BOTH))
        .thenReturn(Optional.of(existing));
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(accountRepository.save(any(TradingAccountEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Object result = capture(() -> service().fillPerpetual(
        order,
        account,
        fill("99", "1.0000"),
        new BigDecimal("100"),
        10,
        "Perpetual position margin held"));

    assertAll(
        () -> assertThat(result).isSameAs(order),
        () -> {
          if (result == order) {
            assertThat(existing.getStatus()).isEqualTo(com.fxplatform.trading.enums.PositionStatus.CLOSED);
            assertThat(existing.getRealizedPnl()).isEqualByComparingTo("-101.00000000");
            assertThat(existing.getVersion()).isEqualTo(1L);
            assertThat(order.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(account.getUsedMargin()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(account.getBalance()).isEqualByComparingTo("49898.95050000");
            assertThat(account.getEquity()).isEqualByComparingTo("49898.95050000");
            assertThat(account.getFreeMargin()).isEqualByComparingTo("49898.95050000");
          }
        });
    org.mockito.ArgumentCaptor<TradeEntity> trade =
        org.mockito.ArgumentCaptor.forClass(TradeEntity.class);
    verify(tradeRepository).save(trade.capture());
    assertThat(trade.getValue().getRealizedPnl()).isEqualByComparingTo("-101.00000000");
    verify(accountRepository, never()).reserveMarginIfAvailable(eq(accountId), any());
    InOrder ledgerOrder = inOrder(ledgerService);
    ledgerOrder.verify(ledgerService).recordOrderRelease(
        account, hold, order.getId(), "Perpetual order hold consumed");
    ledgerOrder.verify(ledgerService).recordMarginRelease(
        account, new BigDecimal("20.00000000"), existing.getId(), "Position margin released");
    ledgerOrder.verify(ledgerService).recordTradePnl(
        account, new BigDecimal("-101.00000000"), existing.getId(), "Position realized PnL");
  }

  @Test
  void isolatedIncreasePreservesSignedManualMarginAboveNewTheoreticalInitial() {
    UUID accountId = UUID.randomUUID();
    BigDecimal hold = new BigDecimal("11.05500000");
    TradingAccountEntity account = account(accountId, "26.05500000");
    account.setFreeMargin(new BigDecimal("49973.94500000"));
    OrderEntity order = order(accountId, hold.toPlainString());
    order.setMarginMode(MarginMode.ISOLATED);
    order.setQuantity(new BigDecimal("1.0000"));
    order.setOriginalQuantity(new BigDecimal("1.0000"));
    order.setQuantityUnit(QuantityUnit.BASE);
    PositionEntity existing = openPosition(
        accountId,
        OrderSide.BUY,
        "1",
        "100",
        "10",
        "15",
        MarginMode.ISOLATED,
        "0");
    AtomicReference<PositionEntity> savedPosition = new AtomicReference<>();
    stubExistingFill(accountId, existing, savedPosition);

    service().fillPerpetual(
        order,
        account,
        fill("110", "1.0000"),
        new BigDecimal("105"),
        10,
        "Perpetual position margin held");

    PositionEntity position = savedPosition.get();
    assertAll(
        () -> assertThat(position.getLots()).isEqualByComparingTo("2"),
        () -> assertThat(position.getOpenPrice()).isEqualByComparingTo("105.00000000"),
        () -> assertThat(position.getInitialMargin()).isEqualByComparingTo("21.00000000"),
        () -> assertThat(position.getMarginHeld()).isEqualByComparingTo("26.00000000"),
        () -> assertThat(position.getNotional()).isEqualByComparingTo("210.00000000"),
        () -> assertThat(position.getMaintenanceMargin()).isEqualByComparingTo("1.05000000"),
        () -> assertThat(position.getVersion()).isEqualTo(1L),
        () -> assertThat(account.getUsedMargin()).isEqualByComparingTo("26.00000000"),
        () -> assertThat(account.getBalance()).isEqualByComparingTo("49999.94500000"),
        () -> assertThat(account.getEquity()).isEqualByComparingTo("49999.94500000"),
        () -> assertThat(account.getFreeMargin()).isEqualByComparingTo("49973.94500000"));
    verify(ledgerService).recordOrderRelease(
        account, hold, order.getId(), "Perpetual order hold consumed");
    verify(ledgerService).recordMarginHold(
        account,
        new BigDecimal("11.00000000"),
        position.getId(),
        "Position margin held from order hold");
  }

  @Test
  void sameSideIncreaseConsumesSliceMarginWithoutWeightedEntryRoundingOverrun() {
    UUID accountId = UUID.randomUUID();
    BigDecimal oldMargin = new BigDecimal("58211.61602100");
    BigDecimal openingMargin = new BigDecimal("222468.20789850");
    BigDecimal fee = new BigDecimal("222.46820790");
    BigDecimal hold = openingMargin.add(fee);
    TradingAccountEntity account = account(accountId, oldMargin.add(hold).toPlainString());
    account.setBalance(new BigDecimal("1000000.00000000"));
    account.setEquity(new BigDecimal("1000000.00000000"));
    account.setFreeMargin(account.getBalance().subtract(account.getUsedMargin()));
    OrderEntity order = order(accountId, hold.toPlainString());
    order.setLots(new BigDecimal("7.4871"));
    order.setQuantity(new BigDecimal("7.4871"));
    order.setOriginalQuantity(new BigDecimal("7.4871"));
    order.setBaseQuantity(new BigDecimal("7.4871"));
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(new BigDecimal("7.4871"));
    PositionEntity existing = openPosition(
        accountId,
        OrderSide.BUY,
        "4.7242",
        "24644.01",
        oldMargin.toPlainString(),
        oldMargin.toPlainString(),
        MarginMode.CROSS,
        "0");
    existing.setLeverage(2);
    existing.setCurrentPrice(new BigDecimal("24644.01"));
    existing.setMarkPrice(new BigDecimal("24644.01"));
    AtomicReference<PositionEntity> savedPosition = new AtomicReference<>();
    stubExistingFill(accountId, existing, savedPosition);

    service().fillPerpetual(
        order,
        account,
        fill("59427.07", "7.4871"),
        new BigDecimal("24644.01"),
        2,
        "Perpetual position margin held");

    PositionEntity position = savedPosition.get();
    assertAll(
        () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED),
        () -> assertThat(order.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO),
        () -> assertThat(order.getFee()).isEqualByComparingTo(fee),
        () -> assertThat(position.getLots()).isEqualByComparingTo("12.2113"),
        () -> assertThat(position.getOpenPrice()).isEqualByComparingTo("45970.50664868"),
        () -> assertThat(position.getInitialMargin()).isEqualByComparingTo("280679.82391952"),
        () -> assertThat(position.getMarginHeld()).isEqualByComparingTo("280679.82391950"),
        () -> assertThat(account.getUsedMargin()).isEqualByComparingTo("280679.82391950"),
        () -> assertThat(account.getBalance()).isEqualByComparingTo("999777.53179210"),
        () -> assertThat(account.getEquity()).isEqualByComparingTo("739353.28326607"),
        () -> assertThat(account.getFreeMargin()).isEqualByComparingTo("458673.45934657"));
    verify(ledgerService).recordOrderRelease(
        account, hold, order.getId(), "Perpetual order hold consumed");
    verify(ledgerService).recordMarginHold(
        account,
        openingMargin,
        position.getId(),
        "Position margin held from order hold");
  }

  @Test
  void isolatedPartialReduceKeepsTheoreticalAndActualMarginSeparate() {
    UUID accountId = UUID.randomUUID();
    BigDecimal hold = new BigDecimal("1.04950000");
    TradingAccountEntity account = account(accountId, "31.04950000");
    account.setFreeMargin(new BigDecimal("49968.95050000"));
    OrderEntity order = order(accountId, hold.toPlainString());
    order.setSide(OrderSide.SELL);
    order.setMarginMode(MarginMode.ISOLATED);
    order.setReduceOnly(true);
    order.setQuantity(new BigDecimal("1.0000"));
    order.setOriginalQuantity(new BigDecimal("1.0000"));
    order.setQuantityUnit(QuantityUnit.BASE);
    PositionEntity existing = openPosition(
        accountId,
        OrderSide.BUY,
        "2",
        "100",
        "20",
        "30",
        MarginMode.ISOLATED,
        "0");
    order.setParentPositionId(existing.getId());
    account.setFreeMargin(new BigDecimal("49970.00000000"));
    AtomicReference<PositionEntity> savedPosition = new AtomicReference<>();
    stubExistingFill(accountId, existing, savedPosition);

    service().fillPerpetual(
        order,
        account,
        fill("99", "1.0000"),
        new BigDecimal("100"),
        10,
        "Perpetual position margin held");

    PositionEntity position = savedPosition.get();
    assertAll(
        () -> assertThat(position.getLots()).isEqualByComparingTo("1"),
        () -> assertThat(position.getInitialMargin()).isEqualByComparingTo("10.00000000"),
        () -> assertThat(position.getMarginHeld()).isEqualByComparingTo("15.00000000"),
        () -> assertThat(position.getRealizedPnl()).isEqualByComparingTo("-1.00000000"),
        () -> assertThat(position.getFloatingPnl()).isEqualByComparingTo(BigDecimal.ZERO),
        () -> assertThat(position.getVersion()).isEqualTo(1L),
        () -> assertThat(account.getUsedMargin()).isEqualByComparingTo("15.00000000"),
        () -> assertThat(account.getBalance()).isEqualByComparingTo("49998.95050000"),
        () -> assertThat(account.getEquity()).isEqualByComparingTo("49998.95050000"),
        () -> assertThat(account.getFreeMargin()).isEqualByComparingTo("49983.95050000"));
    org.mockito.ArgumentCaptor<TradeEntity> trade =
        org.mockito.ArgumentCaptor.forClass(TradeEntity.class);
    verify(tradeRepository).save(trade.capture());
    assertThat(trade.getValue().getRealizedPnl()).isEqualByComparingTo("-1.00000000");
    verify(ledgerService).recordOrderRelease(
        account, hold, order.getId(), "Perpetual order hold consumed");
    verify(ledgerService).recordMarginRelease(
        account,
        new BigDecimal("15.00000000"),
        position.getId(),
        "Position margin released");
  }

  @Test
  void tinyImmediatePerpetualFeeUsesCanonicalMoneyScaleForHoldCoverage() {
    TinyFillResult result = tinyFeeFill(OrderStatus.ACCEPTED);

    assertTinyFeeFill(result);
  }

  @Test
  void tinyPendingPerpetualFeeUsesCanonicalMoneyScaleForHoldCoverage() {
    TinyFillResult result = tinyFeeFill(OrderStatus.WORKING);

    assertTinyFeeFill(result);
  }

  @Test
  void canonicalOpeningHoldCoversNotionalFirstMarginRoundingAtLowLeverage() {
    UUID accountId = UUID.randomUUID();
    BigDecimal hold = new BigDecimal("12.51377629");
    TradingAccountEntity account = account(accountId, hold.toPlainString());
    OrderEntity order = order(accountId, hold.toPlainString());
    order.setLots(new BigDecimal("0.0005"));
    order.setQuantity(new BigDecimal("0.0005"));
    order.setOriginalQuantity(new BigDecimal("0.0005"));
    order.setBaseQuantity(new BigDecimal("0.0005"));
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(new BigDecimal("0.0005"));
    AtomicReference<PositionEntity> savedPosition = new AtomicReference<>();
    when(symbolRepository.findBySymbol(SYMBOL)).thenReturn(Optional.of(linearSymbol()));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(tradeRepository.save(any(TradeEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, SYMBOL, PositionMode.ONE_WAY, PositionSide.BOTH)).thenReturn(Optional.empty());
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      position.setId(UUID.randomUUID());
      savedPosition.set(position);
      return position;
    });
    when(accountRepository.save(any(TradingAccountEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service().fillPerpetual(
        order,
        account,
        fill("50005.10001000", "0.0005"),
        new BigDecimal("50000"),
        2,
        "Perpetual position margin held");

    assertAll(
        () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED),
        () -> assertThat(order.getFee()).isEqualByComparingTo("0.01250128"),
        () -> assertThat(savedPosition.get().getInitialMargin())
            .isEqualByComparingTo("12.50127501"),
        () -> assertThat(order.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO));
  }

  private TinyFillResult tinyFeeFill(OrderStatus initialStatus) {
    // The stored hold and the persisted fee share the canonical 8-decimal money scale.
    UUID accountId = UUID.randomUUID();
    BigDecimal hold = new BigDecimal("0.50255226");
    TradingAccountEntity account = account(accountId, hold.toPlainString());
    OrderEntity order = order(accountId, hold.toPlainString());
    order.setStatus(initialStatus);
    order.setLots(new BigDecimal("0.0001"));
    order.setQuantity(new BigDecimal("0.0001"));
    order.setOriginalQuantity(new BigDecimal("0.0001"));
    order.setBaseQuantity(new BigDecimal("0.0001"));
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(new BigDecimal("0.0001"));
    AtomicReference<PositionEntity> savedPosition = new AtomicReference<>();
    AtomicReference<TradeEntity> savedTrade = new AtomicReference<>();
    when(symbolRepository.findBySymbol(SYMBOL)).thenReturn(Optional.of(linearSymbol()));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(tradeRepository.save(any(TradeEntity.class))).thenAnswer(invocation -> {
      TradeEntity trade = invocation.getArgument(0);
      savedTrade.set(trade);
      return trade;
    });
    when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, SYMBOL, PositionMode.ONE_WAY, PositionSide.BOTH))
        .thenReturn(Optional.empty());
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      if (position.getId() == null) {
        position.setId(UUID.randomUUID());
      }
      savedPosition.set(position);
      return position;
    });
    when(accountRepository.save(any(TradingAccountEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service().fillPerpetual(
        order,
        account,
        fill("50005.20002", "0.0001"),
        new BigDecimal("50000"),
        10,
        "Perpetual position margin held");
    return new TinyFillResult(order, savedPosition.get(), savedTrade.get());
  }

  private static void assertTinyFeeFill(TinyFillResult result) {
    assertAll(
        () -> assertThat(result.order().getStatus()).isEqualTo(OrderStatus.FILLED),
        () -> assertThat(result.order().getFee()).isEqualByComparingTo("0.00250026"),
        () -> assertThat(result.trade().getFee()).isEqualByComparingTo("0.00250026"),
        () -> assertThat(result.position().getInitialMargin()).isEqualByComparingTo("0.50005200"));
  }

  private record TinyFillResult(
      OrderEntity order,
      PositionEntity position,
      TradeEntity trade
  ) {
  }

  private OrderFillService service() {
    PositionEngine positionEngine = new PositionEngine(
        positionRepository,
        accountRepository,
        ledgerService,
        new MarginCalculator(),
        new PnLCalculator(),
        new PerpMarginCalculator());
    return new OrderFillService(
        orderRepository,
        tradeRepository,
        positionRepository,
        accountRepository,
        ledgerService,
        symbolRepository,
        null,
        positionEngine,
        null);
  }

  private static TradingAccountEntity account(UUID accountId, String usedMargin) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(UUID.randomUUID());
    account.setBaseCurrency("USDT");
    account.setBalance(new BigDecimal("50000"));
    account.setEquity(new BigDecimal("50000"));
    account.setUsedMargin(new BigDecimal(usedMargin));
    account.setFreeMargin(new BigDecimal("50000").subtract(new BigDecimal(usedMargin)));
    account.setPositionMode(PositionMode.ONE_WAY);
    return account;
  }

  private static OrderEntity order(UUID accountId, String hold) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setUserId(UUID.randomUUID());
    order.setAccountId(accountId);
    order.setSymbol(SYMBOL);
    order.setProductType(ProductType.LINEAR_PERP);
    order.setPositionMode(PositionMode.ONE_WAY);
    order.setPositionSide(PositionSide.BOTH);
    order.setMarginMode(MarginMode.CROSS);
    order.setSide(OrderSide.BUY);
    order.setOrderType(OrderType.LIMIT);
    order.setStatus(OrderStatus.WORKING);
    order.setLots(new BigDecimal("1.0000"));
    order.setQuantity(new BigDecimal("100"));
    order.setOriginalQuantity(new BigDecimal("100"));
    order.setBaseQuantity(new BigDecimal("1.0000"));
    order.setQuantityUnit(QuantityUnit.QUOTE);
    order.setLeverage(10);
    order.setReduceOnly(false);
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(new BigDecimal("1.0000"));
    order.setHoldAmount(new BigDecimal(hold));
    order.setHoldCurrency("USDT");
    return order;
  }

  private static SymbolEntity linearSymbol() {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(UUID.randomUUID());
    symbol.setSymbol(SYMBOL);
    symbol.setProductType(ProductType.LINEAR_PERP);
    symbol.setAssetClass("LINEAR_PERP");
    symbol.setBaseCurrency("BTC");
    symbol.setQuoteCurrency("USDT");
    symbol.setMarginAsset("USDT");
    symbol.setSettlementAsset("USDT");
    // Quantity is already canonical BASE; these values must not be applied a second time.
    symbol.setContractSize(new BigDecimal("100"));
    symbol.setContractMultiplier(new BigDecimal("10"));
    symbol.setMaintenanceMarginRate(new BigDecimal("0.005"));
    return symbol;
  }

  private void stubExistingFill(
      UUID accountId,
      PositionEntity existing,
      AtomicReference<PositionEntity> savedPosition
  ) {
    when(symbolRepository.findBySymbol(SYMBOL)).thenReturn(Optional.of(linearSymbol()));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(tradeRepository.save(any(TradeEntity.class))).thenAnswer(invocation -> {
      TradeEntity trade = invocation.getArgument(0);
      trade.setId(UUID.randomUUID());
      return trade;
    });
    when(positionRepository.findOpenPerpetualSlotForUpdate(
        accountId, SYMBOL, PositionMode.ONE_WAY, PositionSide.BOTH))
        .thenReturn(Optional.of(existing));
    when(positionRepository.save(any(PositionEntity.class))).thenAnswer(invocation -> {
      PositionEntity position = invocation.getArgument(0);
      savedPosition.set(position);
      return position;
    });
    when(accountRepository.save(any(TradingAccountEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  private static PositionEntity openPosition(
      UUID accountId,
      OrderSide side,
      String quantity,
      String entry,
      String initialMargin,
      String marginHeld,
      MarginMode marginMode,
      String floatingPnl
  ) {
    PositionEntity position = new PositionEntity();
    position.setId(UUID.randomUUID());
    position.setAccountId(accountId);
    position.setSymbol(SYMBOL);
    position.setProductType(ProductType.LINEAR_PERP);
    position.setPositionMode(PositionMode.ONE_WAY);
    position.setPositionSide(PositionSide.BOTH);
    position.setMarginMode(marginMode);
    position.setSide(side);
    position.setLots(new BigDecimal(quantity));
    position.setOpenPrice(new BigDecimal(entry));
    position.setCurrentPrice(new BigDecimal("100"));
    position.setMarkPrice(new BigDecimal("100"));
    position.setNotional(new BigDecimal(quantity).multiply(new BigDecimal("100")));
    position.setInitialMargin(new BigDecimal(initialMargin));
    position.setMaintenanceMargin(
        new BigDecimal(quantity).multiply(new BigDecimal("0.50000000")));
    position.setMarginHeld(new BigDecimal(marginHeld));
    position.setFloatingPnl(new BigDecimal(floatingPnl));
    position.setRealizedPnl(BigDecimal.ZERO);
    position.setLeverage(10);
    position.setStatus(com.fxplatform.trading.enums.PositionStatus.OPEN);
    return position;
  }

  private static PositionEntity openLong(UUID accountId) {
    return openPosition(
        accountId,
        OrderSide.BUY,
        "1",
        "200",
        "20.00000000",
        "20.00000000",
        MarginMode.CROSS,
        "-100.00000000");
  }

  private static FullFillResult fill(String price, String quantity) {
    BigDecimal filledPrice = new BigDecimal(price);
    BigDecimal filledQuantity = new BigDecimal(quantity);
    return new FullFillResult(
        filledPrice,
        NOW,
        filledQuantity,
        BigDecimal.ZERO,
        new BigDecimal("0.0005"),
        filledQuantity.multiply(filledPrice).multiply(new BigDecimal("0.0005")),
        "USDT",
        LiquidityRole.TAKER,
        BigDecimal.ZERO,
        MarketSourceMode.PUBLIC_EXTERNAL,
        "binance-usdm",
        "BTCUSDT",
        NOW.minusSeconds(1),
        NOW.plusSeconds(30));
  }

  private static Object capture(Supplier<?> action) {
    try {
      return action.get();
    } catch (Throwable throwable) {
      return throwable;
    }
  }
}
