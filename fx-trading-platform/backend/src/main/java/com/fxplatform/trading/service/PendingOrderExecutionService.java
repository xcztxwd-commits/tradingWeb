package com.fxplatform.trading.service;

import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.execution.FullFillExecutionPath;
import com.fxplatform.execution.FullFillRequest;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.MarginMode;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Scans pending orders and executes each candidate in its own local transaction. */
@Service
@ConditionalOnProperty(prefix = "trading", name = "pending-order-execution-enabled", havingValue = "true")
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

  @Scheduled(fixedDelayString = "${trading.pending-order-scan-ms:1000}")
  public int executePendingOrders() {
    int filled = 0;
    for (OrderEntity order : orderRepository.findByStatus(OrderStatus.PENDING)) {
      boolean demoAuthorized = false;
      try {
        TradingAccountEntity accountSnapshot = requireDemoCandidate(order);
        demoAuthorized = true;
        boolean executed = isP0Order(order)
            ? prepareAndExecuteP0(order, accountSnapshot)
            : prepareAndExecuteLegacy(order, accountSnapshot);
        if (executed) {
          filled++;
        }
      } catch (BusinessException exception) {
        if (demoAuthorized && !DEMO_GUARD_REJECTION_CODES.contains(exception.getCode())) {
          recordWorkerFailure(order, exception.getCode());
        }
        log.debug(
            "Pending order {} remains pending after business rejection {}: {}",
            order.getId(),
            exception.getCode(),
            exception.getMessage());
      } catch (RuntimeException exception) {
        if (demoAuthorized) {
          recordWorkerFailure(order, ErrorCode.EXECUTION_UNAVAILABLE);
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

  private TradingAccountEntity requireDemoCandidate(OrderEntity order) {
    ProductType productType = requestedProduct(order.getSymbol());
    TradingAccountEntity account = accountRepository.findById(order.getAccountId())
        .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));
    demoExecutionGuard.requireDemo(account, productType, order.getSymbol());
    return account;
  }

  private void recordWorkerFailure(OrderEntity order, String errorCode) {
    try {
      orderEventService.recordWorkerFailure(
          order.getId(),
          EXECUTION_FAILURE_EVENT,
          OrderStatus.PENDING,
          errorCode,
          EXECUTION_FAILURE_MESSAGE);
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

  private boolean prepareAndExecuteP0(
      OrderEntity order,
      TradingAccountEntity accountSnapshot
  ) {
    ProductType productType = requestedProduct(order.getSymbol());

    if (productType == ProductType.CRYPTO_SPOT && pendingOrderExecutionProcessor != null) {
      return prepareAndExecuteSpot(order);
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
      return prepareAndExecutePerpetual(order);
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
        null);
    return transactionExecutor.execute(() -> tryExecuteP0(candidate));
  }

  private boolean prepareAndExecuteSpot(OrderEntity order) {
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        ExecutableMarketSnapshot snapshot = resolveExecutableSnapshot(
            order.getSymbol(), ProductType.CRYPTO_SPOT);
        if (!isTriggered(order, snapshot)) {
          return false;
        }
        return pendingOrderExecutionProcessor.process(order, snapshot);
      } catch (BusinessException exception) {
        if (attempt == 0 && "MARKET_DATA_STALE".equals(exception.getCode())) {
          continue;
        }
        throw exception;
      }
    }
    return false;
  }

  private boolean prepareAndExecutePerpetual(OrderEntity order) {
    BusinessException lastStale = null;
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        ExecutableMarketSnapshot snapshot = resolveExecutableSnapshot(
            order.getSymbol(), ProductType.LINEAR_PERP);
        if (!isPerpetualTriggered(order, snapshot)) {
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
                Map.of(order.getSymbol(), snapshot)));
        return transactionExecutor.execute(() -> tryExecutePerpetual(candidate));
      } catch (BusinessException exception) {
        if (!ErrorCode.MARKET_DATA_STALE.equals(exception.getCode()) || attempt > 0) {
          throw exception;
        }
        lastStale = exception;
      }
    }
    throw new BusinessException(
        ErrorCode.MARKET_DATA_STALE,
        lastStale == null ? "No fresh executable Perpetual snapshot" : lastStale.getMessage());
  }

  private boolean tryExecutePerpetual(PendingCandidate candidate) {
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
    if (lockedOrder.getStatus() != OrderStatus.PENDING
        || !isPerpetualTriggered(lockedOrder, candidate.snapshot())) {
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
    PerpetualOrderRiskService.OrderRisk fillRisk = perpetualOrderRiskService.evaluate(
        account.getPositionMode(),
        lockedSetting,
        lockedPositions,
        lockedOrder.getSide(),
        lockedOrder.getPositionSide(),
        Boolean.TRUE.equals(lockedOrder.getReduceOnly()),
        lockedOrder.getOrderType(),
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
    FullFillExecutionPath path = lockedOrder.getOrderType() == OrderType.LIMIT
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
            path == FullFillExecutionPath.RESTING_LIMIT ? currentPrice(lockedOrder) : null),
        candidate.snapshot());
    BigDecimal holdIncrease = orZero(fillRisk.holdAmount())
        .subtract(orZero(lockedOrder.getHoldAmount()))
        .max(BigDecimal.ZERO);
    boolean internalIsolatedClose = isInternalIsolatedClose(lockedOrder, fillRisk);
    if (holdIncrease.signum() > 0
        && !internalIsolatedClose
        && accountRisk.crossAvailable().compareTo(holdIncrease) < 0) {
      throw new BusinessException(
          ErrorCode.INSUFFICIENT_MARGIN,
          "Fresh available margin cannot cover the pending trigger hold increase");
    }

    fullFillCoordinator.requireFresh(fullFill);
    requirePreparedAccountRiskFresh(candidate.preparedAccountRisk());
    if (orderRepository.claimPending(lockedOrder.getId()) != 1) {
      return false;
    }
    lockedOrder.setStatus(OrderStatus.WORKING);
    try {
      if (holdIncrease.signum() > 0) {
        account.setUsedMargin(orZero(account.getUsedMargin()).add(holdIncrease));
        if (!internalIsolatedClose) {
          account.setFreeMargin(orZero(account.getFreeMargin()).subtract(holdIncrease));
        }
        lockedOrder.setHoldAmount(orZero(lockedOrder.getHoldAmount()).add(holdIncrease));
        orderFillService.recordPerpetualOrderHoldIncrease(
            account,
            holdIncrease,
            lockedOrder.getId());
      }
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
      lockedOrder.setStatus(OrderStatus.PENDING);
      throw exception;
    }
    recordFill(lockedOrder);
    return true;
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
    if (aggregateClosing.compareTo(abs(parent.getLots())) > 0) {
      throw new BusinessException(
          ErrorCode.REDUCE_ONLY_EXCEEDS_POSITION,
          "Aggregate Isolated close orders exceed the current position slot");
    }
    BigDecimal freshIncrease = orZero(fillRisk.holdAmount())
        .subtract(orZero(order.getHoldAmount()))
        .max(BigDecimal.ZERO);
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
    return new CreateOrderRequest(
        order.getAccountId(),
        order.getSymbol(),
        order.getSide(),
        order.getOrderType(),
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
        order.getTriggerPrice(),
        order.getOrderType() == OrderType.STOP_MARKET
            ? com.fxplatform.trading.enums.TriggerPriceType.MARK_PRICE
            : null,
        order.getReduceOnly(),
        List.of());
  }

  private boolean isPerpetualTriggered(
      OrderEntity order,
      ExecutableMarketSnapshot snapshot
  ) {
    if (order.getOrderType() == OrderType.LIMIT) {
      BigDecimal price = currentPrice(order);
      BigDecimal executable = order.getSide() == OrderSide.BUY ? snapshot.ask() : snapshot.bid();
      return price != null && executable != null && (order.getSide() == OrderSide.BUY
          ? executable.compareTo(price) <= 0
          : executable.compareTo(price) >= 0);
    }
    if (order.getOrderType() == OrderType.STOP_MARKET) {
      BigDecimal trigger = order.getTriggerPrice();
      return trigger != null && snapshot.mark() != null && (order.getSide() == OrderSide.BUY
          ? snapshot.mark().compareTo(trigger) >= 0
          : snapshot.mark().compareTo(trigger) <= 0);
    }
    return false;
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
      TradingAccountEntity accountSnapshot
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
    return transactionExecutor.execute(() -> tryExecuteLegacy(candidate));
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
    BigDecimal requested = triggerOrRequestedPrice(order);
    if (requested == null) {
      return false;
    }
    if (order.getOrderType() == OrderType.LIMIT) {
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
      PerpetualAccountRiskSnapshotService.PreparedAccountRisk preparedAccountRisk
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
