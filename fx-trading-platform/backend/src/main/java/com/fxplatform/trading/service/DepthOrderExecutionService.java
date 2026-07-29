package com.fxplatform.trading.service;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoBookLevel;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.DemoExecutionPolicyProvider;
import com.fxplatform.execution.DemoFillIdentity;
import com.fxplatform.execution.DemoMatchFill;
import com.fxplatform.execution.DemoMatchingEngine;
import com.fxplatform.execution.DemoMatchingMode;
import com.fxplatform.execution.DemoMatchingRequest;
import com.fxplatform.execution.DemoMatchingResult;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.ExecutableMarketSnapshots;
import com.fxplatform.execution.ExecutableMarketTimeAuthority;
import com.fxplatform.execution.WallClockExecutableMarketTimeAuthority;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.repository.TradeRepository;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Pure DEPTH matching, quote conversion, hold planning, and whole-Tick replay gating. */
@Service
public class DepthOrderExecutionService {

  private static final int MONEY_SCALE = 8;
  private static final BigDecimal MONEY_UNIT = new BigDecimal("0.00000001");
  private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE);
  private static final PlanSeal PLAN_SEAL = new PlanSeal();
  private static final RiskPricingAuthority RISK_PRICING_AUTHORITY =
      new RiskPricingAuthority();

  private final DemoExecutionPolicyProvider policyProvider;
  private final TradeRepository tradeRepository;
  private final OrderFillService orderFillService;
  private final OrderEventService orderEventService;
  private final ExecutableMarketTimeAuthority timeAuthority;
  private final DemoMatchingEngine matchingEngine = new DemoMatchingEngine();

  @Autowired
  public DepthOrderExecutionService(
      DemoExecutionPolicyProvider policyProvider,
      TradeRepository tradeRepository,
      OrderFillService orderFillService,
      OrderEventService orderEventService,
      ExecutableMarketTimeAuthority timeAuthority
  ) {
    this.policyProvider = Objects.requireNonNull(policyProvider, "policyProvider");
    this.tradeRepository = Objects.requireNonNull(tradeRepository, "tradeRepository");
    this.orderFillService = Objects.requireNonNull(orderFillService, "orderFillService");
    this.orderEventService = Objects.requireNonNull(orderEventService, "orderEventService");
    this.timeAuthority = Objects.requireNonNull(timeAuthority, "timeAuthority");
  }

  DepthOrderExecutionService(
      DemoExecutionPolicyProvider policyProvider,
      TradeRepository tradeRepository,
      OrderFillService orderFillService,
      OrderEventService orderEventService
  ) {
    this(
        policyProvider,
        tradeRepository,
        orderFillService,
        orderEventService,
        new WallClockExecutableMarketTimeAuthority());
  }

  DepthOrderExecutionService(
      DemoExecutionPolicyProvider policyProvider,
      TradeRepository tradeRepository,
      OrderFillService orderFillService,
      OrderEventService orderEventService,
      Clock clock
  ) {
    this(
        policyProvider,
        tradeRepository,
        orderFillService,
        orderEventService,
        new WallClockExecutableMarketTimeAuthority(clock));
  }

  public DemoExecutionPolicy currentPolicy() {
    return Objects.requireNonNull(policyProvider.current(), "current demo execution policy");
  }

  public boolean isDepth(DemoExecutionPolicy policy) {
    return policy != null && policy.matchingMode() == DemoMatchingMode.DEPTH;
  }

  /** Prevents a transaction selected for SIMPLE from committing after policy authority changes. */
  public void requireCurrentSimplePolicy(DemoExecutionPolicy policy) {
    if (policy == null
        || policy.matchingMode() != DemoMatchingMode.SIMPLE
        || policy != currentPolicy()) {
      throw new StalePolicyException(
          "SIMPLE matching must use the current policy provider instance");
    }
  }

  private void requireCurrentPolicy(DemoExecutionPolicy policy) {
    if (policy != currentPolicy()) {
      throw new StalePolicyException(
          "DEPTH matching must use the current policy provider instance");
    }
  }

  public DepthMatchPlan prepare(
      DemoExecutionPolicy policy,
      String symbol,
      ProductType productType,
      OrderSide side,
      OrderType executableType,
      TimeInForce timeInForce,
      BigDecimal remainingBaseQuantity,
      BigDecimal limitPrice,
      boolean placementPostOnly,
      LiquidityRole role,
      ExecutableMarketSnapshot snapshot
  ) {
    return prepare(
        policy,
        symbol,
        productType,
        side,
        executableType,
        executableType,
        timeInForce,
        remainingBaseQuantity,
        limitPrice,
        placementPostOnly,
        role,
        snapshot);
  }

  public DepthMatchPlan prepare(
      DemoExecutionPolicy policy,
      String symbol,
      ProductType productType,
      OrderSide side,
      OrderType sourceOrderType,
      OrderType executableType,
      TimeInForce timeInForce,
      BigDecimal remainingBaseQuantity,
      BigDecimal limitPrice,
      boolean placementPostOnly,
      LiquidityRole role,
      ExecutableMarketSnapshot snapshot
  ) {
    requireCurrentPolicy(policy);
    requireDepthPolicy(policy);
    validateSourceExecutableMapping(sourceOrderType, executableType);
    if (executableType == OrderType.MARKET && role != LiquidityRole.TAKER) {
      throw new IllegalArgumentException("MARKET DEPTH execution must use TAKER liquidity");
    }
    ExecutableMarketSnapshots.requireComplete(symbol, productType, snapshot);
    validateSnapshotNumeric(snapshot);
    if (!snapshot.asOf().isBefore(snapshot.expiresAt())) {
      throw new BusinessException(
          ErrorCode.MARKET_BUNDLE_INCOMPLETE,
          "Executable market snapshot must have a positive freshness window");
    }
    if (!timeAuthority.currentTime(snapshot).isBefore(snapshot.expiresAt())) {
      throw new BusinessException(
          ErrorCode.MARKET_DATA_STALE,
          "Executable market snapshot expired");
    }
    validateBoundRequest(
        policy,
        side,
        executableType,
        remainingBaseQuantity,
        limitPrice,
        true);
    DemoMatchingResult matching = matchingEngine.match(new DemoMatchingRequest(
        symbol,
        side,
        executableType,
        timeInForce,
        remainingBaseQuantity,
        limitPrice,
        policy,
        placementPostOnly,
        role,
        null));
    if (placementPostOnly && matching.terminalOrWorkingStatus()
        == com.fxplatform.trading.enums.OrderStatus.REJECTED) {
      throw new BusinessException(
          ErrorCode.POST_ONLY_WOULD_TAKE,
          "Post Only order would consume configured DEPTH liquidity");
    }
    if (timeInForce == TimeInForce.FOK && matching.remainingQuantity().signum() > 0) {
      throw new BusinessException(
          ErrorCode.FOK_NOT_FILLABLE,
          "FOK order is not fillable by eligible DEPTH liquidity in this Tick");
    }
    validateMatching(matching);
    validatePlanQuantity(remainingBaseQuantity, matching);
    validatePersistentFills(matching);
    return new DepthMatchPlan(
        policy,
        snapshot,
        symbol,
        productType,
        side,
        sourceOrderType,
        executableType,
        timeInForce,
        remainingBaseQuantity,
        limitPrice,
        placementPostOnly,
        role,
        matching,
        false,
        PLAN_SEAL);
  }

  /** Creates a sealed no-fill plan without exposing a STOP order to the executable matcher. */
  public DepthMatchPlan preparePendingStop(
      DemoExecutionPolicy policy,
      String symbol,
      ProductType productType,
      OrderSide side,
      OrderType sourceOrderType,
      TimeInForce timeInForce,
      BigDecimal remainingBaseQuantity,
      BigDecimal limitPrice,
      ExecutableMarketSnapshot snapshot
  ) {
    requireCurrentPolicy(policy);
    requireDepthPolicy(policy);
    if (sourceOrderType != OrderType.STOP_MARKET
        && sourceOrderType != OrderType.STOP_LIMIT) {
      throw new IllegalArgumentException(
          "Pending DEPTH plan requires STOP_MARKET or STOP_LIMIT source order type");
    }
    if (timeInForce != TimeInForce.GTC) {
      throw new IllegalArgumentException("Pending DEPTH stop plan requires GTC");
    }
    OrderType executableType = sourceOrderType == OrderType.STOP_MARKET
        ? OrderType.MARKET
        : OrderType.LIMIT;
    validateSourceExecutableMapping(sourceOrderType, executableType);
    ExecutableMarketSnapshots.requireComplete(symbol, productType, snapshot);
    validateSnapshotNumeric(snapshot);
    if (!snapshot.asOf().isBefore(snapshot.expiresAt())) {
      throw new BusinessException(
          ErrorCode.MARKET_BUNDLE_INCOMPLETE,
          "Executable market snapshot must have a positive freshness window");
    }
    if (!timeAuthority.currentTime(snapshot).isBefore(snapshot.expiresAt())) {
      throw new BusinessException(
          ErrorCode.MARKET_DATA_STALE,
          "Executable market snapshot expired");
    }
    validateBoundRequest(
        policy,
        side,
        executableType,
        remainingBaseQuantity,
        limitPrice,
        false);
    DemoMatchingResult pending = new DemoMatchingResult(
        List.of(),
        BigDecimal.ZERO,
        remainingBaseQuantity,
        OrderStatus.PENDING);
    validateMatching(pending);
    validatePlanQuantity(remainingBaseQuantity, pending);
    return new DepthMatchPlan(
        policy,
        snapshot,
        symbol,
        productType,
        side,
        sourceOrderType,
        executableType,
        timeInForce,
        remainingBaseQuantity,
        limitPrice,
        false,
        LiquidityRole.TAKER,
        pending,
        true,
        PLAN_SEAL);
  }

  /** Rechecks freshness after callers acquire their authority locks and before any write. */
  public void requireFresh(DepthMatchPlan plan) {
    if (plan == null) {
      throw new BusinessException(
          ErrorCode.MARKET_BUNDLE_INCOMPLETE,
          "DEPTH match plan is required");
    }
    ExecutableMarketSnapshot snapshot = plan.snapshot();
    ExecutableMarketSnapshots.requireComplete(plan.symbol(), plan.productType(), snapshot);
    validateSnapshotNumeric(snapshot);
    if (!snapshot.asOf().isBefore(snapshot.expiresAt())) {
      throw new BusinessException(
          ErrorCode.MARKET_BUNDLE_INCOMPLETE,
          "Executable market snapshot must have a positive freshness window");
    }
    if (!timeAuthority.currentTime(snapshot).isBefore(snapshot.expiresAt())) {
      throw new BusinessException(
          ErrorCode.MARKET_DATA_STALE,
          "Executable market snapshot expired");
    }
  }

  /** Rechecks every volatile plan authority after callers acquire their financial locks. */
  public void requireApplicable(DepthMatchPlan plan) {
    if (plan == null) {
      requireFresh(null);
      return;
    }
    DepthMatchPlan requiredPlan = requirePlan(plan, plan.productType());
    requireCurrentPolicyForApply(requiredPlan.policy());
    requireFresh(requiredPlan);
  }

  /** Binds every persisted match quantity to the instrument's effective storage step. */
  public void requireQuantityStep(DepthMatchPlan plan, BigDecimal storageStep) {
    if (storageStep == null
        || storageStep.signum() <= 0
        || !isExactlyRepresentableAsNumeric(storageStep, 12, 4)) {
      throw invalidInstrumentRules(
          "DEPTH effective storage step must be positive and fit NUMERIC(12,4)");
    }
    if (plan == null) {
      throw invalidInstrumentRules("DEPTH match plan is required for step validation");
    }
    DepthMatchPlan requiredPlan = requirePlan(plan, plan.productType());
    requireStepAligned(
        requiredPlan.remainingBaseQuantity(),
        storageStep,
        "DEPTH requested quantity must align with the effective storage step");
    DemoMatchingResult matching = requiredPlan.matchingResult();
    requireStepAligned(
        matching.filledQuantity(),
        storageStep,
        "DEPTH filled quantity must align with the effective storage step");
    requireStepAligned(
        matching.remainingQuantity(),
        storageStep,
        "DEPTH remaining quantity must align with the effective storage step");
    for (DemoMatchFill fill : matching.fills()) {
      requireStepAligned(
          fill.quantity(),
          storageStep,
          "DEPTH fill quantity must align with the effective storage step");
    }
    BigDecimal cap = requiredPlan.policy().maxFillQuantityPerTick();
    if (cap != null) {
      requireStepAligned(
          cap,
          storageStep,
          "DEPTH max fill quantity per Tick must align with the effective storage step");
    }
  }

  public boolean tickAlreadyApplied(OrderEntity lockedOrder, ExecutableMarketSnapshot snapshot) {
    if (lockedOrder == null || lockedOrder.getId() == null) {
      throw new IllegalArgumentException("locked order id is required");
    }
    String ordinalZero = DemoFillIdentity.forSnapshot(lockedOrder.getId(), snapshot, 0);
    return tradeRepository.findByOrderIdAndFillIdentity(lockedOrder.getId(), ordinalZero)
        .isPresent();
  }

  /** Applies one preplanned DEPTH Tick while the caller holds every financial authority lock. */
  @Transactional(propagation = Propagation.MANDATORY)
  public DepthExecutionOutcome applyLocked(
      OrderEntity lockedOrder,
      OrderEntity lockedHoldOwner,
      TradingAccountEntity lockedAccount,
      DepthMatchPlan match,
      DepthHoldPlan holds,
      boolean parentTerminalAfterBatch
  ) {
    if (match == null) {
      throw new IllegalArgumentException("DEPTH match plan is required");
    }
    DepthMatchPlan requiredPlan = requirePlan(match, match.productType());
    DemoMatchingResult matching = validateMatching(requiredPlan.matchingResult());
    validatePlanQuantity(requiredPlan, matching);
    validateBoundFills(requiredPlan, matching);
    validateLockedIdentityAuthority(
        lockedOrder, lockedHoldOwner, lockedAccount, requiredPlan);

    List<DemoMatchFill> fills = matching.fills();
    if (!fills.isEmpty()) {
      String ordinalZero = DemoFillIdentity.forSnapshot(
          lockedOrder.getId(), requiredPlan.snapshot(), 0);
      if (tradeRepository.findByOrderIdAndFillIdentity(lockedOrder.getId(), ordinalZero)
          .isPresent()) {
        return executionOutcome(lockedOrder, lockedHoldOwner, 0, true);
      }
    }

    requireCurrentPolicyForApply(requiredPlan.policy());
    requireFresh(requiredPlan);
    validateLockedApplicationSchedule(
        lockedOrder, lockedHoldOwner, requiredPlan, holds, matching);
    if (fills.isEmpty()) {
      return executionOutcome(lockedOrder, lockedHoldOwner, 0, false);
    }
    for (int ordinal = 1; ordinal < fills.size(); ordinal++) {
      String identity = DemoFillIdentity.forSnapshot(
          lockedOrder.getId(), requiredPlan.snapshot(), ordinal);
      if (tradeRepository.findByOrderIdAndFillIdentity(lockedOrder.getId(), identity)
          .isPresent()) {
        throw new BusinessException(
            ErrorCode.FILL_IDENTITY_CONFLICT,
            "A later DEPTH fill identity exists without snapshot ordinal zero");
      }
    }

    for (int ordinal = 0; ordinal < fills.size(); ordinal++) {
      DemoMatchFill fill = fills.get(ordinal);
      BigDecimal expectedRemaining = lockedOrder.getRemainingQuantity().subtract(fill.quantity());
      BigDecimal expectedHold = holds.holdAfterEachFill().get(ordinal);
      OrderStatus fromStatus = lockedOrder.getStatus();
      String identity = DemoFillIdentity.forSnapshot(
          lockedOrder.getId(), requiredPlan.snapshot(), ordinal);
      boolean terminalForParent = parentTerminalAfterBatch && ordinal == fills.size() - 1;

      OrderEntity applied = orderFillService.applyFill(
          lockedOrder,
          lockedHoldOwner,
          lockedAccount,
          fill,
          identity,
          requiredPlan.snapshot(),
          requiredPlan.policy(),
          expectedHold,
          terminalForParent);
      if (applied != lockedOrder) {
        throw new IllegalStateException(
            "DEPTH fill service must mutate and return the locked order authority");
      }
      OrderStatus expectedStatus = expectedRemaining.signum() == 0
          ? OrderStatus.FILLED
          : OrderStatus.PARTIALLY_FILLED;
      if (lockedOrder.getRemainingQuantity() == null
          || lockedOrder.getRemainingQuantity().compareTo(expectedRemaining) != 0
          || lockedOrder.getStatus() != expectedStatus
          || lockedHoldOwner.getHoldAmount() == null
          || lockedHoldOwner.getHoldAmount().compareTo(expectedHold) != 0) {
        throw new IllegalStateException(
            "DEPTH fill result does not match its sealed remaining/status/hold schedule");
      }
      String eventType = expectedStatus == OrderStatus.FILLED
          ? "ORDER_FILLED"
          : "ORDER_PARTIALLY_FILLED";
      orderEventService.record(
          lockedOrder.getId(),
          eventType,
          fromStatus,
          expectedStatus,
          null,
          "DEPTH snapshot fill ordinal " + ordinal + " applied");
    }
    return executionOutcome(lockedOrder, lockedHoldOwner, fills.size(), false);
  }

  private void requireCurrentPolicyForApply(DemoExecutionPolicy plannedPolicy) {
    DemoExecutionPolicy current = policyProvider.current();
    if (current == null || plannedPolicy != current) {
      throw new BusinessException(
          ErrorCode.MARKET_DATA_STALE,
          "DEPTH execution policy changed after planning");
    }
  }

  private static void validateLockedIdentityAuthority(
      OrderEntity lockedOrder,
      OrderEntity lockedHoldOwner,
      TradingAccountEntity lockedAccount,
      DepthMatchPlan plan
  ) {
    if (lockedOrder == null || lockedOrder.getId() == null) {
      throw new IllegalArgumentException("Locked DEPTH order id is required");
    }
    if (lockedHoldOwner == null || lockedHoldOwner.getId() == null) {
      throw new IllegalArgumentException("Locked DEPTH hold owner id is required");
    }
    if (lockedAccount == null || lockedAccount.getId() == null
        || !Objects.equals(lockedOrder.getAccountId(), lockedAccount.getId())
        || !Objects.equals(lockedHoldOwner.getAccountId(), lockedAccount.getId())) {
      throw new IllegalArgumentException(
          "Locked DEPTH order, hold owner, and account authority do not match");
    }
    if (plan.productType() == ProductType.LINEAR_PERP && lockedHoldOwner != lockedOrder) {
      throw new IllegalArgumentException(
          "Perpetual DEPTH order must own its locked hold");
    }
    if (lockedOrder.getSymbol() == null
        || !lockedOrder.getSymbol().trim().equalsIgnoreCase(plan.symbol())
        || lockedOrder.getProductType() != plan.productType()
        || lockedOrder.getSide() != plan.side()
        || lockedOrder.getOrderType() != plan.sourceOrderType()) {
      throw new IllegalArgumentException(
          "Locked DEPTH order identity does not match its sealed plan");
    }
  }

  private static void validateLockedApplicationSchedule(
      OrderEntity lockedOrder,
      OrderEntity lockedHoldOwner,
      DepthMatchPlan plan,
      DepthHoldPlan holds,
      DemoMatchingResult matching
  ) {
    if (lockedOrder.getRemainingQuantity() == null
        || lockedOrder.getRemainingQuantity().compareTo(plan.remainingBaseQuantity()) != 0) {
      throw new IllegalArgumentException(
          "Locked DEPTH order remaining quantity does not match its sealed plan");
    }
    if (holds == null
        || holds.holdAfterEachFill().size() != matching.fills().size()) {
      throw new IllegalArgumentException(
          "DEPTH hold schedule must contain exactly one H1 for every fill");
    }
    if (lockedHoldOwner.getHoldAmount() == null
        || lockedHoldOwner.getHoldAmount().compareTo(holds.initialHold()) != 0) {
      throw new IllegalArgumentException(
          "Locked DEPTH hold does not match the planned initial hold");
    }
    BigDecimal remaining = plan.remainingBaseQuantity();
    for (int ordinal = 0; ordinal < matching.fills().size(); ordinal++) {
      remaining = remaining.subtract(matching.fills().get(ordinal).quantity());
      BigDecimal h1 = holds.holdAfterEachFill().get(ordinal);
      if ((remaining.signum() > 0 && h1.signum() <= 0)
          || (remaining.signum() == 0 && h1.signum() != 0)) {
        throw new IllegalArgumentException(
            "Every DEPTH H1 must be positive for a remainder and zero only at terminal fill");
      }
    }
  }

  private static DepthExecutionOutcome executionOutcome(
      OrderEntity order,
      OrderEntity holdOwner,
      int newlyAppliedFillCount,
      boolean replayed
  ) {
    BigDecimal remaining = Objects.requireNonNull(
        order.getRemainingQuantity(), "locked order remainingQuantity");
    BigDecimal remainingHold = Objects.requireNonNull(
        holdOwner.getHoldAmount(), "locked hold owner holdAmount");
    return new DepthExecutionOutcome(
        order,
        newlyAppliedFillCount,
        remaining,
        remainingHold,
        replayed);
  }

  public SpotDepthConversion convertSpotMarketBuy(
      BigDecimal quoteBudget,
      BigDecimal storageStep,
      DemoExecutionPolicy policy
  ) {
    requireCurrentPolicy(policy);
    requirePositive(quoteBudget, "quote budget");
    quoteBudget = requireOrderHold(
        quoteBudget,
        "Spot DEPTH quote budget exceeds NUMERIC(24,8)");
    requirePositive(storageStep, "storage step");
    if (!isExactlyRepresentableAsNumeric(storageStep, 12, 4)) {
      throw invalidInstrumentRules(
          "Spot DEPTH storage step exceeds NUMERIC(12,4)");
    }
    requireDepthPolicy(policy);
    validateDepthCap(policy.maxFillQuantityPerTick());
    List<DemoBookLevel> asks = validatedAsks(policy, storageStep);
    BigDecimal executableQuantity = asks.stream()
        .map(DemoBookLevel::quantity)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigInteger maximumSteps = executableQuantity
        .divide(storageStep, 0, RoundingMode.FLOOR)
        .toBigIntegerExact();
    if (maximumSteps.signum() == 0) {
      throw new BusinessException(
          ErrorCode.QUANTITY_CONVERTS_TO_ZERO,
          "depth cannot produce one storage-compatible base quantity step");
    }

    BigInteger low = BigInteger.ONE;
    BigInteger high = maximumSteps;
    BigInteger best = BigInteger.ZERO;
    while (low.compareTo(high) <= 0) {
      BigInteger middle = low.add(high).shiftRight(1);
      BigDecimal quantity = storageStep.multiply(new BigDecimal(middle));
      BigDecimal spend = projectedSpotBuySpend(
          quantity,
          asks,
          policy.takerFeeRate(),
          policy.maxFillQuantityPerTick(),
          storageStep);
      if (spend.compareTo(quoteBudget) <= 0) {
        best = middle;
        low = middle.add(BigInteger.ONE);
      } else {
        high = middle.subtract(BigInteger.ONE);
      }
    }
    if (best.signum() == 0) {
      throw new BusinessException(
          ErrorCode.QUANTITY_CONVERTS_TO_ZERO,
          "quote budget cannot buy one storage-compatible DEPTH quantity step");
    }
    BigDecimal baseQuantity = storageStep.multiply(new BigDecimal(best));
    return new SpotDepthConversion(
        baseQuantity,
        projectedSpotBuySpend(
            baseQuantity,
            asks,
            policy.takerFeeRate(),
            policy.maxFillQuantityPerTick(),
            storageStep));
  }

  public DepthHoldPlan planSpot(BigDecimal currentHold, DepthMatchPlan plan) {
    requireFresh(plan);
    DepthMatchPlan requiredPlan = requirePlan(plan, ProductType.CRYPTO_SPOT);
    OrderSide side = requiredPlan.side();
    requireNonNegative(currentHold, "current Spot hold");
    requireOrderHold(currentHold, "Current Spot hold exceeds NUMERIC(24,8)");
    DemoMatchingResult requiredMatching = validateMatching(requiredPlan.matchingResult());
    validatePlanQuantity(requiredPlan, requiredMatching);
    validateBoundFills(requiredPlan, requiredMatching);
    if (isZeroFillIoc(requiredPlan, requiredMatching)) {
      return new DepthHoldPlan(ZERO_MONEY, List.of());
    }
    List<BigDecimal> spends = new ArrayList<>();
    for (DemoMatchFill fill : requiredMatching.fills()) {
      validateFill(fill);
      BigDecimal spend = side == OrderSide.BUY
          ? money(money(fill.quantity().multiply(fill.price()))
              .add(money(fill.quantity().multiply(fill.price()).multiply(fill.feeRate()))))
          : money(fill.quantity());
      spends.add(requireOrderHold(spend, "Spot fill spend exceeds NUMERIC(24,8)"));
    }
    BigDecimal retainedTail = spotTailHold(requiredPlan, currentHold, spends);
    DepthHoldPlan holdPlan = backwardHoldPlan(currentHold, retainedTail, spends);
    if (side == OrderSide.BUY
        && requiredPlan.sourceOrderType() == OrderType.MARKET
        && holdPlan.initialHold().compareTo(currentHold) > 0) {
      throw orderHoldInvalid(
          "Spot MARKET BUY DEPTH execution exceeds its public quote budget");
    }
    return holdPlan;
  }

  public DepthHoldPlan planPerpetual(
      PerpetualOrderRiskService.DepthPlanningAuthority lockedAuthority,
      PerpetualOrderRiskService.OrderRisk risk,
      BigDecimal currentHold,
      DepthMatchPlan plan
  ) {
    requireFresh(plan);
    DepthMatchPlan requiredPlan = requirePlan(plan, ProductType.LINEAR_PERP);
    requireNonNegative(currentHold, "current Perpetual hold");
    requireOrderHold(currentHold, "Current Perpetual hold exceeds NUMERIC(24,8)");
    DemoMatchingResult requiredMatching = validateMatching(requiredPlan.matchingResult());
    validatePlanQuantity(requiredPlan, requiredMatching);
    validateBoundFills(requiredPlan, requiredMatching);
    if (isZeroFillIoc(requiredPlan, requiredMatching)) {
      return new DepthHoldPlan(ZERO_MONEY, List.of());
    }

    OrderSide side = requiredPlan.side();
    BigDecimal authorityMark = requiredPlan.snapshot().mark();
    DemoExecutionPolicy policy = requiredPlan.policy();
    if (lockedAuthority == null) {
      throw new IllegalArgumentException("Locked Perpetual planning authority is required");
    }
    int leverage = lockedAuthority.leverage();
    if (leverage <= 0) {
      throw new IllegalArgumentException("Perpetual leverage must be positive");
    }
    requirePositive(authorityMark, "authority mark");
    requireDepthPolicy(policy);
    if (risk == null) {
      throw new IllegalArgumentException("Perpetual order risk is required");
    }
    if (risk.leverage() != leverage) {
      throw new IllegalArgumentException("Perpetual leverage does not match locked order risk");
    }
    if (!risk.claimForPlanning(
        lockedAuthority,
        requiredPlan.symbol(),
        requiredPlan.side(),
        requiredPlan.remainingBaseQuantity(),
        requiredPlan.sourceOrderType(),
        requiredPlan.limitPrice(),
        requiredPlan.snapshot(),
        perpetualRiskPricing(requiredPlan))) {
      throw new IllegalArgumentException(
          "Perpetual order risk does not match the bound DEPTH plan authority");
    }
    requireNonNegative(risk.closingBase(), "classified closing quantity");
    requireNonNegative(risk.openingBase(), "classified opening quantity");
    BigDecimal matchedQuantity = requiredMatching.fills().stream()
        .peek(DepthOrderExecutionService::validateFill)
        .map(DemoMatchFill::quantity)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    requireNonNegative(requiredMatching.remainingQuantity(), "matching remainder");
    if (matchedQuantity.compareTo(requiredMatching.filledQuantity()) != 0) {
      throw new IllegalArgumentException("matching filled quantity does not equal its fill list");
    }
    BigDecimal classifiedQuantity = risk.closingBase().add(risk.openingBase());
    BigDecimal matchingQuantity = matchedQuantity.add(requiredMatching.remainingQuantity());
    if (classifiedQuantity.compareTo(matchingQuantity) != 0) {
      throw new IllegalArgumentException(
          "Perpetual closing and opening classification does not cover the order remainder");
    }

    BigDecimal closingRemaining = risk.closingBase();
    BigDecimal openingRemaining = risk.openingBase();
    List<BigDecimal> slices = new ArrayList<>();
    for (DemoMatchFill fill : requiredMatching.fills()) {
      BigDecimal closingQuantity = fill.quantity().min(closingRemaining);
      BigDecimal openingQuantity = fill.quantity().subtract(closingQuantity);
      if (openingQuantity.compareTo(openingRemaining) > 0) {
        throw new IllegalArgumentException("Perpetual fill exceeds classified opening quantity");
      }
      closingRemaining = closingRemaining.subtract(closingQuantity);
      openingRemaining = openingRemaining.subtract(openingQuantity);

      BigDecimal openingMargin = openingMargin(openingQuantity, fill.price(), leverage);
      BigDecimal adverseLoss = adverseCloseLoss(
          side, closingQuantity, authorityMark, fill.price());
      BigDecimal fee = money(fill.quantity().multiply(fill.price()).multiply(fill.feeRate()));
      BigDecimal slice = money(openingMargin.add(adverseLoss).add(fee));
      slices.add(requireOrderHold(
          nonzeroRiskUnit(fill.quantity(), slice),
          "Perpetual fill risk exceeds NUMERIC(24,8)"));
    }

    if (closingRemaining.add(openingRemaining)
        .compareTo(requiredMatching.remainingQuantity()) != 0) {
      throw new IllegalArgumentException(
          "Perpetual classified tail does not equal the matching remainder");
    }
    BigDecimal tail = perpetualTail(
        requiredPlan,
        side,
        leverage,
        authorityMark,
        closingRemaining,
        openingRemaining,
        requiredMatching.remainingQuantity().signum() == 0
            ? null
            : futureTailRiskPricing(requiredPlan));
    return backwardHoldPlan(currentHold, tail, slices);
  }

  /** Derives an unforgeable conservative risk authority from exact fills plus any future tail. */
  public PerpetualRiskPricing perpetualRiskPricing(DepthMatchPlan plan) {
    requireFresh(plan);
    DepthMatchPlan requiredPlan = requirePlan(plan, ProductType.LINEAR_PERP);
    OrderSide side = requiredPlan.side();
    DemoMatchingResult matching = validateMatching(requiredPlan.matchingResult());
    validatePlanQuantity(requiredPlan, matching);
    validateBoundFills(requiredPlan, matching);

    BigDecimal marginAndFeePrice = null;
    BigDecimal adverseClosePrice = null;
    BigDecimal feeRate = BigDecimal.ZERO;
    for (DemoMatchFill fill : matching.fills()) {
      validateFill(fill);
      marginAndFeePrice = marginAndFeePrice == null
          ? fill.price()
          : marginAndFeePrice.max(fill.price());
      adverseClosePrice = adverseClosePrice == null
          ? fill.price()
          : side == OrderSide.BUY
              ? adverseClosePrice.max(fill.price())
              : adverseClosePrice.min(fill.price());
      feeRate = feeRate.max(fill.feeRate());
    }
    if (matching.remainingQuantity().signum() > 0) {
      PerpetualRiskPricing tailPricing = futureTailRiskPricing(requiredPlan);
      marginAndFeePrice = marginAndFeePrice == null
          ? tailPricing.marginAndFeePrice()
          : marginAndFeePrice.max(tailPricing.marginAndFeePrice());
      adverseClosePrice = adverseClosePrice == null
          ? tailPricing.adverseClosePrice()
          : side == OrderSide.BUY
              ? adverseClosePrice.max(tailPricing.adverseClosePrice())
              : adverseClosePrice.min(tailPricing.adverseClosePrice());
      feeRate = feeRate.max(tailPricing.feeRate());
    }
    if (marginAndFeePrice == null || adverseClosePrice == null) {
      throw invalidInstrumentRules(
          "Perpetual DEPTH plan has no conservative risk price");
    }
    if (!isExactlyRepresentableAsNumeric(marginAndFeePrice, 24, 10)
        || !isExactlyRepresentableAsNumeric(adverseClosePrice, 24, 10)) {
      throw invalidInstrumentRules(
          "Perpetual DEPTH conservative price exceeds NUMERIC(24,10)");
    }
    return PerpetualRiskPricing.boundToPlan(
        marginAndFeePrice,
        adverseClosePrice,
        feeRate,
        requiredPlan,
        RISK_PRICING_AUTHORITY);
  }

  private static PerpetualRiskPricing futureTailRiskPricing(DepthMatchPlan requiredPlan) {
    DemoExecutionPolicy policy = requiredPlan.policy();
    OrderSide side = requiredPlan.side();
    FutureDepthTemplate template = futureDepthTemplate(requiredPlan);
    List<BigDecimal> eligiblePrices = template.levels().stream()
        .map(DemoBookLevel::price)
        .toList();
    BigDecimal marginAndFeePrice = eligiblePrices.stream()
        .max(BigDecimal::compareTo)
        .orElseThrow(() -> invalidInstrumentRules(
            "Perpetual DEPTH tail has no conservative price"));
    BigDecimal adverseClosePrice = side == OrderSide.BUY
        ? marginAndFeePrice
        : eligiblePrices.stream()
            .min(BigDecimal::compareTo)
            .orElseThrow(() -> invalidInstrumentRules(
                "Perpetual DEPTH tail has no adverse close price"));
    if (!isExactlyRepresentableAsNumeric(marginAndFeePrice, 24, 10)
        || !isExactlyRepresentableAsNumeric(adverseClosePrice, 24, 10)) {
      throw invalidInstrumentRules(
          "Perpetual DEPTH conservative price exceeds NUMERIC(24,10)");
    }
    return new PerpetualRiskPricing(
        marginAndFeePrice,
        adverseClosePrice,
        policy.makerFeeRate().max(policy.takerFeeRate()));
  }

  private static List<DemoBookLevel> validatedAsks(
      DemoExecutionPolicy policy,
      BigDecimal storageStep
  ) {
    if (policy.asks() == null || policy.asks().isEmpty()) {
      throw new BusinessException(
          ErrorCode.MARKET_BUNDLE_INCOMPLETE,
          "Spot MARKET BUY requires a non-empty DEPTH ask book");
    }
    List<DemoBookLevel> asks = new ArrayList<>(policy.asks());
    for (DemoBookLevel level : asks) {
      if (level == null) {
        throw invalidInstrumentRules("DEPTH level is required");
      }
      requirePositive(level.price(), "depth level price");
      requirePositive(level.quantity(), "depth level quantity");
      validateDepthLevel(level);
      if (level.quantity().remainder(storageStep).signum() != 0) {
        throw invalidInstrumentRules(
            "depth level quantity must align with the storage step");
      }
      if (money(storageStep.multiply(level.price())).signum() <= 0) {
        throw invalidInstrumentRules(
            "depth level must produce a positive gross amount for one storage step");
      }
    }
    asks.sort(Comparator.comparing(DemoBookLevel::price));
    return List.copyOf(asks);
  }

  private static BigDecimal projectedSpotBuySpend(
      BigDecimal requestedQuantity,
      List<DemoBookLevel> asks,
      BigDecimal feeRate,
      BigDecimal maxFillQuantityPerTick,
      BigDecimal storageStep
  ) {
    requireRate(feeRate, "Spot taker fee rate");
    BigDecimal bookQuantity = asks.stream()
        .map(DemoBookLevel::quantity)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal tickCapacity = bookQuantity;
    if (maxFillQuantityPerTick != null) {
      requirePositive(maxFillQuantityPerTick, "DEPTH max fill quantity per Tick");
      if (maxFillQuantityPerTick.remainder(storageStep).signum() != 0) {
        throw invalidInstrumentRules(
            "DEPTH max fill quantity per Tick must align with the storage step");
      }
      tickCapacity = tickCapacity.min(maxFillQuantityPerTick);
    }
    BigInteger fullTickCount = requestedQuantity
        .divide(tickCapacity, 0, RoundingMode.FLOOR)
        .toBigIntegerExact();
    BigDecimal fullTickQuantity = tickCapacity.multiply(new BigDecimal(fullTickCount));
    BigDecimal remainder = requestedQuantity.subtract(fullTickQuantity);
    BigDecimal fullTickSpend = projectedSingleTickSpotBuySpend(
        tickCapacity, asks, feeRate);
    BigDecimal remainderSpend = remainder.signum() == 0
        ? ZERO_MONEY
        : projectedSingleTickSpotBuySpend(remainder, asks, feeRate);
    return money(fullTickSpend.multiply(new BigDecimal(fullTickCount)).add(remainderSpend));
  }

  private static BigDecimal projectedSingleTickSpotBuySpend(
      BigDecimal requestedQuantity,
      List<DemoBookLevel> asks,
      BigDecimal feeRate
  ) {
    return projectedSingleTickSpotBuySpend(
        requestedQuantity, asks, feeRate, null);
  }

  private static BigDecimal projectedSingleTickSpotBuySpend(
      BigDecimal requestedQuantity,
      List<DemoBookLevel> asks,
      BigDecimal feeRate,
      BigDecimal conservativePrice
  ) {
    BigDecimal remaining = requestedQuantity;
    BigDecimal spend = ZERO_MONEY;
    for (DemoBookLevel level : asks) {
      if (remaining.signum() == 0) {
        break;
      }
      BigDecimal quantity = remaining.min(level.quantity());
      BigDecimal price = conservativePrice == null ? level.price() : conservativePrice;
      BigDecimal gross = money(quantity.multiply(price));
      BigDecimal fee = money(quantity.multiply(price).multiply(feeRate));
      spend = money(spend.add(gross).add(fee));
      remaining = remaining.subtract(quantity);
    }
    if (remaining.signum() != 0) {
      throw new IllegalArgumentException("depth cannot cover projected Spot base quantity");
    }
    return spend;
  }

  private static BigDecimal projectedFutureSpotBuySpend(
      BigDecimal requestedQuantity,
      FutureDepthTemplate template,
      BigDecimal feeRate,
      BigDecimal conservativePrice
  ) {
    requirePositive(requestedQuantity, "Spot future DEPTH quantity");
    requireRate(feeRate, "Spot future DEPTH fee rate");
    BigDecimal tickQuantity = template.tickQuantity();
    BigInteger fullTickCount = requestedQuantity
        .divide(tickQuantity, 0, RoundingMode.FLOOR)
        .toBigIntegerExact();
    BigDecimal fullTickQuantity = tickQuantity.multiply(new BigDecimal(fullTickCount));
    BigDecimal remainder = requestedQuantity.subtract(fullTickQuantity);
    BigDecimal fullTickSpend = projectedSingleTickSpotBuySpend(
        tickQuantity, template.levels(), feeRate, conservativePrice);
    BigDecimal fullTicksSpend = requireOrderHold(
        money(fullTickSpend.multiply(new BigDecimal(fullTickCount))),
        "Spot future full-Tick spend exceeds NUMERIC(24,8)");
    BigDecimal remainderSpend = remainder.signum() == 0
        ? ZERO_MONEY
        : projectedSingleTickSpotBuySpend(
            remainder, template.levels(), feeRate, conservativePrice);
    return requireOrderHold(
        money(fullTicksSpend.add(remainderSpend)),
        "Spot future spend exceeds NUMERIC(24,8)");
  }

  private static FutureDepthTemplate futureDepthTemplate(DepthMatchPlan plan) {
    BigDecimal remaining = plan.matchingResult().remainingQuantity();
    requirePositive(remaining, "DEPTH future-tail quantity");
    boolean limitLike = isLimitLike(plan.executableType());
    List<DemoBookLevel> eligible = new ArrayList<>();
    List<DemoBookLevel> configured = plan.side() == OrderSide.BUY
        ? plan.policy().asks()
        : plan.policy().bids();
    for (DemoBookLevel level : configured) {
      validateDepthLevel(level);
      if (!limitLike || isEligible(plan.side(), level.price(), plan.limitPrice())) {
        eligible.add(level);
      }
    }
    eligible.sort(levelComparator(plan.side()));

    BigDecimal target = remaining;
    BigDecimal cap = plan.policy().maxFillQuantityPerTick();
    if (cap != null) {
      target = target.min(cap);
    }
    if (eligible.isEmpty()) {
      BigDecimal fallback = limitLike
          ? plan.limitPrice()
          : plan.side() == OrderSide.BUY
              ? plan.snapshot().ask()
              : plan.snapshot().bid();
      requirePositive(fallback, "DEPTH future-tail fallback price");
      if (!isExactlyRepresentableAsNumeric(fallback, 24, 10)) {
        throw invalidInstrumentRules(
            "DEPTH future-tail fallback exceeds NUMERIC(24,10)");
      }
      return new FutureDepthTemplate(
          List.of(new DemoBookLevel(fallback, target)), target);
    }

    BigDecimal available = eligible.stream()
        .map(DemoBookLevel::quantity)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    target = target.min(available);
    List<DemoBookLevel> truncated = new ArrayList<>();
    BigDecimal needed = target;
    for (DemoBookLevel level : eligible) {
      if (needed.signum() == 0) {
        break;
      }
      BigDecimal quantity = needed.min(level.quantity());
      truncated.add(new DemoBookLevel(level.price(), quantity));
      needed = needed.subtract(quantity);
    }
    if (needed.signum() != 0 || truncated.isEmpty()) {
      throw invalidInstrumentRules("DEPTH future-tail template is incomplete");
    }
    return new FutureDepthTemplate(truncated, target);
  }

  private static BigDecimal projectedFuturePerpetualTail(
      DepthMatchPlan plan,
      OrderSide side,
      int leverage,
      BigDecimal authorityMark,
      BigDecimal closingQuantity,
      BigDecimal openingQuantity,
      FutureDepthTemplate template,
      PerpetualRiskPricing pricing
  ) {
    BigDecimal total = ZERO_MONEY;
    BigDecimal tickQuantity = template.tickQuantity();
    BigInteger fullClosingTicks = closingQuantity
        .divide(tickQuantity, 0, RoundingMode.FLOOR)
        .toBigIntegerExact();
    BigDecimal fullClosingQuantity = tickQuantity.multiply(
        new BigDecimal(fullClosingTicks));
    BigDecimal closingRemainder = closingQuantity.subtract(fullClosingQuantity);
    if (fullClosingTicks.signum() > 0) {
      BigDecimal oneClosingTick = projectedPerpetualTick(
          template.levels(),
          tickQuantity,
          tickQuantity,
          side,
          leverage,
          authorityMark,
          pricing);
      total = addProjectedHold(total, multiplyProjectedHold(
          oneClosingTick,
          fullClosingTicks,
          "Perpetual closing full-Tick risk exceeds NUMERIC(24,8)"));
    }

    BigDecimal openingRemaining = openingQuantity;
    if (closingRemainder.signum() > 0) {
      BigDecimal boundaryQuantity = tickQuantity.min(
          closingRemainder.add(openingRemaining));
      total = addProjectedHold(total, projectedPerpetualTick(
          template.levels(),
          boundaryQuantity,
          closingRemainder,
          side,
          leverage,
          authorityMark,
          pricing));
      openingRemaining = openingRemaining.subtract(
          boundaryQuantity.subtract(closingRemainder));
    }

    if (openingRemaining.signum() > 0) {
      BigInteger fullOpeningTicks = openingRemaining
          .divide(tickQuantity, 0, RoundingMode.FLOOR)
          .toBigIntegerExact();
      BigDecimal fullOpeningQuantity = tickQuantity.multiply(
          new BigDecimal(fullOpeningTicks));
      BigDecimal openingRemainder = openingRemaining.subtract(fullOpeningQuantity);
      if (fullOpeningTicks.signum() > 0) {
        BigDecimal oneOpeningTick = projectedPerpetualTick(
            template.levels(),
            tickQuantity,
            BigDecimal.ZERO,
            side,
            leverage,
            authorityMark,
            pricing);
        total = addProjectedHold(total, multiplyProjectedHold(
            oneOpeningTick,
            fullOpeningTicks,
            "Perpetual opening full-Tick risk exceeds NUMERIC(24,8)"));
      }
      if (openingRemainder.signum() > 0) {
        total = addProjectedHold(total, projectedPerpetualTick(
            template.levels(),
            openingRemainder,
            BigDecimal.ZERO,
            side,
            leverage,
            authorityMark,
            pricing));
      }
    }

    BigDecimal expected = closingQuantity.add(openingQuantity);
    if (expected.compareTo(plan.matchingResult().remainingQuantity()) != 0) {
      throw new IllegalArgumentException(
          "Perpetual future classification does not cover the bound DEPTH tail");
    }
    return total;
  }

  private static BigDecimal projectedPerpetualTick(
      List<DemoBookLevel> levels,
      BigDecimal requestedQuantity,
      BigDecimal closingQuantity,
      OrderSide side,
      int leverage,
      BigDecimal authorityMark,
      PerpetualRiskPricing pricing
  ) {
    BigDecimal remaining = requestedQuantity;
    BigDecimal closingRemaining = closingQuantity;
    BigDecimal risk = ZERO_MONEY;
    for (DemoBookLevel level : levels) {
      if (remaining.signum() == 0) {
        break;
      }
      BigDecimal fillQuantity = remaining.min(level.quantity());
      BigDecimal close = fillQuantity.min(closingRemaining);
      BigDecimal open = fillQuantity.subtract(close);
      BigDecimal openingMargin = openingMargin(
          open, pricing.marginAndFeePrice(), leverage);
      BigDecimal adverseLoss = adverseCloseLoss(
          side, close, authorityMark, pricing.adverseClosePrice());
      BigDecimal fee = money(fillQuantity
          .multiply(pricing.marginAndFeePrice())
          .multiply(pricing.feeRate()));
      BigDecimal slice = nonzeroRiskUnit(
          fillQuantity, money(openingMargin.add(adverseLoss).add(fee)));
      risk = addProjectedHold(risk, slice);
      remaining = remaining.subtract(fillQuantity);
      closingRemaining = closingRemaining.subtract(close);
    }
    if (remaining.signum() != 0 || closingRemaining.signum() != 0) {
      throw invalidInstrumentRules("DEPTH template cannot cover projected Perpetual Tick");
    }
    return risk;
  }

  private static BigDecimal multiplyProjectedHold(
      BigDecimal unitAmount,
      BigInteger multiplier,
      String message
  ) {
    return requireOrderHold(
        money(unitAmount.multiply(new BigDecimal(multiplier))), message);
  }

  private static BigDecimal addProjectedHold(BigDecimal left, BigDecimal right) {
    return requireOrderHold(
        money(left.add(right)),
        "Projected DEPTH hold exceeds NUMERIC(24,8)");
  }

  private static BigDecimal spotTailHold(
      DepthMatchPlan plan,
      BigDecimal currentHold,
      List<BigDecimal> spends
  ) {
    BigDecimal remaining = plan.matchingResult().remainingQuantity();
    if (remaining.signum() == 0) {
      return ZERO_MONEY;
    }
    if (plan.side() == OrderSide.SELL) {
      return requireOrderHold(
          remaining.setScale(MONEY_SCALE, RoundingMode.CEILING),
          "Spot sell tail exceeds NUMERIC(24,8)");
    }

    boolean limitLike = plan.executableType() == OrderType.LIMIT
        || plan.executableType() == OrderType.STOP_LIMIT;
    if (limitLike) {
      requirePositive(plan.limitPrice(), "Spot DEPTH limit price");
      return projectedFutureSpotBuySpend(
          remaining,
          futureDepthTemplate(plan),
          maxFeeRate(plan.policy()),
          plan.limitPrice());
    }

    BigDecimal projectedTail = projectedFutureSpotBuySpend(
        remaining,
        futureDepthTemplate(plan),
        maxFeeRate(plan.policy()),
        null);
    if (plan.sourceOrderType() == OrderType.STOP_MARKET) {
      return projectedTail;
    }
    BigDecimal batchSpend = spends.stream()
        .reduce(ZERO_MONEY, (left, right) -> money(left.add(right)));
    BigDecimal quoteBudgetAfterBatch = requireOrderHold(
        money(currentHold).subtract(batchSpend),
        "Spot MARKET BUY remaining quote budget exceeds NUMERIC(24,8)");
    if (quoteBudgetAfterBatch.compareTo(projectedTail) < 0) {
      throw orderHoldInvalid(
          "Spot MARKET BUY quote budget cannot fund its remaining DEPTH base quantity");
    }
    return projectedTail;
  }

  private static BigDecimal perpetualTail(
      DepthMatchPlan plan,
      OrderSide side,
      int leverage,
      BigDecimal authorityMark,
      BigDecimal closingRemaining,
      BigDecimal openingRemaining,
      PerpetualRiskPricing pricing
  ) {
    BigDecimal remaining = closingRemaining.add(openingRemaining);
    if (remaining.signum() == 0) {
      return ZERO_MONEY;
    }
    if (pricing == null) {
      throw new IllegalArgumentException("Perpetual DEPTH tail pricing is required");
    }
    requirePositive(pricing.marginAndFeePrice(), "Perpetual tail worst margin price");
    if (closingRemaining.signum() > 0) {
      requirePositive(pricing.adverseClosePrice(), "Perpetual tail adverse close price");
    }
    requireRate(pricing.feeRate(), "Perpetual tail fee rate");
    BigDecimal projected = projectedFuturePerpetualTail(
        plan,
        side,
        leverage,
        authorityMark,
        closingRemaining,
        openingRemaining,
        futureDepthTemplate(plan),
        pricing);
    return requireOrderHold(
        projected,
        "Perpetual tail risk exceeds NUMERIC(24,8)");
  }

  private static BigDecimal openingMargin(
      BigDecimal openingQuantity,
      BigDecimal price,
      int leverage
  ) {
    if (openingQuantity.signum() == 0) {
      return ZERO_MONEY;
    }
    requirePositive(price, "Perpetual opening price");
    return money(openingQuantity.multiply(price))
        .divide(BigDecimal.valueOf(leverage), MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal adverseCloseLoss(
      OrderSide side,
      BigDecimal closingQuantity,
      BigDecimal authorityMark,
      BigDecimal closePrice
  ) {
    if (closingQuantity.signum() == 0) {
      return ZERO_MONEY;
    }
    requirePositive(closePrice, "Perpetual close price");
    BigDecimal adverseMove = side == OrderSide.SELL
        ? authorityMark.subtract(closePrice)
        : closePrice.subtract(authorityMark);
    return money(closingQuantity.multiply(adverseMove.max(BigDecimal.ZERO)));
  }

  private static BigDecimal nonzeroRiskUnit(BigDecimal quantity, BigDecimal riskAmount) {
    return quantity.signum() > 0 && riskAmount.signum() == 0 ? MONEY_UNIT : riskAmount;
  }

  private static DepthHoldPlan backwardHoldPlan(
      BigDecimal currentHold,
      BigDecimal retainedTail,
      List<BigDecimal> spends
  ) {
    BigDecimal required = requireOrderHold(
        retainedTail, "DEPTH retained tail exceeds NUMERIC(24,8)");
    List<BigDecimal> holdAfterEachFill = new ArrayList<>(
        java.util.Collections.nCopies(spends.size(), ZERO_MONEY));
    for (int index = spends.size() - 1; index >= 0; index--) {
      holdAfterEachFill.set(index, required);
      required = requireOrderHold(
          money(requireOrderHold(
              spends.get(index),
              "DEPTH fill spend exceeds NUMERIC(24,8)").add(required)),
          "DEPTH backward hold exceeds NUMERIC(24,8)");
    }
    BigDecimal initial = requireOrderHold(
        requireOrderHold(currentHold, "Current DEPTH hold exceeds NUMERIC(24,8)")
            .max(required),
        "DEPTH initial hold exceeds NUMERIC(24,8)");
    return new DepthHoldPlan(
        initial,
        holdAfterEachFill);
  }

  private static DepthMatchPlan requirePlan(
      DepthMatchPlan plan,
      ProductType productType
  ) {
    if (plan == null
        || plan.seal != PLAN_SEAL
        || plan.policy() == null
        || plan.snapshot() == null
        || plan.productType() != productType
        || plan.snapshot().productType() != productType
        || plan.side() == null
        || plan.sourceOrderType() == null
        || plan.executableType() == null
        || plan.timeInForce() == null
        || plan.remainingBaseQuantity() == null
        || plan.remainingBaseQuantity().signum() <= 0
        || plan.role() == null) {
      throw new IllegalArgumentException(
          "complete bound " + productType + " DEPTH match plan is required");
    }
    requireDepthPolicy(plan.policy());
    validateSourceExecutableMapping(plan.sourceOrderType(), plan.executableType());
    ExecutableMarketSnapshots.requireComplete(
        plan.symbol(), plan.productType(), plan.snapshot());
    validateSnapshotNumeric(plan.snapshot());
    validateBoundRequest(
        plan.policy(),
        plan.side(),
        plan.executableType(),
        plan.remainingBaseQuantity(),
        plan.limitPrice(),
        false);
    DemoMatchingResult matching = validateMatching(plan.matchingResult());
    validatePlanQuantity(plan, matching);
    validatePersistentFills(matching);
    validateBoundFills(plan, matching);
    return plan;
  }

  private static void validatePlanQuantity(
      DepthMatchPlan plan,
      DemoMatchingResult matching
  ) {
    validatePlanQuantity(plan.remainingBaseQuantity(), matching);
  }

  private static void validatePlanQuantity(
      BigDecimal boundQuantity,
      DemoMatchingResult matching
  ) {
    BigDecimal matchingTotal = matching.filledQuantity().add(matching.remainingQuantity());
    if (matchingTotal.compareTo(boundQuantity) != 0) {
      throw new IllegalArgumentException(
          "matching filled quantity and remainder do not cover the bound plan quantity");
    }
  }

  private static DemoMatchingResult requireMatching(DemoMatchingResult matching) {
    if (matching == null || matching.fills() == null || matching.filledQuantity() == null
        || matching.remainingQuantity() == null || matching.terminalOrWorkingStatus() == null) {
      throw new IllegalArgumentException("complete matching result is required");
    }
    return matching;
  }

  private static DemoMatchingResult validateMatching(DemoMatchingResult matching) {
    DemoMatchingResult required = requireMatching(matching);
    requireNonNegative(required.filledQuantity(), "matching filled quantity");
    requireNonNegative(required.remainingQuantity(), "matching remainder");
    if (!isExactlyRepresentableAsNumeric(required.filledQuantity(), 12, 4)
        || !isExactlyRepresentableAsNumeric(required.remainingQuantity(), 12, 4)) {
      throw invalidInstrumentRules(
          "DEPTH matching quantities exceed NUMERIC(12,4)");
    }
    BigDecimal fillSum = required.fills().stream()
        .peek(DepthOrderExecutionService::validateFill)
        .map(DemoMatchFill::quantity)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (fillSum.compareTo(required.filledQuantity()) != 0) {
      throw new IllegalArgumentException(
          "matching filled quantity does not equal its fill list");
    }
    boolean hasRemainder = required.remainingQuantity().signum() > 0;
    boolean statusConsistent = switch (required.terminalOrWorkingStatus()) {
      case FILLED -> !hasRemainder;
      case PENDING -> hasRemainder && required.filledQuantity().signum() == 0;
      case PARTIALLY_FILLED -> hasRemainder && required.filledQuantity().signum() > 0;
      case CANCELLED -> hasRemainder;
      default -> false;
    };
    if (!statusConsistent) {
      throw new IllegalArgumentException(
          "matching status contradicts its filled quantity or remainder");
    }
    return required;
  }

  private static void validateBoundFills(
      DepthMatchPlan plan,
      DemoMatchingResult matching
  ) {
    validateBoundFills(
        plan.policy(),
        plan.side(),
        plan.executableType(),
        plan.limitPrice(),
        plan.role(),
        matching,
        false);
  }

  private static void validateBoundFills(
      DemoExecutionPolicy policy,
      OrderSide side,
      OrderType executableType,
      BigDecimal limitPrice,
      LiquidityRole role,
      DemoMatchingResult matching,
      boolean constructionBoundary
  ) {
    BigDecimal expectedFee = role == LiquidityRole.MAKER
        ? policy.makerFeeRate()
        : policy.takerFeeRate();
    BigDecimal previousPrice = null;
    for (DemoMatchFill fill : matching.fills()) {
      boolean coherent = fill.liquidityRole() == role
          && fill.feeRate().compareTo(expectedFee) == 0
          && (!isLimitLike(executableType)
              || isEligible(side, fill.price(), limitPrice));
      if (previousPrice != null) {
        coherent = coherent && (side == OrderSide.BUY
            ? previousPrice.compareTo(fill.price()) <= 0
            : previousPrice.compareTo(fill.price()) >= 0);
      }
      if (!coherent) {
        if (constructionBoundary) {
          throw invalidInstrumentRules(
              "DEPTH fill role, fee, price eligibility, or ordering does not match its plan");
        }
        throw new IllegalArgumentException(
            "DEPTH fill role or fee does not match its bound plan");
      }
      previousPrice = fill.price();
    }
  }

  private static boolean isZeroFillIoc(
      DepthMatchPlan plan,
      DemoMatchingResult matching
  ) {
    return plan.timeInForce() == TimeInForce.IOC
        && matching.terminalOrWorkingStatus() == OrderStatus.CANCELLED
        && matching.fills().isEmpty();
  }

  private static void validateBoundRequest(
      DemoExecutionPolicy policy,
      OrderSide side,
      OrderType executableType,
      BigDecimal remainingBaseQuantity,
      BigDecimal limitPrice,
      boolean rejectEmptyMarketBook
  ) {
    requireDepthPolicy(policy);
    if (side == null || executableType == null) {
      throw invalidInstrumentRules("DEPTH side and executable order type are required");
    }
    if (!isExactlyRepresentableAsNumeric(remainingBaseQuantity, 12, 4)
        || remainingBaseQuantity.signum() <= 0) {
      throw invalidInstrumentRules(
          "DEPTH requested quantity exceeds NUMERIC(12,4)");
    }
    if (isLimitLike(executableType)) {
      if (limitPrice == null || limitPrice.signum() <= 0
          || !isExactlyRepresentableAsNumeric(limitPrice, 24, 10)) {
        throw invalidInstrumentRules(
            "DEPTH limit price exceeds NUMERIC(24,10)");
      }
    } else if (executableType != OrderType.MARKET) {
      throw invalidInstrumentRules(
          "Only executable MARKET or LIMIT orders can use DEPTH matching");
    }
    BigDecimal cap = policy.maxFillQuantityPerTick();
    validateDepthCap(cap);
    List<DemoBookLevel> relevant = side == OrderSide.BUY
        ? policy.asks()
        : policy.bids();
    if (rejectEmptyMarketBook && executableType == OrderType.MARKET
        && relevant.isEmpty()) {
      throw new BusinessException(
          ErrorCode.MARKET_BUNDLE_INCOMPLETE,
          "MARKET DEPTH book is empty for the requested side");
    }
    relevant.forEach(DepthOrderExecutionService::validateDepthLevel);
  }

  private static void validateSourceExecutableMapping(
      OrderType sourceOrderType,
      OrderType executableType
  ) {
    boolean valid = sourceOrderType != null && executableType != null && switch (sourceOrderType) {
      case MARKET -> executableType == OrderType.MARKET;
      case LIMIT -> executableType == OrderType.LIMIT;
      case STOP_MARKET -> executableType == OrderType.MARKET;
      case STOP_LIMIT -> executableType == OrderType.LIMIT;
      default -> false;
    };
    if (!valid) {
      throw new IllegalArgumentException(
          "Invalid DEPTH source-to-executable order type mapping");
    }
  }

  private static void validateDepthCap(BigDecimal cap) {
    if (cap != null && (cap.signum() <= 0
        || !isExactlyRepresentableAsNumeric(cap, 12, 4))) {
      throw invalidInstrumentRules(
          "DEPTH max fill quantity per Tick exceeds NUMERIC(12,4)");
    }
  }

  private static void validateDepthLevel(DemoBookLevel level) {
    if (level == null
        || level.price() == null
        || level.quantity() == null
        || level.price().signum() <= 0
        || level.quantity().signum() <= 0) {
      throw invalidInstrumentRules("DEPTH level price and quantity must be positive");
    }
    if (!isExactlyRepresentableAsNumeric(level.price(), 24, 10)) {
      throw invalidInstrumentRules("DEPTH level price exceeds NUMERIC(24,10)");
    }
    if (!isExactlyRepresentableAsNumeric(level.quantity(), 12, 4)) {
      throw invalidInstrumentRules("DEPTH level quantity exceeds NUMERIC(12,4)");
    }
    if (money(level.price().multiply(level.quantity())).signum() <= 0) {
      throw invalidInstrumentRules(
          "DEPTH level gross amount must remain positive at money scale");
    }
  }

  private static void validateSnapshotNumeric(ExecutableMarketSnapshot snapshot) {
    boolean invalid = !isExactlyRepresentableAsNumeric(snapshot.bid(), 24, 10)
        || !isExactlyRepresentableAsNumeric(snapshot.ask(), 24, 10)
        || !isExactlyRepresentableAsNumeric(snapshot.last(), 24, 10);
    if (snapshot.productType() == ProductType.LINEAR_PERP) {
      invalid = invalid
          || !isExactlyRepresentableAsNumeric(snapshot.mark(), 24, 10)
          || !isExactlyRepresentableAsNumeric(snapshot.index(), 24, 10);
    }
    if (invalid) {
      throw invalidInstrumentRules(
          "Executable market snapshot prices exceed NUMERIC(24,10)");
    }
  }

  private static boolean isLimitLike(OrderType type) {
    return type == OrderType.LIMIT || type == OrderType.STOP_LIMIT;
  }

  private static boolean isEligible(
      OrderSide side,
      BigDecimal price,
      BigDecimal limitPrice
  ) {
    return side == OrderSide.BUY
        ? price.compareTo(limitPrice) <= 0
        : price.compareTo(limitPrice) >= 0;
  }

  private static Comparator<DemoBookLevel> levelComparator(OrderSide side) {
    Comparator<DemoBookLevel> byPrice = Comparator.comparing(DemoBookLevel::price);
    return side == OrderSide.BUY ? byPrice : byPrice.reversed();
  }

  private static BigDecimal maxFeeRate(DemoExecutionPolicy policy) {
    return policy.makerFeeRate().max(policy.takerFeeRate());
  }

  private static void validatePersistentFills(DemoMatchingResult matching) {
    for (DemoMatchFill fill : requireMatching(matching).fills()) {
      validateFill(fill);
      if (!isExactlyRepresentableAsNumeric(fill.quantity(), 12, 4)) {
        throw invalidInstrumentRules(
            "DEPTH fill quantity exceeds NUMERIC(12,4)");
      }
      if (!isExactlyRepresentableAsNumeric(fill.price(), 24, 10)) {
        throw invalidInstrumentRules(
            "DEPTH fill price exceeds NUMERIC(24,10)");
      }
      BigDecimal gross = money(fill.quantity().multiply(fill.price()));
      if (gross.signum() <= 0) {
        throw invalidInstrumentRules(
            "DEPTH fill gross amount must remain positive at money scale");
      }
      if (!isExactlyRepresentableAsNumeric(gross, 24, MONEY_SCALE)) {
        throw invalidInstrumentRules(
            "DEPTH fill gross amount exceeds NUMERIC(24,8)");
      }
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
    int valueScale = normalized.scale();
    int fractionalDigits = Math.max(valueScale, 0);
    long integerDigits = Math.max(
        (long) normalized.precision() - valueScale,
        0L);
    return fractionalDigits <= scale
        && integerDigits <= precision - scale;
  }

  private static BusinessException invalidInstrumentRules(String message) {
    return new BusinessException("INVALID_INSTRUMENT_RULES", message);
  }

  private static void requireStepAligned(
      BigDecimal quantity,
      BigDecimal storageStep,
      String message
  ) {
    if (quantity == null || quantity.signum() < 0
        || quantity.remainder(storageStep).signum() != 0) {
      throw invalidInstrumentRules(message);
    }
  }

  static final class StalePolicyException extends IllegalArgumentException {

    private StalePolicyException(String message) {
      super(message);
    }
  }

  private static BusinessException orderHoldInvalid(String message) {
    return new BusinessException(ErrorCode.ORDER_HOLD_INVALID, message);
  }

  private static BigDecimal requireOrderHold(BigDecimal value, String message) {
    if (value == null || value.signum() < 0
        || !isExactlyRepresentableAsNumeric(value, 24, MONEY_SCALE)) {
      throw orderHoldInvalid(message);
    }
    return value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
  }

  private static void validateFill(DemoMatchFill fill) {
    if (fill == null) {
      throw new IllegalArgumentException("DEPTH fill is required");
    }
    requirePositive(fill.quantity(), "DEPTH fill quantity");
    requirePositive(fill.price(), "DEPTH fill price");
    requireRate(fill.feeRate(), "DEPTH fill fee rate");
    if (fill.liquidityRole() == null) {
      throw new IllegalArgumentException("DEPTH fill liquidity role is required");
    }
  }

  private static void requireDepthPolicy(DemoExecutionPolicy policy) {
    if (policy == null || policy.matchingMode() != DemoMatchingMode.DEPTH) {
      throw new IllegalArgumentException("depth execution policy is required");
    }
    requireRate(policy.makerFeeRate(), "DEPTH maker fee rate");
    requireRate(policy.takerFeeRate(), "DEPTH taker fee rate");
    if (!isExactlyRepresentableAsNumeric(policy.makerFeeRate(), 18, 8)
        || !isExactlyRepresentableAsNumeric(policy.takerFeeRate(), 18, 8)) {
      throw invalidInstrumentRules(
          "DEPTH maker and taker fee rates exceed NUMERIC(18,8)");
    }
  }

  private static void requireRate(BigDecimal value, String name) {
    if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) >= 0) {
      throw new IllegalArgumentException(name + " must be in [0, 1)");
    }
  }

  private static void requirePositive(BigDecimal value, String name) {
    if (value == null || value.signum() <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireNonNegative(BigDecimal value, String name) {
    if (value == null || value.signum() < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  public static final class DepthMatchPlan {

    private final DemoExecutionPolicy policy;
    private final ExecutableMarketSnapshot snapshot;
    private final String symbol;
    private final ProductType productType;
    private final OrderSide side;
    private final OrderType sourceOrderType;
    private final OrderType executableType;
    private final TimeInForce timeInForce;
    private final BigDecimal remainingBaseQuantity;
    private final BigDecimal limitPrice;
    private final boolean placementPostOnly;
    private final LiquidityRole role;
    private final DemoMatchingResult matchingResult;
    private final boolean triggerPending;
    private final PlanSeal seal;

    private DepthMatchPlan(
        DemoExecutionPolicy policy,
        ExecutableMarketSnapshot snapshot,
        String symbol,
        ProductType productType,
        OrderSide side,
        OrderType sourceOrderType,
        OrderType executableType,
        TimeInForce timeInForce,
        BigDecimal remainingBaseQuantity,
        BigDecimal limitPrice,
        boolean placementPostOnly,
        LiquidityRole role,
        DemoMatchingResult matchingResult,
        boolean triggerPending,
        PlanSeal seal
    ) {
      this.policy = Objects.requireNonNull(policy, "policy");
      this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
      this.symbol = Objects.requireNonNull(symbol, "symbol");
      this.productType = Objects.requireNonNull(productType, "productType");
      this.side = Objects.requireNonNull(side, "side");
      this.sourceOrderType = Objects.requireNonNull(sourceOrderType, "sourceOrderType");
      this.executableType = Objects.requireNonNull(executableType, "executableType");
      this.timeInForce = Objects.requireNonNull(timeInForce, "timeInForce");
      this.remainingBaseQuantity = Objects.requireNonNull(
          remainingBaseQuantity, "remainingBaseQuantity");
      this.limitPrice = limitPrice;
      this.placementPostOnly = placementPostOnly;
      this.role = Objects.requireNonNull(role, "role");
      this.matchingResult = Objects.requireNonNull(matchingResult, "matchingResult");
      this.triggerPending = triggerPending;
      if (seal != PLAN_SEAL) {
        throw new IllegalArgumentException(
            "DEPTH match plans can only be produced by the matching authority");
      }
      this.seal = seal;
      validateSourceExecutableMapping(sourceOrderType, executableType);
      if (triggerPending && sourceOrderType != OrderType.STOP_MARKET
          && sourceOrderType != OrderType.STOP_LIMIT) {
        throw new IllegalArgumentException(
            "Only STOP source orders can use a trigger-pending DEPTH plan");
      }
      ExecutableMarketSnapshots.requireComplete(symbol, productType, snapshot);
      validateBoundRequest(
          policy,
          side,
          executableType,
          remainingBaseQuantity,
          limitPrice,
          false);
      DemoMatchingResult requiredMatching = validateMatching(matchingResult);
      validatePlanQuantity(remainingBaseQuantity, requiredMatching);
      validatePersistentFills(requiredMatching);
      validateBoundFills(
          policy,
          side,
          executableType,
          limitPrice,
          role,
          requiredMatching,
          true);
      if (placementPostOnly && !requiredMatching.fills().isEmpty()) {
        throw invalidInstrumentRules(
            "Post Only DEPTH plan cannot contain taking fills");
      }
      if (timeInForce == TimeInForce.FOK
          && requiredMatching.remainingQuantity().signum() > 0) {
        throw invalidInstrumentRules(
            "FOK DEPTH plan cannot retain an unmatched quantity");
      }
    }

    public DemoExecutionPolicy policy() {
      return policy;
    }

    public ExecutableMarketSnapshot snapshot() {
      return snapshot;
    }

    public String symbol() {
      return symbol;
    }

    public ProductType productType() {
      return productType;
    }

    public OrderSide side() {
      return side;
    }

    public OrderType sourceOrderType() {
      return sourceOrderType;
    }

    public OrderType executableType() {
      return executableType;
    }

    public TimeInForce timeInForce() {
      return timeInForce;
    }

    public BigDecimal remainingBaseQuantity() {
      return remainingBaseQuantity;
    }

    public BigDecimal limitPrice() {
      return limitPrice;
    }

    public boolean placementPostOnly() {
      return placementPostOnly;
    }

    public LiquidityRole role() {
      return role;
    }

    public DemoMatchingResult matchingResult() {
      return matchingResult;
    }

    public boolean triggerPending() {
      return triggerPending;
    }

    public OrderStatus initialOrderStatus() {
      return triggerPending && sourceOrderType == OrderType.STOP_LIMIT
          ? OrderStatus.PENDING_ACTIVATION
          : matchingResult.terminalOrWorkingStatus();
    }
  }

  @SuppressWarnings("unused")
  private static DepthMatchPlan syntheticPlanForTests(
      DemoExecutionPolicy policy,
      ExecutableMarketSnapshot snapshot,
      String symbol,
      ProductType productType,
      OrderSide side,
      OrderType executableType,
      TimeInForce timeInForce,
      BigDecimal remainingBaseQuantity,
      BigDecimal limitPrice,
      boolean placementPostOnly,
      LiquidityRole role,
      DemoMatchingResult matchingResult
  ) {
    return new DepthMatchPlan(
        policy,
        snapshot,
        symbol,
        productType,
        side,
        executableType,
        executableType,
        timeInForce,
        remainingBaseQuantity,
        limitPrice,
        placementPostOnly,
        role,
        matchingResult,
        false,
        PLAN_SEAL);
  }

  private static final class PlanSeal {

    private PlanSeal() {
    }
  }

  static final class RiskPricingAuthority {

    private RiskPricingAuthority() {
    }
  }

  public record SpotDepthConversion(
      BigDecimal baseQuantity,
      BigDecimal projectedQuoteSpend
  ) {
    public SpotDepthConversion {
      Objects.requireNonNull(baseQuantity, "baseQuantity");
      Objects.requireNonNull(projectedQuoteSpend, "projectedQuoteSpend");
      if (!isExactlyRepresentableAsNumeric(baseQuantity, 12, 4)
          || baseQuantity.signum() <= 0) {
        throw invalidInstrumentRules(
            "Spot DEPTH base conversion exceeds NUMERIC(12,4)");
      }
      projectedQuoteSpend = requireOrderHold(
          projectedQuoteSpend,
          "Spot DEPTH quote conversion exceeds NUMERIC(24,8)");
    }
  }

  public record DepthExecutionOutcome(
      OrderEntity order,
      int newlyAppliedFillCount,
      BigDecimal remainingQuantity,
      BigDecimal remainingHold,
      boolean replayed
  ) {
    public DepthExecutionOutcome {
      Objects.requireNonNull(order, "order");
      Objects.requireNonNull(remainingQuantity, "remainingQuantity");
      Objects.requireNonNull(remainingHold, "remainingHold");
      if (newlyAppliedFillCount < 0
          || remainingQuantity.signum() < 0
          || remainingHold.signum() < 0) {
        throw new IllegalArgumentException(
            "DEPTH execution outcome values must not be negative");
      }
    }
  }

  public record DepthHoldPlan(
      BigDecimal initialHold,
      List<BigDecimal> holdAfterEachFill
  ) {
    public DepthHoldPlan {
      initialHold = requireOrderHold(
          Objects.requireNonNull(initialHold, "initialHold"),
          "DEPTH initial hold exceeds NUMERIC(24,8)");
      holdAfterEachFill = List.copyOf(
          Objects.requireNonNull(holdAfterEachFill, "holdAfterEachFill"));
      for (BigDecimal hold : holdAfterEachFill) {
        requireOrderHold(hold, "DEPTH H1 exceeds NUMERIC(24,8)");
      }
    }
  }

  private record FutureDepthTemplate(
      List<DemoBookLevel> levels,
      BigDecimal tickQuantity
  ) {
    private FutureDepthTemplate {
      levels = List.copyOf(Objects.requireNonNull(levels, "levels"));
      requirePositive(tickQuantity, "DEPTH future template Tick quantity");
      if (levels.isEmpty()) {
        throw invalidInstrumentRules("DEPTH future template levels are required");
      }
      BigDecimal total = levels.stream()
          .map(DemoBookLevel::quantity)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
      if (total.compareTo(tickQuantity) != 0) {
        throw invalidInstrumentRules(
            "DEPTH future template levels do not cover its Tick quantity");
      }
      for (DemoBookLevel level : levels) {
        BigDecimal gross = money(level.price().multiply(level.quantity()));
        if (gross.signum() <= 0
            || !isExactlyRepresentableAsNumeric(gross, 24, MONEY_SCALE)) {
          throw invalidInstrumentRules(
              "DEPTH future fill gross amount exceeds NUMERIC(24,8)");
        }
      }
    }
  }
}
