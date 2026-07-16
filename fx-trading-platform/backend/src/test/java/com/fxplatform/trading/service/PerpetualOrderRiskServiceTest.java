package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.PositionStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PerpetualOrderRiskServiceTest {

  private static final String SYMBOL = "BTCUSDT-PERP";
  private static final Instant NOW = Instant.parse("2026-07-12T08:00:00Z");

  private final PerpetualOrderRiskService service = new PerpetualOrderRiskService(
      new FullFillCoordinator(request -> null, Clock.fixed(NOW, ZoneOffset.UTC)));

  @Test
  void oneWayClassifiesOpeningClosingAndNonReduceReversal() {
    PerpetualOrderRiskService.OrderRisk opening = evaluate(
        PositionMode.ONE_WAY,
        setting(10, MarginMode.CROSS),
        List.of(),
        OrderSide.BUY,
        PositionSide.BOTH,
        false,
        OrderType.MARKET,
        "2",
        null);
    assertThat(opening.closingBase()).isEqualByComparingTo("0");
    assertThat(opening.openingBase()).isEqualByComparingTo("2");

    PositionEntity longPosition = position(PositionMode.ONE_WAY, PositionSide.BOTH, OrderSide.BUY, "1");
    PerpetualOrderRiskService.OrderRisk partialClose = evaluate(
        PositionMode.ONE_WAY,
        setting(10, MarginMode.CROSS),
        List.of(longPosition),
        OrderSide.SELL,
        PositionSide.BOTH,
        false,
        OrderType.MARKET,
        "0.4",
        null);
    assertThat(partialClose.closingBase()).isEqualByComparingTo("0.4");
    assertThat(partialClose.openingBase()).isEqualByComparingTo("0");

    PerpetualOrderRiskService.OrderRisk reversal = evaluate(
        PositionMode.ONE_WAY,
        setting(10, MarginMode.CROSS),
        List.of(longPosition),
        OrderSide.SELL,
        PositionSide.BOTH,
        false,
        OrderType.MARKET,
        "1.5",
        null);
    assertThat(reversal.closingBase()).isEqualByComparingTo("1");
    assertThat(reversal.openingBase()).isEqualByComparingTo("0.5");
  }

  @Test
  void oneWayReduceOnlyRejectsOpeningAndOverClose() {
    PositionEntity longPosition = position(PositionMode.ONE_WAY, PositionSide.BOTH, OrderSide.BUY, "1");

    assertCode("REDUCE_ONLY_WOULD_INCREASE", () -> evaluate(
        PositionMode.ONE_WAY,
        setting(10, MarginMode.CROSS),
        List.of(longPosition),
        OrderSide.BUY,
        PositionSide.BOTH,
        true,
        OrderType.MARKET,
        "0.1",
        null));
    assertCode("REDUCE_ONLY_EXCEEDS_POSITION", () -> evaluate(
        PositionMode.ONE_WAY,
        setting(10, MarginMode.CROSS),
        List.of(longPosition),
        OrderSide.SELL,
        PositionSide.BOTH,
        true,
        OrderType.MARKET,
        "1.1",
        null));
  }

  @Test
  void hedgeClassifiesLongAndShortSlotsAndAlwaysRejectsOverClose() {
    PositionEntity longPosition = position(PositionMode.HEDGE, PositionSide.LONG, OrderSide.BUY, "1");
    PositionEntity shortPosition = position(PositionMode.HEDGE, PositionSide.SHORT, OrderSide.SELL, "2");
    List<PositionEntity> positions = List.of(longPosition, shortPosition);

    PerpetualOrderRiskService.OrderRisk openLong = evaluate(
        PositionMode.HEDGE, setting(20, MarginMode.ISOLATED), positions,
        OrderSide.BUY, PositionSide.LONG, false, OrderType.LIMIT, "0.5", "95");
    assertThat(openLong.closingBase()).isEqualByComparingTo("0");
    assertThat(openLong.openingBase()).isEqualByComparingTo("0.5");

    PerpetualOrderRiskService.OrderRisk closeLong = evaluate(
        PositionMode.HEDGE, setting(20, MarginMode.ISOLATED), positions,
        OrderSide.SELL, PositionSide.LONG, true, OrderType.MARKET, "0.4", null);
    assertThat(closeLong.closingBase()).isEqualByComparingTo("0.4");
    assertThat(closeLong.openingBase()).isEqualByComparingTo("0");

    PerpetualOrderRiskService.OrderRisk openShort = evaluate(
        PositionMode.HEDGE, setting(20, MarginMode.ISOLATED), positions,
        OrderSide.SELL, PositionSide.SHORT, false, OrderType.MARKET, "0.3", null);
    assertThat(openShort.closingBase()).isEqualByComparingTo("0");
    assertThat(openShort.openingBase()).isEqualByComparingTo("0.3");

    PerpetualOrderRiskService.OrderRisk closeShort = evaluate(
        PositionMode.HEDGE, setting(20, MarginMode.ISOLATED), positions,
        OrderSide.BUY, PositionSide.SHORT, true, OrderType.MARKET, "0.7", null);
    assertThat(closeShort.closingBase()).isEqualByComparingTo("0.7");
    assertThat(closeShort.openingBase()).isEqualByComparingTo("0");

    assertCode("REDUCE_ONLY_EXCEEDS_POSITION", () -> evaluate(
        PositionMode.HEDGE, setting(20, MarginMode.ISOLATED), positions,
        OrderSide.SELL, PositionSide.LONG, false, OrderType.MARKET, "1.1", null));
    assertCode("REDUCE_ONLY_EXCEEDS_POSITION", () -> evaluate(
        PositionMode.HEDGE, setting(20, MarginMode.ISOLATED), positions,
        OrderSide.BUY, PositionSide.SHORT, false, OrderType.MARKET, "2.1", null));
  }

  @Test
  void marketAndStopUseCanonicalBidAskSlippageWhileLimitUsesItsOwnPrice() {
    PerpetualOrderRiskService.OrderRisk marketBuy = evaluate(
        PositionMode.ONE_WAY, setting(10, MarginMode.CROSS), List.of(),
        OrderSide.BUY, PositionSide.BOTH, false, OrderType.MARKET, "1", null);
    PerpetualOrderRiskService.OrderRisk marketSell = evaluate(
        PositionMode.ONE_WAY, setting(10, MarginMode.CROSS), List.of(),
        OrderSide.SELL, PositionSide.BOTH, false, OrderType.MARKET, "1", null);
    PerpetualOrderRiskService.OrderRisk stopBuy = evaluate(
        PositionMode.ONE_WAY, setting(10, MarginMode.CROSS), List.of(),
        OrderSide.BUY, PositionSide.BOTH, false, OrderType.STOP_MARKET, "1", null);
    PerpetualOrderRiskService.OrderRisk limitBuy = evaluate(
        PositionMode.ONE_WAY, setting(10, MarginMode.CROSS), List.of(),
        OrderSide.BUY, PositionSide.BOTH, false, OrderType.LIMIT, "1", "95");

    assertThat(marketBuy.worstPrice()).isEqualByComparingTo("101.01010000");
    assertThat(marketSell.worstPrice()).isEqualByComparingTo("98.99010000");
    assertThat(stopBuy.worstPrice()).isEqualByComparingTo("101.01010000");
    assertThat(limitBuy.worstPrice()).isEqualByComparingTo("95.00000000");
  }

  @Test
  void openingHoldRoundsNotionalBeforeMarginExactlyLikeTheFillKernel() {
    PerpetualOrderRiskService.OrderRisk risk = evaluateWithSnapshot(
        PositionMode.ONE_WAY,
        setting(2, MarginMode.CROSS),
        List.of(),
        OrderSide.BUY,
        PositionSide.BOTH,
        false,
        OrderType.MARKET,
        "0.0005",
        null,
        snapshot("50000", "50000.1", "50000"));

    assertThat(risk.worstPrice()).isEqualByComparingTo("50005.10001000");
    assertThat(risk.openingInitialMargin()).isEqualByComparingTo("12.50127501");
    assertThat(risk.feeBuffer()).isEqualByComparingTo("0.01250128");
    assertThat(risk.holdAmount()).isEqualByComparingTo("12.51377629");
  }

  @Test
  void marketableSellLimitUsesProjectedFillForOpeningRiskAndLimitForCloseAdverse() {
    PerpetualOrderRiskService.OrderRisk oneWaySell = evaluate(
        PositionMode.ONE_WAY, setting(10, MarginMode.CROSS), List.of(),
        OrderSide.SELL, PositionSide.BOTH, false, OrderType.LIMIT, "1", "90");
    PerpetualOrderRiskService.OrderRisk hedgeShort = evaluate(
        PositionMode.HEDGE, setting(10, MarginMode.CROSS), List.of(),
        OrderSide.SELL, PositionSide.SHORT, false, OrderType.LIMIT, "1", "90");

    assertThat(oneWaySell.worstPrice()).isEqualByComparingTo("99.00000000");
    assertThat(oneWaySell.closeWorstPrice()).isEqualByComparingTo("90.00000000");
    assertThat(oneWaySell.openingInitialMargin()).isEqualByComparingTo("9.90000000");
    assertThat(oneWaySell.feeBuffer()).isEqualByComparingTo("0.04950000");
    assertThat(hedgeShort.holdAmount()).isEqualByComparingTo("9.94950000");
  }

  @Test
  void mixedOneWayReversalHoldsOpeningMarginFullFeeAndClosingAdverseLoss() {
    PositionEntity longPosition = position(PositionMode.ONE_WAY, PositionSide.BOTH, OrderSide.BUY, "1");

    PerpetualOrderRiskService.OrderRisk risk = evaluate(
        PositionMode.ONE_WAY,
        setting(10, MarginMode.CROSS),
        List.of(longPosition),
        OrderSide.SELL,
        PositionSide.BOTH,
        false,
        OrderType.MARKET,
        "2",
        null);

    assertThat(risk.closingBase()).isEqualByComparingTo("1");
    assertThat(risk.openingBase()).isEqualByComparingTo("1");
    assertThat(risk.openingInitialMargin()).isEqualByComparingTo("9.89901000");
    assertThat(risk.feeBuffer()).isEqualByComparingTo("0.09899010");
    assertThat(risk.adverseCloseLoss()).isEqualByComparingTo("1.00990000");
    assertThat(risk.holdAmount()).isEqualByComparingTo("11.00790010");
    assertThat(risk.holdCurrency()).isEqualTo("USDT");
  }

  @Test
  void pureReduceOnlyHoldsFeeAndDirectionalAdverseLossWithoutOpeningMargin() {
    PositionEntity longPosition = position(PositionMode.ONE_WAY, PositionSide.BOTH, OrderSide.BUY, "1");
    PerpetualOrderRiskService.OrderRisk closeLong = evaluate(
        PositionMode.ONE_WAY, setting(10, MarginMode.CROSS), List.of(longPosition),
        OrderSide.SELL, PositionSide.BOTH, true, OrderType.MARKET, "1", null);

    assertThat(closeLong.openingBase()).isEqualByComparingTo("0");
    assertThat(closeLong.openingInitialMargin()).isEqualByComparingTo("0");
    assertThat(closeLong.feeBuffer()).isEqualByComparingTo("0.04949505");
    assertThat(closeLong.adverseCloseLoss()).isEqualByComparingTo("1.00990000");
    assertThat(closeLong.holdAmount()).isEqualByComparingTo("1.05939505");

    PositionEntity shortPosition = position(PositionMode.HEDGE, PositionSide.SHORT, OrderSide.SELL, "1");
    PerpetualOrderRiskService.OrderRisk closeShort = evaluate(
        PositionMode.HEDGE, setting(10, MarginMode.ISOLATED), List.of(shortPosition),
        OrderSide.BUY, PositionSide.SHORT, true, OrderType.MARKET, "1", null);

    assertThat(closeShort.openingInitialMargin()).isEqualByComparingTo("0");
    assertThat(closeShort.feeBuffer()).isEqualByComparingTo("0.05050505");
    assertThat(closeShort.adverseCloseLoss()).isEqualByComparingTo("1.01010000");
    assertThat(closeShort.holdAmount()).isEqualByComparingTo("1.06060505");
  }

  @Test
  void returnsOnlyTheLockedAccountAndSymbolSettingAuthority() {
    PerpetualOrderRiskService.OrderRisk risk = evaluate(
        PositionMode.HEDGE,
        setting(7, MarginMode.ISOLATED),
        List.of(),
        OrderSide.BUY,
        PositionSide.LONG,
        false,
        OrderType.LIMIT,
        "1",
        "98");

    assertThat(risk.positionMode()).isEqualTo(PositionMode.HEDGE);
    assertThat(risk.positionSide()).isEqualTo(PositionSide.LONG);
    assertThat(risk.marginMode()).isEqualTo(MarginMode.ISOLATED);
    assertThat(risk.leverage()).isEqualTo(7);
    assertThat(risk.openingInitialMargin()).isEqualByComparingTo("14.00000000");
  }

  @Test
  void failsClosedForInvalidModeSlotDirectionSettingQuantityAndAuthorityPrices() {
    AccountSymbolSettingEntity cross10 = setting(10, MarginMode.CROSS);
    PositionEntity wrongLongDirection = position(
        PositionMode.HEDGE, PositionSide.LONG, OrderSide.SELL, "1");

    assertCode("INVALID_POSITION_SIDE", () -> evaluate(
        PositionMode.ONE_WAY, cross10, List.of(),
        OrderSide.BUY, PositionSide.LONG, false, OrderType.MARKET, "1", null));
    assertCode("INVALID_POSITION_SIDE", () -> evaluate(
        PositionMode.HEDGE, cross10, List.of(),
        OrderSide.BUY, PositionSide.BOTH, false, OrderType.MARKET, "1", null));
    assertCode("INVALID_POSITION_SIDE", () -> evaluate(
        PositionMode.HEDGE, cross10, List.of(wrongLongDirection),
        OrderSide.SELL, PositionSide.LONG, true, OrderType.MARKET, "0.1", null));
    assertCode("REDUCE_ONLY_EXCEEDS_POSITION", () -> evaluate(
        PositionMode.HEDGE, cross10, List.of(),
        OrderSide.SELL, PositionSide.LONG, true, OrderType.MARKET, "0.1", null));
    assertCode("BAD_QUANTITY", () -> evaluate(
        PositionMode.ONE_WAY, cross10, List.of(),
        OrderSide.BUY, PositionSide.BOTH, false, OrderType.MARKET, "0", null));
    assertCode("ORDER_PRICE_REQUIRED", () -> evaluate(
        PositionMode.ONE_WAY, cross10, List.of(),
        OrderSide.BUY, PositionSide.BOTH, false, OrderType.LIMIT, "1", "0"));

    AccountSymbolSettingEntity cash = setting(10, MarginMode.CASH);
    assertCode("INVALID_MARGIN_MODE", () -> evaluate(
        PositionMode.ONE_WAY, cash, List.of(),
        OrderSide.BUY, PositionSide.BOTH, false, OrderType.MARKET, "1", null));
    assertCode("INVALID_INSTRUMENT_RULES", () -> service.evaluate(
        PositionMode.ONE_WAY,
        null,
        List.of(),
        OrderSide.BUY,
        PositionSide.BOTH,
        false,
        OrderType.MARKET,
        decimal("1"),
        null,
        snapshot(),
        decimal("0.005")));
    assertCode("INVALID_INSTRUMENT_RULES", () -> service.evaluate(
        PositionMode.ONE_WAY,
        cross10,
        List.of(),
        OrderSide.BUY,
        PositionSide.BOTH,
        false,
        OrderType.MARKET,
        decimal("1"),
        null,
        snapshot(),
        BigDecimal.ZERO));

    ExecutableMarketSnapshot missingMark = new ExecutableMarketSnapshot(
        SYMBOL,
        ProductType.LINEAR_PERP,
        "binance-usdm",
        "BTCUSDT",
        MarketSourceMode.PUBLIC_EXTERNAL,
        decimal("99"),
        decimal("101"),
        decimal("100"),
        null,
        decimal("100"),
        NOW.minusSeconds(1),
        NOW.plusSeconds(30));
    assertCode("MARKET_BUNDLE_INCOMPLETE", () -> service.evaluate(
        PositionMode.ONE_WAY,
        cross10,
        List.of(),
        OrderSide.BUY,
        PositionSide.BOTH,
        false,
        OrderType.MARKET,
        decimal("1"),
        null,
        missingMark,
        decimal("0.005")));
  }

  @Test
  void isolatedCloseCannotUseCrossFreeMarginToHideASlotDeficit() {
    PositionEntity deepLoss = position(
        PositionMode.ONE_WAY, PositionSide.BOTH, OrderSide.BUY, "1");
    deepLoss.setOpenPrice(decimal("100"));
    deepLoss.setCurrentPrice(decimal("50"));
    deepLoss.setMarkPrice(decimal("50"));
    deepLoss.setMarginHeld(decimal("10"));
    deepLoss.setFundingPnl(BigDecimal.ZERO);
    deepLoss.setNotional(decimal("50"));
    deepLoss.setMaintenanceMargin(decimal("0.25"));
    ExecutableMarketSnapshot deepSnapshot = snapshot("49", "51", "50");

    assertCode("MARGIN_REDUCTION_UNSAFE", () -> evaluateWithSnapshot(
        PositionMode.ONE_WAY,
        setting(10, MarginMode.ISOLATED),
        List.of(deepLoss),
        OrderSide.SELL,
        PositionSide.BOTH,
        true,
        OrderType.LIMIT,
        "1",
        "49",
        deepSnapshot));

    PerpetualOrderRiskService.OrderRisk crossClose = evaluateWithSnapshot(
        PositionMode.ONE_WAY,
        setting(10, MarginMode.CROSS),
        List.of(deepLoss),
        OrderSide.SELL,
        PositionSide.BOTH,
        true,
        OrderType.LIMIT,
        "1",
        "49",
        deepSnapshot);
    assertThat(crossClose.holdAmount()).isEqualByComparingTo("1.02450000");
  }

  @Test
  void liquidationCloseKeepsCanonicalPricingButDoesNotSolvencyGateOrReserveAHold() {
    PositionEntity deepLoss = position(
        PositionMode.ONE_WAY, PositionSide.BOTH, OrderSide.BUY, "1");
    deepLoss.setOpenPrice(decimal("100"));
    deepLoss.setMarginHeld(decimal("1"));
    deepLoss.setFundingPnl(decimal("-2"));
    ExecutableMarketSnapshot deepSnapshot = snapshot("49", "51", "50");

    PerpetualOrderRiskService.OrderRisk risk = service.evaluateLiquidationClose(
        PositionMode.ONE_WAY,
        setting(10, MarginMode.ISOLATED),
        List.of(deepLoss),
        OrderSide.SELL,
        PositionSide.BOTH,
        decimal("1"),
        deepSnapshot,
        decimal("0.005"));

    assertThat(risk.closingBase()).isEqualByComparingTo("1");
    assertThat(risk.openingBase()).isZero();
    assertThat(risk.holdAmount()).isEqualByComparingTo("0.00000000");
    assertThat(risk.isolatedHoldCapacity()).isEqualByComparingTo("0.00000000");
  }

  @Test
  void isolatedPartialCloseChecksPostFillSlotEquityAgainstRemainingThreshold() {
    PositionEntity safe = position(
        PositionMode.ONE_WAY, PositionSide.BOTH, OrderSide.BUY, "2");
    safe.setOpenPrice(decimal("100"));
    safe.setMarginHeld(decimal("30"));
    safe.setFundingPnl(BigDecimal.ZERO);
    safe.setNotional(decimal("200"));
    safe.setMaintenanceMargin(decimal("1"));

    PerpetualOrderRiskService.OrderRisk risk = evaluate(
        PositionMode.ONE_WAY,
        setting(10, MarginMode.ISOLATED),
        List.of(safe),
        OrderSide.SELL,
        PositionSide.BOTH,
        true,
        OrderType.LIMIT,
        "1",
        "99");
    assertThat(risk.closingBase()).isEqualByComparingTo("1");

    PositionEntity unsafe = position(
        PositionMode.ONE_WAY, PositionSide.BOTH, OrderSide.BUY, "2");
    unsafe.setOpenPrice(decimal("100"));
    unsafe.setMarginHeld(decimal("1"));
    unsafe.setFundingPnl(BigDecimal.ZERO);
    unsafe.setNotional(decimal("200"));
    unsafe.setMaintenanceMargin(decimal("1"));
    assertCode("MARGIN_REDUCTION_UNSAFE", () -> evaluate(
        PositionMode.ONE_WAY,
        setting(10, MarginMode.ISOLATED),
        List.of(unsafe),
        OrderSide.SELL,
        PositionSide.BOTH,
        true,
        OrderType.LIMIT,
        "1",
        "99"));

    PositionEntity masking = position(
        PositionMode.ONE_WAY, PositionSide.BOTH, OrderSide.BUY, "10");
    masking.setOpenPrice(decimal("100"));
    masking.setMarginHeld(decimal("100"));
    masking.setFundingPnl(BigDecimal.ZERO);
    assertCode("MARGIN_REDUCTION_UNSAFE", () -> evaluateWithSnapshot(
        PositionMode.ONE_WAY,
        setting(10, MarginMode.ISOLATED),
        List.of(masking),
        OrderSide.SELL,
        PositionSide.BOTH,
        true,
        OrderType.LIMIT,
        "9",
        "80",
        snapshot("80", "201", "200")));
  }

  @Test
  void isolatedFullCloseIncludesFundingAndAllowsZeroMarginWithProfitableUpl() {
    PositionEntity funded = position(
        PositionMode.ONE_WAY, PositionSide.BOTH, OrderSide.BUY, "1");
    funded.setMarginHeld(decimal("1"));
    funded.setFundingPnl(decimal("0.10"));
    PerpetualOrderRiskService.OrderRisk safe = evaluate(
        PositionMode.ONE_WAY,
        setting(10, MarginMode.ISOLATED),
        List.of(funded),
        OrderSide.SELL,
        PositionSide.BOTH,
        true,
        OrderType.LIMIT,
        "1",
        "99");
    assertThat(safe.closingBase()).isEqualByComparingTo("1");

    funded.setFundingPnl(decimal("-0.10"));
    assertCode("MARGIN_REDUCTION_UNSAFE", () -> evaluate(
        PositionMode.ONE_WAY,
        setting(10, MarginMode.ISOLATED),
        List.of(funded),
        OrderSide.SELL,
        PositionSide.BOTH,
        true,
        OrderType.LIMIT,
        "1",
        "99"));

    PositionEntity profitable = position(
        PositionMode.ONE_WAY, PositionSide.BOTH, OrderSide.BUY, "1");
    profitable.setOpenPrice(decimal("100"));
    profitable.setMarginHeld(BigDecimal.ZERO);
    profitable.setFundingPnl(BigDecimal.ZERO);
    PerpetualOrderRiskService.OrderRisk zeroMarginClose = evaluateWithSnapshot(
        PositionMode.ONE_WAY,
        setting(10, MarginMode.ISOLATED),
        List.of(profitable),
        OrderSide.SELL,
        PositionSide.BOTH,
        true,
        OrderType.LIMIT,
        "1",
        "199",
        snapshot("199", "201", "200"));
    assertThat(zeroMarginClose.isolatedHoldCapacity()).isGreaterThan(decimal("98"));
  }

  private PerpetualOrderRiskService.OrderRisk evaluate(
      PositionMode positionMode,
      AccountSymbolSettingEntity setting,
      List<PositionEntity> positions,
      OrderSide side,
      PositionSide positionSide,
      boolean reduceOnly,
      OrderType orderType,
      String quantity,
      String limitPrice
  ) {
    return evaluateWithSnapshot(
        positionMode,
        setting,
        positions,
        side,
        positionSide,
        reduceOnly,
        orderType,
        quantity,
        limitPrice,
        snapshot());
  }

  private PerpetualOrderRiskService.OrderRisk evaluateWithSnapshot(
      PositionMode positionMode,
      AccountSymbolSettingEntity setting,
      List<PositionEntity> positions,
      OrderSide side,
      PositionSide positionSide,
      boolean reduceOnly,
      OrderType orderType,
      String quantity,
      String limitPrice,
      ExecutableMarketSnapshot snapshot
  ) {
    return service.evaluate(
        positionMode,
        setting,
        positions,
        side,
        positionSide,
        reduceOnly,
        orderType,
        decimal(quantity),
        decimal(limitPrice),
        snapshot,
        decimal("0.005"));
  }

  private static AccountSymbolSettingEntity setting(int leverage, MarginMode marginMode) {
    AccountSymbolSettingEntity setting = new AccountSymbolSettingEntity();
    setting.setAccountId(UUID.randomUUID());
    setting.setSymbol(SYMBOL);
    setting.setLeverage(leverage);
    setting.setMarginMode(marginMode);
    return setting;
  }

  private static PositionEntity position(
      PositionMode positionMode,
      PositionSide positionSide,
      OrderSide side,
      String quantity
  ) {
    PositionEntity position = new PositionEntity();
    position.setId(UUID.randomUUID());
    position.setAccountId(UUID.randomUUID());
    position.setSymbol(SYMBOL);
    position.setProductType(ProductType.LINEAR_PERP);
    position.setPositionMode(positionMode);
    position.setPositionSide(positionSide);
    position.setSide(side);
    position.setLots(decimal(quantity));
    position.setOpenPrice(decimal("100"));
    position.setCurrentPrice(decimal("100"));
    position.setMarkPrice(decimal("100"));
    position.setNotional(decimal(quantity).multiply(decimal("100")));
    position.setInitialMargin(decimal(quantity).multiply(decimal("10")));
    position.setMaintenanceMargin(decimal(quantity).multiply(decimal("0.5")));
    position.setMarginHeld(decimal(quantity).multiply(decimal("1000")));
    position.setFundingPnl(BigDecimal.ZERO);
    position.setStatus(PositionStatus.OPEN);
    return position;
  }

  private static ExecutableMarketSnapshot snapshot() {
    return snapshot("99", "101", "100");
  }

  private static ExecutableMarketSnapshot snapshot(
      String bid,
      String ask,
      String mark
  ) {
    return new ExecutableMarketSnapshot(
        SYMBOL,
        ProductType.LINEAR_PERP,
        "binance-usdm",
        "BTCUSDT",
        MarketSourceMode.PUBLIC_EXTERNAL,
        decimal(bid),
        decimal(ask),
        decimal(mark),
        decimal(mark),
        decimal(mark),
        NOW.minusSeconds(1),
        NOW.plusSeconds(30));
  }

  private static BigDecimal decimal(String value) {
    return value == null ? null : new BigDecimal(value);
  }

  private static void assertCode(
      String code,
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action
  ) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(code));
  }
}
