package com.fxplatform.trading.service;

import static com.fxplatform.common.money.MoneyAmount.accountEquity;
import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.ExecutionResult;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.risk.model.InstrumentProfile;
import com.fxplatform.risk.service.MarginCalculator;
import com.fxplatform.risk.service.PerpMarginCalculator;
import com.fxplatform.risk.service.PnLCalculator;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PositionEngine {

  private static final int PRICE_SCALE = 8;
  private static final int INTERNAL_SCALE = 18;
  private static final String DEFAULT_MARGIN_DESCRIPTION = "Position margin held";

  private final PositionRepository positionRepository;
  private final TradingAccountRepository accountRepository;
  private final LedgerService ledgerService;
  private final MarginCalculator marginCalculator;
  private final PnLCalculator pnlCalculator;
  private final PerpMarginCalculator perpMarginCalculator;

  @Autowired
  public PositionEngine(
      PositionRepository positionRepository,
      TradingAccountRepository accountRepository,
      LedgerService ledgerService,
      MarginCalculator marginCalculator,
      PnLCalculator pnlCalculator
  ) {
    this(
        positionRepository,
        accountRepository,
        ledgerService,
        marginCalculator,
        pnlCalculator,
        new PerpMarginCalculator());
  }

  public PositionEngine(
      PositionRepository positionRepository,
      TradingAccountRepository accountRepository,
      LedgerService ledgerService,
      MarginCalculator marginCalculator,
      PnLCalculator pnlCalculator,
      PerpMarginCalculator perpMarginCalculator
  ) {
    this.positionRepository = positionRepository;
    this.accountRepository = accountRepository;
    this.ledgerService = ledgerService;
    this.marginCalculator = marginCalculator;
    this.pnlCalculator = pnlCalculator;
    this.perpMarginCalculator = perpMarginCalculator;
  }

  public PositionUpdateResult applyFill(
      TradingAccountEntity account,
      OrderEntity order,
      ExecutionResult fill,
      InstrumentProfile symbolProfile
  ) {
    return applyFill(account, order, fill, symbolProfile, DEFAULT_MARGIN_DESCRIPTION);
  }

  public PositionUpdateResult applyFill(
      TradingAccountEntity account,
      OrderEntity order,
      ExecutionResult fill,
      InstrumentProfile symbolProfile,
      String marginDescription
  ) {
    FillContext context = FillContext.from(account, order, fill, symbolProfile, marginDescription);
    if (symbolProfile.kind() == InstrumentKind.LINEAR_PERPETUAL) {
      return applyLinearPerpetualFill(account, context);
    }
    return positionRepository.findOpenNetPosition(account.getId(), context.symbol())
        .map(position -> sameSide(position, context)
            ? increasePosition(account, position, context)
            : reduceOrReverse(account, position, context))
        .orElseGet(() -> increasePosition(account, null, context));
  }

  /** Applies a canonical BASE Linear Perpetual fill with an explicit authority mark snapshot. */
  public PositionUpdateResult applyPerpetualFill(
      TradingAccountEntity account,
      OrderEntity order,
      ExecutionResult fill,
      InstrumentProfile symbolProfile,
      BigDecimal authorityMark,
      int executionLeverage,
      String marginDescription
  ) {
    if (symbolProfile == null || symbolProfile.kind() != InstrumentKind.LINEAR_PERPETUAL) {
      throw new BusinessException(
          "PRODUCT_NOT_ALLOWED",
          "Canonical Perpetual fill requires a Linear Perpetual profile");
    }
    FillContext context = FillContext.fromPerpetual(
        account,
        order,
        fill,
        symbolProfile,
        authorityMark,
        executionLeverage,
        marginDescription);
    return applyAuthorityLinearPerpetualFill(account, order, context);
  }

  private PositionUpdateResult applyAuthorityLinearPerpetualFill(
      TradingAccountEntity account,
      OrderEntity order,
      FillContext fill
  ) {
    validatePerpetualSlot(fill);
    PositionEntity existing = positionRepository.findOpenPerpetualSlotForUpdate(
        account.getId(),
        fill.symbol(),
        fill.positionMode(),
        fill.positionSide()).orElse(null);
    if (existing != null
        && fill.positionMode() == PositionMode.HEDGE
        && existing.getSide() != openingSide(fill.positionSide())) {
      throw new BusinessException(
          "INVALID_POSITION_SIDE",
          "Existing HEDGE slot direction is inconsistent with its position side");
    }
    boolean increasesSlot = fill.positionMode() == PositionMode.ONE_WAY
        ? existing == null || existing.getSide() == fill.side()
        : fill.side() == openingSide(fill.positionSide());
    boolean isolatedPositionBackedHold = fill.marginMode() == MarginMode.ISOLATED
        && fill.parentPositionId() != null;
    if (isolatedPositionBackedHold
        && (existing == null
            || !fill.parentPositionId().equals(existing.getId())
            || increasesSlot
            || abs(fill.quantity()).compareTo(abs(existing.getLots())) > 0)) {
      throw new BusinessException(
          "ORDER_HOLD_INVALID",
          "Position-backed Isolated hold no longer matches a pure close fill");
    }

    if (existing == null) {
      if (!increasesSlot) {
        throw new BusinessException(
            "REDUCE_ONLY_EXCEEDS_POSITION",
            "Perpetual close quantity exceeds the open slot");
      }
      if (fill.reduceOnly()) {
        throw new BusinessException(
            "REDUCE_ONLY_WOULD_INCREASE",
            "Reduce-only order cannot open a Perpetual position");
      }
      return openAuthorityPosition(account, order, fill);
    }
    if (increasesSlot) {
      if (fill.reduceOnly()) {
        throw new BusinessException(
            "REDUCE_ONLY_WOULD_INCREASE",
            "Reduce-only order cannot increase a Perpetual position");
      }
      return increaseAuthorityPosition(account, order, existing, fill);
    }

    BigDecimal fillQuantity = abs(fill.quantity());
    BigDecimal openQuantity = abs(existing.getLots());
    if (fillQuantity.compareTo(openQuantity) > 0) {
      if (fill.positionMode() == PositionMode.ONE_WAY && !fill.reduceOnly()) {
        return reverseAuthorityPosition(account, order, existing, fill);
      }
      throw new BusinessException(
          "REDUCE_ONLY_EXCEEDS_POSITION",
          "Perpetual close quantity exceeds the open slot");
    }
    return reduceAuthorityPosition(account, order, existing, fill);
  }

  private PositionUpdateResult applyLinearPerpetualFill(
      TradingAccountEntity account,
      FillContext fill
  ) {
    validatePerpetualSlot(fill);
    PositionEntity existing = positionRepository.findOpenPerpetualSlotForUpdate(
        account.getId(),
        fill.symbol(),
        fill.positionMode(),
        fill.positionSide()).orElse(null);
    if (existing != null
        && fill.positionMode() == PositionMode.HEDGE
        && existing.getSide() != openingSide(fill.positionSide())) {
      throw new BusinessException(
          "INVALID_POSITION_SIDE",
          "Existing HEDGE slot direction is inconsistent with its position side");
    }
    boolean increasesSlot = fill.positionMode() == PositionMode.ONE_WAY
        ? existing == null || existing.getSide() == fill.side()
        : fill.side() == openingSide(fill.positionSide());

    if (existing == null) {
      if (!increasesSlot) {
        throw new BusinessException(
            "REDUCE_ONLY_EXCEEDS_POSITION",
            "Perpetual close quantity exceeds the open slot");
      }
      if (fill.reduceOnly()) {
        throw new BusinessException(
            "REDUCE_ONLY_WOULD_INCREASE",
            "Reduce-only order cannot open a Perpetual position");
      }
      return increasePosition(account, null, fill);
    }

    if (increasesSlot) {
      if (fill.reduceOnly()) {
        throw new BusinessException(
            "REDUCE_ONLY_WOULD_INCREASE",
            "Reduce-only order cannot increase a Perpetual position");
      }
      return increasePosition(account, existing, fill);
    }

    BigDecimal fillQuantity = abs(fill.quantity());
    BigDecimal openQuantity = abs(existing.getLots());
    if (fillQuantity.compareTo(openQuantity) > 0) {
      if (fill.positionMode() == PositionMode.ONE_WAY && !fill.reduceOnly()) {
        return reversePosition(account, existing, fill);
      }
      throw new BusinessException(
          "REDUCE_ONLY_EXCEEDS_POSITION",
          "Perpetual close quantity exceeds the open slot");
    }
    return reducePosition(account, existing, fill);
  }

  private PositionUpdateResult openAuthorityPosition(
      TradingAccountEntity account,
      OrderEntity order,
      FillContext fill
  ) {
    PositionEntity opened = openPosition(fill);
    PerpetualSnapshot snapshot = authoritySnapshot(
        opened.getSide(),
        fill.quantity(),
        fill.price(),
        fill.authorityMark(),
        fill.leverage(),
        fill.profile());
    applyAuthoritySnapshot(opened, snapshot, snapshot.initialMargin());
    requireCoveredHold(fill.orderHold(), snapshot.initialMargin().add(fill.fee()));
    PositionEntity saved = positionRepository.save(opened);

    applyAuthorityAccountState(
        account,
        fill,
        BigDecimal.ZERO,
        snapshot.initialMargin(),
        BigDecimal.ZERO,
        snapshot.upl(),
        BigDecimal.ZERO);
    return authorityResult(
        saved,
        order,
        fill.orderHold(),
        BigDecimal.ZERO,
        snapshot.initialMargin(),
        null,
        saved,
        BigDecimal.ZERO,
        null);
  }

  private PositionUpdateResult increaseAuthorityPosition(
      TradingAccountEntity account,
      OrderEntity order,
      PositionEntity existing,
      FillContext fill
  ) {
    BigDecimal oldQuantity = abs(existing.getLots());
    BigDecimal fillQuantity = abs(fill.quantity());
    BigDecimal newQuantity = oldQuantity.add(fillQuantity);
    BigDecimal newEntry = averageEntryForIncrease(
        fill.profile(),
        existing.getOpenPrice(),
        oldQuantity,
        fill.price(),
        fillQuantity);
    BigDecimal oldMargin = orZero(existing.getMarginHeld());
    BigDecimal oldUpl = orZero(existing.getFloatingPnl());
    PerpetualSnapshot snapshot = authoritySnapshot(
        existing.getSide(),
        newQuantity,
        newEntry,
        fill.authorityMark(),
        fill.leverage(),
        fill.profile());
    BigDecimal openingMargin = perpMarginCalculator.calculateLinear(
        fillQuantity,
        fill.price(),
        fill.authorityMark(),
        fill.leverage(),
        fill.profile().maintenanceMarginRate()).initialMargin();
    // Actual allocation is additive per fill. Recomputing a total from the 8-decimal weighted
    // entry can differ by a few money-scale units from the slice margin reserved by order risk.
    BigDecimal newMargin = oldMargin.add(openingMargin);
    if (newMargin.compareTo(BigDecimal.ZERO) < 0) {
      throw new BusinessException(
          "MARGIN_REDUCTION_UNSAFE",
          "Signed Isolated margin adjustment makes the increased position unsafe");
    }
    requireCoveredHold(
        fill.orderHold(),
        openingMargin.add(fill.fee()));

    existing.setLots(newQuantity);
    existing.setOpenPrice(newEntry);
    existing.setLeverage(fill.leverage());
    applyAuthoritySnapshot(existing, snapshot, newMargin);
    incrementVersion(existing);
    PositionEntity saved = positionRepository.save(existing);
    applyAuthorityAccountState(
        account,
        fill,
        oldMargin,
        newMargin,
        oldUpl,
        snapshot.upl(),
        BigDecimal.ZERO);
    return authorityResult(
        saved,
        order,
        fill.orderHold(),
        oldMargin,
        newMargin,
        existing,
        saved,
        BigDecimal.ZERO,
        null);
  }

  private PositionUpdateResult reduceAuthorityPosition(
      TradingAccountEntity account,
      OrderEntity order,
      PositionEntity existing,
      FillContext fill
  ) {
    BigDecimal oldQuantity = abs(existing.getLots());
    BigDecimal closedQuantity = abs(fill.quantity());
    BigDecimal remainingQuantity = oldQuantity.subtract(closedQuantity);
    BigDecimal oldMargin = orZero(existing.getMarginHeld());
    BigDecimal oldUpl = orZero(existing.getFloatingPnl());
    BigDecimal realized = realizedPnl(existing, closedQuantity, fill.price(), fill.profile());
    requireCoveredHold(
        fill.orderHold(),
        adverseCloseLoss(existing, closedQuantity, fill.price(), fill.authorityMark())
            .add(fill.fee()));

    if (remainingQuantity.compareTo(BigDecimal.ZERO) == 0) {
      closePosition(existing, fill.price(), fill.filledAt(), realized);
      existing.setMarkPrice(fill.authorityMark());
      existing.setCurrentPrice(fill.authorityMark());
      incrementVersion(existing);
      positionRepository.save(existing);
      applyAuthorityAccountState(
          account,
          fill,
          oldMargin,
          BigDecimal.ZERO,
          oldUpl,
          BigDecimal.ZERO,
          realized);
      return authorityResult(
          existing,
          order,
          fill.orderHold(),
          oldMargin,
          BigDecimal.ZERO,
          existing,
          existing,
          realized,
          existing);
    }

    BigDecimal newMargin = proportionalMargin(oldMargin, remainingQuantity, oldQuantity);
    PerpetualSnapshot snapshot = authoritySnapshot(
        existing.getSide(),
        remainingQuantity,
        existing.getOpenPrice(),
        fill.authorityMark(),
        fill.leverage(),
        fill.profile());
    existing.setLots(remainingQuantity);
    existing.setRealizedPnl(orZero(existing.getRealizedPnl()).add(realized));
    applyAuthoritySnapshot(existing, snapshot, newMargin);
    incrementVersion(existing);
    PositionEntity saved = positionRepository.save(existing);
    applyAuthorityAccountState(
        account,
        fill,
        oldMargin,
        newMargin,
        oldUpl,
        snapshot.upl(),
        realized);
    return authorityResult(
        saved,
        order,
        fill.orderHold(),
        oldMargin,
        newMargin,
        existing,
        saved,
        realized,
        saved);
  }

  private PositionUpdateResult reverseAuthorityPosition(
      TradingAccountEntity account,
      OrderEntity order,
      PositionEntity existing,
      FillContext fill
  ) {
    BigDecimal oldQuantity = abs(existing.getLots());
    BigDecimal newQuantity = abs(fill.quantity()).subtract(oldQuantity);
    BigDecimal oldMargin = orZero(existing.getMarginHeld());
    BigDecimal oldUpl = orZero(existing.getFloatingPnl());
    BigDecimal realized = realizedPnl(existing, oldQuantity, fill.price(), fill.profile());
    PerpetualSnapshot snapshot = authoritySnapshot(
        fill.side(),
        newQuantity,
        fill.price(),
        fill.authorityMark(),
        fill.leverage(),
        fill.profile());
    requireCoveredHold(
        fill.orderHold(),
        snapshot.initialMargin()
            .add(adverseCloseLoss(existing, oldQuantity, fill.price(), fill.authorityMark()))
            .add(fill.fee()));

    closePosition(existing, fill.price(), fill.filledAt(), realized);
    existing.setMarkPrice(fill.authorityMark());
    existing.setCurrentPrice(fill.authorityMark());
    incrementVersion(existing);
    positionRepository.save(existing);
    FillContext remainder = fill.withQuantity(newQuantity);
    PositionEntity opened = openPosition(remainder);
    applyAuthoritySnapshot(opened, snapshot, snapshot.initialMargin());
    PositionEntity saved = positionRepository.save(opened);
    applyAuthorityAccountState(
        account,
        fill,
        oldMargin,
        snapshot.initialMargin(),
        oldUpl,
        snapshot.upl(),
        realized);
    List<PerpetualLedgerEffect> ledgerEffects = new ArrayList<>();
    addOrderReleaseEffect(ledgerEffects, fill.orderHold(), order);
    addMarginReleaseEffect(ledgerEffects, oldMargin, existing);
    addMarginHoldEffect(
        ledgerEffects,
        snapshot.initialMargin(),
        saved,
        "Reversed position margin held");
    addTradePnlEffect(ledgerEffects, realized, existing);
    return new PositionUpdateResult(saved, realized, List.copyOf(ledgerEffects));
  }

  private void validatePerpetualSlot(FillContext fill) {
    if (fill.side() == null) {
      throw new BusinessException("INVALID_ORDER_SIDE", "Perpetual order side is required");
    }
    if (fill.positionMode() == PositionMode.ONE_WAY
        && fill.positionSide() != PositionSide.BOTH) {
      throw new BusinessException(
          "INVALID_POSITION_SIDE",
          "ONE_WAY Perpetual orders require the BOTH slot");
    }
    if (fill.positionMode() == PositionMode.HEDGE
        && fill.positionSide() != PositionSide.LONG
        && fill.positionSide() != PositionSide.SHORT) {
      throw new BusinessException(
          "INVALID_POSITION_SIDE",
          "HEDGE Perpetual orders require a LONG or SHORT slot");
    }
    if (fill.marginMode() != MarginMode.CROSS && fill.marginMode() != MarginMode.ISOLATED) {
      throw new BusinessException(
          "INVALID_MARGIN_MODE",
          "Perpetual margin mode must be CROSS or ISOLATED");
    }
  }

  private OrderSide openingSide(PositionSide side) {
    return side == PositionSide.LONG ? OrderSide.BUY : OrderSide.SELL;
  }

  private PositionUpdateResult increasePosition(
      TradingAccountEntity account,
      PositionEntity existingPosition,
      FillContext fill
  ) {
    if (existingPosition == null) {
      PositionEntity opened = openPosition(fill);
      BigDecimal newMargin = applyMarginSnapshot(opened, fill.profile(), fill.quantity(), fill.price(), fill.leverage());
      opened.setMarginHeld(newMargin);
      PositionEntity saved = positionRepository.save(opened);
      applyAccountMarginDelta(account, newMargin.subtract(fill.orderHold()));
      saveAccount(account);
      recordMarginHold(account, marginHoldAfterOrderHold(newMargin, fill), saved, fill.marginDescription());
      return new PositionUpdateResult(saved);
    }

    BigDecimal oldQty = abs(existingPosition.getLots());
    BigDecimal fillQty = abs(fill.quantity());
    BigDecimal newQty = oldQty.add(fillQty);
    BigDecimal newAverageEntry = averageEntryForIncrease(
        fill.profile(),
        existingPosition.getOpenPrice(),
        oldQty,
        fill.price(),
        fillQty);
    BigDecimal oldMargin = orZero(existingPosition.getMarginHeld());
    BigDecimal marginPrice = isPerpetual(fill.profile().kind()) ? fill.price() : newAverageEntry;
    BigDecimal newMargin = applyMarginSnapshot(existingPosition, fill.profile(), newQty, marginPrice, fill.leverage());

    existingPosition.setLots(newQty);
    existingPosition.setOpenPrice(newAverageEntry);
    existingPosition.setCurrentPrice(fill.price());
    existingPosition.setMarginHeld(newMargin);
    existingPosition.setFloatingPnl(BigDecimal.ZERO);
    existingPosition.setLeverage(fill.leverage());
    PositionEntity saved = positionRepository.save(existingPosition);

    BigDecimal marginIncrease = newMargin.subtract(oldMargin);
    applyAccountMarginDelta(account, marginIncrease.subtract(fill.orderHold()));
    saveAccount(account);
    recordMarginHold(account, marginHoldAfterOrderHold(marginIncrease, fill), saved, "Position margin increased");
    return new PositionUpdateResult(saved);
  }

  private PositionUpdateResult reducePosition(
      TradingAccountEntity account,
      PositionEntity existingPosition,
      FillContext fill
  ) {
    BigDecimal oldQty = abs(existingPosition.getLots());
    BigDecimal fillQty = abs(fill.quantity());
    if (fillQty.compareTo(oldQty) > 0) {
      return reversePosition(account, existingPosition, fill);
    }

    BigDecimal closedQty = fillQty;
    BigDecimal remainingQty = oldQty.subtract(closedQty);
    BigDecimal oldMargin = orZero(existingPosition.getMarginHeld());
    BigDecimal marginToRelease = proportionalMargin(oldMargin, closedQty, oldQty);
    BigDecimal realizedPnl = realizedPnl(existingPosition, closedQty, fill.price(), fill.profile());

    if (remainingQty.compareTo(BigDecimal.ZERO) == 0) {
      closePosition(existingPosition, fill.price(), fill.filledAt(), realizedPnl);
      positionRepository.save(existingPosition);
      applyRealizedPnl(account, realizedPnl);
      applyAccountMarginDelta(account, oldMargin.negate().subtract(fill.orderHold()));
      saveAccount(account);
      recordMarginRelease(account, oldMargin, existingPosition);
      recordTradePnl(account, realizedPnl, existingPosition);
      return new PositionUpdateResult(existingPosition);
    }

    existingPosition.setLots(remainingQty);
    existingPosition.setCurrentPrice(fill.price());
    existingPosition.setMarginHeld(oldMargin.subtract(marginToRelease));
    reducePerpMarginSnapshot(existingPosition, fill.profile(), remainingQty, oldQty, fill.price());
    existingPosition.setRealizedPnl(orZero(existingPosition.getRealizedPnl()).add(realizedPnl));
    existingPosition.setFloatingPnl(BigDecimal.ZERO);
    positionRepository.save(existingPosition);

    applyRealizedPnl(account, realizedPnl);
    applyAccountMarginDelta(account, marginToRelease.negate().subtract(fill.orderHold()));
    saveAccount(account);
    recordMarginRelease(account, marginToRelease, existingPosition);
    recordTradePnl(account, realizedPnl, existingPosition);
    return new PositionUpdateResult(existingPosition);
  }

  private PositionUpdateResult reversePosition(
      TradingAccountEntity account,
      PositionEntity existingPosition,
      FillContext fill
  ) {
    BigDecimal oldQty = abs(existingPosition.getLots());
    BigDecimal newQty = abs(fill.quantity()).subtract(oldQty);
    if (newQty.compareTo(BigDecimal.ZERO) <= 0) {
      return reducePosition(account, existingPosition, fill);
    }

    BigDecimal oldMargin = orZero(existingPosition.getMarginHeld());
    BigDecimal realizedPnl = realizedPnl(existingPosition, oldQty, fill.price(), fill.profile());
    closePosition(existingPosition, fill.price(), fill.filledAt(), realizedPnl);
    positionRepository.save(existingPosition);

    FillContext remainder = fill.withQuantity(newQty);
    PositionEntity opened = openPosition(remainder);
    BigDecimal newMargin = applyMarginSnapshot(opened, fill.profile(), newQty, fill.price(), fill.leverage());
    opened.setMarginHeld(newMargin);
    PositionEntity saved = positionRepository.save(opened);

    applyRealizedPnl(account, realizedPnl);
    applyAccountMarginDelta(account, newMargin.subtract(oldMargin).subtract(fill.orderHold()));
    saveAccount(account);
    recordMarginRelease(account, oldMargin, existingPosition);
    recordMarginHold(account, marginHoldAfterOrderHold(newMargin, fill), saved, DEFAULT_MARGIN_DESCRIPTION);
    recordTradePnl(account, realizedPnl, existingPosition);
    return new PositionUpdateResult(saved);
  }

  private PositionUpdateResult reduceOrReverse(
      TradingAccountEntity account,
      PositionEntity existingPosition,
      FillContext fill
  ) {
    return abs(fill.quantity()).compareTo(abs(existingPosition.getLots())) > 0
        ? reversePosition(account, existingPosition, fill)
        : reducePosition(account, existingPosition, fill);
  }

  private PositionEntity openPosition(FillContext fill) {
    PositionEntity position = new PositionEntity();
    position.setAccountId(fill.accountId());
    position.setSymbol(fill.symbol());
    position.setSide(fill.side());
    position.setLots(fill.quantity());
    position.setOpenPrice(fill.price());
    position.setCurrentPrice(fill.price());
    position.setStopLoss(fill.stopLoss());
    position.setTakeProfit(fill.takeProfit());
    position.setLeverage(fill.leverage());
    if (fill.profile().kind() == InstrumentKind.LINEAR_PERPETUAL) {
      position.setProductType(ProductType.LINEAR_PERP);
      position.setPositionMode(fill.positionMode());
      position.setPositionSide(fill.positionSide());
      position.setMarginMode(fill.marginMode());
    }
    return position;
  }

  private void closePosition(PositionEntity position, BigDecimal price, Instant filledAt, BigDecimal realizedPnl) {
    position.setCurrentPrice(price);
    position.setFloatingPnl(BigDecimal.ZERO);
    position.setRealizedPnl(orZero(position.getRealizedPnl()).add(realizedPnl));
    position.setMarginHeld(BigDecimal.ZERO);
    position.setNotional(BigDecimal.ZERO);
    position.setInitialMargin(BigDecimal.ZERO);
    position.setMaintenanceMargin(BigDecimal.ZERO);
    position.setMarkPrice(price);
    position.setStatus(PositionStatus.CLOSED);
    position.setClosedAt(filledAt);
  }

  private boolean sameSide(PositionEntity position, FillContext fill) {
    return position.getSide() == fill.side();
  }

  private BigDecimal weightedAverage(BigDecimal oldPrice, BigDecimal oldQty, BigDecimal fillPrice, BigDecimal fillQty) {
    BigDecimal newQty = oldQty.add(fillQty);
    if (newQty.compareTo(BigDecimal.ZERO) <= 0) {
      return fillPrice;
    }
    return oldPrice.multiply(oldQty)
        .add(fillPrice.multiply(fillQty))
        .divide(newQty, PRICE_SCALE, RoundingMode.HALF_UP);
  }

  private BigDecimal averageEntryForIncrease(
      InstrumentProfile profile,
      BigDecimal oldPrice,
      BigDecimal oldQty,
      BigDecimal fillPrice,
      BigDecimal fillQty
  ) {
    if (profile.kind() == InstrumentKind.INVERSE_PERPETUAL) {
      return inverseHarmonicAverage(profile, oldPrice, oldQty, fillPrice, fillQty);
    }
    return weightedAverage(oldPrice, oldQty, fillPrice, fillQty);
  }

  private BigDecimal inverseHarmonicAverage(
      InstrumentProfile profile,
      BigDecimal oldPrice,
      BigDecimal oldQty,
      BigDecimal fillPrice,
      BigDecimal fillQty
  ) {
    BigDecimal contractValue = inverseContractValue(profile);
    BigDecimal oldUsdNotional = oldQty.multiply(contractValue);
    BigDecimal fillUsdNotional = fillQty.multiply(contractValue);
    BigDecimal denominator = oldUsdNotional.divide(oldPrice, INTERNAL_SCALE, RoundingMode.HALF_UP)
        .add(fillUsdNotional.divide(fillPrice, INTERNAL_SCALE, RoundingMode.HALF_UP));
    if (denominator.compareTo(BigDecimal.ZERO) <= 0) {
      return fillPrice;
    }
    return oldUsdNotional.add(fillUsdNotional).divide(denominator, PRICE_SCALE, RoundingMode.HALF_UP);
  }

  private BigDecimal proportionalMargin(BigDecimal margin, BigDecimal closedQty, BigDecimal oldQty) {
    if (oldQty.compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ZERO;
    }
    return margin.multiply(closedQty).divide(oldQty, PRICE_SCALE, RoundingMode.HALF_UP);
  }

  private BigDecimal requiredMargin(InstrumentProfile profile, BigDecimal quantity, BigDecimal price, int leverage) {
    return marginCalculator.requiredMargin(profile.kind(), quantity, price, leverage, profile.unitSize());
  }

  private BigDecimal applyMarginSnapshot(
      PositionEntity position,
      InstrumentProfile profile,
      BigDecimal quantity,
      BigDecimal markPrice,
      int leverage
  ) {
    if (!isPerpetual(profile.kind())) {
      return requiredMargin(profile, quantity, markPrice, leverage);
    }
    PerpMarginCalculator.MarginResult margin = profile.kind() == InstrumentKind.LINEAR_PERPETUAL
        ? perpMarginCalculator.calculate(
            InstrumentKind.LINEAR_PERPETUAL,
            quantity,
            BigDecimal.ONE,
            BigDecimal.ONE,
            markPrice,
            leverage,
            profile.maintenanceMarginRate())
        : perpMarginCalculator.calculate(profile, quantity, markPrice, leverage);
    position.setNotional(margin.notional());
    position.setInitialMargin(margin.initialMargin());
    position.setMaintenanceMargin(margin.maintenanceMargin());
    position.setMarkPrice(markPrice);
    position.setSettlementAsset(profile.settlementAsset());
    position.setMarginAsset(profile.marginAsset());
    return margin.initialMargin();
  }

  private void reducePerpMarginSnapshot(
      PositionEntity position,
      InstrumentProfile profile,
      BigDecimal remainingQty,
      BigDecimal oldQty,
      BigDecimal markPrice
  ) {
    if (!isPerpetual(profile.kind())) {
      return;
    }
    position.setNotional(proportionalMargin(orZero(position.getNotional()), remainingQty, oldQty));
    position.setInitialMargin(position.getMarginHeld());
    position.setMaintenanceMargin(proportionalMargin(orZero(position.getMaintenanceMargin()), remainingQty, oldQty));
    position.setMarkPrice(markPrice);
    position.setSettlementAsset(profile.settlementAsset());
    position.setMarginAsset(profile.marginAsset());
  }

  private boolean isPerpetual(InstrumentKind kind) {
    return kind == InstrumentKind.LINEAR_PERPETUAL || kind == InstrumentKind.INVERSE_PERPETUAL;
  }

  private BigDecimal marginHoldAfterOrderHold(BigDecimal positionMarginIncrease, FillContext fill) {
    return positionMarginIncrease.subtract(fill.orderHold()).max(BigDecimal.ZERO);
  }

  private BigDecimal realizedPnl(
      PositionEntity position,
      BigDecimal closedQty,
      BigDecimal closePrice,
      InstrumentProfile profile
  ) {
    BigDecimal unitSize = switch (profile.kind()) {
      case LINEAR_PERPETUAL -> BigDecimal.ONE;
      case INVERSE_PERPETUAL -> inverseContractValue(profile);
      default -> profile.unitSize();
    };
    return pnlCalculator.floatingPnl(
        profile.kind(),
        position.getSide(),
        closedQty,
        position.getOpenPrice(),
        closePrice,
        unitSize);
  }

  private BigDecimal inverseContractValue(InstrumentProfile profile) {
    BigDecimal contractSize = positiveOrDefault(profile.contractSize(), profile.unitSize());
    return contractSize.multiply(positiveOrDefault(profile.contractMultiplier(), BigDecimal.ONE));
  }

  private BigDecimal positiveOrDefault(BigDecimal value, BigDecimal fallback) {
    return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? fallback : value;
  }

  private PerpetualSnapshot authoritySnapshot(
      OrderSide side,
      BigDecimal quantity,
      BigDecimal entryPrice,
      BigDecimal authorityMark,
      int leverage,
      InstrumentProfile profile
  ) {
    PerpMarginCalculator.LinearMarginResult margin = perpMarginCalculator.calculateLinear(
        abs(quantity),
        entryPrice,
        authorityMark,
        leverage,
        profile.maintenanceMarginRate());
    BigDecimal upl = (side == OrderSide.BUY
        ? authorityMark.subtract(entryPrice)
        : entryPrice.subtract(authorityMark))
        .multiply(abs(quantity))
        .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    return new PerpetualSnapshot(
        margin.markNotional(),
        margin.initialMargin(),
        margin.maintenanceMargin(),
        authorityMark.setScale(PRICE_SCALE, RoundingMode.HALF_UP),
        upl);
  }

  private void applyAuthoritySnapshot(
      PositionEntity position,
      PerpetualSnapshot snapshot,
      BigDecimal marginHeld
  ) {
    position.setCurrentPrice(snapshot.markPrice());
    position.setMarkPrice(snapshot.markPrice());
    position.setNotional(snapshot.markNotional());
    position.setInitialMargin(snapshot.initialMargin());
    position.setMaintenanceMargin(snapshot.maintenanceMargin());
    position.setMarginHeld(marginHeld.setScale(PRICE_SCALE, RoundingMode.HALF_UP));
    position.setFloatingPnl(snapshot.upl());
    position.setSettlementAsset("USDT");
    position.setMarginAsset("USDT");
  }

  private void requireCoveredHold(BigDecimal storedHold, BigDecimal requiredAtFill) {
    if (orZero(storedHold).compareTo(orZero(requiredAtFill)) < 0) {
      throw new BusinessException(
          "ORDER_HOLD_INVALID",
          "Stored Perpetual order hold does not cover the canonical fill");
    }
  }

  private BigDecimal adverseCloseLoss(
      PositionEntity position,
      BigDecimal closingQuantity,
      BigDecimal fillPrice,
      BigDecimal authorityMark
  ) {
    BigDecimal adversePerBase = position.getSide() == OrderSide.BUY
        ? authorityMark.subtract(fillPrice).max(BigDecimal.ZERO)
        : fillPrice.subtract(authorityMark).max(BigDecimal.ZERO);
    return abs(closingQuantity)
        .multiply(adversePerBase)
        .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
  }

  private void applyAuthorityAccountState(
      TradingAccountEntity account,
      FillContext fill,
      BigDecimal oldMargin,
      BigDecimal newMargin,
      BigDecimal oldUpl,
      BigDecimal newUpl,
      BigDecimal realized
  ) {
    BigDecimal marginDelta = orZero(newMargin).subtract(orZero(oldMargin));
    BigDecimal uplDelta = orZero(newUpl).subtract(orZero(oldUpl));
    BigDecimal nextUsed = orZero(account.getUsedMargin())
        .add(marginDelta)
        .subtract(fill.orderHold());
    if (nextUsed.compareTo(BigDecimal.ZERO) < 0) {
      throw new BusinessException(
          "ORDER_HOLD_INVALID",
          "Perpetual order hold exceeds reconciled used margin");
    }
    account.setUsedMargin(nextUsed.setScale(PRICE_SCALE, RoundingMode.HALF_UP));
    account.setBalance(orZero(account.getBalance()).add(realized));
    account.setEquity(accountEquity(account).add(realized).add(uplDelta));
    BigDecimal crossUplDelta = fill.marginMode() == MarginMode.CROSS
        ? uplDelta
        : BigDecimal.ZERO;
    boolean isolatedPositionBackedHold = fill.marginMode() == MarginMode.ISOLATED
        && fill.parentPositionId() != null;
    BigDecimal externalHoldRelease = isolatedPositionBackedHold
        ? BigDecimal.ZERO
        : fill.orderHold();
    account.setFreeMargin(orZero(account.getFreeMargin())
        .add(externalHoldRelease)
        .subtract(marginDelta)
        .add(realized)
        .add(crossUplDelta));
    saveAccount(account);
  }

  private PositionUpdateResult authorityResult(
      PositionEntity position,
      OrderEntity order,
      BigDecimal orderHold,
      BigDecimal oldMargin,
      BigDecimal newMargin,
      PositionEntity previous,
      PositionEntity current,
      BigDecimal realizedPnlDelta,
      PositionEntity realizedPosition
  ) {
    List<PerpetualLedgerEffect> effects = new ArrayList<>();
    addOrderReleaseEffect(effects, orderHold, order);
    BigDecimal marginDelta = orZero(newMargin).subtract(orZero(oldMargin));
    if (marginDelta.compareTo(BigDecimal.ZERO) > 0) {
      addMarginHoldEffect(
          effects,
          marginDelta,
          current,
          "Position margin held from order hold");
    } else if (marginDelta.compareTo(BigDecimal.ZERO) < 0) {
      addMarginReleaseEffect(
          effects,
          marginDelta.abs(),
          previous == null ? current : previous);
    }
    if (realizedPosition != null) {
      addTradePnlEffect(effects, realizedPnlDelta, realizedPosition);
    }
    return new PositionUpdateResult(
        position,
        orZero(realizedPnlDelta),
        List.copyOf(effects));
  }

  private void addOrderReleaseEffect(
      List<PerpetualLedgerEffect> effects,
      BigDecimal orderHold,
      OrderEntity order
  ) {
    if (orZero(orderHold).compareTo(BigDecimal.ZERO) > 0) {
      effects.add(new PerpetualLedgerEffect(
          PerpetualLedgerEffectType.ORDER_RELEASE,
          orderHold,
          order.getId(),
          "Perpetual order hold consumed"));
    }
  }

  private void addMarginHoldEffect(
      List<PerpetualLedgerEffect> effects,
      BigDecimal amount,
      PositionEntity position,
      String description
  ) {
    if (orZero(amount).compareTo(BigDecimal.ZERO) > 0) {
      effects.add(new PerpetualLedgerEffect(
          PerpetualLedgerEffectType.MARGIN_HOLD,
          amount,
          position.getId(),
          description));
    }
  }

  private void addMarginReleaseEffect(
      List<PerpetualLedgerEffect> effects,
      BigDecimal amount,
      PositionEntity position
  ) {
    if (orZero(amount).compareTo(BigDecimal.ZERO) > 0) {
      effects.add(new PerpetualLedgerEffect(
          PerpetualLedgerEffectType.MARGIN_RELEASE,
          amount,
          position.getId(),
          "Position margin released"));
    }
  }

  private void addTradePnlEffect(
      List<PerpetualLedgerEffect> effects,
      BigDecimal amount,
      PositionEntity position
  ) {
    if (orZero(amount).compareTo(BigDecimal.ZERO) != 0) {
      effects.add(new PerpetualLedgerEffect(
          PerpetualLedgerEffectType.TRADE_PNL,
          amount,
          position.getId(),
          "Position realized PnL"));
    }
  }

  public void recordPerpetualFillLedger(
      TradingAccountEntity account,
      PositionUpdateResult update
  ) {
    for (PerpetualLedgerEffect effect : update.ledgerEffects()) {
      switch (effect.type()) {
        case ORDER_RELEASE -> ledgerService.recordOrderRelease(
            account, effect.amount(), effect.referenceId(), effect.description());
        case MARGIN_HOLD -> ledgerService.recordMarginHold(
            account, effect.amount(), effect.referenceId(), effect.description());
        case MARGIN_RELEASE -> ledgerService.recordMarginRelease(
            account, effect.amount(), effect.referenceId(), effect.description());
        case TRADE_PNL -> ledgerService.recordTradePnl(
            account, effect.amount(), effect.referenceId(), effect.description());
      }
    }
  }

  private void applyRealizedPnl(TradingAccountEntity account, BigDecimal realizedPnl) {
    if (realizedPnl.compareTo(BigDecimal.ZERO) == 0) {
      return;
    }
    BigDecimal balance = orZero(account.getBalance()).add(realizedPnl);
    account.setBalance(balance);
    account.setEquity(balance);
  }

  private void applyAccountMarginDelta(TradingAccountEntity account, BigDecimal delta) {
    if (delta.compareTo(BigDecimal.ZERO) > 0) {
      if (accountRepository.reserveMarginIfAvailable(account.getId(), delta) != 1) {
        throw new BusinessException("INSUFFICIENT_MARGIN", "Free margin is not enough");
      }
    }
    account.setUsedMargin(orZero(account.getUsedMargin()).add(delta).max(BigDecimal.ZERO));
    account.setFreeMargin(accountEquity(account).subtract(orZero(account.getUsedMargin())));
  }

  private void saveAccount(TradingAccountEntity account) {
    accountRepository.save(account);
  }

  private void recordMarginHold(
      TradingAccountEntity account,
      BigDecimal amount,
      PositionEntity position,
      String description
  ) {
    if (amount.compareTo(BigDecimal.ZERO) > 0) {
      ledgerService.recordMarginHold(account, amount, position.getId(), description);
    }
  }

  private void recordMarginRelease(TradingAccountEntity account, BigDecimal amount, PositionEntity position) {
    if (amount.compareTo(BigDecimal.ZERO) > 0) {
      ledgerService.recordMarginRelease(account, amount, position.getId(), "Position margin released");
    }
  }

  private void recordTradePnl(TradingAccountEntity account, BigDecimal amount, PositionEntity position) {
    if (amount.compareTo(BigDecimal.ZERO) != 0) {
      ledgerService.recordTradePnl(account, amount, position.getId(), "Position realized PnL");
    }
  }

  private static BigDecimal abs(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value.abs();
  }

  private static void incrementVersion(PositionEntity position) {
    long currentVersion = position.getVersion() == null ? 0L : position.getVersion();
    position.setVersion(currentVersion + 1L);
  }

  public record PositionUpdateResult(
      PositionEntity position,
      BigDecimal realizedPnlDelta,
      List<PerpetualLedgerEffect> ledgerEffects
  ) {

    public PositionUpdateResult(PositionEntity position) {
      this(position, BigDecimal.ZERO, List.of());
    }

    public PositionUpdateResult(PositionEntity position, BigDecimal realizedPnlDelta) {
      this(position, realizedPnlDelta, List.of());
    }
  }

  public record PerpetualLedgerEffect(
      PerpetualLedgerEffectType type,
      BigDecimal amount,
      UUID referenceId,
      String description
  ) {
  }

  public enum PerpetualLedgerEffectType {
    ORDER_RELEASE,
    MARGIN_HOLD,
    MARGIN_RELEASE,
    TRADE_PNL
  }

  private record PerpetualSnapshot(
      BigDecimal markNotional,
      BigDecimal initialMargin,
      BigDecimal maintenanceMargin,
      BigDecimal markPrice,
      BigDecimal upl
  ) {
  }

  public record FillContext(
      java.util.UUID accountId,
      String symbol,
      OrderSide side,
      PositionMode positionMode,
      PositionSide positionSide,
      MarginMode marginMode,
      boolean reduceOnly,
      BigDecimal quantity,
      BigDecimal price,
      BigDecimal authorityMark,
      BigDecimal fee,
      Instant filledAt,
      int leverage,
      BigDecimal orderHold,
      UUID parentPositionId,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      InstrumentProfile profile,
      String marginDescription
  ) {

    private static FillContext from(
        TradingAccountEntity account,
        OrderEntity order,
        ExecutionResult fill,
        InstrumentProfile profile,
        String marginDescription
    ) {
      requireNettable(profile.kind());
      BigDecimal quantity = fill.filledQuantity() != null ? fill.filledQuantity() : orderQuantity(order);
      if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
        throw new BusinessException("INVALID_FILL_QUANTITY", "Fill quantity must be positive");
      }
      if (fill.filledPrice() == null || fill.filledPrice().compareTo(BigDecimal.ZERO) <= 0) {
        throw new BusinessException("INVALID_FILL_PRICE", "Fill price must be positive");
      }
      return new FillContext(
          account.getId(),
          normalizeSymbol(order.getSymbol()),
          order.getSide(),
          effectivePositionMode(account),
          effectivePositionSide(order),
          effectiveMarginMode(order),
          Boolean.TRUE.equals(order.getReduceOnly()),
          quantity,
          fill.filledPrice(),
          fill.filledPrice(),
          orZero(fill.fee()),
          fill.filledAt(),
          effectiveLeverage(order, account),
          orZero(order.getHoldAmount()),
          order.getParentPositionId(),
          order.getStopLoss(),
          order.getTakeProfit(),
          profile,
          marginDescription == null || marginDescription.isBlank() ? DEFAULT_MARGIN_DESCRIPTION : marginDescription);
    }

    private static FillContext fromPerpetual(
        TradingAccountEntity account,
        OrderEntity order,
        ExecutionResult fill,
        InstrumentProfile profile,
        BigDecimal authorityMark,
        int executionLeverage,
        String marginDescription
    ) {
      requireNettable(profile.kind());
      BigDecimal quantity = fill.filledQuantity() != null
          ? fill.filledQuantity()
          : orderQuantity(order);
      if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
        throw new BusinessException("INVALID_FILL_QUANTITY", "Fill quantity must be positive");
      }
      if (fill.filledPrice() == null || fill.filledPrice().compareTo(BigDecimal.ZERO) <= 0) {
        throw new BusinessException("INVALID_FILL_PRICE", "Fill price must be positive");
      }
      if (authorityMark == null || authorityMark.compareTo(BigDecimal.ZERO) <= 0) {
        throw new BusinessException(
            "MARKET_BUNDLE_INCOMPLETE",
            "Canonical Perpetual fill requires a positive authority mark");
      }
      if (order.getPositionMode() == null) {
        throw new BusinessException(
            "INVALID_POSITION_MODE",
            "Perpetual order position-mode snapshot is required");
      }
      if (executionLeverage <= 0) {
        throw new BusinessException(
            "INVALID_INSTRUMENT_RULES",
            "Locked Perpetual execution leverage must be positive");
      }
      return new FillContext(
          account.getId(),
          normalizeSymbol(order.getSymbol()),
          order.getSide(),
          order.getPositionMode(),
          effectivePositionSide(order),
          effectiveMarginMode(order),
          Boolean.TRUE.equals(order.getReduceOnly()),
          quantity,
          fill.filledPrice(),
          authorityMark,
          orZero(fill.fee()),
          fill.filledAt(),
          executionLeverage,
          orZero(order.getHoldAmount()),
          order.getParentPositionId(),
          order.getStopLoss(),
          order.getTakeProfit(),
          profile,
          marginDescription == null || marginDescription.isBlank()
              ? DEFAULT_MARGIN_DESCRIPTION
              : marginDescription);
    }

    private FillContext withQuantity(BigDecimal quantity) {
      return new FillContext(
          accountId,
          symbol,
          side,
          positionMode,
          positionSide,
          marginMode,
          reduceOnly,
          quantity,
          price,
          authorityMark,
          fee,
          filledAt,
          leverage,
          orderHold,
          parentPositionId,
          stopLoss,
          takeProfit,
          profile,
          marginDescription);
    }

    private static PositionMode effectivePositionMode(TradingAccountEntity account) {
      return account.getPositionMode() == null ? PositionMode.ONE_WAY : account.getPositionMode();
    }

    private static PositionSide effectivePositionSide(OrderEntity order) {
      return order.getPositionSide() == null ? PositionSide.BOTH : order.getPositionSide();
    }

    private static MarginMode effectiveMarginMode(OrderEntity order) {
      return order.getMarginMode() == null ? MarginMode.CROSS : order.getMarginMode();
    }

    private static void requireNettable(InstrumentKind kind) {
      if (kind != InstrumentKind.FOREX
          && kind != InstrumentKind.LINEAR_PERPETUAL
          && kind != InstrumentKind.INVERSE_PERPETUAL) {
        throw new BusinessException(
            "UNSUPPORTED_NET_POSITION",
            "Only FOREX, LINEAR_PERPETUAL and INVERSE_PERPETUAL positions use net mode");
      }
    }

    private static BigDecimal orderQuantity(OrderEntity order) {
      return order.getQuantity() != null ? order.getQuantity() : order.getLots();
    }

    private static int effectiveLeverage(OrderEntity order, TradingAccountEntity account) {
      if (order.getLeverage() != null && order.getLeverage() > 0) {
        return order.getLeverage();
      }
      return account.getLeverage();
    }

    private static String normalizeSymbol(String symbol) {
      return symbol == null ? "" : symbol.trim().toUpperCase();
    }
  }
}
