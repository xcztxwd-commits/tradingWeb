package com.fxplatform.trading.service;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.common.money.ExactNumeric;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.ExecutableMarketSnapshots;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.repository.OrderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Deterministic MARK-price evaluation and execution entry for Demo Perpetual trailing stops. */
@Service
public class TrailingStopService {

  private static final int AUTHORITY_MARK_PRECISION = 24;
  private static final int AUTHORITY_MARK_SCALE = 10;
  private static final int TRIGGER_PRICE_PRECISION = 31;
  private static final int TRIGGER_PRICE_SCALE = 10;
  private static final int MAX_CAS_ATTEMPTS = 3;

  private final OrderRepository orderRepository;
  private final SystemCloseOrderService systemCloseOrderService;
  private final TradingAccountRepository accountRepository;
  private final DemoExecutionGuard demoExecutionGuard;

  public TrailingStopService(
      OrderRepository orderRepository,
      SystemCloseOrderService systemCloseOrderService,
      TradingAccountRepository accountRepository,
      DemoExecutionGuard demoExecutionGuard
  ) {
    this.orderRepository = orderRepository;
    this.systemCloseOrderService = systemCloseOrderService;
    this.accountRepository = accountRepository;
    this.demoExecutionGuard = demoExecutionGuard;
  }

  /** Pure evaluation of one carrier against the current positive authority MARK price. */
  public TrailingStopUpdate evaluate(OrderEntity order, BigDecimal triggerPrice) {
    requireEvaluable(order, triggerPrice);
    BigDecimal previousExtreme = order.getTrailingExtreme();
    if (previousExtreme == null && !activationReached(order, triggerPrice)) {
      return new TrailingStopUpdate(order.getId(), null, false, false);
    }

    BigDecimal nextExtreme = previousExtreme == null
        ? triggerPrice
        : nextExtreme(order.getSide(), previousExtreme, triggerPrice);
    BigDecimal threshold = callbackThreshold(order, nextExtreme);
    boolean triggered = order.getSide() == OrderSide.SELL
        ? triggerPrice.compareTo(threshold) <= 0
        : triggerPrice.compareTo(threshold) >= 0;
    return new TrailingStopUpdate(order.getId(), nextExtreme, true, triggered);
  }

  /**
   * Executes one immutable Perpetual market Tick before ordinary pending/protection processors.
   * The returned count is the number of trigger CAS winners, not replayed canonical closes.
   */
  public int onTick(ExecutableMarketSnapshot snapshot) {
    updateExtrema(snapshot);
    return triggerReady(snapshot);
  }

  /** Updates activation/extrema/threshold state without executing a close. */
  public int updateExtrema(ExecutableMarketSnapshot snapshot) {
    return processTick(snapshot, false);
  }

  /** Executes carriers whose persisted threshold is crossed after ordinary matching. */
  public int triggerReady(ExecutableMarketSnapshot snapshot) {
    return processTick(snapshot, true, false);
  }

  /** Validation-only trigger pass that joins the owning system-step transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public int triggerReadyStrict(ExecutableMarketSnapshot snapshot) {
    return processTick(snapshot, true, true);
  }

  private int processTick(ExecutableMarketSnapshot snapshot, boolean triggerAllowed) {
    return processTick(snapshot, triggerAllowed, false);
  }

  private int processTick(
      ExecutableMarketSnapshot snapshot,
      boolean triggerAllowed,
      boolean joinCallerTransaction
  ) {
    requireTick(snapshot);
    List<OrderEntity> candidates = orderRepository.findTrailingStopsAwaitingActivation();
    if (candidates == null || candidates.isEmpty()) {
      return 0;
    }

    int triggered = 0;
    for (OrderEntity order : candidates) {
      if (!sameSymbol(order.getSymbol(), snapshot.platformSymbol())) {
        continue;
      }
      ExecutableMarketSnapshots.requireComplete(
          order.getSymbol(), ProductType.LINEAR_PERP, snapshot);
      TradingAccountEntity account = accountRepository.findById(order.getAccountId())
          .orElseThrow(() -> new BusinessException(
              ErrorCode.ACCOUNT_NOT_FOUND,
              "Trailing stop account not found"));
      demoExecutionGuard.requireDemo(account, ProductType.LINEAR_PERP, order.getSymbol());
      triggered += processCandidate(
          order,
          snapshot,
          triggerAllowed,
          joinCallerTransaction) ? 1 : 0;
    }
    return triggered;
  }

  private boolean processCandidate(
      OrderEntity initial,
      ExecutableMarketSnapshot snapshot,
      boolean triggerAllowed,
      boolean joinCallerTransaction
  ) {
    OrderEntity current = initial;
    for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
      if (!isAwaitingTrailing(current)) {
        return false;
      }
      TrailingStopUpdate update = evaluate(current, snapshot.mark());
      if (!update.activated()) {
        return false;
      }
      BigDecimal exactThreshold = callbackThreshold(current, update.nextExtreme());
      BigDecimal persistedThreshold = persistentThreshold(current.getSide(), exactThreshold);
      boolean stateChanged = !sameDecimal(current.getTrailingExtreme(), update.nextExtreme())
          || !sameDecimal(current.getTriggerPrice(), persistedThreshold);
      if (!stateChanged && (!triggerAllowed || !update.triggered())) {
        return false;
      }
      long expectedVersion = current.getVersion() == null ? 0L : current.getVersion();
      if (orderRepository.updateTrailingState(
          current.getId(), expectedVersion, update.nextExtreme(), persistedThreshold) == 1) {
        if (triggerAllowed && update.triggered()) {
          if (joinCallerTransaction) {
            systemCloseOrderService.executeProtectionStrict(current.getId(), snapshot);
          } else {
            systemCloseOrderService.executeProtection(current.getId(), snapshot);
          }
          return true;
        }
        return !triggerAllowed && stateChanged;
      }
      current = orderRepository.findById(initial.getId()).orElse(null);
    }
    return false;
  }

  private static boolean isAwaitingTrailing(OrderEntity order) {
    return order != null
        && order.getStatus() == OrderStatus.PENDING_ACTIVATION
        && order.getOrderType() == OrderType.TRAILING_STOP_MARKET;
  }

  private static void requireEvaluable(OrderEntity order, BigDecimal currentPrice) {
    boolean delta = order != null && positive(order.getTrailingDelta());
    boolean rate = order != null
        && positive(order.getTrailingRate())
        && order.getTrailingRate().compareTo(BigDecimal.ONE) < 0;
    if (order == null
        || order.getId() == null
        || (order.getSide() != OrderSide.SELL && order.getSide() != OrderSide.BUY)
        || !positive(currentPrice)
        || delta == rate
        || (order.getTrailingDelta() != null && !delta)
        || (order.getTrailingRate() != null && !rate)
        || (order.getActivationPrice() != null && !positive(order.getActivationPrice()))
        || (order.getTrailingExtreme() != null && !positive(order.getTrailingExtreme()))) {
      throw new BusinessException(
          ErrorCode.PROTECTION_NOT_EXECUTABLE,
          "Trailing stop state is not executable");
    }
  }

  private static boolean activationReached(OrderEntity order, BigDecimal currentPrice) {
    if (order.getActivationPrice() == null) {
      return true;
    }
    return order.getSide() == OrderSide.SELL
        ? currentPrice.compareTo(order.getActivationPrice()) >= 0
        : currentPrice.compareTo(order.getActivationPrice()) <= 0;
  }

  private static BigDecimal nextExtreme(
      OrderSide side,
      BigDecimal previousExtreme,
      BigDecimal currentPrice
  ) {
    if (side == OrderSide.SELL) {
      return previousExtreme.max(currentPrice);
    }
    return previousExtreme.min(currentPrice);
  }

  private static BigDecimal callbackThreshold(OrderEntity order, BigDecimal extreme) {
    if (order.getTrailingDelta() != null) {
      return order.getSide() == OrderSide.SELL
          ? extreme.subtract(order.getTrailingDelta())
          : extreme.add(order.getTrailingDelta());
    }
    return order.getSide() == OrderSide.SELL
        ? extreme.multiply(BigDecimal.ONE.subtract(order.getTrailingRate()))
        : extreme.multiply(BigDecimal.ONE.add(order.getTrailingRate()));
  }

  private static BigDecimal persistentThreshold(OrderSide side, BigDecimal exactThreshold) {
    RoundingMode rounding = side == OrderSide.SELL ? RoundingMode.FLOOR : RoundingMode.CEILING;
    BigDecimal persisted = exactThreshold.setScale(TRIGGER_PRICE_SCALE, rounding);
    if (!ExactNumeric.fits(persisted, TRIGGER_PRICE_PRECISION, TRIGGER_PRICE_SCALE)) {
      throw new BusinessException(
          "INVALID_INSTRUMENT_RULES",
          "Trailing stop threshold exceeds NUMERIC(31,10)");
    }
    return persisted;
  }

  private static void requireTick(ExecutableMarketSnapshot snapshot) {
    if (snapshot == null
        || snapshot.productType() != ProductType.LINEAR_PERP
        || snapshot.platformSymbol() == null
        || snapshot.platformSymbol().isBlank()
        || !positive(snapshot.mark())
        || !ExactNumeric.fits(
            snapshot.mark(), AUTHORITY_MARK_PRECISION, AUTHORITY_MARK_SCALE)) {
      throw new BusinessException(
          ErrorCode.MARKET_BUNDLE_INCOMPLETE,
          "Trailing stop Tick requires a matching NUMERIC(24,10) Perpetual authority mark");
    }
  }

  private static boolean sameSymbol(String left, String right) {
    return left != null
        && right != null
        && SymbolNormalizer.normalize(left).equals(SymbolNormalizer.normalize(right));
  }

  private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
    return left == null ? right == null : right != null && left.compareTo(right) == 0;
  }

  private static boolean positive(BigDecimal value) {
    return value != null && value.compareTo(BigDecimal.ZERO) > 0;
  }
}
