package com.fxplatform.trading.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.ExecutableMarketSnapshot;
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
import java.util.ArrayList;
import java.util.List;
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
        false);
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
        true);
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
      boolean liquidationClose
  ) {
    LockedAuthority authority = requireAuthority(lockedPositionMode, lockedSetting);
    requirePositive(
        maintenanceMarginRate,
        "INVALID_INSTRUMENT_RULES",
        "Configured maintenance margin rate is required");
    if (maintenanceMarginRate.compareTo(BigDecimal.ONE) >= 0) {
      throw new BusinessException(
          "INVALID_INSTRUMENT_RULES",
          "Configured maintenance margin rate must be below one");
    }
    requirePositive(canonicalBaseQuantity, "BAD_QUANTITY", "Canonical BASE quantity must be positive");
    if (side == null) {
      throw new BusinessException("INVALID_ORDER_SIDE", "Perpetual order side is required");
    }
    validateSlot(lockedPositionMode, positionSide);
    if (snapshot == null
        || snapshot.platformSymbol() == null
        || !snapshot.platformSymbol().equals(lockedSetting.getSymbol())) {
      throw new BusinessException(
          ErrorCode.MARKET_BUNDLE_INCOMPLETE,
          "Perpetual market snapshot does not match the locked symbol setting");
    }

    FullFillExecutionPath executionPath = executionPath(orderType);
    if (orderType == OrderType.LIMIT) {
      requirePositive(limitPrice, ErrorCode.ORDER_PRICE_REQUIRED, "Perpetual LIMIT price is required");
    }
    FullFillPricingProjection pricing = fullFillCoordinator.project(
        ProductType.LINEAR_PERP,
        side,
        executionPath,
        limitPrice,
        snapshot);
    BigDecimal marginAndFeePrice = money(orderType == OrderType.LIMIT
        ? limitPrice.max(pricing.filledPrice())
        : pricing.filledPrice());
    BigDecimal closeWorstPrice = money(orderType == OrderType.LIMIT && side == OrderSide.SELL
        ? limitPrice.min(pricing.filledPrice())
        : marginAndFeePrice);

    List<PositionEntity> openPositions = requirePositions(
        lockedPositions,
        lockedPositionMode,
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
        .multiply(pricing.worstFeeRate()));
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
          pricing.worstFeeRate(),
          maintenanceMarginRate,
          marginAndFeePrice);
    }
    BigDecimal holdAmount = liquidationClose
        ? money(BigDecimal.ZERO)
        : money(openingInitialMargin.add(feeBuffer).add(adverseCloseLoss));
    BigDecimal isolatedHoldCapacity = liquidationClose
        ? money(BigDecimal.ZERO)
        : isolatedHoldCapacity(
            authority.marginMode(),
            positionSide,
            exposure,
            openPositions,
            snapshot.mark(),
            maintenanceMarginRate,
            pricing.worstFeeRate());

    return new OrderRisk(
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
  }

  private LockedAuthority requireAuthority(
      PositionMode lockedPositionMode,
      AccountSymbolSettingEntity lockedSetting
  ) {
    if (lockedPositionMode == null) {
      throw new BusinessException(ErrorCode.INVALID_POSITION_MODE, "Locked position mode is required");
    }
    if (lockedSetting == null
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
      String symbol
  ) {
    if (lockedPositions == null) {
      throw new BusinessException(ErrorCode.INVALID_POSITION_MODE, "Locked positions are required");
    }
    List<PositionEntity> open = new ArrayList<>();
    for (PositionEntity position : lockedPositions) {
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
    requirePositive(closeFeeRate, "INVALID_INSTRUMENT_RULES", "Close fee rate is required");
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
      case LIMIT -> FullFillExecutionPath.IMMEDIATE_LIMIT;
      case STOP_MARKET -> FullFillExecutionPath.TRIGGERED_STOP_MARKET;
      case STOP -> throw new BusinessException(
          "INVALID_PERPETUAL_ORDER_TYPE",
          "STOP is not supported for P0 Linear Perpetual orders");
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

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private record LockedAuthority(int leverage, MarginMode marginMode) {
  }

  private record Exposure(BigDecimal closingBase, BigDecimal openingBase) {
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
      String holdCurrency
  ) {
  }
}
