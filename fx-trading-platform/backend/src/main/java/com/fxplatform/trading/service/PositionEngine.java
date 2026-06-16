package com.fxplatform.trading.service;

import static com.fxplatform.common.money.MoneyAmount.accountEquity;
import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.ExecutionResult;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.risk.model.InstrumentProfile;
import com.fxplatform.risk.service.MarginCalculator;
import com.fxplatform.risk.service.PerpMarginCalculator;
import com.fxplatform.risk.service.PnLCalculator;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
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
    return positionRepository.findOpenNetPosition(account.getId(), context.symbol())
        .map(position -> sameSide(position, context)
            ? increasePosition(account, position, context)
            : reduceOrReverse(account, position, context))
        .orElseGet(() -> increasePosition(account, null, context));
  }

  public PositionUpdateResult increasePosition(
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

  public PositionUpdateResult reducePosition(
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

  public PositionUpdateResult reversePosition(
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
    PerpMarginCalculator.MarginResult margin = perpMarginCalculator.calculate(profile, quantity, markPrice, leverage);
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
    BigDecimal unitSize = profile.kind() == InstrumentKind.INVERSE_PERPETUAL
        ? inverseContractValue(profile)
        : profile.unitSize();
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

  public record PositionUpdateResult(PositionEntity position) {
  }

  public record FillContext(
      java.util.UUID accountId,
      String symbol,
      OrderSide side,
      BigDecimal quantity,
      BigDecimal price,
      Instant filledAt,
      int leverage,
      BigDecimal orderHold,
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
          quantity,
          fill.filledPrice(),
          fill.filledAt(),
          effectiveLeverage(order, account),
          orZero(order.getHoldAmount()),
          order.getStopLoss(),
          order.getTakeProfit(),
          profile,
          marginDescription == null || marginDescription.isBlank() ? DEFAULT_MARGIN_DESCRIPTION : marginDescription);
    }

    private FillContext withQuantity(BigDecimal quantity) {
      return new FillContext(
          accountId,
          symbol,
          side,
          quantity,
          price,
          filledAt,
          leverage,
          orderHold,
          stopLoss,
          takeProfit,
          profile,
          marginDescription);
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
