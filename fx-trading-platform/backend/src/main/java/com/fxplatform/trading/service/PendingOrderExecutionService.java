package com.fxplatform.trading.service;

import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.execution.FullFillExecutionPath;
import com.fxplatform.execution.FullFillRequest;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Scans pending orders and executes each candidate in its own local transaction. */
@Service
public class PendingOrderExecutionService {

  private static final Logger log = LoggerFactory.getLogger(PendingOrderExecutionService.class);
  private static final String EXECUTION_FAILURE_EVENT = "ORDER_EXECUTION_FAILED";
  private static final String EXECUTION_FAILURE_MESSAGE = "Pending order execution deferred";
  private static final Set<String> DEMO_GUARD_REJECTION_CODES = Set.of(
      ErrorCode.EXECUTION_DISABLED,
      ErrorCode.DEMO_ACCOUNT_REQUIRED,
      "DEMO_EXECUTION_REQUIRED",
      ErrorCode.ACCOUNT_NOT_ACTIVE,
      ErrorCode.PRODUCT_NOT_ALLOWED,
      ErrorCode.SYMBOL_NOT_ALLOWED);

  private static final Set<String> P0_SYMBOLS = Set.of(
      "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
      "BTCUSDT-PERP", "ETHUSDT-PERP", "BNBUSDT-PERP", "SOLUSDT-PERP", "XRPUSDT-PERP");

  private final OrderRepository orderRepository;
  private final TradingAccountRepository accountRepository;
  private final QuoteService quoteService;
  private final RiskCheckService riskCheckService;
  private final OrderFillService orderFillService;
  private final OrderEventService orderEventService;
  private final DemoExecutionGuard demoExecutionGuard;
  private final WalletBalanceRepository walletBalanceRepository;
  private final WalletService walletService;
  private final SpotPositionService spotPositionService;
  private final PositionRepository positionRepository;
  private final TradingTransactionExecutor transactionExecutor;
  private final MarketBundleResolver marketBundleResolver;
  private final FullFillCoordinator fullFillCoordinator;
  private final PerpetualOrderRiskService perpetualOrderRiskService;
  private final AccountSymbolSettingRepository accountSymbolSettingRepository;
  private final SymbolRepository symbolRepository;
  private final PerpetualAccountRiskSnapshotService perpetualAccountRiskSnapshotService;
  private PendingOrderExecutionProcessor pendingOrderExecutionProcessor;
  private DepthOrderExecutionService depthOrderExecutionService;
  private InstrumentRulesEngine instrumentRulesEngine;

  @Autowired
  public PendingOrderExecutionService(
      OrderRepository orderRepository,
      TradingAccountRepository accountRepository,
      QuoteService quoteService,
      RiskCheckService riskCheckService,
      OrderFillService orderFillService,
      OrderEventService orderEventService,
      DemoExecutionGuard demoExecutionGuard,
      WalletBalanceRepository walletBalanceRepository,
      WalletService walletService,
      SpotPositionService spotPositionService,
      PositionRepository positionRepository,
      TradingTransactionExecutor transactionExecutor,
      MarketBundleResolver marketBundleResolver,
      FullFillCoordinator fullFillCoordinator,
      PerpetualOrderRiskService perpetualOrderRiskService,
      AccountSymbolSettingRepository accountSymbolSettingRepository,
      SymbolRepository symbolRepository,
      PerpetualAccountRiskSnapshotService perpetualAccountRiskSnapshotService
  ) {
    this.orderRepository = orderRepository;
    this.accountRepository = accountRepository;
    this.quoteService = quoteService;
    this.riskCheckService = riskCheckService;
    this.orderFillService = orderFillService;
    this.orderEventService = orderEventService;
    this.demoExecutionGuard = demoExecutionGuard;
    this.walletBalanceRepository = walletBalanceRepository;
    this.walletService = walletService;
    this.spotPositionService = spotPositionService;
    this.positionRepository = positionRepository;
    this.transactionExecutor = transactionExecutor;
    this.marketBundleResolver = marketBundleResolver;
    this.fullFillCoordinator = fullFillCoordinator;
    this.perpetualOrderRiskService = perpetualOrderRiskService;
    this.accountSymbolSettingRepository = accountSymbolSettingRepository;
    this.symbolRepository = symbolRepository;
    this.perpetualAccountRiskSnapshotService = perpetualAccountRiskSnapshotService;
  }

  /** Compatibility constructor for callers created before account-wide Perpetual risk snapshots. */
  public PendingOrderExecutionService(
      OrderRepository orderRepository,
      TradingAccountRepository accountRepository,
      QuoteService quoteService,
      RiskCheckService riskCheckService,
      OrderFillService orderFillService,
      OrderEventService orderEventService,
      DemoExecutionGuard demoExecutionGuard,
      WalletBalanceRepository walletBalanceRepository,
      WalletService walletService,
      SpotPositionService spotPositionService,
      PositionRepository positionRepository,
      TradingTransactionExecutor transactionExecutor,
      MarketBundleResolver marketBundleResolver,
      FullFillCoordinator fullFillCoordinator,
      PerpetualOrderRiskService perpetualOrderRiskService,
      AccountSymbolSettingRepository accountSymbolSettingRepository,
      SymbolRepository symbolRepository
  ) {
    this(
        orderRepository,
        accountRepository,
        quoteService,
        riskCheckService,
        orderFillService,
        orderEventService,
        demoExecutionGuard,
        walletBalanceRepository,
        walletService,
        spotPositionService,
        positionRepository,
        transactionExecutor,
        marketBundleResolver,
        fullFillCoordinator,
        perpetualOrderRiskService,
        accountSymbolSettingRepository,
        symbolRepository,
        null);
  }

  /** Compatibility constructor for focused Perpetual fixtures created before symbol MMR became explicit. */
  public PendingOrderExecutionService(
      OrderRepository orderRepository,
      TradingAccountRepository accountRepository,
      QuoteService quoteService,
      RiskCheckService riskCheckService,
      OrderFillService orderFillService,
      OrderEventService orderEventService,
      DemoExecutionGuard demoExecutionGuard,
      WalletBalanceRepository walletBalanceRepository,
      WalletService walletService,
      SpotPositionService spotPositionService,
      PositionRepository positionRepository,
      TradingTransactionExecutor transactionExecutor,
      MarketBundleResolver marketBundleResolver,
      FullFillCoordinator fullFillCoordinator,
      PerpetualOrderRiskService perpetualOrderRiskService,
      AccountSymbolSettingRepository accountSymbolSettingRepository
  ) {
    this(
        orderRepository,
        accountRepository,
        quoteService,
        riskCheckService,
        orderFillService,
        orderEventService,
        demoExecutionGuard,
        walletBalanceRepository,
        walletService,
        spotPositionService,
        positionRepository,
        transactionExecutor,
        marketBundleResolver,
        fullFillCoordinator,
        perpetualOrderRiskService,
        accountSymbolSettingRepository,
        null,
        null);
  }

  /** Compatibility constructor for Task 5/6 P0 fixtures. */
  public PendingOrderExecutionService(
      OrderRepository orderRepository,
      TradingAccountRepository accountRepository,
      QuoteService quoteService,
      RiskCheckService riskCheckService,
      OrderFillService orderFillService,
      OrderEventService orderEventService,
      DemoExecutionGuard demoExecutionGuard,
      WalletBalanceRepository walletBalanceRepository,
      WalletService walletService,
      SpotPositionService spotPositionService,
      PositionRepository positionRepository,
      TradingTransactionExecutor transactionExecutor,
      MarketBundleResolver marketBundleResolver,
      FullFillCoordinator fullFillCoordinator
  ) {
    this(
        orderRepository,
        accountRepository,
        quoteService,
        riskCheckService,
        orderFillService,
        orderEventService,
        demoExecutionGuard,
        walletBalanceRepository,
        walletService,
        spotPositionService,
        positionRepository,
        transactionExecutor,
        marketBundleResolver,
        fullFillCoordinator,
        null,
        null,
        null,
        null);
  }

  /** Compatibility constructor for historical non-P0 unit fixtures. */
  public PendingOrderExecutionService(
      OrderRepository orderRepository,
      TradingAccountRepository accountRepository,
      QuoteService quoteService,
      RiskCheckService riskCheckService,
      OrderFillService orderFillService,
      OrderEventService orderEventService,
      DemoExecutionGuard demoExecutionGuard,
      WalletBalanceRepository walletBalanceRepository,
      WalletService walletService,
      SpotPositionService spotPositionService,
      PositionRepository positionRepository,
      TradingTransactionExecutor transactionExecutor
  ) {
    this(
        orderRepository,
        accountRepository,
        quoteService,
        riskCheckService,
        orderFillService,
        orderEventService,
        demoExecutionGuard,
        walletBalanceRepository,
        walletService,
        spotPositionService,
        positionRepository,
        transactionExecutor,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public int executePendingOrders() {
    return executeCandidates(
        order -> isRestingCandidate(order) || isConditionalCandidate(order),
        false);
  }

  /** Executes ordinary resting/depth orders before conditional protection work. */
  public int executeRestingOrders() {
    return executeCandidates(this::isRestingCandidate, false);
  }

  /** Validation-only strict scan that joins the owning system-step transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public int executeRestingOrdersStrict() {
    return executeCandidates(this::isRestingCandidate, true);
  }

  /** Activates or executes user conditional orders after ordinary resting matching. */
  public int executeConditionalOrders() {
    return executeCandidates(this::isConditionalCandidate, false);
  }

  /** Validation-only strict scan that joins the owning system-step transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public int executeConditionalOrdersStrict() {
    return executeCandidates(this::isConditionalCandidate, true);
  }

  private int executeCandidates(Predicate<OrderEntity> selector, boolean failClosed) {
    int filled = 0;
    List<OrderEntity> pending = orderRepository.findByStatus(OrderStatus.PENDING);
    List<OrderEntity> partiallyFilled = orderRepository.findByStatus(OrderStatus.PARTIALLY_FILLED);
    List<OrderEntity> awaitingActivation = orderRepository.findUserStopLimitsAwaitingActivation();
    Map<UUID, OrderEntity> uniqueCandidates = new HashMap<>();
    Stream.of(pending, partiallyFilled, awaitingActivation)
        .flatMap(orders -> orders == null ? Stream.empty() : orders.stream())
        .filter(java.util.Objects::nonNull)
        .forEach(order -> uniqueCandidates.put(order.getId(), order));
    List<OrderEntity> candidates = uniqueCandidates.values().stream()
        .sorted(Comparator
            .comparing(
                OrderEntity::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(
                OrderEntity::getId,
                Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
    for (OrderEntity order : candidates) {
      if (!selector.test(order)) {
        continue;
      }
      OrderStatus sourceStatus = order.getStatus();
      PendingOrderExecutionFingerprint fingerprint =
          PendingOrderExecutionFingerprint.capture(order);
      boolean demoAuthorized = false;
      if (failClosed) {
        TradingAccountEntity accountSnapshot = requireDemoCandidate(order);
        if (executeCandidate(order, accountSnapshot, true)) {
          filled++;
        }
        continue;
      }
      try {
        TradingAccountEntity accountSnapshot = requireDemoCandidate(order);
        demoAuthorized = true;
        boolean executed = executeCandidate(order, accountSnapshot, false);
        if (executed) {
          filled++;
        }
      } catch (BusinessException exception) {
        if (demoAuthorized && !DEMO_GUARD_REJECTION_CODES.contains(exception.getCode())) {
          recordWorkerFailure(order, sourceStatus, fingerprint, exception.getCode());
        }
        log.debug(
            "Pending order {} remains pending after business rejection {}: {}",
            order.getId(),
            exception.getCode(),
            exception.getMessage());
      } catch (RuntimeException exception) {
        if (demoAuthorized) {
          recordWorkerFailure(
              order, sourceStatus, fingerprint, ErrorCode.EXECUTION_UNAVAILABLE);
        }
        log.warn(
            "Pending order {} failed without aborting later candidates: {}: {}",
            order.getId(),
            exception.getClass().getSimpleName(),
            exception.getMessage());
      }
    }
    return filled;
  }

  private boolean executeCandidate(
      OrderEntity order,
      TradingAccountEntity accountSnapshot,
      boolean joinCallerTransaction
  ) {
    return isP0Order(order)
        ? prepareAndExecuteP0(order, accountSnapshot, joinCallerTransaction)
        : prepareAndExecuteLegacy(order, accountSnapshot, joinCallerTransaction);
  }

  private boolean isRestingCandidate(OrderEntity order) {
    return order.getOrderType() == OrderType.LIMIT
        || (order.getOrderType() == OrderType.STOP_LIMIT
            && (order.getStatus() == OrderStatus.PENDING
                || order.getStatus() == OrderStatus.PARTIALLY_FILLED));
  }

  private boolean isConditionalCandidate(OrderEntity order) {
    return order.getOrderType() == OrderType.STOP_MARKET
        || (order.getOrderType() == OrderType.STOP_LIMIT
            && order.getStatus() == OrderStatus.PENDING_ACTIVATION);
  }

  private TradingAccountEntity requireDemoCandidate(OrderEntity order) {
    ProductType productType = requestedProduct(order.getSymbol());
    TradingAccountEntity account = accountRepository.findById(order.getAccountId())
        .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));
    demoExecutionGuard.requireDemo(account, productType, order.getSymbol());
    return account;
  }

  private void recordWorkerFailure(
      OrderEntity order,
      OrderStatus sourceStatus,
      PendingOrderExecutionFingerprint fingerprint,
      String errorCode
  ) {
    try {
      orderEventService.recordWorkerFailure(
          order.getId(),
          EXECUTION_FAILURE_EVENT,
          sourceStatus,
          errorCode,
          EXECUTION_FAILURE_MESSAGE,
          fingerprint);
    } catch (RuntimeException eventFailure) {
      log.warn(
          "Pending order {} failure event could not be persisted: {}: {}",
          order.getId(),
          eventFailure.getClass().getSimpleName(),
          eventFailure.getMessage());
    }
  }

  @Autowired
  void setPendingOrderExecutionProcessor(
      PendingOrderExecutionProcessor pendingOrderExecutionProcessor
  ) {
    this.pendingOrderExecutionProcessor = pendingOrderExecutionProcessor;
  }

  @Autowired
  void setDepthOrderExecutionService(
      DepthOrderExecutionService depthOrderExecutionService
  ) {
    this.depthOrderExecutionService = depthOrderExecutionService;
  }

  @Autowired
  void setInstrumentRulesEngine(InstrumentRulesEngine instrumentRulesEngine) {
    this.instrumentRulesEngine = instrumentRulesEngine;
  }

  private boolean prepareAndExecuteP0(
      OrderEntity order,
      TradingAccountEntity accountSnapshot,
      boolean joinCallerTransaction
  ) {
    ProductType productType = requestedProduct(order.getSymbol());

    if (productType == ProductType.CRYPTO_SPOT && pendingOrderExecutionProcessor != null) {
      return prepareAndExecuteSpot(order, joinCallerTransaction);
    }

    if (productType == ProductType.LINEAR_PERP) {
      if (perpetualOrderRiskService == null
          || accountSymbolSettingRepository == null
          || symbolRepository == null
          || perpetualAccountRiskSnapshotService == null
          || marketBundleResolver == null
          || fullFillCoordinator == null) {
        throw new BusinessException(
            ErrorCode.EXECUTION_UNAVAILABLE,
            "P0 Linear Perpetual pending execution authority is unavailable");
      }
      return prepareAndExecutePerpetual(order, joinCallerTransaction);
    }

    ExecutableMarketSnapshot snapshot = resolveExecutableSnapshot(order.getSymbol(), productType);
    if (!isTriggered(order, snapshot)) {
      return false;
    }
    BigDecimal requiredMargin = order.getHoldAmount() != null
        ? order.getHoldAmount()
        : riskCheckService.checkOrder(accountSnapshot, toRequest(order, canonicalQuantity(order)));
    PendingCandidate candidate = new PendingCandidate(
        order.getId(),
        order.getAccountId(),
        order.getSymbol(),
        productType,
        requiredMargin,
        snapshot,
        null,
        PendingOrderExecutionFingerprint.capture(order));
    return joinCallerTransaction
        ? transactionExecutor.executeJoined(() -> tryExecuteP0(candidate))
        : transactionExecutor.execute(() -> tryExecuteP0(candidate));
  }

  private boolean prepareAndExecuteSpot(
      OrderEntity order,
      boolean joinCallerTransaction
  ) {
    PendingOrderExecutionFingerprint fingerprint =
        PendingOrderExecutionFingerprint.capture(order);
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        ExecutableMarketSnapshot snapshot = resolveExecutableSnapshot(
            order.getSymbol(), ProductType.CRYPTO_SPOT);
        fullFillCoordinator.requireFresh(snapshot);
        if (!fingerprint.matches(order)) {
          return false;
        }
        return joinCallerTransaction
            ? pendingOrderExecutionProcessor.processStrict(order, snapshot)
            : pendingOrderExecutionProcessor.process(order, snapshot);
      } catch (BusinessException exception) {
        if (!joinCallerTransaction
            && attempt == 0
            && "MARKET_DATA_STALE".equals(exception.getCode())) {
          continue;
        }
        throw exception;
      }
    }
    return false;
  }

  private boolean prepareAndExecutePerpetual(
      OrderEntity order,
      boolean joinCallerTransaction
  ) {
    BusinessException lastStale = null;
    PendingOrderExecutionFingerprint fingerprint =
        PendingOrderExecutionFingerprint.capture(order);
    for (int attempt = 0; attempt < 2; attempt++) {
      DemoExecutionPolicy policy = depthOrderExecutionService == null
          ? null
          : depthOrderExecutionService.currentPolicy();
      try {
        boolean depth = depthOrderExecutionService != null
            && depthOrderExecutionService.isDepth(policy);
        ExecutableMarketSnapshot snapshot = resolveExecutableSnapshot(
            order.getSymbol(), ProductType.LINEAR_PERP);
        fullFillCoordinator.requireFresh(snapshot);
        if (depth && instrumentRulesEngine == null) {
          throw new BusinessException(
              ErrorCode.EXECUTION_UNAVAILABLE,
              "P0 Linear Perpetual DEPTH instrument authority is unavailable");
        }
        if (depth
            ? !isPerpetualDepthExecutableState(order, snapshot)
            : !isPerpetualTriggered(order, snapshot)) {
          return false;
        }
        PendingCandidate candidate = new PendingCandidate(
            order.getId(),
            order.getAccountId(),
            order.getSymbol(),
            ProductType.LINEAR_PERP,
            orZero(order.getHoldAmount()),
            snapshot,
            perpetualAccountRiskSnapshotService.prepare(
                order.getAccountId(),
                Map.of(order.getSymbol(), snapshot)),
            fingerprint);
        if (depth) {
          return joinCallerTransaction
              ? transactionExecutor.executeJoined(
                  () -> tryExecutePerpetualDepth(candidate, policy))
              : transactionExecutor.execute(
                  () -> tryExecutePerpetualDepth(candidate, policy));
        }
        return joinCallerTransaction
            ? transactionExecutor.executeJoined(() -> tryExecutePerpetual(candidate, policy))
            : transactionExecutor.execute(() -> tryExecutePerpetual(candidate, policy));
      } catch (DepthOrderExecutionService.StalePolicyException exception) {
        BusinessException stale = new BusinessException(
            ErrorCode.MARKET_DATA_STALE,
            "DEPTH execution policy changed during pending Perpetual planning",
            exception);
        if (joinCallerTransaction
            || (depthOrderExecutionService != null
                && !depthOrderExecutionService.isDepth(policy))) {
          throw stale;
        }
        lastStale = stale;
      } catch (BusinessException exception) {
        if (joinCallerTransaction
            || !ErrorCode.MARKET_DATA_STALE.equals(exception.getCode())
            || attempt > 0) {
          throw exception;
        }
        lastStale = exception;
      }
    }
    throw new BusinessException(
        ErrorCode.MARKET_DATA_STALE,
        lastStale == null ? "No fresh executable Perpetual snapshot" : lastStale.getMessage());
  }

  private boolean tryExecutePerpetualDepth(
      PendingCandidate candidate,
      DemoExecutionPolicy policy
  ) {
    TradingAccountEntity account = accountRepository.findByIdForUpdate(candidate.accountId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    demoExecutionGuard.requireDemo(account, ProductType.LINEAR_PERP, candidate.symbol());
    AccountSymbolSettingEntity lockedSetting = accountSymbolSettingRepository
        .findByAccountIdAndSymbolForUpdate(account.getId(), candidate.symbol())
        .orElseThrow(() -> new BusinessException(
            "INVALID_INSTRUMENT_RULES",
            "Locked Perpetual symbol setting is required"));
    perpetualAccountRiskSnapshotService.requireCurrentLeverageWithinLimit(lockedSetting);
    List<PositionEntity> accountPositions = positionRepository
        .findOpenLinearPerpByAccountIdForUpdate(account.getId());
    List<OrderEntity> accountActiveOrders = orderRepository
        .findActiveLinearPerpByAccountIdForUpdate(account.getId());
    OrderEntity lockedOrder = orderRepository.findByIdForUpdate(candidate.orderId())
        .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found"));
    requirePerpetualCandidate(candidate, account, lockedSetting, lockedOrder);
    if (!candidate.fingerprint().matches(lockedOrder)) {
      return false;
    }
    if (depthOrderExecutionService.tickAlreadyApplied(lockedOrder, candidate.snapshot())) {
      return false;
    }
    if (!isPerpetualDepthExecutableState(lockedOrder, candidate.snapshot())) {
      return false;
    }

    SymbolEntity symbol = symbolRepository.findBySymbol(candidate.symbol())
        .orElseThrow(() -> new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found"));
    InstrumentRules rules = instrumentRulesEngine.rules(symbol);
    if (rules == null || rules.productType() != ProductType.LINEAR_PERP) {
      throw new BusinessException(
          "INVALID_INSTRUMENT_RULES",
          "Pending Perpetual DEPTH instrument rules are unavailable");
    }
    instrumentRulesEngine.validateDepthExecutionAuthority(symbol, rules);
    BigDecimal remaining = depthRemainingQuantity(lockedOrder);
    boolean activatingStopLimit = isAwaitingStopLimitActivation(lockedOrder);
    OrderType executableType = lockedOrder.getOrderType() == OrderType.STOP_MARKET
        ? OrderType.MARKET
        : OrderType.LIMIT;
    LiquidityRole role = activatingStopLimit
        || lockedOrder.getOrderType() == OrderType.STOP_MARKET
        ? LiquidityRole.TAKER
        : LiquidityRole.MAKER;
    DepthOrderExecutionService.DepthMatchPlan match = depthOrderExecutionService.prepare(
        policy,
        lockedOrder.getSymbol(),
        ProductType.LINEAR_PERP,
        lockedOrder.getSide(),
        lockedOrder.getOrderType(),
        executableType,
        lockedOrder.getTimeInForce(),
        remaining,
        executableType == OrderType.LIMIT ? currentPrice(lockedOrder) : null,
        false,
        role,
        candidate.snapshot());
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
      if (orderRepository.activateStopLimitPending(lockedOrder.getId()) != 1) {
        return false;
      }
      lockedOrder.setStatus(OrderStatus.PENDING);
      orderEventService.record(
          lockedOrder.getId(),
          "ORDER_TRIGGERED",
          OrderStatus.PENDING_ACTIVATION,
          OrderStatus.PENDING,
          null,
          "Perpetual STOP_LIMIT activated and resting in DEPTH mode");
      return false;
    }

    PerpetualAccountRiskSnapshotService.AccountRiskProjection accountRisk =
        perpetualAccountRiskSnapshotService.project(
            account,
            accountPositions,
            accountActiveOrders,
            candidate.preparedAccountRisk());
    List<PositionEntity> lockedPositions = accountPositions.stream()
        .filter(position -> candidate.symbol().equals(position.getSymbol()))
        .toList();
    List<OrderEntity> activeOrders = accountActiveOrders.stream()
        .filter(order -> candidate.symbol().equals(order.getSymbol()))
        .toList();
    PerpetualAccountRiskSnapshotService.PreparedSymbolRisk targetRisk =
        candidate.preparedAccountRisk().symbols().get(candidate.symbol());
    if (targetRisk == null) {
      throw new BusinessException(
          ErrorCode.MARKET_DATA_UNAVAILABLE,
          "Target Perpetual risk snapshot was not prepared");
    }
    PerpetualRiskPricing pricing = depthOrderExecutionService.perpetualRiskPricing(match);
    PerpetualOrderRiskService.OrderRisk risk = perpetualOrderRiskService.evaluateDepth(
        account.getPositionMode(),
        lockedSetting,
        lockedPositions,
        lockedOrder.getSide(),
        lockedOrder.getPositionSide(),
        Boolean.TRUE.equals(lockedOrder.getReduceOnly()),
        lockedOrder.getOrderType(),
        remaining,
        executableType == OrderType.LIMIT ? currentPrice(lockedOrder) : null,
        candidate.snapshot(),
        targetRisk.maintenanceMarginRate(),
        pricing);
    requireFreshInternalIsolatedClose(
        lockedOrder,
        lockedPositions,
        activeOrders,
        risk);
    PerpetualOrderRiskService.DepthPlanningAuthority planningAuthority =
        new PerpetualOrderRiskService.DepthPlanningAuthority(
            account.getId(),
            account.getPositionMode(),
            risk.positionSide(),
            risk.marginMode(),
            risk.leverage(),
            Boolean.TRUE.equals(lockedOrder.getReduceOnly()),
            targetRisk.maintenanceMarginRate());
    BigDecimal currentHold = orZero(lockedOrder.getHoldAmount());
    DepthOrderExecutionService.DepthHoldPlan holds = depthOrderExecutionService.planPerpetual(
        planningAuthority,
        risk,
        currentHold,
        match);
    if (risk.openingBase().signum() > 0 && accountRisk.crossAvailable().signum() < 0) {
      throw new BusinessException(
          ErrorCode.INSUFFICIENT_MARGIN,
          "Fresh Cross available margin cannot support the pending opening fill");
    }
    BigDecimal holdIncrease = depthPerpetualHoldIncrease(
        lockedOrder,
        risk,
        holds,
        accountRisk.crossAvailable());
    depthOrderExecutionService.requireApplicable(match);

    OrderStatus sourceStatus = lockedOrder.getStatus();
    try {
      if (activatingStopLimit) {
        if (orderRepository.activateStopLimitWorking(lockedOrder.getId()) != 1) {
          return false;
        }
        lockedOrder.setStatus(OrderStatus.WORKING);
        orderEventService.record(
            lockedOrder.getId(),
            "ORDER_TRIGGERED",
            OrderStatus.PENDING_ACTIVATION,
            OrderStatus.WORKING,
            null,
            "Perpetual STOP_LIMIT activated for DEPTH execution");
      }
      perpetualAccountRiskSnapshotService.applyRevaluation(
          account,
          accountPositions,
          accountRisk);
      increasePerpetualOrderHold(
          lockedOrder,
          account,
          risk,
          holdIncrease);
      for (PositionEntity position : accountPositions) {
        positionRepository.save(position);
      }
      accountRepository.save(account);
      DepthOrderExecutionService.DepthExecutionOutcome outcome =
          depthOrderExecutionService.applyLocked(
              lockedOrder,
              lockedOrder,
              account,
              match,
              holds,
              false);
      return outcome.newlyAppliedFillCount() > 0;
    } catch (RuntimeException exception) {
      lockedOrder.setStatus(sourceStatus);
      throw exception;
    }
  }

  private BigDecimal depthPerpetualHoldIncrease(
      OrderEntity order,
      PerpetualOrderRiskService.OrderRisk risk,
      DepthOrderExecutionService.DepthHoldPlan holds,
      BigDecimal crossAvailable
  ) {
    BigDecimal current = orZero(order.getHoldAmount());
    BigDecimal required = holds.initialHold();
    if (required == null
        || required.signum() <= 0
        || order.getHoldCurrency() == null
        || risk.holdCurrency() == null
        || !order.getHoldCurrency().equalsIgnoreCase(risk.holdCurrency())) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "Stored Perpetual order hold is inconsistent with the DEPTH remainder");
    }
    BigDecimal increase = required.subtract(current).max(BigDecimal.ZERO);
    if (increase.signum() > 0
        && !isInternalIsolatedClose(order, risk)
        && orZero(crossAvailable).compareTo(increase) < 0) {
      throw new BusinessException(
          ErrorCode.INSUFFICIENT_MARGIN,
          "Fresh available margin cannot cover the pending DEPTH hold increase");
    }
    return increase;
  }

  private boolean tryExecutePerpetual(
      PendingCandidate candidate,
      DemoExecutionPolicy policy
  ) {
    TradingAccountEntity account = accountRepository.findByIdForUpdate(candidate.accountId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    demoExecutionGuard.requireDemo(account, ProductType.LINEAR_PERP, candidate.symbol());
    AccountSymbolSettingEntity lockedSetting = accountSymbolSettingRepository
        .findByAccountIdAndSymbolForUpdate(account.getId(), candidate.symbol())
        .orElseThrow(() -> new BusinessException(
            "INVALID_INSTRUMENT_RULES",
            "Locked Perpetual symbol setting is required"));
    perpetualAccountRiskSnapshotService.requireCurrentLeverageWithinLimit(lockedSetting);
    List<PositionEntity> accountPositions = positionRepository
        .findOpenLinearPerpByAccountIdForUpdate(account.getId());
    List<OrderEntity> accountActiveOrders = orderRepository
        .findActiveLinearPerpByAccountIdForUpdate(account.getId());
    OrderEntity lockedOrder = orderRepository.findByIdForUpdate(candidate.orderId())
        .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found"));
    requirePerpetualCandidate(candidate, account, lockedSetting, lockedOrder);
    if (!candidate.fingerprint().matches(lockedOrder)) {
      return false;
    }
    if (depthOrderExecutionService != null) {
      depthOrderExecutionService.requireCurrentSimplePolicy(policy);
    }
    fullFillCoordinator.requireFresh(candidate.snapshot());
    OrderStatus sourceStatus = lockedOrder.getStatus();
    boolean awaitingStopLimitActivation = isAwaitingStopLimitActivation(lockedOrder);
    if ((lockedOrder.getOrderType() == OrderType.STOP_LIMIT
            && !isStandaloneUserStopLimit(lockedOrder))
        || !isPerpetualTriggered(lockedOrder, candidate.snapshot())) {
      return false;
    }
    if (awaitingStopLimitActivation
        && !perpetualLimitMarketable(lockedOrder, candidate.snapshot())) {
      fullFillCoordinator.requireFresh(candidate.snapshot());
      requirePreparedAccountRiskFresh(candidate.preparedAccountRisk());
      if (orderRepository.activateStopLimitPending(lockedOrder.getId()) != 1) {
        return false;
      }
      orderEventService.record(
          lockedOrder.getId(),
          "ORDER_TRIGGERED",
          OrderStatus.PENDING_ACTIVATION,
          OrderStatus.PENDING,
          null,
          "Perpetual STOP_LIMIT activated and resting");
      lockedOrder.setStatus(OrderStatus.PENDING);
      return false;
    }

    PerpetualAccountRiskSnapshotService.AccountRiskProjection accountRisk =
        perpetualAccountRiskSnapshotService.project(
            account,
            accountPositions,
            accountActiveOrders,
            candidate.preparedAccountRisk());
    perpetualAccountRiskSnapshotService.applyRevaluation(
        account,
        accountPositions,
        accountRisk);
    List<PositionEntity> lockedPositions = accountPositions.stream()
        .filter(position -> candidate.symbol().equals(position.getSymbol()))
        .toList();
    List<OrderEntity> activeOrders = accountActiveOrders.stream()
        .filter(order -> candidate.symbol().equals(order.getSymbol()))
        .toList();
    PerpetualAccountRiskSnapshotService.PreparedSymbolRisk targetRisk =
        candidate.preparedAccountRisk().symbols().get(candidate.symbol());
    if (targetRisk == null) {
      throw new BusinessException(
          ErrorCode.MARKET_DATA_UNAVAILABLE,
          "Target Perpetual risk snapshot was not prepared");
    }
    BigDecimal quantity = canonicalQuantity(lockedOrder);
    OrderType executionType = canonicalPendingExecutionType(lockedOrder);
    PerpetualOrderRiskService.OrderRisk fillRisk = perpetualOrderRiskService.evaluate(
        account.getPositionMode(),
        lockedSetting,
        lockedPositions,
        lockedOrder.getSide(),
        lockedOrder.getPositionSide(),
        Boolean.TRUE.equals(lockedOrder.getReduceOnly()),
        executionType,
        quantity,
        currentPrice(lockedOrder),
        candidate.snapshot(),
        targetRisk.maintenanceMarginRate());
    requireFreshInternalIsolatedClose(
        lockedOrder,
        lockedPositions,
        activeOrders,
        fillRisk);
    if (fillRisk.openingBase().compareTo(BigDecimal.ZERO) > 0
        && accountRisk.crossAvailable().compareTo(BigDecimal.ZERO) < 0) {
      throw new BusinessException(
          ErrorCode.INSUFFICIENT_MARGIN,
          "Fresh Cross available margin cannot support the pending opening fill");
    }
    FullFillExecutionPath path = awaitingStopLimitActivation
        ? FullFillExecutionPath.IMMEDIATE_LIMIT
        : executionType == OrderType.LIMIT
            ? FullFillExecutionPath.RESTING_LIMIT
            : FullFillExecutionPath.TRIGGERED_STOP_MARKET;
    FullFillResult fullFill = fullFillCoordinator.execute(
        new FullFillRequest(
            perpetualExecutionIntent(lockedOrder, quantity),
            lockedOrder.getSymbol(),
            ProductType.LINEAR_PERP,
            lockedOrder.getSide(),
            path,
            quantity,
            path == FullFillExecutionPath.TRIGGERED_STOP_MARKET
                ? null
                : currentPrice(lockedOrder)),
        candidate.snapshot());
    fullFillCoordinator.requireFresh(fullFill);
    requirePreparedAccountRiskFresh(candidate.preparedAccountRisk());
    BigDecimal holdIncrease = requiredPerpetualHoldIncrease(
        lockedOrder,
        fillRisk,
        accountRisk.crossAvailable());
    int claimed = awaitingStopLimitActivation
        ? orderRepository.activateStopLimitWorking(lockedOrder.getId())
        : orderRepository.claimPending(lockedOrder.getId());
    if (claimed != 1) {
      return false;
    }
    try {
      if (awaitingStopLimitActivation) {
        orderEventService.record(
            lockedOrder.getId(),
            "ORDER_TRIGGERED",
            OrderStatus.PENDING_ACTIVATION,
            OrderStatus.WORKING,
            null,
            "Perpetual STOP_LIMIT activated for immediate execution");
      }
      lockedOrder.setStatus(OrderStatus.WORKING);
      increasePerpetualOrderHold(
          lockedOrder,
          account,
          fillRisk,
          holdIncrease);
      for (PositionEntity position : accountPositions) {
        positionRepository.save(position);
      }
      accountRepository.save(account);
      orderFillService.fillPerpetual(
          lockedOrder,
          account,
          fullFill,
          candidate.snapshot().mark(),
          lockedSetting.getLeverage(),
          "Pending Perpetual order hold");
    } catch (RuntimeException exception) {
      // The transaction rolls the claim back; keep the in-memory candidate consistent as well.
      lockedOrder.setStatus(sourceStatus);
      throw exception;
    }
    recordFill(lockedOrder);
    return true;
  }

  private BigDecimal requiredPerpetualHoldIncrease(
      OrderEntity order,
      PerpetualOrderRiskService.OrderRisk fillRisk,
      BigDecimal crossAvailable
  ) {
    BigDecimal stored = orZero(order.getHoldAmount());
    BigDecimal required = orZero(fillRisk.holdAmount());
    if (stored.compareTo(BigDecimal.ZERO) <= 0
        || required.compareTo(BigDecimal.ZERO) <= 0
        || order.getHoldCurrency() == null
        || fillRisk.holdCurrency() == null
        || !order.getHoldCurrency().equalsIgnoreCase(fillRisk.holdCurrency())) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "Stored Perpetual order hold is inconsistent with the canonical fill");
    }
    BigDecimal increase = required.subtract(stored).max(BigDecimal.ZERO);
    if (increase.signum() > 0
        && fillRisk.openingBase().signum() > 0
        && order.getLeverage() != null
        && fillRisk.leverage() < order.getLeverage()) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "Current Perpetual leverage requires more hold than the order snapshot");
    }
    if (increase.signum() == 0 || isInternalIsolatedClose(order, fillRisk)) {
      return increase;
    }
    if (orZero(crossAvailable).compareTo(increase) < 0) {
      throw new BusinessException(
          ErrorCode.INSUFFICIENT_MARGIN,
          "Fresh available margin cannot cover the pending trigger hold increase");
    }
    return increase;
  }

  private void increasePerpetualOrderHold(
      OrderEntity order,
      TradingAccountEntity account,
      PerpetualOrderRiskService.OrderRisk fillRisk,
      BigDecimal increase
  ) {
    if (increase.signum() == 0) {
      return;
    }
    account.setUsedMargin(orZero(account.getUsedMargin()).add(increase));
    if (!isInternalIsolatedClose(order, fillRisk)) {
      account.setFreeMargin(orZero(account.getFreeMargin()).subtract(increase));
    }
    order.setHoldAmount(orZero(order.getHoldAmount()).add(increase));
    orderFillService.recordPerpetualOrderHoldIncrease(
        account,
        increase,
        order.getId());
  }

  private static boolean isInternalIsolatedClose(
      OrderEntity order,
      PerpetualOrderRiskService.OrderRisk fillRisk
  ) {
    return order.getParentPositionId() != null
        && order.getMarginMode() == MarginMode.ISOLATED
        && fillRisk.marginMode() == MarginMode.ISOLATED
        && fillRisk.openingBase().signum() == 0
        && fillRisk.closingBase().signum() > 0;
  }

  private void requirePreparedAccountRiskFresh(
      PerpetualAccountRiskSnapshotService.PreparedAccountRisk preparedAccountRisk
  ) {
    if (preparedAccountRisk == null || preparedAccountRisk.snapshots().isEmpty()) {
      throw new BusinessException(
          ErrorCode.MARKET_DATA_UNAVAILABLE,
          "Prepared Perpetual account risk is unavailable");
    }
    preparedAccountRisk.snapshots().values().forEach(fullFillCoordinator::requireFresh);
  }

  private void requirePerpetualCandidate(
      PendingCandidate candidate,
      TradingAccountEntity account,
      AccountSymbolSettingEntity setting,
      OrderEntity order
  ) {
    if (!candidate.accountId().equals(order.getAccountId())
        || !candidate.symbol().equals(order.getSymbol())
        || order.getProductType() != ProductType.LINEAR_PERP
        || account.getPositionMode() != order.getPositionMode()
        || setting.getMarginMode() != order.getMarginMode()
        || account.getUserId() == null
        || order.getUserId() == null
        || !account.getUserId().equals(order.getUserId())) {
      throw new BusinessException("ORDER_NOT_FOUND", "Pending Perpetual order does not match its lock scope");
    }
    if (setting.getLeverage() == null || setting.getLeverage() <= 0) {
      throw new BusinessException(
          "INVALID_INSTRUMENT_RULES",
          "Locked Perpetual execution leverage must be positive");
    }
  }

  private void requireFreshInternalIsolatedClose(
      OrderEntity order,
      List<PositionEntity> lockedPositions,
      List<OrderEntity> activeOrders,
      PerpetualOrderRiskService.OrderRisk fillRisk
  ) {
    if (order.getParentPositionId() == null
        || order.getMarginMode() != MarginMode.ISOLATED) {
      return;
    }
    if (fillRisk.marginMode() != MarginMode.ISOLATED
        || fillRisk.openingBase().compareTo(BigDecimal.ZERO) != 0
        || fillRisk.closingBase().compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "Position-backed hold no longer represents a pure Isolated close");
    }
    PositionEntity parent = lockedPositions.stream()
        .filter(position -> order.getParentPositionId().equals(position.getId()))
        .filter(position -> position.getPositionMode() == order.getPositionMode())
        .filter(position -> position.getPositionSide() == order.getPositionSide())
        .findFirst()
        .orElseThrow(() -> new BusinessException(
            ErrorCode.ORDER_HOLD_INVALID,
            "Position-backed hold no longer matches the locked position slot"));
    BigDecimal aggregateClosing = BigDecimal.ZERO;
    BigDecimal aggregateHolds = BigDecimal.ZERO;
    for (OrderEntity active : activeOrders == null ? List.<OrderEntity>of() : activeOrders) {
      if (!order.getParentPositionId().equals(active.getParentPositionId())
          || active.getProtectionType() != null) {
        continue;
      }
      aggregateClosing = aggregateClosing.add(activeRemainingBase(active));
      aggregateHolds = aggregateHolds.add(orZero(active.getHoldAmount()));
    }
    BigDecimal freshIncrease = orZero(fillRisk.holdAmount())
        .subtract(orZero(order.getHoldAmount()))
        .max(BigDecimal.ZERO);
    if (aggregateClosing.compareTo(abs(parent.getLots())) > 0) {
      throw new BusinessException(
          ErrorCode.REDUCE_ONLY_EXCEEDS_POSITION,
          "Aggregate Isolated close orders exceed the current position slot");
    }
    if (aggregateHolds.add(freshIncrease).compareTo(fillRisk.isolatedHoldCapacity()) >= 0) {
      throw new BusinessException(
          ErrorCode.MARGIN_REDUCTION_UNSAFE,
          "Aggregate Isolated close holds exceed the current position risk buffer");
    }
  }

  private static BigDecimal activeRemainingBase(OrderEntity order) {
    if (order.getRemainingQuantity() != null
        && order.getRemainingQuantity().compareTo(BigDecimal.ZERO) > 0) {
      return order.getRemainingQuantity();
    }
    return orZero(order.getBaseQuantity());
  }

  private static BigDecimal abs(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value.abs();
  }

  private CreateOrderRequest perpetualExecutionIntent(
      OrderEntity order,
      BigDecimal baseQuantity
  ) {
    OrderType executionType = canonicalPendingExecutionType(order);
    return new CreateOrderRequest(
        order.getAccountId(),
        order.getSymbol(),
        order.getSide(),
        executionType,
        baseQuantity,
        currentPrice(order),
        null,
        null,
        order.getIdempotencyKey(),
        order.getClientOrderId(),
        baseQuantity,
        currentPrice(order),
        order.getLeverage(),
        order.getPositionSide(),
        com.fxplatform.trading.enums.QuantityUnit.BASE,
        order.getMarginMode(),
        executionType == OrderType.STOP_MARKET ? order.getTriggerPrice() : null,
        executionType == OrderType.STOP_MARKET
            ? com.fxplatform.trading.enums.TriggerPriceType.MARK_PRICE
            : null,
        order.getReduceOnly(),
        List.of(),
        order.getTimeInForce(),
        Boolean.TRUE.equals(order.getPostOnly()),
        null,
        null,
        null);
  }

  private OrderType canonicalPendingExecutionType(OrderEntity order) {
    return order.getOrderType() == OrderType.STOP_LIMIT
        ? OrderType.LIMIT
        : order.getOrderType();
  }

  private boolean isAwaitingStopLimitActivation(OrderEntity order) {
    return order.getOrderType() == OrderType.STOP_LIMIT
        && order.getStatus() == OrderStatus.PENDING_ACTIVATION;
  }

  private boolean isStandaloneUserStopLimit(OrderEntity order) {
    return order.getOrderOrigin() == OrderOrigin.USER
        && order.getProtectionType() == null
        && order.getContingencyGroupId() == null
        && order.getParentOrderId() == null;
  }

  private boolean perpetualLimitMarketable(
      OrderEntity order,
      ExecutableMarketSnapshot snapshot
  ) {
    BigDecimal price = currentPrice(order);
    BigDecimal executable = order.getSide() == OrderSide.BUY ? snapshot.ask() : snapshot.bid();
    return price != null && executable != null && (order.getSide() == OrderSide.BUY
        ? executable.compareTo(price) <= 0
        : executable.compareTo(price) >= 0);
  }

  private boolean isPerpetualTriggered(
      OrderEntity order,
      ExecutableMarketSnapshot snapshot
  ) {
    if (order.getOrderType() == OrderType.LIMIT
        || (order.getOrderType() == OrderType.STOP_LIMIT
            && order.getStatus() == OrderStatus.PENDING)) {
      return perpetualLimitMarketable(order, snapshot);
    }
    if (order.getOrderType() == OrderType.STOP_MARKET) {
      BigDecimal trigger = order.getTriggerPrice();
      return trigger != null && snapshot.mark() != null && (order.getSide() == OrderSide.BUY
          ? snapshot.mark().compareTo(trigger) >= 0
          : snapshot.mark().compareTo(trigger) <= 0);
    }
    if (order.getOrderType() == OrderType.STOP_LIMIT
        && order.getStatus() == OrderStatus.PENDING_ACTIVATION) {
      BigDecimal trigger = order.getTriggerPrice();
      return trigger != null && snapshot.mark() != null && (order.getSide() == OrderSide.BUY
          ? snapshot.mark().compareTo(trigger) >= 0
          : snapshot.mark().compareTo(trigger) <= 0);
    }
    return false;
  }

  private boolean isPerpetualDepthExecutableState(
      OrderEntity order,
      ExecutableMarketSnapshot snapshot
  ) {
    if (order.getOrderType() == OrderType.LIMIT) {
      return order.getStatus() == OrderStatus.PENDING
          || order.getStatus() == OrderStatus.PARTIALLY_FILLED;
    }
    if (order.getOrderType() == OrderType.STOP_LIMIT) {
      if (!isStandaloneUserStopLimit(order)) {
        return false;
      }
      if (order.getStatus() == OrderStatus.PENDING_ACTIVATION) {
        return isPerpetualTriggered(order, snapshot);
      }
      return order.getStatus() == OrderStatus.PENDING
          || order.getStatus() == OrderStatus.PARTIALLY_FILLED;
    }
    if (order.getOrderType() == OrderType.STOP_MARKET) {
      if (order.getStatus() == OrderStatus.PARTIALLY_FILLED) {
        return true;
      }
      return order.getStatus() == OrderStatus.PENDING
          && isPerpetualTriggered(order, snapshot);
    }
    return false;
  }

  private BigDecimal depthRemainingQuantity(OrderEntity order) {
    BigDecimal remaining = order.getRemainingQuantity();
    if (remaining == null || remaining.signum() <= 0) {
      throw new BusinessException(
          "BAD_QUANTITY",
          "Pending Perpetual DEPTH order requires a positive remaining quantity");
    }
    return remaining;
  }

  private boolean tryExecuteP0(PendingCandidate candidate) {
    TradingAccountEntity account = accountRepository.findByIdForUpdate(candidate.accountId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    demoExecutionGuard.requireDemo(account, candidate.productType(), candidate.symbol());
    lockExistingP0State(account.getId(), candidate.productType());
    OrderEntity lockedOrder = orderRepository.findByIdForUpdate(candidate.orderId())
        .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found"));
    if (lockedOrder.getStatus() != OrderStatus.PENDING || !isTriggered(lockedOrder, candidate.snapshot())) {
      return false;
    }

    BigDecimal quantity = canonicalQuantity(lockedOrder);
    FullFillExecutionPath path = lockedOrder.getOrderType() == OrderType.LIMIT
        ? FullFillExecutionPath.RESTING_LIMIT
        : FullFillExecutionPath.TRIGGERED_STOP_MARKET;
    CreateOrderRequest executionIntent = toRequest(lockedOrder, quantity);
    FullFillResult fullFill = fullFillCoordinator.execute(
        new FullFillRequest(
            executionIntent,
            lockedOrder.getSymbol(),
            candidate.productType(),
            lockedOrder.getSide(),
            path,
            quantity,
            path == FullFillExecutionPath.RESTING_LIMIT ? currentPrice(lockedOrder) : null),
        candidate.snapshot());

    ensureP0MutationState(account.getId(), candidate.productType(), lockedOrder.getSymbol());
    // The final freshness gate must stay adjacent to the first claim/write.
    fullFillCoordinator.requireFresh(fullFill);
    if (orderRepository.claimPending(lockedOrder.getId()) != 1) {
      return false;
    }
    lockedOrder.setStatus(OrderStatus.WORKING);
    BigDecimal lockedRequiredMargin = lockedOrder.getHoldAmount() != null
        ? lockedOrder.getHoldAmount()
        : candidate.requiredMargin();
    orderFillService.fill(
        lockedOrder,
        account,
        fullFill,
        lockedRequiredMargin,
        "Pending order margin hold");
    recordFill(lockedOrder);
    return true;
  }

  private boolean prepareAndExecuteLegacy(
      OrderEntity order,
      TradingAccountEntity accountSnapshot,
      boolean joinCallerTransaction
  ) {
    if (order.getRequestedPrice() == null) {
      return false;
    }
    ProductType productType = requestedProduct(order.getSymbol());
    QuoteResponse quote = quoteService.freshQuote(order.getSymbol());
    if (!isTriggered(order, quote)) {
      return false;
    }
    BigDecimal requiredMargin = order.getHoldAmount() != null
        ? order.getHoldAmount()
        : riskCheckService.checkOrder(accountSnapshot, toRequest(order, canonicalQuantity(order)));
    LegacyPendingCandidate candidate = new LegacyPendingCandidate(
        order,
        quote,
        productType,
        requiredMargin,
        executablePrice(order, quote));
    return joinCallerTransaction
        ? transactionExecutor.executeJoined(() -> tryExecuteLegacy(candidate))
        : transactionExecutor.execute(() -> tryExecuteLegacy(candidate));
  }

  private boolean tryExecuteLegacy(LegacyPendingCandidate candidate) {
    OrderEntity snapshotOrder = candidate.order();
    TradingAccountEntity account = accountRepository.findByIdForUpdate(snapshotOrder.getAccountId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    demoExecutionGuard.requireDemo(account, candidate.productType(), snapshotOrder.getSymbol());
    lockMutationState(account.getId(), candidate.productType(), snapshotOrder.getSymbol());
    OrderEntity lockedOrder = orderRepository.findByIdForUpdate(snapshotOrder.getId())
        .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found"));
    if (lockedOrder.getStatus() != OrderStatus.PENDING || !isTriggered(lockedOrder, candidate.quote())) {
      return false;
    }
    if (orderRepository.claimPending(lockedOrder.getId()) != 1) {
      return false;
    }
    lockedOrder.setStatus(OrderStatus.WORKING);
    orderFillService.fill(
        lockedOrder,
        account,
        candidate.executionPrice(),
        Instant.now(),
        candidate.requiredMargin(),
        "Pending order margin hold");
    recordFill(lockedOrder);
    return true;
  }

  private void recordFill(OrderEntity order) {
    orderEventService.record(
        order.getId(),
        "ORDER_FILLED",
        OrderStatus.WORKING,
        OrderStatus.FILLED,
        null,
        "Pending order filled");
  }

  private boolean isTriggered(OrderEntity order, ExecutableMarketSnapshot snapshot) {
    boolean limitLike = order.getOrderType() == OrderType.LIMIT
        || (order.getOrderType() == OrderType.STOP_LIMIT
            && order.getStatus() == OrderStatus.PENDING);
    BigDecimal requested = limitLike ? currentPrice(order) : triggerOrRequestedPrice(order);
    if (requested == null) {
      return false;
    }
    if (limitLike) {
      BigDecimal executable = order.getSide() == OrderSide.BUY ? snapshot.ask() : snapshot.bid();
      return executable != null && (order.getSide() == OrderSide.BUY
          ? executable.compareTo(requested) <= 0
          : executable.compareTo(requested) >= 0);
    }
    if (order.getOrderType() == OrderType.STOP || order.getOrderType() == OrderType.STOP_MARKET) {
      return snapshot.last() != null && (order.getSide() == OrderSide.BUY
          ? snapshot.last().compareTo(requested) >= 0
          : snapshot.last().compareTo(requested) <= 0);
    }
    if (order.getOrderType() == OrderType.STOP_LIMIT
        && order.getStatus() == OrderStatus.PENDING_ACTIVATION) {
      return snapshot.last() != null && (order.getSide() == OrderSide.BUY
          ? snapshot.last().compareTo(requested) >= 0
          : snapshot.last().compareTo(requested) <= 0);
    }
    return false;
  }

  private boolean isTriggered(OrderEntity order, QuoteResponse quote) {
    BigDecimal price = executablePrice(order, quote);
    BigDecimal requestedPrice = order.getRequestedPrice();
    if (requestedPrice == null) {
      return false;
    }
    if (order.getOrderType() == OrderType.LIMIT) {
      return order.getSide() == OrderSide.BUY
          ? price.compareTo(requestedPrice) <= 0
          : price.compareTo(requestedPrice) >= 0;
    }
    if (order.getOrderType() == OrderType.STOP) {
      return order.getSide() == OrderSide.BUY
          ? price.compareTo(requestedPrice) >= 0
          : price.compareTo(requestedPrice) <= 0;
    }
    return false;
  }

  private BigDecimal executablePrice(OrderEntity order, QuoteResponse quote) {
    return order.getSide() == OrderSide.BUY ? quote.ask() : quote.bid();
  }

  private CreateOrderRequest toRequest(OrderEntity order, BigDecimal quantity) {
    OrderType executionType = order.getOrderType() == OrderType.STOP
        ? OrderType.STOP_MARKET
        : order.getOrderType();
    return new CreateOrderRequest(
        order.getAccountId(),
        order.getSymbol(),
        order.getSide(),
        executionType,
        order.getLots(),
        order.getRequestedPrice(),
        order.getStopLoss(),
        order.getTakeProfit(),
        order.getIdempotencyKey(),
        order.getClientOrderId(),
        quantity,
        order.getPrice(),
        order.getLeverage(),
        order.getPositionSide(),
        order.getQuantityUnit(),
        order.getMarginMode(),
        order.getTriggerPrice(),
        order.getTriggerPriceType(),
        order.getReduceOnly(),
        List.of());
  }

  private ExecutableMarketSnapshot resolveExecutableSnapshot(String symbol, ProductType productType) {
    Instant to = Instant.now();
    CandleRequest candles = new CandleRequest("1m", to.minus(Duration.ofMinutes(30)), to);
    return productType == ProductType.CRYPTO_SPOT
        ? ExecutableMarketSnapshot.from(marketBundleResolver.resolveSpot(symbol, candles))
        : ExecutableMarketSnapshot.from(marketBundleResolver.resolvePerp(symbol, candles));
  }

  private ProductType requestedProduct(String canonicalSymbol) {
    return canonicalSymbol.endsWith("-PERP") ? ProductType.LINEAR_PERP : ProductType.CRYPTO_SPOT;
  }

  private boolean isP0Order(OrderEntity order) {
    if (order.getSymbol() == null
        || !P0_SYMBOLS.contains(SymbolNormalizer.normalize(order.getSymbol()))) {
      return false;
    }
    return requestedProduct(order.getSymbol()) == ProductType.LINEAR_PERP
        || (marketBundleResolver != null && fullFillCoordinator != null);
  }

  private BigDecimal canonicalQuantity(OrderEntity order) {
    if (order.getBaseQuantity() != null) {
      return order.getBaseQuantity();
    }
    return order.getQuantity() != null ? order.getQuantity() : order.getLots();
  }

  private BigDecimal currentPrice(OrderEntity order) {
    return order.getPrice() != null ? order.getPrice() : order.getRequestedPrice();
  }

  private BigDecimal triggerOrRequestedPrice(OrderEntity order) {
    return order.getTriggerPrice() != null ? order.getTriggerPrice() : order.getRequestedPrice();
  }

  private void lockMutationState(UUID accountId, ProductType productType, String canonicalSymbol) {
    if (productType == ProductType.CRYPTO_SPOT) {
      if (canonicalSymbol != null && canonicalSymbol.endsWith("USDT") && canonicalSymbol.length() > 4) {
        String baseAsset = canonicalSymbol.substring(0, canonicalSymbol.length() - 4);
        walletService.lockBalancesInOrder(accountId, List.of(baseAsset, "USDT"));
        spotPositionService.lockOrCreate(accountId, baseAsset, "USDT");
      } else {
        walletBalanceRepository.findByAccountIdForUpdate(accountId);
      }
      return;
    }
    positionRepository.findOpenByAccountIdForUpdate(accountId);
  }

  private void lockExistingP0State(UUID accountId, ProductType productType) {
    if (productType == ProductType.CRYPTO_SPOT) {
      walletBalanceRepository.findByAccountIdForUpdate(accountId);
      spotPositionService.lockExisting(accountId);
      return;
    }
    positionRepository.findOpenByAccountIdForUpdate(accountId);
  }

  private void ensureP0MutationState(
      UUID accountId,
      ProductType productType,
      String canonicalSymbol
  ) {
    if (productType != ProductType.CRYPTO_SPOT
        || canonicalSymbol == null
        || !canonicalSymbol.endsWith("USDT")
        || canonicalSymbol.length() <= 4) {
      return;
    }
    String baseAsset = canonicalSymbol.substring(0, canonicalSymbol.length() - 4);
    walletService.lockBalancesInOrder(accountId, List.of(baseAsset, "USDT"));
    spotPositionService.lockOrCreate(accountId, baseAsset, "USDT");
  }

  private record PendingCandidate(
      UUID orderId,
      UUID accountId,
      String symbol,
      ProductType productType,
      BigDecimal requiredMargin,
      ExecutableMarketSnapshot snapshot,
      PerpetualAccountRiskSnapshotService.PreparedAccountRisk preparedAccountRisk,
      PendingOrderExecutionFingerprint fingerprint
  ) {
  }

  private record LegacyPendingCandidate(
      OrderEntity order,
      QuoteResponse quote,
      ProductType productType,
      BigDecimal requiredMargin,
      BigDecimal executionPrice
  ) {
  }
}
