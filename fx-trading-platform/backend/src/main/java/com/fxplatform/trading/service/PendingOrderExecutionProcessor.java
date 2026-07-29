package com.fxplatform.trading.service;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.DemoFillIdentity;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.execution.FullFillExecutionPath;
import com.fxplatform.execution.FullFillRequest;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Executes one already-resolved pending Spot candidate in the caller-selected transaction scope. */
@Service
@RequiredArgsConstructor
public class PendingOrderExecutionProcessor {

  private static final int WALLET_SCALE = 8;
  private static final String CLAIM_LOST = "PENDING_ORDER_CLAIM_LOST";

  private final OrderRepository orderRepository;
  private final TradingAccountRepository accountRepository;
  private final DemoExecutionGuard demoExecutionGuard;
  private final WalletBalanceRepository walletBalanceRepository;
  private final WalletService walletService;
  private final SpotPositionService spotPositionService;
  private final FullFillCoordinator fullFillCoordinator;
  private final OrderFillService orderFillService;
  private final OrderEventService orderEventService;
  private final TradingTransactionExecutor transactionExecutor;
  private DepthOrderExecutionService depthOrderExecutionService;
  private SymbolRepository symbolRepository;
  private InstrumentRulesEngine instrumentRulesEngine;

  @Autowired
  void setDepthOrderExecutionService(
      DepthOrderExecutionService depthOrderExecutionService
  ) {
    this.depthOrderExecutionService = depthOrderExecutionService;
  }

  @Autowired
  void setSymbolRepository(SymbolRepository symbolRepository) {
    this.symbolRepository = symbolRepository;
  }

  @Autowired
  void setInstrumentRulesEngine(InstrumentRulesEngine instrumentRulesEngine) {
    this.instrumentRulesEngine = instrumentRulesEngine;
  }

  public boolean process(OrderEntity candidate, ExecutableMarketSnapshot snapshot) {
    return process(candidate, snapshot, false);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean processStrict(OrderEntity candidate, ExecutableMarketSnapshot snapshot) {
    return process(candidate, snapshot, true);
  }

  private boolean process(
      OrderEntity candidate,
      ExecutableMarketSnapshot snapshot,
      boolean joinCallerTransaction
  ) {
    if (candidate == null || snapshot == null) {
      return false;
    }
    PendingOrderExecutionFingerprint fingerprint =
        PendingOrderExecutionFingerprint.capture(candidate);
    try {
      DemoExecutionPolicy policy = depthOrderExecutionService == null
          ? null
          : depthOrderExecutionService.currentPolicy();
      if (depthOrderExecutionService != null) {
        if (depthOrderExecutionService.isDepth(policy)) {
          if (candidate.getContingencyGroupId() != null) {
            throw new BusinessException(
                ErrorCode.DEPTH_OCO_UNSUPPORTED,
                "DEPTH pending execution does not support OCO shared holds");
          }
          requireDepthInstrumentAuthority();
          return joinCallerTransaction
              ? transactionExecutor.executeJoined(
                  () -> processDepthLocked(candidate, fingerprint, snapshot, policy))
              : transactionExecutor.execute(
                  () -> processDepthLocked(candidate, fingerprint, snapshot, policy));
        }
      }
      return joinCallerTransaction
          ? transactionExecutor.executeJoined(
              () -> processSimpleLocked(candidate, fingerprint, snapshot, policy))
          : transactionExecutor.execute(
              () -> processSimpleLocked(candidate, fingerprint, snapshot, policy));
    } catch (DepthOrderExecutionService.StalePolicyException exception) {
      throw new BusinessException(
          ErrorCode.MARKET_DATA_STALE,
          "DEPTH execution policy changed during pending Spot planning",
          exception);
    } catch (BusinessException exception) {
      if (!joinCallerTransaction && CLAIM_LOST.equals(exception.getCode())) {
        return false;
      }
      throw exception;
    }
  }

  private boolean processSimpleLocked(
      OrderEntity candidate,
      PendingOrderExecutionFingerprint fingerprint,
      ExecutableMarketSnapshot snapshot,
      DemoExecutionPolicy policy
  ) {
    TradingAccountEntity account = accountRepository.findByIdForUpdate(candidate.getAccountId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    if (account.getUserId() == null
        || candidate.getUserId() == null
        || !account.getUserId().equals(candidate.getUserId())) {
      throw new com.fxplatform.common.exception.AuthorizationException(
          "ACCOUNT_NOT_FOUND",
          "Account not found");
    }
    demoExecutionGuard.requireDemo(account, ProductType.CRYPTO_SPOT, candidate.getSymbol());
    walletBalanceRepository.findByAccountIdForUpdate(account.getId());
    spotPositionService.lockExisting(account.getId());

    List<OrderEntity> group = candidate.getContingencyGroupId() == null
        ? List.of(orderRepository.findByIdForUpdate(candidate.getId())
            .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found")))
        : requireGroup(orderRepository.findByContingencyGroupIdForUpdate(
            candidate.getContingencyGroupId()));
    OrderEntity winner = group.stream()
        .filter(order -> order.getId().equals(candidate.getId()))
        .findFirst()
        .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Pending order not found in locked group"));
    if (!account.getId().equals(winner.getAccountId())
        || !account.getUserId().equals(winner.getUserId())
        || !candidate.getUserId().equals(winner.getUserId())
        || !candidate.getAccountId().equals(winner.getAccountId())
        || !java.util.Objects.equals(candidate.getContingencyGroupId(), winner.getContingencyGroupId())
        || !com.fxplatform.common.market.SymbolNormalizer.normalize(candidate.getSymbol())
            .equals(com.fxplatform.common.market.SymbolNormalizer.normalize(winner.getSymbol()))) {
      throw new BusinessException(
          "OCO_GROUP_INCOMPLETE",
          "Locked OCO group does not match the scanned candidate");
    }
    if (!fingerprint.matches(winner)) {
      return false;
    }
    if (depthOrderExecutionService != null) {
      depthOrderExecutionService.requireCurrentSimplePolicy(policy);
    }
    fullFillCoordinator.requireFresh(snapshot);
    if (!isExecutableState(winner, snapshot)) {
      return false;
    }
    OrderEntity peer = group.size() == 2
        ? group.stream().filter(order -> !order.getId().equals(winner.getId())).findFirst().orElseThrow()
        : null;
    if (peer != null && peer.getStatus() != OrderStatus.PENDING) {
      return false;
    }
    OrderEntity owner = peer == null ? winner : holdOwner(group);
    BigDecimal baseQuantity = canonicalQuantity(winner);
    if (isAwaitingStopLimitActivation(winner) && !limitMarketable(winner, snapshot)) {
      if (orderRepository.activateStopLimitPending(winner.getId()) != 1) {
        throw new BusinessException(CLAIM_LOST, "STOP_LIMIT activation claim was lost");
      }
      winner.setStatus(OrderStatus.PENDING);
      orderEventService.record(
          winner.getId(), "ORDER_TRIGGERED", OrderStatus.PENDING_ACTIVATION, OrderStatus.PENDING,
          null, "Spot STOP_LIMIT activated and resting");
      return false;
    }
    boolean immediateStopLimit = isAwaitingStopLimitActivation(winner);
    FullFillExecutionPath path = executionPath(winner, immediateStopLimit);
    FullFillResult fill = fullFillCoordinator.execute(
        new FullFillRequest(
            executionIntent(winner, baseQuantity),
            winner.getSymbol(),
            ProductType.CRYPTO_SPOT,
            winner.getSide(),
            path,
            baseQuantity,
            path == FullFillExecutionPath.TRIGGERED_STOP_MARKET
                ? null
                : currentPrice(winner)),
        snapshot);

    fullFillCoordinator.requireFresh(fill);
    BigDecimal held = ensureBuyStopMarketHold(
        winner,
        owner,
        account,
        baseQuantity,
        fill);
    OrderStatus sourceStatus = winner.getStatus();
    int claimed = immediateStopLimit
        ? orderRepository.activateStopLimitWorking(winner.getId())
        : orderRepository.claimPending(winner.getId());
    if (claimed != 1) {
      throw new BusinessException(
          CLAIM_LOST,
          "Pending order claim was lost after trigger hold preparation");
    }
    try {
      winner.setStatus(OrderStatus.WORKING);
      if (immediateStopLimit) {
        orderEventService.record(
            winner.getId(), "ORDER_TRIGGERED", OrderStatus.PENDING_ACTIVATION, OrderStatus.WORKING,
            null, "Spot STOP_LIMIT activated for immediate execution");
      }
      if (peer != null) {
        peer.setStatus(OrderStatus.CANCELED);
        peer.setCanceledAt(Instant.now());
        peer.setRemainingQuantity(BigDecimal.ZERO);
        orderRepository.save(peer);
        orderEventService.record(
            peer.getId(), "ORDER_CANCELED", OrderStatus.PENDING, OrderStatus.CANCELED,
            null, "OCO peer canceled by winning leg");
      }
      orderFillService.fill(
          winner,
          owner,
          account,
          fill,
          held,
          peer == null ? "Pending order hold" : "Pending OCO order hold");
    } catch (RuntimeException exception) {
      // The transaction rolls the claim back; keep the scanned candidate consistent as well.
      winner.setStatus(sourceStatus);
      throw exception;
    }
    orderEventService.record(
        winner.getId(), "ORDER_FILLED", OrderStatus.WORKING, OrderStatus.FILLED,
        null, peer == null ? "Pending order filled" : "OCO winning leg filled");
    return true;
  }

  private boolean processDepthLocked(
      OrderEntity candidate,
      PendingOrderExecutionFingerprint fingerprint,
      ExecutableMarketSnapshot snapshot,
      DemoExecutionPolicy policy
  ) {
    TradingAccountEntity account = accountRepository.findByIdForUpdate(candidate.getAccountId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    if (account.getUserId() == null
        || candidate.getUserId() == null
        || !account.getUserId().equals(candidate.getUserId())) {
      throw new com.fxplatform.common.exception.AuthorizationException(
          "ACCOUNT_NOT_FOUND",
          "Account not found");
    }
    demoExecutionGuard.requireDemo(account, ProductType.CRYPTO_SPOT, candidate.getSymbol());
    walletBalanceRepository.findByAccountIdForUpdate(account.getId());
    spotPositionService.lockExisting(account.getId());
    OrderEntity order = orderRepository.findByIdForUpdate(candidate.getId())
        .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found"));
    if (!fingerprint.matches(order)
        || !account.getId().equals(order.getAccountId())
        || !account.getUserId().equals(order.getUserId())
        || order.getProductType() != ProductType.CRYPTO_SPOT) {
      return false;
    }
    if (depthOrderExecutionService.tickAlreadyApplied(order, snapshot)) {
      return false;
    }
    if (!isDepthExecutableState(order, snapshot)) {
      return false;
    }

    SymbolEntity symbol = symbolRepository.findBySymbol(order.getSymbol())
        .orElseThrow(() -> new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found"));
    InstrumentRules rules = instrumentRulesEngine.rules(symbol);
    if (rules == null || rules.productType() != ProductType.CRYPTO_SPOT) {
      throw new BusinessException(
          "INVALID_INSTRUMENT_RULES",
          "Pending Spot DEPTH instrument rules are unavailable");
    }
    instrumentRulesEngine.validateDepthExecutionAuthority(symbol, rules);

    BigDecimal remaining = depthRemainingQuantity(order);
    boolean activatingStopLimit = isAwaitingStopLimitActivation(order);
    OrderType executableType = order.getOrderType() == OrderType.STOP_MARKET
        ? OrderType.MARKET
        : OrderType.LIMIT;
    LiquidityRole role = activatingStopLimit || order.getOrderType() == OrderType.STOP_MARKET
        ? LiquidityRole.TAKER
        : LiquidityRole.MAKER;
    DepthOrderExecutionService.DepthMatchPlan match = depthOrderExecutionService.prepare(
        policy,
        order.getSymbol(),
        ProductType.CRYPTO_SPOT,
        order.getSide(),
        order.getOrderType(),
        executableType,
        order.getTimeInForce(),
        remaining,
        executableType == OrderType.LIMIT ? currentPrice(order) : null,
        false,
        role,
        snapshot);
    depthOrderExecutionService.requireQuantityStep(
        match,
        QuantityConversionService.storageCompatibleStep(rules.stepSize()));
    instrumentRulesEngine.validateCanonicalDepthFills(
        symbol,
        rules,
        match.matchingResult().fills());

    if (match.matchingResult().fills().isEmpty()) {
      depthOrderExecutionService.requireApplicable(match);
      if (!activatingStopLimit) {
        return false;
      }
      if (orderRepository.activateStopLimitPending(order.getId()) != 1) {
        throw new BusinessException(CLAIM_LOST, "STOP_LIMIT activation claim was lost");
      }
      order.setStatus(OrderStatus.PENDING);
      orderEventService.record(
          order.getId(),
          "ORDER_TRIGGERED",
          OrderStatus.PENDING_ACTIVATION,
          OrderStatus.PENDING,
          null,
          "Spot STOP_LIMIT activated and resting in DEPTH mode");
      return false;
    }

    BigDecimal currentHold = order.getHoldAmount() == null
        ? BigDecimal.ZERO
        : order.getHoldAmount();
    DepthOrderExecutionService.DepthHoldPlan holds =
        depthOrderExecutionService.planSpot(currentHold, match);
    depthOrderExecutionService.requireApplicable(match);
    if (activatingStopLimit) {
      if (orderRepository.activateStopLimitWorking(order.getId()) != 1) {
        throw new BusinessException(CLAIM_LOST, "STOP_LIMIT activation claim was lost");
      }
      order.setStatus(OrderStatus.WORKING);
      orderEventService.record(
          order.getId(),
          "ORDER_TRIGGERED",
          OrderStatus.PENDING_ACTIVATION,
          OrderStatus.WORKING,
          null,
          "Spot STOP_LIMIT activated for DEPTH execution");
    }
    increaseDepthSpotHold(order, account, holds.initialHold(), snapshot);
    DepthOrderExecutionService.DepthExecutionOutcome outcome =
        depthOrderExecutionService.applyLocked(
            order,
            order,
            account,
            match,
            holds,
            false);
    return outcome.newlyAppliedFillCount() > 0;
  }

  private void requireDepthInstrumentAuthority() {
    if (symbolRepository == null || instrumentRulesEngine == null) {
      throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "Pending Spot DEPTH instrument authority is unavailable");
    }
  }

  private void increaseDepthSpotHold(
      OrderEntity order,
      TradingAccountEntity account,
      BigDecimal requiredHold,
      ExecutableMarketSnapshot snapshot
  ) {
    BigDecimal currentHold = order.getHoldAmount() == null
        ? BigDecimal.ZERO
        : order.getHoldAmount();
    BigDecimal increase = requiredHold.subtract(currentHold);
    if (increase.signum() <= 0) {
      return;
    }
    String ordinalZero = DemoFillIdentity.forSnapshot(order.getId(), snapshot, 0);
    UUID tickReference = DemoFillIdentity.tradeId(order.getId(), ordinalZero);
    walletService.lockAvailableWithEntryType(
        account.getId(),
        order.getHoldCurrency(),
        walletAmount(increase),
        "ORDER_TICK",
        tickReference,
        "Pending Spot DEPTH hold increased",
        "SPOT_ORDER_LOCK");
    order.setHoldAmount(walletAmount(requiredHold));
  }

  private boolean isDepthExecutableState(
      OrderEntity order,
      ExecutableMarketSnapshot snapshot
  ) {
    if (order.getOrderType() == OrderType.LIMIT) {
      return order.getStatus() == OrderStatus.PENDING
          || order.getStatus() == OrderStatus.PARTIALLY_FILLED;
    }
    if (order.getOrderType() == OrderType.STOP_LIMIT) {
      if (order.getStatus() == OrderStatus.PENDING_ACTIVATION) {
        return stopLimitTriggered(order, snapshot);
      }
      return order.getStatus() == OrderStatus.PENDING
          || order.getStatus() == OrderStatus.PARTIALLY_FILLED;
    }
    if (order.getOrderType() == OrderType.STOP_MARKET) {
      if (order.getStatus() == OrderStatus.PARTIALLY_FILLED) {
        return true;
      }
      return order.getStatus() == OrderStatus.PENDING && triggered(order, snapshot);
    }
    return false;
  }

  private BigDecimal depthRemainingQuantity(OrderEntity order) {
    BigDecimal remaining = order.getRemainingQuantity();
    if (remaining == null || remaining.signum() <= 0) {
      throw new BusinessException(
          "BAD_QUANTITY",
          "Pending DEPTH order requires a positive remaining quantity");
    }
    return remaining;
  }

  private BigDecimal ensureBuyStopMarketHold(
      OrderEntity winner,
      OrderEntity owner,
      TradingAccountEntity account,
      BigDecimal baseQuantity,
      FullFillResult fill
  ) {
    BigDecimal held = owner.getHoldAmount() == null ? BigDecimal.ZERO : owner.getHoldAmount();
    if (winner.getSide() != OrderSide.BUY || winner.getOrderType() != OrderType.STOP_MARKET) {
      return held;
    }
    BigDecimal quoteSpend = walletAmount(
        baseQuantity.multiply(fill.filledPrice()).add(fill.fee()));
    BigDecimal normalizedHeld = walletAmount(held);
    if (quoteSpend.compareTo(normalizedHeld) <= 0) {
      return held;
    }
    BigDecimal topUp = walletAmount(quoteSpend.subtract(normalizedHeld));
    walletService.lockAvailableWithEntryType(
        account.getId(),
        owner.getHoldCurrency(),
        topUp,
        "ORDER_TRIGGER",
        owner.getId(),
        "Pending Spot BUY trigger hold increased",
        "SPOT_ORDER_LOCK");
    BigDecimal updatedHold = walletAmount(normalizedHeld.add(topUp));
    owner.setHoldAmount(updatedHold);
    return updatedHold;
  }

  private BigDecimal walletAmount(BigDecimal value) {
    return value.setScale(WALLET_SCALE, RoundingMode.HALF_UP);
  }

  private boolean isExecutableState(OrderEntity order, ExecutableMarketSnapshot snapshot) {
    if (order.getOrderType() == OrderType.STOP_LIMIT) {
      if (!isStandaloneUserStopLimit(order)) {
        return false;
      }
      if (order.getStatus() == OrderStatus.PENDING_ACTIVATION) {
        return stopLimitTriggered(order, snapshot);
      }
      return order.getStatus() == OrderStatus.PENDING && limitMarketable(order, snapshot);
    }
    return order.getStatus() == OrderStatus.PENDING && triggered(order, snapshot);
  }

  private boolean isStandaloneUserStopLimit(OrderEntity order) {
    return order.getOrderOrigin() == OrderOrigin.USER
        && order.getProtectionType() == null
        && order.getContingencyGroupId() == null
        && order.getParentOrderId() == null
        && order.getParentPositionId() == null;
  }

  private boolean isAwaitingStopLimitActivation(OrderEntity order) {
    return order.getOrderType() == OrderType.STOP_LIMIT
        && order.getStatus() == OrderStatus.PENDING_ACTIVATION;
  }

  private boolean stopLimitTriggered(OrderEntity order, ExecutableMarketSnapshot snapshot) {
    BigDecimal trigger = order.getTriggerPrice();
    return trigger != null && (order.getSide() == OrderSide.BUY
        ? snapshot.last().compareTo(trigger) >= 0
        : snapshot.last().compareTo(trigger) <= 0);
  }

  private boolean limitMarketable(OrderEntity order, ExecutableMarketSnapshot snapshot) {
    BigDecimal price = currentPrice(order);
    return price != null && (order.getSide() == OrderSide.BUY
        ? snapshot.ask().compareTo(price) <= 0
        : snapshot.bid().compareTo(price) >= 0);
  }

  private boolean triggered(OrderEntity order, ExecutableMarketSnapshot snapshot) {
    if (order.getOrderType() == OrderType.LIMIT) {
      return limitMarketable(order, snapshot);
    }
    if (order.getOrderType() == OrderType.STOP_MARKET) {
      BigDecimal trigger = order.getTriggerPrice();
      return trigger != null && (order.getSide() == OrderSide.BUY
          ? snapshot.last().compareTo(trigger) >= 0
          : snapshot.last().compareTo(trigger) <= 0);
    }
    return false;
  }

  private FullFillExecutionPath executionPath(
      OrderEntity order,
      boolean immediateStopLimit
  ) {
    if (immediateStopLimit) {
      return FullFillExecutionPath.IMMEDIATE_LIMIT;
    }
    if (order.getOrderType() == OrderType.LIMIT || order.getOrderType() == OrderType.STOP_LIMIT) {
      return FullFillExecutionPath.RESTING_LIMIT;
    }
    return FullFillExecutionPath.TRIGGERED_STOP_MARKET;
  }

  private CreateOrderRequest executionIntent(OrderEntity order, BigDecimal baseQuantity) {
    boolean stopLimit = order.getOrderType() == OrderType.STOP_LIMIT;
    return new CreateOrderRequest(
        order.getAccountId(), order.getSymbol(), order.getSide(),
        stopLimit ? OrderType.LIMIT : order.getOrderType(),
        baseQuantity, currentPrice(order), null, null,
        order.getIdempotencyKey(), order.getClientOrderId(), baseQuantity, currentPrice(order),
        1, PositionSide.BOTH, QuantityUnit.BASE, MarginMode.CASH,
        stopLimit ? null : order.getTriggerPrice(), order.getOrderType() == OrderType.STOP_MARKET
            ? TriggerPriceType.LAST_PRICE : null, Boolean.TRUE.equals(order.getReduceOnly()),
        List.of(), order.getTimeInForce(), Boolean.TRUE.equals(order.getPostOnly()),
        null, null, null);
  }

  private List<OrderEntity> requireGroup(List<OrderEntity> group) {
    return OcoGroupValidator.requireValid(group);
  }

  private OrderEntity holdOwner(List<OrderEntity> group) {
    UUID ownerId = group.getFirst().getHoldOwnerOrderId();
    if (ownerId == null || group.stream().anyMatch(order -> !ownerId.equals(order.getHoldOwnerOrderId()))) {
      throw new BusinessException("OCO_GROUP_INCOMPLETE", "OCO hold owner is inconsistent");
    }
    return group.stream().filter(order -> ownerId.equals(order.getId())).findFirst()
        .orElseThrow(() -> new BusinessException("OCO_GROUP_INCOMPLETE", "OCO hold owner is missing"));
  }

  private BigDecimal canonicalQuantity(OrderEntity order) {
    BigDecimal baseQuantity = order.getBaseQuantity();
    if (baseQuantity == null || baseQuantity.signum() <= 0) {
      throw new BusinessException(
          "BAD_QUANTITY",
          "Pending P0 Spot order requires a positive canonical base quantity");
    }
    return baseQuantity;
  }

  private BigDecimal currentPrice(OrderEntity order) {
    return order.getPrice() != null ? order.getPrice() : order.getRequestedPrice();
  }
}
