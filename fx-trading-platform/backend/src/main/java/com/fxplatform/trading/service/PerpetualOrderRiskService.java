package com.fxplatform.trading.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.ExecutableMarketSnapshots;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.execution.FullFillExecutionPath;
import com.fxplatform.execution.FullFillPricingProjection;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.risk.service.PerpMarginCalculator;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.PositionStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Projects one canonical BASE Linear Perpetual order against locked account and symbol state. */
@Service
public class PerpetualOrderRiskService {

  private static final int MONEY_SCALE = 8;

  private final FullFillCoordinator fullFillCoordinator;
  private final PerpMarginCalculator perpMarginCalculator;

  @Autowired
  public PerpetualOrderRiskService(
      FullFillCoordinator fullFillCoordinator,
      PerpMarginCalculator perpMarginCalculator
  ) {
    this.fullFillCoordinator = fullFillCoordinator;
    this.perpMarginCalculator = perpMarginCalculator;
  }

  /** Compatibility constructor retained for focused unit fixtures. */
  public PerpetualOrderRiskService(FullFillCoordinator fullFillCoordinator) {
    this(fullFillCoordinator, new PerpMarginCalculator());
  }

  public OrderRisk evaluate(
      PositionMode lockedPositionMode,
      AccountSymbolSettingEntity lockedSetting,
      List<PositionEntity> lockedPositions,
      OrderSide side,
      PositionSide positionSide,
      boolean reduceOnly,
      OrderType orderType,
      BigDecimal canonicalBaseQuantity,
      BigDecimal limitPrice,
      ExecutableMarketSnapshot snapshot,
      BigDecimal maintenanceMarginRate
  ) {
    return evaluate(
        lockedPositionMode,
        lockedSetting,
        lockedPositions,
        side,
        positionSide,
        reduceOnly,
        orderType,
        canonicalBaseQuantity,
        limitPrice,
        snapshot,
        maintenanceMarginRate,
        false,
        null);
  }

  /** Projects one DEPTH order from explicit conservative prices without SIMPLE price projection. */
  public OrderRisk evaluateDepth(
      PositionMode lockedPositionMode,
      AccountSymbolSettingEntity lockedSetting,
      List<PositionEntity> lockedPositions,
      OrderSide side,
      PositionSide positionSide,
      boolean reduceOnly,
      OrderType orderType,
      BigDecimal canonicalBaseQuantity,
      BigDecimal limitPrice,
      ExecutableMarketSnapshot snapshot,
      BigDecimal maintenanceMarginRate,
      PerpetualRiskPricing pricing
  ) {
    return evaluate(
        lockedPositionMode,
        lockedSetting,
        lockedPositions,
        side,
        positionSide,
        reduceOnly,
        orderType,
        canonicalBaseQuantity,
        limitPrice,
        snapshot,
        maintenanceMarginRate,
        false,
        requireDepthPricing(pricing));
  }

  /** Prices and classifies a whole liquidation close without solvency-gating its execution. */
  public OrderRisk evaluateLiquidationClose(
      PositionMode lockedPositionMode,
      AccountSymbolSettingEntity lockedSetting,
      List<PositionEntity> lockedPositions,
      OrderSide side,
      PositionSide positionSide,
      BigDecimal canonicalBaseQuantity,
      ExecutableMarketSnapshot snapshot,
      BigDecimal maintenanceMarginRate
  ) {
    return evaluate(
        lockedPositionMode,
        lockedSetting,
        lockedPositions,
        side,
        positionSide,
        true,
        OrderType.MARKET,
        canonicalBaseQuantity,
        null,
        snapshot,
        maintenanceMarginRate,
        true,
        null);
  }

  private OrderRisk evaluate(
      PositionMode lockedPositionMode,
      AccountSymbolSettingEntity lockedSetting,
      List<PositionEntity> lockedPositions,
      OrderSide side,
      PositionSide positionSide,
      boolean reduceOnly,
      OrderType orderType,
      BigDecimal canonicalBaseQuantity,
      BigDecimal limitPrice,
      ExecutableMarketSnapshot snapshot,
      BigDecimal maintenanceMarginRate,
      boolean liquidationClose,
      PerpetualRiskPricing depthPricing
  ) {
    LockedAuthority authority = requireAuthority(lockedPositionMode, lockedSetting);
    requirePositive(
        maintenanceMarginRate,
        "INVALID_INSTRUMENT_RULES",
        "Configured maintenance margin rate is required");
    if (maintenanceMarginRate.compareTo(BigDecimal.ONE) >= 0
        || !isExactlyRepresentableAsNumeric(maintenanceMarginRate, 18, 8)) {
      throw new BusinessException(
          "INVALID_INSTRUMENT_RULES",
          "Configured maintenance margin rate must fit NUMERIC(18,8) below one");
    }
    requirePositive(canonicalBaseQuantity, "BAD_QUANTITY", "Canonical BASE quantity must be positive");
    if (!isExactlyRepresentableAsNumeric(canonicalBaseQuantity, 12, 4)) {
      throw invalidInstrumentRules(
          "Canonical BASE quantity exceeds NUMERIC(12,4)");
    }
    if (side == null) {
      throw new BusinessException("INVALID_ORDER_SIDE", "Perpetual order side is required");
    }
    validateSlot(lockedPositionMode, positionSide);
    ExecutableMarketSnapshots.requireComplete(
        lockedSetting.getSymbol(), ProductType.LINEAR_PERP, snapshot);
    validatePerpetualSnapshotNumeric(snapshot);
    if (depthPricing != null) {
      fullFillCoordinator.requireFresh(snapshot);
    }

    FullFillExecutionPath executionPath = executionPath(orderType);
    boolean limitOrder = orderType == OrderType.LIMIT || orderType == OrderType.STOP_LIMIT;
    if (limitOrder) {
      requirePositive(limitPrice, ErrorCode.ORDER_PRICE_REQUIRED, "Perpetual LIMIT price is required");
      requireDepthPrice(limitPrice);
    }
    FullFillPricingProjection simplePricing = depthPricing == null
        ? fullFillCoordinator.project(
            ProductType.LINEAR_PERP,
            side,
            executionPath,
            limitPrice,
            snapshot)
        : null;
    BigDecimal marginAndFeePrice = depthPricing == null
        ? money(limitOrder
            ? limitPrice.max(simplePricing.filledPrice())
            : simplePricing.filledPrice())
        : depthPricing.marginAndFeePrice();
    BigDecimal closeWorstPrice = depthPricing == null
        ? money(limitOrder && side == OrderSide.SELL
            ? limitPrice.min(simplePricing.filledPrice())
            : marginAndFeePrice)
        : depthPricing.adverseClosePrice();
    BigDecimal riskFeeRate = depthPricing == null
        ? simplePricing.worstFeeRate()
        : depthPricing.feeRate();
    if (!isExactlyRepresentableAsNumeric(riskFeeRate, 18, 8)) {
      throw invalidInstrumentRules(
          "Perpetual risk fee rate exceeds NUMERIC(18,8)");
    }
    requirePersistentNotional(canonicalBaseQuantity, marginAndFeePrice);
    requirePersistentNotional(canonicalBaseQuantity, snapshot.mark());

    List<PositionEntity> openPositions = requirePositions(
        lockedPositions,
        lockedPositionMode,
        lockedSetting.getAccountId(),
        lockedSetting.getSymbol());
    Exposure exposure = classify(
        lockedPositionMode,
        positionSide,
        side,
        reduceOnly,
        canonicalBaseQuantity,
        openPositions);
    BigDecimal openingInitialMargin = exposure.openingBase().compareTo(BigDecimal.ZERO) == 0
        ? money(BigDecimal.ZERO)
        : perpMarginCalculator.calculateLinear(
            exposure.openingBase(),
            marginAndFeePrice,
            marginAndFeePrice,
            authority.leverage(),
            maintenanceMarginRate).initialMargin();
    BigDecimal feeBuffer = money(canonicalBaseQuantity
        .multiply(marginAndFeePrice)
        .multiply(riskFeeRate));
    BigDecimal adverseCloseLoss = adverseCloseLoss(
        side,
        exposure.closingBase(),
        snapshot.mark(),
        closeWorstPrice);
    if (!liquidationClose) {
      validateIsolatedCloseCapacity(
          authority.marginMode(),
          positionSide,
          exposure,
          openPositions,
          closeWorstPrice,
          snapshot.mark(),
          riskFeeRate,
          maintenanceMarginRate,
          marginAndFeePrice);
    }
    BigDecimal holdAmount = liquidationClose
        ? money(BigDecimal.ZERO)
        : money(openingInitialMargin.add(feeBuffer).add(adverseCloseLoss));
    holdAmount = requireOrderHold(holdAmount);
    BigDecimal isolatedHoldCapacity = liquidationClose
        ? money(BigDecimal.ZERO)
        : isolatedHoldCapacity(
            authority.marginMode(),
            positionSide,
            exposure,
            openPositions,
            snapshot.mark(),
            maintenanceMarginRate,
            riskFeeRate);

    RiskPayload payload = new RiskPayload(
        lockedPositionMode,
        positionSide,
        authority.marginMode(),
        authority.leverage(),
        exposure.closingBase(),
        exposure.openingBase(),
        marginAndFeePrice,
        closeWorstPrice,
        openingInitialMargin,
        feeBuffer,
        adverseCloseLoss,
        holdAmount,
        isolatedHoldCapacity,
        "USDT");
    RiskBinding binding = depthPricing == null
        ? null
        : new RiskBinding(
            lockedSetting.getAccountId(),
            lockedSetting.getSymbol(),
            side,
            canonicalBaseQuantity,
            lockedPositionMode,
            positionSide,
            authority.marginMode(),
            authority.leverage(),
            reduceOnly,
            orderType,
            limitPrice,
            snapshot,
            maintenanceMarginRate,
            depthPricing,
            payload);
    return payload.toOrderRisk(binding);
  }

  private PerpetualRiskPricing requireDepthPricing(PerpetualRiskPricing pricing) {
    if (pricing == null
        || pricing.marginAndFeePrice() == null
        || pricing.marginAndFeePrice().compareTo(BigDecimal.ZERO) <= 0
        || pricing.adverseClosePrice() == null
        || pricing.adverseClosePrice().compareTo(BigDecimal.ZERO) <= 0
        || pricing.feeRate() == null
        || pricing.feeRate().compareTo(BigDecimal.ZERO) < 0
        || pricing.feeRate().compareTo(BigDecimal.ONE) >= 0) {
      throw new BusinessException(
          "INVALID_INSTRUMENT_RULES",
          "Explicit Perpetual DEPTH prices and fee rate are invalid");
    }
    if (!isExactlyRepresentableAsNumeric(pricing.feeRate(), 18, 8)) {
      throw invalidInstrumentRules(
          "Explicit Perpetual DEPTH fee rate exceeds NUMERIC(18,8)");
    }
    requireDepthPrice(pricing.marginAndFeePrice());
    requireDepthPrice(pricing.adverseClosePrice());
    return pricing;
  }

  private BigDecimal requireDepthPrice(BigDecimal price) {
    if (!isExactlyRepresentableAsNumeric(price, 24, 10)) {
      throw invalidInstrumentRules(
          "Perpetual price exceeds NUMERIC(24,10)");
    }
    return price;
  }

  private static void validatePerpetualSnapshotNumeric(ExecutableMarketSnapshot snapshot) {
    if (!isExactlyRepresentableAsNumeric(snapshot.bid(), 24, 10)
        || !isExactlyRepresentableAsNumeric(snapshot.ask(), 24, 10)
        || !isExactlyRepresentableAsNumeric(snapshot.last(), 24, 10)
        || !isExactlyRepresentableAsNumeric(snapshot.mark(), 24, 10)
        || !isExactlyRepresentableAsNumeric(snapshot.index(), 24, 10)) {
      throw invalidInstrumentRules(
          "Perpetual market snapshot prices exceed NUMERIC(24,10)");
    }
  }

  private static void requirePersistentNotional(BigDecimal quantity, BigDecimal price) {
    BigDecimal notional = money(quantity.multiply(price));
    if (!isExactlyRepresentableAsNumeric(notional, 24, MONEY_SCALE)) {
      throw invalidInstrumentRules(
          "Perpetual position notional exceeds NUMERIC(24,8)");
    }
  }

  private LockedAuthority requireAuthority(
      PositionMode lockedPositionMode,
      AccountSymbolSettingEntity lockedSetting
  ) {
    if (lockedPositionMode == null) {
      throw new BusinessException(ErrorCode.INVALID_POSITION_MODE, "Locked position mode is required");
    }
    if (lockedSetting == null
        || lockedSetting.getAccountId() == null
        || lockedSetting.getSymbol() == null
        || lockedSetting.getSymbol().isBlank()
        || lockedSetting.getLeverage() == null
        || lockedSetting.getLeverage() <= 0) {
      throw new BusinessException("INVALID_INSTRUMENT_RULES", "Locked Perpetual symbol setting is incomplete");
    }
    if (lockedSetting.getMarginMode() != MarginMode.CROSS
        && lockedSetting.getMarginMode() != MarginMode.ISOLATED) {
      throw new BusinessException(
          ErrorCode.INVALID_MARGIN_MODE,
          "Perpetual margin mode must be CROSS or ISOLATED");
    }
    return new LockedAuthority(lockedSetting.getLeverage(), lockedSetting.getMarginMode());
  }

  private List<PositionEntity> requirePositions(
      List<PositionEntity> lockedPositions,
      PositionMode positionMode,
      UUID accountId,
      String symbol
  ) {
    if (lockedPositions == null) {
      throw new BusinessException(ErrorCode.INVALID_POSITION_MODE, "Locked positions are required");
    }
    List<PositionEntity> open = new ArrayList<>();
    for (PositionEntity position : lockedPositions) {
      if (position != null && !accountId.equals(position.getAccountId())) {
        throw new BusinessException(
            "POSITION_ACCOUNT_MISMATCH",
            "Locked Perpetual position does not belong to the locked account");
      }
      if (position == null
          || position.getStatus() != PositionStatus.OPEN
          || !symbol.equals(position.getSymbol())) {
        continue;
      }
      if (position.getProductType() != ProductType.LINEAR_PERP) {
        throw new BusinessException(
            ErrorCode.PRODUCT_NOT_ALLOWED,
            "Locked Perpetual position has a different product type");
      }
      if (position.getPositionMode() != positionMode) {
        throw new BusinessException(
            ErrorCode.INVALID_POSITION_MODE,
            "Locked position mode does not match the account authority");
      }
      validateStoredSlot(positionMode, position);
      requirePositive(position.getLots(), "INVALID_POSITION_QUANTITY", "Open position quantity must be positive");
      open.add(position);
    }
    return open;
  }

  private Exposure classify(
      PositionMode positionMode,
      PositionSide positionSide,
      OrderSide side,
      boolean reduceOnly,
      BigDecimal quantity,
      List<PositionEntity> positions
  ) {
    PositionEntity slot = slot(positions, positionSide);
    if (positionMode == PositionMode.HEDGE) {
      return classifyHedge(positionSide, side, reduceOnly, quantity, slot);
    }
    return classifyOneWay(side, reduceOnly, quantity, slot);
  }

  private Exposure classifyOneWay(
      OrderSide side,
      boolean reduceOnly,
      BigDecimal quantity,
      PositionEntity slot
  ) {
    if (slot == null || slot.getSide() == side) {
      if (reduceOnly) {
        throw new BusinessException(
            ErrorCode.REDUCE_ONLY_WOULD_INCREASE,
            "Reduce-only order cannot open or increase a Perpetual position");
      }
      return new Exposure(BigDecimal.ZERO, quantity);
    }

    BigDecimal openQuantity = slot.getLots().abs();
    if (quantity.compareTo(openQuantity) > 0) {
      if (reduceOnly) {
        throw exceedsPosition();
      }
      return new Exposure(openQuantity, quantity.subtract(openQuantity));
    }
    return new Exposure(quantity, BigDecimal.ZERO);
  }

  private Exposure classifyHedge(
      PositionSide positionSide,
      OrderSide side,
      boolean reduceOnly,
      BigDecimal quantity,
      PositionEntity slot
  ) {
    OrderSide openingSide = positionSide == PositionSide.LONG ? OrderSide.BUY : OrderSide.SELL;
    if (side == openingSide) {
      if (reduceOnly) {
        throw new BusinessException(
            ErrorCode.REDUCE_ONLY_WOULD_INCREASE,
            "Reduce-only order cannot open or increase a Perpetual position");
      }
      return new Exposure(BigDecimal.ZERO, quantity);
    }
    if (slot == null || quantity.compareTo(slot.getLots().abs()) > 0) {
      throw exceedsPosition();
    }
    return new Exposure(quantity, BigDecimal.ZERO);
  }

  private PositionEntity slot(List<PositionEntity> positions, PositionSide positionSide) {
    PositionEntity slot = null;
    for (PositionEntity position : positions) {
      if (position.getPositionSide() != positionSide) {
        continue;
      }
      if (slot != null) {
        throw new BusinessException(
            ErrorCode.INVALID_POSITION_MODE,
            "More than one open position exists for the locked slot");
      }
      slot = position;
    }
    return slot;
  }

  private void validateSlot(PositionMode positionMode, PositionSide positionSide) {
    if (positionMode == PositionMode.ONE_WAY && positionSide != PositionSide.BOTH) {
      throw new BusinessException(
          ErrorCode.INVALID_POSITION_SIDE,
          "ONE_WAY Perpetual orders require the BOTH slot");
    }
    if (positionMode == PositionMode.HEDGE
        && positionSide != PositionSide.LONG
        && positionSide != PositionSide.SHORT) {
      throw new BusinessException(
          ErrorCode.INVALID_POSITION_SIDE,
          "HEDGE Perpetual orders require a LONG or SHORT slot");
    }
  }

  private void validateStoredSlot(PositionMode positionMode, PositionEntity position) {
    validateSlot(positionMode, position.getPositionSide());
    if (positionMode == PositionMode.HEDGE) {
      OrderSide expected = position.getPositionSide() == PositionSide.LONG
          ? OrderSide.BUY
          : OrderSide.SELL;
      if (position.getSide() != expected) {
        throw new BusinessException(
            ErrorCode.INVALID_POSITION_SIDE,
            "HEDGE slot direction is inconsistent with its position side");
      }
    }
  }

  private BigDecimal adverseCloseLoss(
      OrderSide side,
      BigDecimal closingBase,
      BigDecimal mark,
      BigDecimal worstPrice
  ) {
    requirePositive(mark, ErrorCode.MARKET_BUNDLE_INCOMPLETE, "Authority mark is required");
    if (closingBase.compareTo(BigDecimal.ZERO) == 0) {
      return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
    BigDecimal adversePerBase = side == OrderSide.SELL
        ? mark.subtract(worstPrice).max(BigDecimal.ZERO)
        : worstPrice.subtract(mark).max(BigDecimal.ZERO);
    return money(closingBase.multiply(adversePerBase));
  }

  private void validateIsolatedCloseCapacity(
      MarginMode marginMode,
      PositionSide positionSide,
      Exposure exposure,
      List<PositionEntity> positions,
      BigDecimal worstPrice,
      BigDecimal authorityMark,
      BigDecimal closeFeeRate,
      BigDecimal maintenanceMarginRate,
      BigDecimal feePrice
  ) {
    if (marginMode != MarginMode.ISOLATED
        || exposure.closingBase().compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    PositionEntity position = slot(positions, positionSide);
    if (position == null) {
      throw new BusinessException(
          ErrorCode.REDUCE_ONLY_EXCEEDS_POSITION,
          "Isolated close requires an existing position slot");
    }
    requirePositive(position.getOpenPrice(), "INVALID_POSITION_PRICE", "Position entry price is required");
    if (position.getMarginHeld() == null
        || position.getMarginHeld().compareTo(BigDecimal.ZERO) < 0) {
      throw new BusinessException(
          "INVALID_POSITION_MARGIN",
          "Isolated margin pool must be non-negative");
    }
    if (closeFeeRate == null
        || closeFeeRate.compareTo(BigDecimal.ZERO) < 0
        || closeFeeRate.compareTo(BigDecimal.ONE) >= 0) {
      throw new BusinessException(
          "INVALID_INSTRUMENT_RULES",
          "Close fee rate must be in [0, 1)");
    }
    requirePositive(
        maintenanceMarginRate,
        "INVALID_INSTRUMENT_RULES",
        "Configured maintenance margin rate is required");

    BigDecimal openQuantity = position.getLots().abs();
    BigDecimal remainingQuantity = openQuantity.subtract(exposure.closingBase());
    BigDecimal realizedClose = position.getSide() == OrderSide.BUY
        ? worstPrice.subtract(position.getOpenPrice()).multiply(exposure.closingBase())
        : position.getOpenPrice().subtract(worstPrice).multiply(exposure.closingBase());
    BigDecimal remainingUpl = position.getSide() == OrderSide.BUY
        ? authorityMark.subtract(position.getOpenPrice()).multiply(remainingQuantity)
        : position.getOpenPrice().subtract(authorityMark).multiply(remainingQuantity);
    BigDecimal closingFee = exposure.closingBase()
        .multiply(feePrice)
        .multiply(closeFeeRate);
    BigDecimal retainedMargin = position.getMarginHeld()
        .multiply(remainingQuantity)
        .divide(openQuantity, MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal releasedMargin = position.getMarginHeld().subtract(retainedMargin);
    BigDecimal fullCloseFunding = remainingQuantity.compareTo(BigDecimal.ZERO) == 0
        ? orZero(position.getFundingPnl())
        : BigDecimal.ZERO;
    BigDecimal closedShareSettlement = money(releasedMargin
        .add(fullCloseFunding)
        .add(realizedClose)
        .subtract(closingFee));
    if (closedShareSettlement.compareTo(BigDecimal.ZERO) < 0) {
      throw isolatedCapacityFailure();
    }

    if (remainingQuantity.compareTo(BigDecimal.ZERO) == 0) {
      return;
    }
    BigDecimal remainingMarkNotional = remainingQuantity.multiply(authorityMark);
    BigDecimal remainingThreshold = money(remainingMarkNotional
        .multiply(maintenanceMarginRate.add(closeFeeRate)));
    BigDecimal retainedSlotEquity = money(retainedMargin
        .add(orZero(position.getFundingPnl()))
        .add(remainingUpl));
    if (retainedSlotEquity.compareTo(remainingThreshold) <= 0) {
      throw isolatedCapacityFailure();
    }
  }

  private BusinessException isolatedCapacityFailure() {
    return new BusinessException(
        ErrorCode.MARGIN_REDUCTION_UNSAFE,
        "Worst-case Isolated close would leave the slot under-maintained");
  }

  private BigDecimal isolatedHoldCapacity(
      MarginMode marginMode,
      PositionSide positionSide,
      Exposure exposure,
      List<PositionEntity> positions,
      BigDecimal authorityMark,
      BigDecimal maintenanceMarginRate,
      BigDecimal closeFeeRate
  ) {
    if (marginMode != MarginMode.ISOLATED
        || exposure.openingBase().compareTo(BigDecimal.ZERO) != 0
        || exposure.closingBase().compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
    requirePositive(
        maintenanceMarginRate,
        "INVALID_INSTRUMENT_RULES",
        "Configured maintenance margin rate is required");
    PositionEntity position = slot(positions, positionSide);
    if (position == null) {
      throw new BusinessException(
          ErrorCode.REDUCE_ONLY_EXCEEDS_POSITION,
          "Isolated close requires an existing position slot");
    }
    BigDecimal quantity = abs(position.getLots());
    BigDecimal upl = position.getSide() == OrderSide.BUY
        ? authorityMark.subtract(position.getOpenPrice()).multiply(quantity)
        : position.getOpenPrice().subtract(authorityMark).multiply(quantity);
    BigDecimal maintenance = quantity.multiply(authorityMark).multiply(maintenanceMarginRate);
    BigDecimal estimatedCloseFee = quantity.multiply(authorityMark).multiply(closeFeeRate);
    return money(orZero(position.getMarginHeld())
        .add(orZero(position.getFundingPnl()))
        .add(upl)
        .subtract(maintenance)
        .subtract(estimatedCloseFee)
        .max(BigDecimal.ZERO));
  }

  private static BigDecimal abs(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value.abs();
  }

  private static BigDecimal orZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private FullFillExecutionPath executionPath(OrderType orderType) {
    if (orderType == null) {
      throw new BusinessException("INVALID_PERPETUAL_ORDER_TYPE", "Perpetual order type is required");
    }
    return switch (orderType) {
      case MARKET -> FullFillExecutionPath.MARKET;
      case LIMIT, STOP_LIMIT -> FullFillExecutionPath.IMMEDIATE_LIMIT;
      case STOP_MARKET -> FullFillExecutionPath.TRIGGERED_STOP_MARKET;
      case STOP -> throw new BusinessException(
          "INVALID_PERPETUAL_ORDER_TYPE",
          "STOP is not supported for P0 Linear Perpetual orders");
      case TRAILING_STOP_MARKET -> throw new BusinessException(
          "INVALID_PERPETUAL_ORDER_TYPE",
          "Advanced order types are not supported for P0 Linear Perpetual orders");
    };
  }

  private static BusinessException exceedsPosition() {
    return new BusinessException(
        ErrorCode.REDUCE_ONLY_EXCEEDS_POSITION,
        "Perpetual close quantity exceeds the open slot");
  }

  private static void requirePositive(BigDecimal value, String code, String message) {
    if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException(code, message);
    }
  }

  private static boolean isExactlyRepresentableAsNumeric(
      BigDecimal value,
      int precision,
      int scale
  ) {
    if (value == null) {
      return false;
    }
    BigDecimal normalized;
    try {
      normalized = value.stripTrailingZeros();
    } catch (ArithmeticException exception) {
      return false;
    }
    long normalizedScale = normalized.scale();
    long fractionalDigits = Math.max(normalizedScale, 0L);
    long integerDigits = Math.max((long) normalized.precision() - normalizedScale, 0L);
    return fractionalDigits <= scale
        && integerDigits <= precision - scale;
  }

  private static BusinessException invalidInstrumentRules(String message) {
    return new BusinessException("INVALID_INSTRUMENT_RULES", message);
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal requireOrderHold(BigDecimal value) {
    try {
      BigDecimal exact = value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
      if (exact.signum() < 0 || exact.precision() > 24) {
        throw new ArithmeticException("outside NUMERIC(24,8)");
      }
      return exact;
    } catch (ArithmeticException exception) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "Perpetual order hold exceeds NUMERIC(24,8)",
          exception);
    }
  }

  private record LockedAuthority(int leverage, MarginMode marginMode) {
  }

  private record Exposure(BigDecimal closingBase, BigDecimal openingBase) {
  }

  /** Candidate authority copied from rows that the DEPTH caller currently holds locked. */
  public record DepthPlanningAuthority(
      UUID accountId,
      PositionMode positionMode,
      PositionSide positionSide,
      MarginMode marginMode,
      int leverage,
      boolean reduceOnly,
      BigDecimal maintenanceMarginRate
  ) {
    public DepthPlanningAuthority {
      Objects.requireNonNull(accountId, "accountId");
      Objects.requireNonNull(positionMode, "positionMode");
      Objects.requireNonNull(positionSide, "positionSide");
      Objects.requireNonNull(marginMode, "marginMode");
      if (leverage <= 0) {
        throw new IllegalArgumentException("leverage must be positive");
      }
      if (maintenanceMarginRate == null
          || maintenanceMarginRate.signum() <= 0
          || maintenanceMarginRate.compareTo(BigDecimal.ONE) >= 0
          || !isExactlyRepresentableAsNumeric(maintenanceMarginRate, 18, 8)) {
        throw new IllegalArgumentException("maintenanceMarginRate must be in (0, 1)");
      }
    }
  }

  /**
   * Unforgeable commitment tying a DEPTH risk payload to the exact locked request authority.
   * The constructor is deliberately private; callers can only receive a binding from evaluateDepth.
   */
  public static final class RiskBinding {

    private final UUID accountId;
    private final String symbol;
    private final OrderSide side;
    private final BigDecimal baseQuantity;
    private final PositionMode positionMode;
    private final PositionSide positionSide;
    private final MarginMode marginMode;
    private final int leverage;
    private final boolean reduceOnly;
    private final OrderType orderType;
    private final BigDecimal limitPrice;
    private final ExecutableMarketSnapshot snapshot;
    private final BigDecimal maintenanceMarginRate;
    private final PerpetualRiskPricing pricing;
    private final RiskPayload payload;
    private final AtomicBoolean planningClaimed = new AtomicBoolean();

    private RiskBinding(
        UUID accountId,
        String symbol,
        OrderSide side,
        BigDecimal baseQuantity,
        PositionMode positionMode,
        PositionSide positionSide,
        MarginMode marginMode,
        int leverage,
        boolean reduceOnly,
        OrderType orderType,
        BigDecimal limitPrice,
        ExecutableMarketSnapshot snapshot,
        BigDecimal maintenanceMarginRate,
        PerpetualRiskPricing pricing,
        RiskPayload payload
    ) {
      if (accountId == null
          || symbol == null || symbol.isBlank()
          || side == null
          || baseQuantity == null || baseQuantity.signum() <= 0
          || positionMode == null
          || positionSide == null
          || marginMode == null
          || leverage <= 0
          || orderType == null
          || snapshot == null
          || maintenanceMarginRate == null
          || pricing == null
          || payload == null) {
        throw new IllegalArgumentException(
            "Complete Perpetual DEPTH risk binding authority is required");
      }
      this.accountId = accountId;
      this.symbol = SymbolNormalizer.normalize(symbol.trim());
      this.side = side;
      this.baseQuantity = baseQuantity;
      this.positionMode = positionMode;
      this.positionSide = positionSide;
      this.marginMode = marginMode;
      this.leverage = leverage;
      this.reduceOnly = reduceOnly;
      this.orderType = orderType;
      this.limitPrice = limitPrice;
      this.snapshot = snapshot;
      this.maintenanceMarginRate = maintenanceMarginRate;
      this.pricing = pricing;
      this.payload = payload;
    }

    public UUID accountId() {
      return accountId;
    }

    public String symbol() {
      return symbol;
    }

    public OrderSide side() {
      return side;
    }

    public BigDecimal baseQuantity() {
      return baseQuantity;
    }

    public Instant snapshotAsOf() {
      return snapshot.asOf();
    }

    private boolean commits(RiskPayload candidatePayload) {
      return payload.equals(candidatePayload);
    }

    private boolean matches(
        DepthPlanningAuthority candidateAuthority,
        String candidateSymbol,
        OrderSide candidateSide,
        BigDecimal candidateBaseQuantity,
        OrderType candidateOrderType,
        BigDecimal candidateLimitPrice,
        ExecutableMarketSnapshot candidateSnapshot,
        PerpetualRiskPricing candidatePricing
    ) {
      return candidateAuthority != null
          && accountId.equals(candidateAuthority.accountId())
          && positionMode == candidateAuthority.positionMode()
          && positionSide == candidateAuthority.positionSide()
          && marginMode == candidateAuthority.marginMode()
          && leverage == candidateAuthority.leverage()
          && reduceOnly == candidateAuthority.reduceOnly()
          && sameDecimal(maintenanceMarginRate, candidateAuthority.maintenanceMarginRate())
          && candidateSymbol != null
          && symbol.equals(SymbolNormalizer.normalize(candidateSymbol.trim()))
          && side == candidateSide
          && sameDecimal(baseQuantity, candidateBaseQuantity)
          && orderType == candidateOrderType
          && sameDecimal(limitPrice, candidateLimitPrice)
          && snapshot.equals(candidateSnapshot)
          && pricing.hasSamePlanAuthority(candidatePricing);
    }

    private boolean claim(
        DepthPlanningAuthority candidateAuthority,
        String candidateSymbol,
        OrderSide candidateSide,
        BigDecimal candidateBaseQuantity,
        OrderType candidateOrderType,
        BigDecimal candidateLimitPrice,
        ExecutableMarketSnapshot candidateSnapshot,
        PerpetualRiskPricing candidatePricing
    ) {
      return matches(
          candidateAuthority,
          candidateSymbol,
          candidateSide,
          candidateBaseQuantity,
          candidateOrderType,
          candidateLimitPrice,
          candidateSnapshot,
          candidatePricing)
          && planningClaimed.compareAndSet(false, true);
    }

    private static boolean sameDecimal(BigDecimal expected, BigDecimal candidate) {
      return expected == null
          ? candidate == null
          : candidate != null && expected.compareTo(candidate) == 0;
    }
  }

  private record RiskPayload(
      PositionMode positionMode,
      PositionSide positionSide,
      MarginMode marginMode,
      int leverage,
      BigDecimal closingBase,
      BigDecimal openingBase,
      BigDecimal worstPrice,
      BigDecimal closeWorstPrice,
      BigDecimal openingInitialMargin,
      BigDecimal feeBuffer,
      BigDecimal adverseCloseLoss,
      BigDecimal holdAmount,
      BigDecimal isolatedHoldCapacity,
      String holdCurrency
  ) {
    private static RiskPayload from(OrderRisk risk) {
      return new RiskPayload(
          risk.positionMode(),
          risk.positionSide(),
          risk.marginMode(),
          risk.leverage(),
          risk.closingBase(),
          risk.openingBase(),
          risk.worstPrice(),
          risk.closeWorstPrice(),
          risk.openingInitialMargin(),
          risk.feeBuffer(),
          risk.adverseCloseLoss(),
          risk.holdAmount(),
          risk.isolatedHoldCapacity(),
          risk.holdCurrency());
    }

    private OrderRisk toOrderRisk(RiskBinding binding) {
      return new OrderRisk(
          positionMode,
          positionSide,
          marginMode,
          leverage,
          closingBase,
          openingBase,
          worstPrice,
          closeWorstPrice,
          openingInitialMargin,
          feeBuffer,
          adverseCloseLoss,
          holdAmount,
          isolatedHoldCapacity,
          holdCurrency,
          binding);
    }
  }

  public record OrderRisk(
      PositionMode positionMode,
      PositionSide positionSide,
      MarginMode marginMode,
      int leverage,
      BigDecimal closingBase,
      BigDecimal openingBase,
      BigDecimal worstPrice,
      BigDecimal closeWorstPrice,
      BigDecimal openingInitialMargin,
      BigDecimal feeBuffer,
      BigDecimal adverseCloseLoss,
      BigDecimal holdAmount,
      BigDecimal isolatedHoldCapacity,
      String holdCurrency,
      RiskBinding binding
  ) {
    public OrderRisk {
      RiskPayload payload = new RiskPayload(
          positionMode,
          positionSide,
          marginMode,
          leverage,
          closingBase,
          openingBase,
          worstPrice,
          closeWorstPrice,
          openingInitialMargin,
          feeBuffer,
          adverseCloseLoss,
          holdAmount,
          isolatedHoldCapacity,
          holdCurrency);
      if (binding != null && !binding.commits(payload)) {
        throw new IllegalArgumentException(
            "Perpetual DEPTH risk binding does not commit to its risk payload");
      }
    }

    public OrderRisk(
        PositionMode positionMode,
        PositionSide positionSide,
        MarginMode marginMode,
        int leverage,
        BigDecimal closingBase,
        BigDecimal openingBase,
        BigDecimal worstPrice,
        BigDecimal closeWorstPrice,
        BigDecimal openingInitialMargin,
        BigDecimal feeBuffer,
        BigDecimal adverseCloseLoss,
        BigDecimal holdAmount,
        BigDecimal isolatedHoldCapacity,
        String holdCurrency
    ) {
      this(
          positionMode,
          positionSide,
          marginMode,
          leverage,
          closingBase,
          openingBase,
          worstPrice,
          closeWorstPrice,
          openingInitialMargin,
          feeBuffer,
          adverseCloseLoss,
          holdAmount,
          isolatedHoldCapacity,
          holdCurrency,
          null);
    }

    boolean claimForPlanning(
        DepthPlanningAuthority authority,
        String symbol,
        OrderSide side,
        BigDecimal baseQuantity,
        OrderType orderType,
        BigDecimal limitPrice,
        ExecutableMarketSnapshot snapshot,
        PerpetualRiskPricing pricing
    ) {
      return binding != null
          && binding.claim(
              authority,
              symbol,
              side,
              baseQuantity,
              orderType,
              limitPrice,
              snapshot,
              pricing);
    }
  }

  private static OrderRisk syntheticDepthRiskForTests(
      OrderRisk unboundRisk,
      UUID accountId,
      String symbol,
      OrderSide side,
      BigDecimal baseQuantity,
      PositionMode positionMode,
      PositionSide positionSide,
      MarginMode marginMode,
      int leverage,
      boolean reduceOnly,
      OrderType orderType,
      BigDecimal limitPrice,
      ExecutableMarketSnapshot snapshot,
      BigDecimal maintenanceMarginRate,
      PerpetualRiskPricing pricing
  ) {
    RiskPayload payload = RiskPayload.from(Objects.requireNonNull(unboundRisk, "unboundRisk"));
    RiskBinding binding = new RiskBinding(
        accountId,
        symbol,
        side,
        baseQuantity,
        positionMode,
        positionSide,
        marginMode,
        leverage,
        reduceOnly,
        orderType,
        limitPrice,
        snapshot,
        maintenanceMarginRate,
        pricing,
        payload);
    return payload.toOrderRisk(binding);
  }
}
