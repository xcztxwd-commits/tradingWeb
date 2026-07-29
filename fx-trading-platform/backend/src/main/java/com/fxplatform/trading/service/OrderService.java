package com.fxplatform.trading.service;

import static com.fxplatform.common.money.MoneyAmount.accountEquity;
import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.common.money.ExactNumeric;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.execution.ExecutionAdapter;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.ExecutionResult;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.execution.FullFillExecutionPath;
import com.fxplatform.execution.FullFillRequest;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.execution.FullFillPricingProjection;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.dto.request.UpdateOrderRequest;
import com.fxplatform.trading.dto.response.OrderEventResponse;
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

  private static final int MAX_MARKET_ATTEMPTS = 2;
  private static final int SPOT_WALLET_SCALE = 8;
  private static final int TRAILING_AUTHORITY_MARK_PRECISION = 24;
  private static final int TRAILING_AUTHORITY_MARK_SCALE = 10;
  private static final int TRAILING_TRIGGER_PRICE_PRECISION = 31;
  private static final int TRAILING_TRIGGER_PRICE_SCALE = 10;
  private static final Set<String> P0_SYMBOLS = Set.of(
      "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
      "BTCUSDT-PERP", "ETHUSDT-PERP", "BNBUSDT-PERP", "SOLUSDT-PERP", "XRPUSDT-PERP");
  private static final Set<OrderStatus> ACTIVE_CLIENT_ORDER_STATUSES = Set.of(
      OrderStatus.RECEIVED,
      OrderStatus.VALIDATING,
      OrderStatus.ACCEPTED,
      OrderStatus.PENDING_ACTIVATION,
      OrderStatus.PENDING,
      OrderStatus.WORKING,
      OrderStatus.PARTIALLY_FILLED,
      OrderStatus.CANCEL_PENDING);

  private final OrderRepository orderRepository;
  private final TradingAccountRepository accountRepository;
  private final RiskCheckService riskCheckService;
  private final ExecutionAdapter executionAdapter;
  private final OrderFillService orderFillService;
  private final LedgerService ledgerService;
  private final WalletService walletService;
  private final OrderEventService orderEventService;
  private final OrderCommandFactory orderCommandFactory;
  private final OrderEntityFactory orderEntityFactory;
  private final OrderResponseMapper orderResponseMapper;
  private final OrderStatusPolicy orderStatusPolicy;
  private final DemoExecutionGuard demoExecutionGuard;
  private final WalletBalanceRepository walletBalanceRepository;
  private final PositionRepository positionRepository;
  private final SpotPositionService spotPositionService;
  private final MarketBundleResolver marketBundleResolver;
  private final FullFillCoordinator fullFillCoordinator;
  private final TradingTransactionExecutor transactionExecutor;
  private final SymbolRepository symbolRepository;
  private final InstrumentRulesEngine instrumentRulesEngine;
  private final QuantityConversionService quantityConversionService;
  private final OrderHoldCalculator orderHoldCalculator;
  private final AccountSymbolSettingRepository accountSymbolSettingRepository;
  private final PerpetualOrderRiskService perpetualOrderRiskService;
  private final PerpetualAccountRiskSnapshotService perpetualAccountRiskSnapshotService;
  private OcoOrderService ocoOrderService;
  private ProtectionOrderService protectionOrderService;
  private DepthOrderExecutionService depthOrderExecutionService;

  @Autowired
  public OrderService(
      OrderRepository orderRepository,
      TradingAccountRepository accountRepository,
      RiskCheckService riskCheckService,
      ExecutionAdapter executionAdapter,
      OrderFillService orderFillService,
      LedgerService ledgerService,
      WalletService walletService,
      OrderEventService orderEventService,
      OrderCommandFactory orderCommandFactory,
      OrderEntityFactory orderEntityFactory,
      OrderResponseMapper orderResponseMapper,
      OrderStatusPolicy orderStatusPolicy,
      DemoExecutionGuard demoExecutionGuard,
      WalletBalanceRepository walletBalanceRepository,
      PositionRepository positionRepository,
      SpotPositionService spotPositionService,
      MarketBundleResolver marketBundleResolver,
      FullFillCoordinator fullFillCoordinator,
      TradingTransactionExecutor transactionExecutor,
      SymbolRepository symbolRepository,
      InstrumentRulesEngine instrumentRulesEngine,
      QuantityConversionService quantityConversionService,
      OrderHoldCalculator orderHoldCalculator,
      AccountSymbolSettingRepository accountSymbolSettingRepository,
      PerpetualOrderRiskService perpetualOrderRiskService,
      PerpetualAccountRiskSnapshotService perpetualAccountRiskSnapshotService
  ) {
    this.orderRepository = orderRepository;
    this.accountRepository = accountRepository;
    this.riskCheckService = riskCheckService;
    this.executionAdapter = executionAdapter;
    this.orderFillService = orderFillService;
    this.ledgerService = ledgerService;
    this.walletService = walletService;
    this.orderEventService = orderEventService;
    this.orderCommandFactory = orderCommandFactory;
    this.orderEntityFactory = orderEntityFactory;
    this.orderResponseMapper = orderResponseMapper;
    this.orderStatusPolicy = orderStatusPolicy;
    this.demoExecutionGuard = demoExecutionGuard;
    this.walletBalanceRepository = walletBalanceRepository;
    this.positionRepository = positionRepository;
    this.spotPositionService = spotPositionService;
    this.marketBundleResolver = marketBundleResolver;
    this.fullFillCoordinator = fullFillCoordinator;
    this.transactionExecutor = transactionExecutor;
    this.symbolRepository = symbolRepository;
    this.instrumentRulesEngine = instrumentRulesEngine;
    this.quantityConversionService = quantityConversionService;
    this.orderHoldCalculator = orderHoldCalculator;
    this.accountSymbolSettingRepository = accountSymbolSettingRepository;
    this.perpetualOrderRiskService = perpetualOrderRiskService;
    this.perpetualAccountRiskSnapshotService = perpetualAccountRiskSnapshotService;
  }

  /** Compatibility constructor for Task 6 Spot fixtures. */
  public OrderService(
      OrderRepository orderRepository,
      TradingAccountRepository accountRepository,
      RiskCheckService riskCheckService,
      ExecutionAdapter executionAdapter,
      OrderFillService orderFillService,
      LedgerService ledgerService,
      WalletService walletService,
      OrderEventService orderEventService,
      OrderCommandFactory orderCommandFactory,
      OrderEntityFactory orderEntityFactory,
      OrderResponseMapper orderResponseMapper,
      OrderStatusPolicy orderStatusPolicy,
      DemoExecutionGuard demoExecutionGuard,
      WalletBalanceRepository walletBalanceRepository,
      PositionRepository positionRepository,
      SpotPositionService spotPositionService,
      MarketBundleResolver marketBundleResolver,
      FullFillCoordinator fullFillCoordinator,
      TradingTransactionExecutor transactionExecutor,
      SymbolRepository symbolRepository,
      InstrumentRulesEngine instrumentRulesEngine,
      QuantityConversionService quantityConversionService,
      OrderHoldCalculator orderHoldCalculator
  ) {
    this(
        orderRepository,
        accountRepository,
        riskCheckService,
        executionAdapter,
        orderFillService,
        ledgerService,
        walletService,
        orderEventService,
        orderCommandFactory,
        orderEntityFactory,
        orderResponseMapper,
        orderStatusPolicy,
        demoExecutionGuard,
        walletBalanceRepository,
        positionRepository,
        spotPositionService,
        marketBundleResolver,
        fullFillCoordinator,
        transactionExecutor,
        symbolRepository,
        instrumentRulesEngine,
        quantityConversionService,
        orderHoldCalculator,
        null,
        null,
        null);
  }

  /** Compatibility constructor for Task 5 and historical unit fixtures. */
  public OrderService(
      OrderRepository orderRepository,
      TradingAccountRepository accountRepository,
      RiskCheckService riskCheckService,
      ExecutionAdapter executionAdapter,
      OrderFillService orderFillService,
      LedgerService ledgerService,
      WalletService walletService,
      OrderEventService orderEventService,
      OrderCommandFactory orderCommandFactory,
      OrderEntityFactory orderEntityFactory,
      OrderResponseMapper orderResponseMapper,
      OrderStatusPolicy orderStatusPolicy,
      DemoExecutionGuard demoExecutionGuard,
      WalletBalanceRepository walletBalanceRepository,
      PositionRepository positionRepository,
      SpotPositionService spotPositionService,
      MarketBundleResolver marketBundleResolver,
      FullFillCoordinator fullFillCoordinator,
      TradingTransactionExecutor transactionExecutor
  ) {
    this(
        orderRepository,
        accountRepository,
        riskCheckService,
        executionAdapter,
        orderFillService,
        ledgerService,
        walletService,
        orderEventService,
        orderCommandFactory,
        orderEntityFactory,
        orderResponseMapper,
        orderStatusPolicy,
        demoExecutionGuard,
        walletBalanceRepository,
        positionRepository,
        spotPositionService,
        marketBundleResolver,
        fullFillCoordinator,
        transactionExecutor,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /** Compatibility constructor for unit fixtures that exercise unreachable legacy products. */
  public OrderService(
      OrderRepository orderRepository,
      TradingAccountRepository accountRepository,
      RiskCheckService riskCheckService,
      ExecutionAdapter executionAdapter,
      OrderFillService orderFillService,
      LedgerService ledgerService,
      WalletService walletService,
      OrderEventService orderEventService,
      OrderCommandFactory orderCommandFactory,
      OrderEntityFactory orderEntityFactory,
      OrderResponseMapper orderResponseMapper,
      OrderStatusPolicy orderStatusPolicy,
      DemoExecutionGuard demoExecutionGuard,
      WalletBalanceRepository walletBalanceRepository,
      PositionRepository positionRepository,
      SpotPositionService spotPositionService
  ) {
    this(
        orderRepository,
        accountRepository,
        riskCheckService,
        executionAdapter,
        orderFillService,
        ledgerService,
        walletService,
        orderEventService,
        orderCommandFactory,
        orderEntityFactory,
        orderResponseMapper,
        orderStatusPolicy,
        demoExecutionGuard,
        walletBalanceRepository,
        positionRepository,
        spotPositionService,
        null,
        null,
        new TradingTransactionExecutor());
  }

  public OrderResponse createOrder(UserPrincipal principal, CreateOrderRequest request) {
    try {
      CreateOrderNumericBoundary.requireSafe(request);
      requireAdvancedOrderExecutionSupported(request);
      OrderCommand command = orderCommandFactory.from(principal, request);
      String requestFingerprint = OrderRequestFingerprint.calculate(request);
      return findExistingOrder(command)
          .map(order -> requireMatchingReplay(
              order,
              command,
              request,
              requestFingerprint))
          .map(orderResponseMapper::toResponse)
          .orElseGet(() -> createNewOrderOrReturnExisting(
              command,
              request,
              requestFingerprint));
    } catch (DataAccessException exception) {
      throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "Order persistence is temporarily unavailable",
          exception);
    }
  }

  public List<OrderResponse> orders(UserPrincipal principal) {
    return orderRepository.findByUserIdOrderByCreatedAtDesc(principal.id())
        .stream()
        .map(orderResponseMapper::toResponse)
        .toList();
  }

  public List<OrderEventResponse> orderEvents(UserPrincipal principal, UUID orderId) {
    requireOwnedOrder(principal.id(), orderId);
    return orderEventService.events(orderId);
  }

  @Transactional
  public OrderResponse cancelOrder(UserPrincipal principal, UUID orderId) {
    OrderEntity orderSnapshot = requireOwnedOrder(principal.id(), orderId);
    if (orderSnapshot.getProtectionType() != null) {
      if (protectionOrderService == null) {
        throw new BusinessException(
            "EXECUTION_UNAVAILABLE",
            "Perpetual protection service is unavailable");
      }
      return protectionOrderService.cancel(principal.id(), orderId);
    }
    if (orderSnapshot.getContingencyGroupId() != null) {
      if (ocoOrderService == null) {
        throw new BusinessException("EXECUTION_UNAVAILABLE", "OCO order service is unavailable");
      }
      com.fxplatform.trading.dto.response.OcoOrderGroupResponse group =
          ocoOrderService.cancelByLeg(principal, orderId);
      if (group.limitOrder() != null && orderId.equals(group.limitOrder().id())) {
        return group.limitOrder();
      }
      if (group.stopOrder() != null && orderId.equals(group.stopOrder().id())) {
        return group.stopOrder();
      }
      throw new BusinessException("OCO_GROUP_INCOMPLETE", "Canceled OCO group does not contain the leg");
    }
    if (!isSingleOrderCancelableStatus(orderSnapshot.getStatus())) {
      throw new BusinessException(
          "ORDER_NOT_CANCELABLE",
          "Only pending, partially filled, or awaiting-activation orders can be canceled");
    }

    TradingAccountEntity accountSnapshot = requireOwnedAccount(principal.id(), orderSnapshot.getAccountId());
    ProductType productType = requestedProduct(orderSnapshot.getSymbol());
    boolean spotWalletHold = riskCheckService.isSpotSymbol(orderSnapshot.getSymbol());
    demoExecutionGuard.requireDemo(accountSnapshot, productType, orderSnapshot.getSymbol());

    TradingAccountEntity account = accountRepository
        .findByIdAndUserIdForUpdate(orderSnapshot.getAccountId(), principal.id())
        .orElseThrow(() -> new AuthorizationException("ACCOUNT_NOT_FOUND", "Account not found"));
    demoExecutionGuard.requireDemo(account, productType, orderSnapshot.getSymbol());
    List<PositionEntity> lockedPerpetualPositions = List.of();
    List<OrderEntity> lockedPerpetualActiveOrders = List.of();
    if (productType == ProductType.LINEAR_PERP
        && isP0Symbol(orderSnapshot.getSymbol())
        && accountSymbolSettingRepository != null) {
      accountSymbolSettingRepository.findByAccountIdAndSymbolForUpdate(
          account.getId(), orderSnapshot.getSymbol());
      List<PositionEntity> positions = positionRepository.findOpenLinearPerpBySymbolForUpdate(
          account.getId(), orderSnapshot.getSymbol());
      lockedPerpetualPositions = positions == null ? List.of() : List.copyOf(positions);
      List<OrderEntity> activeOrders = orderRepository.findActiveLinearPerpBySymbolForUpdate(
          account.getId(), orderSnapshot.getSymbol());
      lockedPerpetualActiveOrders = activeOrders == null
          ? List.of()
          : List.copyOf(activeOrders);
    } else {
      lockMutationState(account.getId(), productType, orderSnapshot.getSymbol());
    }
    OrderEntity order = requireOwnedOrderForUpdate(principal.id(), orderId);
    if (!isSingleOrderCancelableStatus(order.getStatus())) {
      throw new BusinessException(
          "ORDER_NOT_CANCELABLE",
          "Only pending, partially filled, or awaiting-activation orders can be canceled");
    }
    OrderStatus sourceStatus = order.getStatus();
    BigDecimal holdAmount = orZero(order.getHoldAmount());
    boolean isolatedInternalHold = order.getMarginMode() == MarginMode.ISOLATED
        && order.getParentPositionId() != null;

    Instant previousCanceledAt = order.getCanceledAt();
    order.setCanceledAt(Instant.now());
    int canceled = switch (sourceStatus) {
      case PENDING_ACTIVATION -> orderRepository.cancelPendingActivation(order);
      case PARTIALLY_FILLED -> orderRepository.cancelPartiallyFilled(order);
      default -> orderRepository.cancelPending(order);
    };
    if (canceled != 1) {
      order.setCanceledAt(previousCanceledAt);
      throw new BusinessException(
          "ORDER_NOT_CANCELABLE",
          "Only pending, partially filled, or awaiting-activation orders can be canceled");
    }
    order.setStatus(OrderStatus.CANCELED);
    order.setRemainingQuantity(BigDecimal.ZERO);
    if (holdAmount.compareTo(BigDecimal.ZERO) > 0) {
      if (productType == ProductType.LINEAR_PERP && isP0Symbol(order.getSymbol())) {
        if (isolatedInternalHold) {
          releaseIsolatedPerpetualOrderHold(account, holdAmount);
        } else {
          releasePerpetualOrderHold(account, holdAmount);
        }
        ledgerService.recordOrderRelease(
            account,
            holdAmount,
            order.getId(),
            "Pending Perpetual order canceled");
      } else {
        releaseOrderHold(
            account,
            order,
            holdAmount,
            spotWalletHold,
            "Pending order canceled",
            "Pending spot order canceled");
      }
      order.setHoldAmount(BigDecimal.ZERO);
    }
    if (productType == ProductType.LINEAR_PERP
        && isP0Symbol(order.getSymbol())
        && !Boolean.TRUE.equals(order.getReduceOnly())) {
      if (protectionOrderService == null) {
        throw new BusinessException(
            ErrorCode.EXECUTION_UNAVAILABLE,
            "Perpetual protection service is unavailable");
      }
      if (sourceStatus == OrderStatus.PARTIALLY_FILLED) {
        protectionOrderService.finalizeAttachedForPartiallyFilledCanceledParentLocked(
            order,
            account,
            lockedPerpetualPositions,
            lockedPerpetualActiveOrders);
      } else {
        protectionOrderService.expireAttachedForCanceledParentLocked(order, account);
      }
    }
    orderEventService.record(
        order.getId(),
        "ORDER_CANCELED",
        sourceStatus,
        OrderStatus.CANCELED,
        null,
        "Pending order canceled");
    return orderResponseMapper.toResponse(order);
  }

  public OrderResponse modifyOrder(UserPrincipal principal, UUID orderId, UpdateOrderRequest update) {
    OrderEntity orderSnapshot = requireOwnedOrder(principal.id(), orderId);
    if (orderSnapshot.getContingencyGroupId() != null) {
      throw new BusinessException(
          "OCO_ORDER_NOT_MODIFIABLE",
          "OCO legs cannot be modified independently");
    }
    ProductType productType = requestedProduct(orderSnapshot.getSymbol());
    if (productType == ProductType.CRYPTO_SPOT && isP0Symbol(orderSnapshot.getSymbol())) {
      if (!task6SpotReady()) {
        throw new BusinessException(
            ErrorCode.EXECUTION_UNAVAILABLE,
            "P0 Spot modification authority is unavailable");
      }
      return modifyP0SpotWithRetry(principal, orderSnapshot, update);
    }
    if (productType == ProductType.LINEAR_PERP && isP0Symbol(orderSnapshot.getSymbol())) {
      if (!task9PerpetualReady()) {
        throw new BusinessException(
            ErrorCode.EXECUTION_UNAVAILABLE,
            "P0 Linear Perpetual modification authority is unavailable");
      }
      return modifyP0PerpetualWithRetry(principal, orderSnapshot, update);
    }
    return modifyLegacyOrder(principal, orderId, orderSnapshot, update);
  }

  private OrderResponse modifyP0PerpetualWithRetry(
      UserPrincipal principal,
      OrderEntity orderSnapshot,
      UpdateOrderRequest update
  ) {
    boolean stopLimit = orderSnapshot.getOrderType() == OrderType.STOP_LIMIT;
    boolean awaitingActivation = stopLimit
        && orderSnapshot.getStatus() == OrderStatus.PENDING_ACTIVATION;
    boolean pendingLimitLike = orderSnapshot.getStatus() == OrderStatus.PENDING
        && (orderSnapshot.getOrderType() == OrderType.LIMIT || stopLimit);
    if ((!pendingLimitLike && !awaitingActivation)
        || orderSnapshot.getProtectionType() != null) {
      throw new BusinessException(
          "ORDER_NOT_MODIFIABLE",
          "Only pending LIMIT or STOP_LIMIT Perpetual orders can be modified");
    }
    if (update == null || update.stopLoss() != null || update.takeProfit() != null) {
      throw new BusinessException(
          "ORDER_NOT_MODIFIABLE",
          "Perpetual modification accepts quantity, limit price, and an eligible trigger only");
    }
    if ((orderSnapshot.getOrderType() == OrderType.LIMIT && update.triggerPrice() != null)
        || (stopLimit && !awaitingActivation && update.triggerPrice() != null)) {
      throw new BusinessException(
          "ORDER_NOT_MODIFIABLE",
          "An activated STOP_LIMIT trigger cannot be modified");
    }

    BigDecimal publicQuantity = update.quantity() != null
        ? update.quantity()
        : originalQuantity(orderSnapshot);
    requirePositiveField(publicQuantity, "BAD_QUANTITY", "Modified quantity must be positive");
    BigDecimal newPrice = update.price() != null ? update.price() : currentPrice(orderSnapshot);
    requirePositiveField(
        newPrice,
        ErrorCode.ORDER_PRICE_REQUIRED,
        "Modified Perpetual LIMIT price is required");

    CreateOrderRequest modifiedRequest = new CreateOrderRequest(
        orderSnapshot.getAccountId(),
        orderSnapshot.getSymbol(),
        orderSnapshot.getSide(),
        orderSnapshot.getOrderType(),
        null,
        null,
        null,
        null,
        orderSnapshot.getIdempotencyKey(),
        orderSnapshot.getClientOrderId(),
        publicQuantity,
        newPrice,
        orderSnapshot.getLeverage(),
        orderSnapshot.getPositionSide(),
        orderSnapshot.getQuantityUnit(),
        orderSnapshot.getMarginMode(),
        stopLimit
            ? update.triggerPrice() != null
                ? update.triggerPrice()
                : orderSnapshot.getTriggerPrice()
            : null,
        stopLimit ? TriggerPriceType.MARK_PRICE : null,
        orderSnapshot.getReduceOnly(),
        List.of(),
        orderSnapshot.getTimeInForce(),
        Boolean.TRUE.equals(orderSnapshot.getPostOnly()),
        null,
        null,
        null);
    validateP0PerpetualContract(modifiedRequest);

    TradingAccountEntity accountSnapshot = requireOwnedAccount(
        principal.id(), orderSnapshot.getAccountId());
    demoExecutionGuard.requireDemo(
        accountSnapshot,
        ProductType.LINEAR_PERP,
        orderSnapshot.getSymbol());
    SymbolEntity symbol = symbolRepository.findBySymbol(orderSnapshot.getSymbol())
        .orElseThrow(() -> new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found"));
    InstrumentRules rules = instrumentRulesEngine.rules(symbol);
    PendingPerpetualVersion expected = PendingPerpetualVersion.from(orderSnapshot);
    BusinessException lastStale = null;

    for (int attempt = 0; attempt < MAX_MARKET_ATTEMPTS; attempt++) {
      try {
        ExecutableMarketSnapshot snapshot = resolveExecutableSnapshot(
            orderSnapshot.getSymbol(),
            ProductType.LINEAR_PERP);
        FullFillExecutionPath modificationPath = perpetualModificationExecutionPath(
            modifiedRequest,
            expected.status(),
            snapshot);
        if (Boolean.TRUE.equals(expected.postOnly()) && modificationPath != null) {
          fullFillCoordinator.requireFresh(snapshot);
          throw new BusinessException(
              ErrorCode.POST_ONLY_WOULD_TAKE,
              "Post Only order would execute immediately after modification");
        }
        PerpetualAccountRiskSnapshotService.PreparedAccountRisk preparedAccountRisk =
            perpetualAccountRiskSnapshotService.prepare(
                orderSnapshot.getAccountId(),
                Map.of(orderSnapshot.getSymbol(), snapshot));
        QuantityConversionService.Conversion conversion =
            quantityConversionService.convertPerpetual(
                modifiedRequest.quantityUnit(),
                modifiedRequest.quantity(),
                rules.stepSize(),
                symbol.getContractSize(),
                symbol.getContractMultiplier(),
                snapshot.mark(),
                rules.minNotional());
        instrumentRulesEngine.validateCanonicalOrder(
            perpetualRuleValidationRequest(modifiedRequest),
            symbol,
            conversion.baseQuantity(),
            newPrice);
        return transactionExecutor.execute(() -> persistP0PerpetualModification(
            principal.id(),
            orderSnapshot.getId(),
            modifiedRequest,
            conversion,
            modificationPath,
            snapshot,
            preparedAccountRisk,
            expected));
      } catch (BusinessException exception) {
        if (!ErrorCode.MARKET_DATA_STALE.equals(exception.getCode())) {
          throw exception;
        }
        lastStale = exception;
      }
    }
    throw new BusinessException(
        ErrorCode.MARKET_DATA_STALE,
        lastStale == null
            ? "No fresh executable Perpetual modification snapshot"
            : lastStale.getMessage());
  }

  private OrderResponse persistP0PerpetualModification(
      UUID userId,
      UUID orderId,
      CreateOrderRequest modifiedRequest,
      QuantityConversionService.Conversion conversion,
      FullFillExecutionPath modificationPath,
      ExecutableMarketSnapshot snapshot,
      PerpetualAccountRiskSnapshotService.PreparedAccountRisk preparedAccountRisk,
      PendingPerpetualVersion expected
  ) {
    TradingAccountEntity account = accountRepository.findByIdAndUserIdForUpdate(
            modifiedRequest.accountId(), userId)
        .orElseThrow(() -> new AuthorizationException("ACCOUNT_NOT_FOUND", "Account not found"));
    demoExecutionGuard.requireDemo(
        account,
        ProductType.LINEAR_PERP,
        modifiedRequest.symbol());
    AccountSymbolSettingEntity setting = accountSymbolSettingRepository
        .findByAccountIdAndSymbolForUpdate(account.getId(), modifiedRequest.symbol())
        .orElseThrow(() -> new BusinessException(
            "INVALID_INSTRUMENT_RULES",
            "Locked Perpetual symbol setting is required"));
    perpetualAccountRiskSnapshotService.requireCurrentLeverageWithinLimit(setting);
    List<PositionEntity> accountPositions = positionRepository
        .findOpenLinearPerpByAccountIdForUpdate(account.getId());
    List<OrderEntity> accountActiveOrders = orderRepository
        .findActiveLinearPerpByAccountIdForUpdate(account.getId());
    OrderEntity order = requireOwnedOrderForUpdate(userId, orderId);
    if (!expected.matches(order)
        || accountActiveOrders.stream().noneMatch(active -> orderId.equals(active.getId()))) {
      throw new BusinessException(
          "ORDER_CHANGED",
          "Perpetual order changed while modification was prepared");
    }

    PerpetualAccountRiskSnapshotService.AccountRiskProjection accountRisk =
        perpetualAccountRiskSnapshotService.project(
            account,
            accountPositions,
            accountActiveOrders,
            preparedAccountRisk);
    PerpetualAccountRiskSnapshotService.PreparedSymbolRisk targetRisk =
        preparedAccountRisk.symbols().get(modifiedRequest.symbol());
    if (targetRisk == null) {
      throw new BusinessException(
          ErrorCode.MARKET_DATA_UNAVAILABLE,
          "Target Perpetual risk snapshot was not prepared");
    }
    List<PositionEntity> positions = accountPositions.stream()
        .filter(position -> modifiedRequest.symbol().equals(position.getSymbol()))
        .toList();
    List<OrderEntity> otherActiveOrders = accountActiveOrders.stream()
        .filter(active -> modifiedRequest.symbol().equals(active.getSymbol()))
        .filter(active -> !orderId.equals(active.getId()))
        .toList();
    PerpetualOrderRiskService.OrderRisk risk = perpetualOrderRiskService.evaluate(
        account.getPositionMode(),
        setting,
        positions,
        modifiedRequest.side(),
        modifiedRequest.positionSide(),
        Boolean.TRUE.equals(modifiedRequest.reduceOnly()),
        OrderType.LIMIT,
        conversion.baseQuantity(),
        modifiedRequest.price(),
        snapshot,
        targetRisk.maintenanceMarginRate());
    PositionEntity isolatedHoldPosition = isolatedCloseHoldPosition(risk, positions);
    if (isolatedHoldPosition != null) {
      PerpetualIsolatedCloseHoldValidator.validate(
          isolatedHoldPosition,
          risk,
          otherActiveOrders);
    }

    FullFillResult fullFill = null;
    if (modificationPath != null) {
      CreateOrderRequest executionIntent = canonicalPerpetualExecutionIntent(
          modifiedRequest,
          conversion.baseQuantity(),
          risk);
      fullFill = fullFillCoordinator.execute(
          new FullFillRequest(
              executionIntent,
              modifiedRequest.symbol(),
              ProductType.LINEAR_PERP,
              modifiedRequest.side(),
              modificationPath,
              conversion.baseQuantity(),
              modifiedRequest.price()),
          snapshot);
      fullFillCoordinator.requireFresh(fullFill);
    } else {
      fullFillCoordinator.requireFresh(snapshot);
    }

    BigDecimal oldHold = expected.holdAmount();
    BigDecimal newHold = risk.holdAmount();
    PerpetualHoldAdjustment holdAdjustment = perpetualModificationHoldAdjustment(
        accountRisk.usedMargin(),
        accountRisk.crossAvailable(),
        oldHold,
        newHold,
        expected.parentPositionId() != null,
        isolatedHoldPosition != null);
    requirePreparedAccountRiskFresh(preparedAccountRisk);
    perpetualAccountRiskSnapshotService.applyRevaluation(
        account,
        accountPositions,
        accountRisk);
    account.setUsedMargin(holdAdjustment.usedMargin());
    account.setFreeMargin(holdAdjustment.freeMargin());
    accountRepository.save(account);
    for (PositionEntity position : accountPositions) {
      positionRepository.save(position);
    }

    order.setLots(conversion.baseQuantity());
    order.setQuantity(modifiedRequest.quantity());
    order.setOriginalQuantity(modifiedRequest.quantity());
    order.setBaseQuantity(conversion.baseQuantity());
    order.setQuantityUnit(modifiedRequest.quantityUnit());
    order.setRequestedPrice(modifiedRequest.price());
    order.setPrice(modifiedRequest.price());
    order.setTriggerPrice(modifiedRequest.orderType() == OrderType.STOP_LIMIT
        ? modifiedRequest.triggerPrice() : null);
    order.setTriggerPriceType(modifiedRequest.orderType() == OrderType.STOP_LIMIT
        ? TriggerPriceType.MARK_PRICE : null);
    order.setTriggerExecutionType(modifiedRequest.orderType() == OrderType.STOP_LIMIT
        ? TriggerExecutionType.LIMIT : null);
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(conversion.baseQuantity());
    order.setPositionMode(risk.positionMode());
    order.setPositionSide(risk.positionSide());
    order.setMarginMode(risk.marginMode());
    order.setLeverage(risk.leverage());
    order.setHoldAmount(newHold);
    order.setHoldCurrency(risk.holdCurrency());
    order.setParentPositionId(
        isolatedHoldPosition == null ? null : isolatedHoldPosition.getId());
    order.setUpdatedAt(Instant.now());
    orderRepository.save(order);

    BigDecimal holdDelta = newHold.subtract(oldHold);
    if (holdDelta.compareTo(BigDecimal.ZERO) > 0) {
      ledgerService.recordOrderHold(
          account,
          holdDelta,
          order.getId(),
          "Pending Perpetual order margin increased");
    } else if (holdDelta.compareTo(BigDecimal.ZERO) < 0) {
      ledgerService.recordOrderRelease(
          account,
          holdDelta.abs(),
          order.getId(),
          "Pending Perpetual order margin decreased");
    }
    orderEventService.record(
        order.getId(),
        "ORDER_MODIFIED",
        expected.status(),
        expected.status(),
        null,
        modifiedRequest.orderType() == OrderType.STOP_LIMIT
            ? "Pending Perpetual order modified"
            : "Pending Perpetual LIMIT order modified");
    if (fullFill != null) {
      orderFillService.fillPerpetual(
          order,
          account,
          fullFill,
          snapshot.mark(),
          risk.leverage(),
          "Perpetual order fill after modification");
      orderEventService.record(
          order.getId(),
          "ORDER_FILLED",
          expected.status(),
          OrderStatus.FILLED,
          null,
          "Modified Perpetual order filled");
    }
    return orderResponseMapper.toResponse(order);
  }

  private boolean isSingleOrderCancelableStatus(OrderStatus status) {
    return status == OrderStatus.PENDING
        || status == OrderStatus.PENDING_ACTIVATION
        || status == OrderStatus.PARTIALLY_FILLED;
  }

  private PerpetualHoldAdjustment perpetualModificationHoldAdjustment(
      BigDecimal currentUsed,
      BigDecimal currentFree,
      BigDecimal oldHold,
      BigDecimal newHold,
      boolean oldInternalIsolatedHold,
      boolean newInternalIsolatedHold
  ) {
    if (oldHold == null
        || oldHold.compareTo(BigDecimal.ZERO) <= 0
        || newHold == null
        || newHold.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "Perpetual modification requires positive old and new holds");
    }
    BigDecimal totalDelta = newHold.subtract(oldHold);
    BigDecimal externalOld = oldInternalIsolatedHold ? BigDecimal.ZERO : oldHold;
    BigDecimal externalNew = newInternalIsolatedHold ? BigDecimal.ZERO : newHold;
    BigDecimal externalDelta = externalNew.subtract(externalOld);
    BigDecimal availableUsed = orZero(currentUsed);
    BigDecimal availableFree = orZero(currentFree);
    BigDecimal updatedUsed = availableUsed.add(totalDelta);
    if (updatedUsed.compareTo(BigDecimal.ZERO) < 0) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "Perpetual modification would make used margin negative");
    }
    if (externalDelta.compareTo(BigDecimal.ZERO) > 0
        && availableFree.compareTo(externalDelta) < 0) {
      throw new BusinessException(
          ErrorCode.INSUFFICIENT_MARGIN,
          "Free margin is not enough for the modified Perpetual order");
    }
    return new PerpetualHoldAdjustment(
        updatedUsed,
        availableFree.subtract(externalDelta));
  }

  private OrderResponse modifyLegacyOrder(
      UserPrincipal principal,
      UUID orderId,
      OrderEntity orderSnapshot,
      UpdateOrderRequest update
  ) {
    if (orderSnapshot.getStatus() != OrderStatus.PENDING) {
      throw new BusinessException("ORDER_NOT_MODIFIABLE", "Only pending orders can be modified");
    }

    BigDecimal quantity = update.quantity() != null ? update.quantity() : currentQuantity(orderSnapshot);
    BigDecimal price = update.price() != null ? update.price() : currentPrice(orderSnapshot);
    if (price == null) {
      throw new BusinessException("ORDER_PRICE_REQUIRED", "Limit and stop orders require requested price");
    }

    TradingAccountEntity accountSnapshot = requireOwnedAccount(principal.id(), orderSnapshot.getAccountId());
    ProductType productType = requestedProduct(orderSnapshot.getSymbol());
    demoExecutionGuard.requireDemo(accountSnapshot, productType, orderSnapshot.getSymbol());
    BigDecimal oldHold = orZero(orderSnapshot.getHoldAmount());
    boolean spotWalletHold = riskCheckService.isSpotSymbol(orderSnapshot.getSymbol());
    CreateOrderRequest riskRequest = new CreateOrderRequest(
        orderSnapshot.getAccountId(),
        orderSnapshot.getSymbol(),
        orderSnapshot.getSide(),
        orderSnapshot.getOrderType(),
        quantity,
        price,
        update.stopLoss() != null ? update.stopLoss() : orderSnapshot.getStopLoss(),
        update.takeProfit() != null ? update.takeProfit() : orderSnapshot.getTakeProfit(),
        orderSnapshot.getIdempotencyKey(),
        orderSnapshot.getClientOrderId(),
        quantity,
        price,
        orderSnapshot.getLeverage());
    BigDecimal newHold = riskCheckService.checkOrder(accountForMarginCheck(accountSnapshot, oldHold), riskRequest);
    String holdCurrency = spotWalletHold
        ? riskCheckService.resolveHoldCurrency(accountSnapshot, riskRequest)
        : accountSnapshot.getBaseCurrency();

    return transactionExecutor.execute(() -> persistLegacyModification(
        principal,
        orderId,
        orderSnapshot,
        update,
        productType,
        quantity,
        price,
        oldHold,
        newHold,
        holdCurrency,
        spotWalletHold));
  }

  private OrderResponse persistLegacyModification(
      UserPrincipal principal,
      UUID orderId,
      OrderEntity orderSnapshot,
      UpdateOrderRequest update,
      ProductType productType,
      BigDecimal quantity,
      BigDecimal price,
      BigDecimal oldHold,
      BigDecimal newHold,
      String holdCurrency,
      boolean spotWalletHold
  ) {
    TradingAccountEntity account = accountRepository
        .findByIdAndUserIdForUpdate(orderSnapshot.getAccountId(), principal.id())
        .orElseThrow(() -> new AuthorizationException("ACCOUNT_NOT_FOUND", "Account not found"));
    demoExecutionGuard.requireDemo(account, productType, orderSnapshot.getSymbol());
    lockMutationState(account.getId(), productType, orderSnapshot.getSymbol());
    OrderEntity order = requireOwnedOrderForUpdate(principal.id(), orderId);
    if (order.getStatus() != OrderStatus.PENDING) {
      throw new BusinessException("ORDER_NOT_MODIFIABLE", "Only pending orders can be modified");
    }
    if (orZero(order.getHoldAmount()).compareTo(oldHold) != 0
        || currentQuantity(order).compareTo(currentQuantity(orderSnapshot)) != 0
        || !java.util.Objects.equals(currentPrice(order), currentPrice(orderSnapshot))) {
      throw new BusinessException("ORDER_CHANGED", "Order changed while modification was prepared");
    }
    BigDecimal delta = newHold.subtract(oldHold);
    if (delta.compareTo(BigDecimal.ZERO) > 0) {
      reserveOrderHold(account, delta, holdCurrency, spotWalletHold, order.getId(),
          "Pending order margin increased", "Pending spot order wallet increased");
    } else if (delta.compareTo(BigDecimal.ZERO) < 0) {
      releaseOrderHold(account, order, delta.abs(), spotWalletHold,
          "Pending order margin decreased", "Pending spot order wallet decreased");
    }

    order.setLots(quantity);
    order.setQuantity(quantity);
    order.setOriginalQuantity(quantity);
    order.setBaseQuantity(quantity);
    order.setRequestedPrice(price);
    order.setPrice(price);
    order.setStopLoss(update.stopLoss() != null ? update.stopLoss() : order.getStopLoss());
    order.setTakeProfit(update.takeProfit() != null ? update.takeProfit() : order.getTakeProfit());
    order.setRemainingQuantity(quantity.subtract(orZero(order.getFilledQuantity())));
    order.setHoldAmount(newHold);
    order.setHoldCurrency(holdCurrency);
    orderRepository.save(order);
    orderEventService.record(
        order.getId(),
        "ORDER_MODIFIED",
        OrderStatus.PENDING,
        OrderStatus.PENDING,
        null,
        "Pending order modified");
    return orderResponseMapper.toResponse(order);
  }

  private OrderResponse modifyP0SpotWithRetry(
      UserPrincipal principal,
      OrderEntity orderSnapshot,
      UpdateOrderRequest update
  ) {
    boolean stopLimit = orderSnapshot.getOrderType() == OrderType.STOP_LIMIT;
    boolean awaitingActivation = stopLimit
        && orderSnapshot.getStatus() == OrderStatus.PENDING_ACTIVATION;
    if (orderSnapshot.getStatus() != OrderStatus.PENDING && !awaitingActivation) {
      throw new BusinessException(
          "ORDER_NOT_MODIFIABLE",
          "Only pending or awaiting-activation Spot orders can be modified");
    }
    if (update == null || update.stopLoss() != null || update.takeProfit() != null) {
      throw new BusinessException(
          ErrorCode.PRODUCT_NOT_ALLOWED,
          "P0 Spot pending orders do not support protection fields");
    }
    if (orderSnapshot.getOrderType() != OrderType.LIMIT
        && orderSnapshot.getOrderType() != OrderType.STOP_MARKET
        && !stopLimit) {
      throw new BusinessException(
          ErrorCode.INVALID_SPOT_ORDER_TYPE,
          "Only pending LIMIT, STOP_MARKET, and STOP_LIMIT Spot orders can be modified");
    }
    if ((orderSnapshot.getOrderType() == OrderType.LIMIT && update.triggerPrice() != null)
        || (stopLimit && !awaitingActivation && update.triggerPrice() != null)) {
      throw new BusinessException(
          "ORDER_NOT_MODIFIABLE",
          "An activated STOP_LIMIT trigger cannot be modified");
    }

    BigDecimal publicQuantity = update.quantity() != null
        ? update.quantity()
        : orderSnapshot.getOriginalQuantity() != null
            ? orderSnapshot.getOriginalQuantity()
            : orderSnapshot.getBaseQuantity();
    requirePositiveField(publicQuantity, "BAD_QUANTITY", "Modified quantity must be positive");
    BigDecimal limitPrice = orderSnapshot.getOrderType() == OrderType.LIMIT || stopLimit
        ? update.price() != null ? update.price() : currentPrice(orderSnapshot)
        : null;
    BigDecimal triggerPrice = orderSnapshot.getOrderType() == OrderType.STOP_MARKET
        ? update.triggerPrice() != null
            ? update.triggerPrice()
            : update.price() != null ? update.price() : orderSnapshot.getTriggerPrice()
        : stopLimit
            ? update.triggerPrice() != null
                ? update.triggerPrice()
                : orderSnapshot.getTriggerPrice()
            : null;
    if (orderSnapshot.getOrderType() == OrderType.LIMIT || stopLimit) {
      requirePositiveField(limitPrice, ErrorCode.ORDER_PRICE_REQUIRED, "LIMIT price is required");
    }
    if (orderSnapshot.getOrderType() == OrderType.STOP_MARKET || stopLimit) {
      requirePositiveField(
          triggerPrice,
          ErrorCode.ORDER_TRIGGER_PRICE_REQUIRED,
          "Stop trigger price is required");
    }

    CreateOrderRequest modifiedRequest = new CreateOrderRequest(
        orderSnapshot.getAccountId(),
        orderSnapshot.getSymbol(),
        orderSnapshot.getSide(),
        orderSnapshot.getOrderType(),
        null,
        null,
        null,
        null,
        orderSnapshot.getIdempotencyKey(),
        orderSnapshot.getClientOrderId(),
        publicQuantity,
        limitPrice,
        1,
        PositionSide.BOTH,
        com.fxplatform.trading.enums.QuantityUnit.BASE,
        MarginMode.CASH,
        triggerPrice,
        orderSnapshot.getOrderType() == OrderType.STOP_MARKET || stopLimit
            ? TriggerPriceType.LAST_PRICE
            : null,
        false,
        List.of(),
        orderSnapshot.getTimeInForce(),
        Boolean.TRUE.equals(orderSnapshot.getPostOnly()),
        null,
        null,
        null);
    validateP0SpotContract(modifiedRequest);

    TradingAccountEntity accountSnapshot = requireOwnedAccount(
        principal.id(), orderSnapshot.getAccountId());
    demoExecutionGuard.requireDemo(
        accountSnapshot, ProductType.CRYPTO_SPOT, orderSnapshot.getSymbol());
    SymbolEntity symbol = symbolRepository.findBySymbol(orderSnapshot.getSymbol())
        .orElseThrow(() -> new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found"));
    InstrumentRules rules = instrumentRulesEngine.rules(symbol);
    QuantityConversionService.Conversion conversion = quantityConversionService.convertSpot(
        orderSnapshot.getSide(),
        orderSnapshot.getOrderType(),
        com.fxplatform.trading.enums.QuantityUnit.BASE,
        publicQuantity,
        rules.stepSize(),
        null);
    PendingSpotVersion expected = PendingSpotVersion.from(orderSnapshot);
    UUID modificationId = UUID.randomUUID();
    BusinessException lastStale = null;

    for (int attempt = 0; attempt < MAX_MARKET_ATTEMPTS; attempt++) {
      ExecutableMarketSnapshot snapshot = resolveExecutableSnapshot(
          orderSnapshot.getSymbol(), ProductType.CRYPTO_SPOT);
      try {
        FullFillExecutionPath path = spotModificationExecutionPath(
            modifiedRequest,
            expected.status(),
            snapshot);
        if (Boolean.TRUE.equals(expected.postOnly()) && path != null) {
          fullFillCoordinator.requireFresh(snapshot);
          throw new BusinessException(
              ErrorCode.POST_ONLY_WOULD_TAKE,
              "Post Only order would execute immediately after modification");
        }
        BigDecimal referencePrice = canonicalRulePrice(modifiedRequest, path, snapshot, null);
        instrumentRulesEngine.validateCanonicalOrder(
            modifiedRequest, symbol, conversion.baseQuantity(), referencePrice);
        OrderHoldCalculator.OrderHold hold = pendingHold(
            modifiedRequest, conversion.baseQuantity(), path, snapshot);
        return transactionExecutor.execute(() -> persistP0SpotModification(
            principal.id(),
            orderSnapshot.getId(),
            modifiedRequest,
            conversion.baseQuantity(),
            path,
            hold,
            snapshot,
            expected,
            modificationId));
      } catch (BusinessException exception) {
        if (!ErrorCode.MARKET_DATA_STALE.equals(exception.getCode())) {
          throw exception;
        }
        lastStale = exception;
      }
    }
    throw new BusinessException(
        ErrorCode.MARKET_DATA_STALE,
        lastStale == null ? "No fresh executable Spot modification snapshot" : lastStale.getMessage());
  }

  private OrderResponse persistP0SpotModification(
      UUID userId,
      UUID orderId,
      CreateOrderRequest modifiedRequest,
      BigDecimal baseQuantity,
      FullFillExecutionPath path,
      OrderHoldCalculator.OrderHold newHold,
      ExecutableMarketSnapshot snapshot,
      PendingSpotVersion expected,
      UUID modificationId
  ) {
    TradingAccountEntity account = accountRepository.findByIdAndUserIdForUpdate(
            modifiedRequest.accountId(), userId)
        .orElseThrow(() -> new AuthorizationException("ACCOUNT_NOT_FOUND", "Account not found"));
    demoExecutionGuard.requireDemo(account, ProductType.CRYPTO_SPOT, modifiedRequest.symbol());
    fullFillCoordinator.requireFresh(snapshot);
    prepareP0SpotMutationState(account.getId(), modifiedRequest.symbol());
    OrderEntity order = requireOwnedOrderForUpdate(userId, orderId);
    if (!expected.matches(order)
        || order.getContingencyGroupId() != null
        || !modifiedRequest.symbol().equals(order.getSymbol())) {
      throw new BusinessException("ORDER_CHANGED", "Order changed while modification was prepared");
    }
    if (path != null) {
      return fillModifiedP0SpotImmediately(
          account,
          order,
          modifiedRequest,
          baseQuantity,
          path,
          snapshot,
          expected,
          modificationId);
    }

    requireConsistentModifiedPendingHold(newHold, expected);

    // The final freshness gate is adjacent to the first wallet/order mutation.
    fullFillCoordinator.requireFresh(snapshot);
    BigDecimal delta = newHold.amount().subtract(expected.holdAmount());
    if (delta.compareTo(BigDecimal.ZERO) > 0) {
      walletService.lockAvailableWithEntryType(
          account.getId(),
          newHold.currency(),
          delta,
          "ORDER_MODIFICATION",
          modificationId,
          "Pending spot order hold increased",
          "SPOT_ORDER_LOCK");
    } else if (delta.compareTo(BigDecimal.ZERO) < 0) {
      walletService.releaseLockedWithEntryType(
          account.getId(),
          newHold.currency(),
          delta.abs(),
          "ORDER_MODIFICATION",
          modificationId,
          "Pending spot order hold decreased",
          "SPOT_ORDER_RELEASE");
    }

    applyP0SpotModification(order, modifiedRequest, baseQuantity);
    order.setHoldAmount(newHold.amount());
    order.setHoldCurrency(newHold.currency());
    orderRepository.save(order);
    orderEventService.record(
        order.getId(),
        "ORDER_MODIFIED",
        expected.status(),
        expected.status(),
        null,
        "Pending Spot order modified");
    return orderResponseMapper.toResponse(order);
  }

  private OrderResponse fillModifiedP0SpotImmediately(
      TradingAccountEntity account,
      OrderEntity order,
      CreateOrderRequest modifiedRequest,
      BigDecimal baseQuantity,
      FullFillExecutionPath path,
      ExecutableMarketSnapshot snapshot,
      PendingSpotVersion expected,
      UUID modificationId
  ) {
    String spentAsset = modifiedRequest.side() == com.fxplatform.trading.enums.OrderSide.BUY
        ? "USDT"
        : p0SpotBaseAsset(modifiedRequest.symbol());
    BigDecimal existingHold = walletAmount(expected.holdAmount());
    if (existingHold.compareTo(BigDecimal.ZERO) <= 0
        || expected.holdCurrency() == null
        || !spentAsset.equalsIgnoreCase(expected.holdCurrency())) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "Immediate Spot modification requires the pending order owner hold");
    }

    CreateOrderRequest executionIntent = canonicalExecutionIntent(modifiedRequest, baseQuantity);
    FullFillResult fullFill = fullFillCoordinator.execute(
        new FullFillRequest(
            executionIntent,
            modifiedRequest.symbol(),
            ProductType.CRYPTO_SPOT,
            modifiedRequest.side(),
            path,
            baseQuantity,
            path == FullFillExecutionPath.IMMEDIATE_LIMIT ? modifiedRequest.price() : null),
        snapshot);
    BigDecimal spentAmount = modifiedRequest.side() == com.fxplatform.trading.enums.OrderSide.BUY
        ? walletAmount(baseQuantity.multiply(fullFill.filledPrice()).add(fullFill.fee()))
        : walletAmount(baseQuantity);
    BigDecimal holdIncrease = spentAmount.subtract(existingHold).max(BigDecimal.ZERO);

    // The final freshness gate is adjacent to the first wallet/order/event mutation.
    fullFillCoordinator.requireFresh(fullFill);
    if (holdIncrease.compareTo(BigDecimal.ZERO) > 0) {
      walletService.lockAvailableWithEntryType(
          account.getId(),
          spentAsset,
          holdIncrease,
          "ORDER_MODIFICATION",
          modificationId,
          "Pending spot order hold increased for immediate fill",
          "SPOT_ORDER_LOCK");
    }
    applyP0SpotModification(order, modifiedRequest, baseQuantity);
    order.setHoldAmount(existingHold.add(holdIncrease));
    order.setHoldCurrency(spentAsset);
    orderEventService.record(
        order.getId(),
        "ORDER_MODIFIED",
        expected.status(),
        expected.status(),
        null,
        "Pending Spot order modified for immediate fill");
    orderFillService.fill(order, account, fullFill, BigDecimal.ZERO, "Spot order fill after modification");
    orderEventService.record(
        order.getId(),
        "ORDER_FILLED",
        expected.status(),
        OrderStatus.FILLED,
        null,
        "Modified Spot order filled");
    return orderResponseMapper.toResponse(order);
  }

  private void requireConsistentModifiedPendingHold(
      OrderHoldCalculator.OrderHold newHold,
      PendingSpotVersion expected
  ) {
    if (newHold == null
        || newHold.amount() == null
        || newHold.amount().compareTo(BigDecimal.ZERO) <= 0
        || newHold.currency() == null
        || expected.holdCurrency() == null
        || !expected.holdCurrency().equalsIgnoreCase(newHold.currency())) {
      throw new BusinessException(ErrorCode.ORDER_HOLD_INVALID, "Modified Spot hold is inconsistent");
    }
  }

  private void applyP0SpotModification(
      OrderEntity order,
      CreateOrderRequest modifiedRequest,
      BigDecimal baseQuantity
  ) {
    order.setLots(baseQuantity);
    order.setQuantity(modifiedRequest.quantity());
    order.setOriginalQuantity(modifiedRequest.quantity());
    order.setBaseQuantity(baseQuantity);
    order.setQuantityUnit(com.fxplatform.trading.enums.QuantityUnit.BASE);
    order.setRequestedPrice(modifiedRequest.orderType() == OrderType.LIMIT
            || modifiedRequest.orderType() == OrderType.STOP_LIMIT
        ? modifiedRequest.price() : null);
    order.setPrice(modifiedRequest.orderType() == OrderType.LIMIT
            || modifiedRequest.orderType() == OrderType.STOP_LIMIT
        ? modifiedRequest.price() : null);
    order.setTriggerPrice(modifiedRequest.orderType() == OrderType.STOP_MARKET
            || modifiedRequest.orderType() == OrderType.STOP_LIMIT
        ? modifiedRequest.triggerPrice() : null);
    order.setTriggerPriceType(modifiedRequest.orderType() == OrderType.STOP_MARKET
            || modifiedRequest.orderType() == OrderType.STOP_LIMIT
        ? TriggerPriceType.LAST_PRICE : null);
    order.setTriggerExecutionType(modifiedRequest.orderType() == OrderType.STOP_MARKET
        ? TriggerExecutionType.MARKET
        : modifiedRequest.orderType() == OrderType.STOP_LIMIT
            ? TriggerExecutionType.LIMIT
            : null);
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(baseQuantity);
  }

  private static BigDecimal walletAmount(BigDecimal amount) {
    return orZero(amount).setScale(SPOT_WALLET_SCALE, RoundingMode.HALF_UP);
  }

  private static String p0SpotBaseAsset(String canonicalSymbol) {
    if (canonicalSymbol == null
        || !canonicalSymbol.endsWith("USDT")
        || canonicalSymbol.length() <= 4) {
      throw new BusinessException(
          ErrorCode.MARKET_BUNDLE_INCOMPLETE,
          "P0 Spot symbol does not expose a USDT base asset");
    }
    return canonicalSymbol.substring(0, canonicalSymbol.length() - 4);
  }

  private FullFillExecutionPath spotModificationExecutionPath(
      CreateOrderRequest request,
      OrderStatus sourceStatus,
      ExecutableMarketSnapshot snapshot
  ) {
    if (request.orderType() != OrderType.STOP_LIMIT) {
      return executionPath(request, snapshot);
    }
    if (sourceStatus == OrderStatus.PENDING_ACTIVATION) {
      return null;
    }
    return isLimitMarketable(request, snapshot)
        ? FullFillExecutionPath.IMMEDIATE_LIMIT
        : null;
  }

  static void requireAdvancedOrderExecutionSupported(CreateOrderRequest request) {
    if (request.orderType() == OrderType.TRAILING_STOP_MARKET) {
      boolean delta = positive(request.trailingDelta());
      boolean rate = positive(request.trailingRate())
          && request.trailingRate().compareTo(BigDecimal.ONE) < 0;
      if (!Boolean.TRUE.equals(request.reduceOnly())
          || request.timeInForce() != TimeInForce.GTC
          || Boolean.TRUE.equals(request.postOnly())
          || request.price() != null
          || request.triggerPrice() != null
          || request.triggerPriceType() != null
          || request.stopLoss() != null
          || request.takeProfit() != null
          || !request.attachedProtections().isEmpty()
          || delta == rate
          || (request.trailingDelta() != null && !delta)
          || (request.trailingRate() != null && !rate)
          || (request.activationPrice() != null && !positive(request.activationPrice()))) {
        throw new BusinessException(
            "INVALID_PERPETUAL_ORDER_FIELDS",
            "TRAILING_STOP_MARKET requires one positive callback, GTC, reduceOnly, and no ordinary trigger or protection fields");
      }
      return;
    }
    if (request.activationPrice() != null
        || request.trailingDelta() != null
        || request.trailingRate() != null) {
      throw new BusinessException(
          "INVALID_ORDER_TYPE",
          "Advanced order execution is not supported in Phase 1");
    }
  }

  private void requireSimpleAdvancedExecutionAuthority(
      OrderCommand command,
      CreateOrderRequest request
  ) {
    boolean simpleAdvanced = request.orderType() == OrderType.STOP_LIMIT
        || request.timeInForce() != TimeInForce.GTC
        || Boolean.TRUE.equals(request.postOnly());
    if (!simpleAdvanced) {
      return;
    }
    if (!isP0Symbol(command.symbol())) {
      throw new BusinessException(
          "INVALID_ORDER_TYPE",
          "Post Only, IOC, FOK, and STOP_LIMIT require a P0 simple-matching symbol");
    }
    ProductType productType = requestedProduct(command.symbol());
    if (productType == ProductType.CRYPTO_SPOT && !task6SpotReady()) {
      throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "P0 Spot advanced-order execution authority is unavailable");
    }
    if (productType == ProductType.LINEAR_PERP && !task9PerpetualReady()) {
      throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "P0 Linear Perpetual advanced-order execution authority is unavailable");
    }
  }

  private OrderResponse createNewOrder(OrderCommand command, CreateOrderRequest request) {
    requireAdvancedOrderExecutionSupported(request);
    requireSimpleAdvancedExecutionAuthority(command, request);
    TradingAccountEntity accountSnapshot = accountRepository.findByIdAndUserId(command.accountId(), command.userId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    ProductType productType = requestedProduct(command.symbol());
    demoExecutionGuard.requireDemo(accountSnapshot, productType, command.symbol());

    if (request.orderType() == OrderType.TRAILING_STOP_MARKET) {
      if (productType != ProductType.LINEAR_PERP) {
        throw new BusinessException(
            ErrorCode.PRODUCT_NOT_ALLOWED,
            "TRAILING_STOP_MARKET requires a Linear Perpetual position");
      }
      if (!trailingStopReady()) {
        throw new BusinessException(
            ErrorCode.EXECUTION_UNAVAILABLE,
            "Trailing-stop execution authority is unavailable");
      }
      return createTrailingStopWithRetry(command, request);
    }

    DemoExecutionPolicy depthPolicy = depthOrderExecutionService == null
        ? null
        : depthOrderExecutionService.currentPolicy();
    if (isP0Symbol(command.symbol())
        && depthOrderExecutionService != null
        && depthOrderExecutionService.isDepth(depthPolicy)) {
      if (productType == ProductType.CRYPTO_SPOT && task6SpotReady()) {
        return createP0SpotDepthWithRetry(
            command,
            request,
            depthPolicy);
      }
      if (productType == ProductType.LINEAR_PERP && task9PerpetualReady()) {
        return createP0PerpetualDepthWithRetry(
            command,
            request,
            depthPolicy);
      }
      throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "P0 DEPTH new-order execution authority is unavailable for this product");
    }

    if (productType == ProductType.CRYPTO_SPOT
        && isP0Symbol(command.symbol())
        && task6SpotReady()) {
      return createP0SpotWithRetry(command, request, accountSnapshot);
    }

    if (productType == ProductType.LINEAR_PERP && isP0Symbol(command.symbol())) {
      if (!task9PerpetualReady()) {
        throw new BusinessException(
            ErrorCode.EXECUTION_UNAVAILABLE,
            "P0 Linear Perpetual execution authority is unavailable");
      }
      return createP0PerpetualWithRetry(command, request);
    }

    if (command.orderType() != OrderType.MARKET && command.price() == null) {
      throw new BusinessException("ORDER_PRICE_REQUIRED", "Limit and stop orders require requested price");
    }

    BigDecimal requiredMargin = riskCheckService.checkOrder(accountSnapshot, request);
    int effectiveLeverage = riskCheckService.resolveEffectiveLeverage(accountSnapshot, request);
    boolean spotWalletHold = riskCheckService.isSpotSymbol(command.symbol());
    String holdCurrency = spotWalletHold
        ? riskCheckService.resolveHoldCurrency(accountSnapshot, request)
        : accountSnapshot.getBaseCurrency();

    if (command.orderType() == OrderType.MARKET
        && isP0Symbol(command.symbol())
        && marketBundleResolver != null
        && fullFillCoordinator != null) {
      return createP0MarketWithRetry(
          command,
          request,
          productType,
          requiredMargin,
          effectiveLeverage);
    }

    ExecutionResult execution = command.orderType() == OrderType.MARKET
        ? executionAdapter.execute(request)
        : null;
    if (execution != null && !execution.rejected()) {
      requireFullLegacyExecution(command.quantity(), execution);
    }
    return transactionExecutor.execute(() -> persistPreparedOrder(
        command,
        request,
        productType,
        requiredMargin,
        effectiveLeverage,
        spotWalletHold,
        holdCurrency,
        execution));
  }

  private OrderResponse createP0SpotWithRetry(
      OrderCommand identityCommand,
      CreateOrderRequest request,
      TradingAccountEntity accountSnapshot
  ) {
    validateP0SpotContract(request);
    SymbolEntity symbol = symbolRepository.findBySymbol(identityCommand.symbol())
        .orElseThrow(() -> new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found"));
    InstrumentRules rules = instrumentRulesEngine.rules(symbol);
    BusinessException lastStale = null;
    for (int attempt = 0; attempt < MAX_MARKET_ATTEMPTS; attempt++) {
      ExecutableMarketSnapshot snapshot = resolveExecutableSnapshot(
          identityCommand.symbol(), ProductType.CRYPTO_SPOT);
      try {
        fullFillCoordinator.requireFresh(snapshot);
        FullFillExecutionPath path = executionPath(request, snapshot);
        FullFillPricingProjection marketPricing = request.orderType() == OrderType.MARKET
            && request.side() == com.fxplatform.trading.enums.OrderSide.BUY
            ? fullFillCoordinator.project(
                ProductType.CRYPTO_SPOT,
                request.side(),
                FullFillExecutionPath.MARKET,
                null,
                snapshot)
            : null;
        QuantityConversionService.Conversion conversion = quantityConversionService.convertSpot(
            request.side(),
            request.orderType(),
            request.quantityUnit(),
            request.quantity(),
            rules.stepSize(),
            marketPricing);
        BigDecimal referencePrice = canonicalRulePrice(request, path, snapshot, marketPricing);
        instrumentRulesEngine.validateCanonicalOrder(
            request,
            symbol,
            conversion.baseQuantity(),
            referencePrice);
        requireSimpleCreationMarketability(request, path);
        OrderCommand canonicalCommand = canonicalCommand(
            identityCommand,
            request,
            conversion.baseQuantity());
        OrderHoldCalculator.OrderHold hold =
            request.timeInForce() == TimeInForce.IOC && path == null
                ? null
                : pendingHold(request, conversion.baseQuantity(), path, snapshot);
        return transactionExecutor.execute(() -> persistP0Spot(
            canonicalCommand,
            request,
            accountSnapshot,
            path,
            hold,
            snapshot));
      } catch (BusinessException exception) {
        if (!ErrorCode.MARKET_DATA_STALE.equals(exception.getCode())) {
          throw exception;
        }
        lastStale = exception;
      }
    }
    throw new BusinessException(
        ErrorCode.MARKET_DATA_STALE,
        lastStale == null ? "No fresh executable market snapshot" : lastStale.getMessage());
  }

  private OrderResponse createP0PerpetualWithRetry(
      OrderCommand identityCommand,
      CreateOrderRequest request
  ) {
    validateP0PerpetualContract(request);
    SymbolEntity symbol = symbolRepository.findBySymbol(identityCommand.symbol())
        .orElseThrow(() -> new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found"));
    InstrumentRules rules = instrumentRulesEngine.rules(symbol);
    BusinessException lastStale = null;
    for (int attempt = 0; attempt < MAX_MARKET_ATTEMPTS; attempt++) {
      try {
        ExecutableMarketSnapshot snapshot = resolveExecutableSnapshot(
            identityCommand.symbol(), ProductType.LINEAR_PERP);
        fullFillCoordinator.requireFresh(snapshot);
        PerpetualAccountRiskSnapshotService.PreparedAccountRisk preparedAccountRisk =
            perpetualAccountRiskSnapshotService.prepare(
                identityCommand.accountId(),
                Map.of(identityCommand.symbol(), snapshot));
        QuantityConversionService.Conversion conversion = quantityConversionService.convertPerpetual(
            request.quantityUnit(),
            request.quantity(),
            rules.stepSize(),
            symbol.getContractSize(),
            symbol.getContractMultiplier(),
            snapshot.mark(),
            rules.minNotional());
        instrumentRulesEngine.validateCanonicalOrder(
            perpetualRuleValidationRequest(request),
            symbol,
            conversion.baseQuantity(),
            request.orderType() == OrderType.LIMIT || request.orderType() == OrderType.STOP_LIMIT
                ? request.price()
                : snapshot.mark());
        FullFillExecutionPath path = perpetualExecutionPath(request, snapshot);
        requireSimpleCreationMarketability(request, path);
        return transactionExecutor.execute(() -> persistP0Perpetual(
            identityCommand,
            request,
            conversion,
            path,
            snapshot,
            preparedAccountRisk));
      } catch (BusinessException exception) {
        if (!ErrorCode.MARKET_DATA_STALE.equals(exception.getCode())) {
          throw exception;
        }
        lastStale = exception;
      }
    }
    throw new BusinessException(
        ErrorCode.MARKET_DATA_STALE,
        lastStale == null ? "No fresh executable market snapshot" : lastStale.getMessage());
  }

  private OrderResponse createTrailingStopWithRetry(
      OrderCommand identityCommand,
      CreateOrderRequest request
  ) {
    SymbolEntity symbol = symbolRepository.findBySymbol(identityCommand.symbol())
        .orElseThrow(() -> new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found"));
    InstrumentRules rules = instrumentRulesEngine.rules(symbol);
    requireTrailingTickAlignment(request, rules.tickSize());
    BusinessException lastStale = null;
    for (int attempt = 0; attempt < MAX_MARKET_ATTEMPTS; attempt++) {
      try {
        ExecutableMarketSnapshot snapshot = resolveExecutableSnapshot(
            identityCommand.symbol(), ProductType.LINEAR_PERP);
        fullFillCoordinator.requireFresh(snapshot);
        requireTrailingStorageContract(request, snapshot.mark());
        QuantityConversionService.Conversion conversion = quantityConversionService.convertPerpetual(
            request.quantityUnit(),
            request.quantity(),
            rules.stepSize(),
            symbol.getContractSize(),
            symbol.getContractMultiplier(),
            snapshot.mark(),
            rules.minNotional());
        instrumentRulesEngine.validateCanonicalOrder(
            perpetualRuleValidationRequest(request),
            symbol,
            conversion.baseQuantity(),
            snapshot.mark());
        return transactionExecutor.execute(() -> persistTrailingStop(
            identityCommand,
            request,
            conversion,
            snapshot,
            rules));
      } catch (BusinessException exception) {
        if (!ErrorCode.MARKET_DATA_STALE.equals(exception.getCode())) {
          throw exception;
        }
        lastStale = exception;
      }
    }
    throw new BusinessException(
        ErrorCode.MARKET_DATA_STALE,
        lastStale == null ? "No fresh trailing-stop market snapshot" : lastStale.getMessage());
  }

  private OrderResponse persistTrailingStop(
      OrderCommand identityCommand,
      CreateOrderRequest publicRequest,
      QuantityConversionService.Conversion conversion,
      ExecutableMarketSnapshot snapshot,
      InstrumentRules rules
  ) {
    TradingAccountEntity account = accountRepository.findByIdAndUserIdForUpdate(
            identityCommand.accountId(), identityCommand.userId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    java.util.Optional<OrderEntity> lockedReplay = findExistingOrder(identityCommand);
    if (lockedReplay.isPresent()) {
      OrderEntity existing = requireMatchingReplay(
          lockedReplay.get(),
          identityCommand,
          publicRequest,
          OrderRequestFingerprint.calculate(publicRequest));
      return orderResponseMapper.toResponse(existing);
    }
    demoExecutionGuard.requireDemo(account, ProductType.LINEAR_PERP, identityCommand.symbol());
    AccountSymbolSettingEntity setting = accountSymbolSettingRepository
        .findByAccountIdAndSymbolForUpdate(account.getId(), identityCommand.symbol())
        .orElseThrow(() -> new BusinessException(
            "INVALID_INSTRUMENT_RULES",
            "Locked Perpetual symbol setting is required"));
    perpetualAccountRiskSnapshotService.requireCurrentLeverageWithinLimit(setting);
    List<PositionEntity> lockedPositions = positionRepository.findOpenLinearPerpBySymbolForUpdate(
        account.getId(), identityCommand.symbol());
    List<OrderEntity> lockedActiveOrders = orderRepository.findActiveLinearPerpBySymbolForUpdate(
        account.getId(), identityCommand.symbol());
    PositionEntity target = requireTrailingTarget(
        account,
        setting,
        publicRequest,
        lockedPositions,
        conversion.baseQuantity());
    requireTrailingProtectionBudget(target, lockedActiveOrders, conversion.baseQuantity());
    fullFillCoordinator.requireFresh(snapshot);

    OrderCommand canonical = new OrderCommand(
        identityCommand.userId(),
        identityCommand.accountId(),
        identityCommand.symbol(),
        publicRequest.side(),
        OrderType.TRAILING_STOP_MARKET,
        conversion.baseQuantity(),
        null,
        null,
        null,
        identityCommand.clientOrderId(),
        identityCommand.idempotencyKey(),
        target.getLeverage(),
        publicRequest.quantity(),
        conversion.baseQuantity(),
        publicRequest.quantityUnit(),
        target.getMarginMode(),
        target.getPositionSide(),
        true,
        null,
        TriggerPriceType.MARK_PRICE,
        List.of(),
        TimeInForce.GTC,
        false,
        publicRequest.activationPrice(),
        publicRequest.trailingDelta(),
        publicRequest.trailingRate());
    OrderEntity carrier = preparedOrder(
        canonical,
        publicRequest,
        ProductType.LINEAR_PERP,
        target.getLeverage());
    carrier.setPositionMode(target.getPositionMode());
    carrier.setPositionSide(target.getPositionSide());
    carrier.setMarginMode(target.getMarginMode());
    carrier.setOrderType(OrderType.TRAILING_STOP_MARKET);
    carrier.setStatus(OrderStatus.PENDING_ACTIVATION);
    carrier.setOrderOrigin(OrderOrigin.PROTECTIVE);
    carrier.setProtectionType(ProtectionType.STOP_LOSS);
    carrier.setTriggerPrice(null);
    carrier.setTriggerPriceType(TriggerPriceType.MARK_PRICE);
    carrier.setTriggerExecutionType(TriggerExecutionType.MARKET);
    carrier.setParentPositionId(target.getId());
    carrier.setFilledQuantity(BigDecimal.ZERO);
    carrier.setRemainingQuantity(conversion.baseQuantity());
    carrier.setHoldAmount(BigDecimal.ZERO);
    carrier.setHoldCurrency(rules.settlementAsset());
    carrier.setFee(BigDecimal.ZERO);
    carrier.setSlippage(BigDecimal.ZERO);
    carrier.setVersion(0L);
    orderRepository.save(carrier);
    orderEventService.record(
        carrier.getId(),
        "ORDER_PENDING_ACTIVATION",
        OrderStatus.ACCEPTED,
        OrderStatus.PENDING_ACTIVATION,
        null,
        "Trailing stop accepted and waiting for activation");
    return orderResponseMapper.toResponse(carrier);
  }

  private PositionEntity requireTrailingTarget(
      TradingAccountEntity account,
      AccountSymbolSettingEntity setting,
      CreateOrderRequest request,
      List<PositionEntity> lockedPositions,
      BigDecimal baseQuantity
  ) {
    PositionMode mode = account.getPositionMode();
    PositionSide requestedSide = request.positionSide();
    if (mode == PositionMode.ONE_WAY && requestedSide != PositionSide.BOTH) {
      throw new BusinessException(
          ErrorCode.INVALID_POSITION_SIDE,
          "ONE_WAY trailing stop requires the BOTH position slot");
    }
    if (mode == PositionMode.HEDGE
        && (requestedSide == PositionSide.BOTH
        || (request.side() == com.fxplatform.trading.enums.OrderSide.SELL
        && requestedSide != PositionSide.LONG)
        || (request.side() == com.fxplatform.trading.enums.OrderSide.BUY
        && requestedSide != PositionSide.SHORT))) {
      throw new BusinessException(
          ErrorCode.INVALID_POSITION_SIDE,
          "HEDGE trailing stop must select the position slot reduced by its close side");
    }
    com.fxplatform.trading.enums.OrderSide requiredOpenSide =
        request.side() == com.fxplatform.trading.enums.OrderSide.SELL
            ? com.fxplatform.trading.enums.OrderSide.BUY
            : request.side() == com.fxplatform.trading.enums.OrderSide.BUY
                ? com.fxplatform.trading.enums.OrderSide.SELL
                : null;
    List<PositionEntity> candidates = (lockedPositions == null ? List.<PositionEntity>of() : lockedPositions)
        .stream()
        .filter(position -> position != null
            && position.getStatus() == PositionStatus.OPEN
            && positive(position.getLots())
            && position.getProductType() == ProductType.LINEAR_PERP
            && account.getId().equals(position.getAccountId())
            && identitySymbol(request.symbol()).equals(identitySymbol(position.getSymbol()))
            && position.getPositionMode() == mode
            && position.getPositionSide() == requestedSide
            && requiredOpenSide != null
            && position.getSide() == requiredOpenSide)
        .toList();
    if (candidates.size() != 1) {
      throw new BusinessException(
          ErrorCode.INVALID_POSITION_SIDE,
          "Trailing stop target position is missing or ambiguous");
    }
    PositionEntity target = candidates.getFirst();
    if (request.marginMode() != target.getMarginMode()
        || setting.getMarginMode() != target.getMarginMode()) {
      throw new BusinessException(
          ErrorCode.INVALID_MARGIN_MODE,
          "Trailing stop margin mode conflicts with the locked position authority");
    }
    if (target.getLeverage() == null
        || setting.getLeverage() == null
        || !target.getLeverage().equals(setting.getLeverage())
        || (request.leverage() != null && !request.leverage().equals(target.getLeverage()))) {
      throw new BusinessException(
          ErrorCode.LEVERAGE_OUT_OF_RANGE,
          "Trailing stop leverage conflicts with the locked position authority");
    }
    if (!positive(baseQuantity) || baseQuantity.compareTo(target.getLots()) > 0) {
      throw new BusinessException(
          ErrorCode.REDUCE_ONLY_EXCEEDS_POSITION,
          "Trailing stop quantity exceeds the locked open position");
    }
    return target;
  }

  private static String identitySymbol(String symbol) {
    return symbol == null ? "" : SymbolNormalizer.normalize(symbol);
  }

  private static boolean positive(BigDecimal value) {
    return value != null && value.compareTo(BigDecimal.ZERO) > 0;
  }

  private void requireTrailingProtectionBudget(
      PositionEntity target,
      List<OrderEntity> lockedActiveOrders,
      BigDecimal newQuantity
  ) {
    List<OrderEntity> activeProtections = (lockedActiveOrders == null
        ? List.<OrderEntity>of()
        : lockedActiveOrders).stream()
        .filter(order -> order != null
            && target.getId().equals(order.getParentPositionId())
            && order.getOrderOrigin() == OrderOrigin.PROTECTIVE
            && order.getProtectionType() != null
            && (order.getStatus() == OrderStatus.PENDING_ACTIVATION
            || order.getStatus() == OrderStatus.PENDING
            || order.getStatus() == OrderStatus.WORKING
            || order.getStatus() == OrderStatus.PARTIALLY_FILLED
            || order.getStatus() == OrderStatus.CANCEL_PENDING))
        .toList();
    if (activeProtections.size() >= 10) {
      throw new BusinessException(
          ErrorCode.PROTECTION_LIMIT_EXCEEDED,
          "A position may have at most ten active protections");
    }
    BigDecimal protectedStopLoss = activeProtections.stream()
        .filter(order -> order.getProtectionType() == ProtectionType.STOP_LOSS)
        .map(this::activeProtectionQuantity)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (protectedStopLoss.add(newQuantity).compareTo(target.getLots()) > 0) {
      throw new BusinessException(
          ErrorCode.PROTECTION_QUANTITY_EXCEEDED,
          "Trailing stop quantity exceeds the unprotected position quantity");
    }
  }

  private BigDecimal activeProtectionQuantity(OrderEntity order) {
    if (positive(order.getRemainingQuantity())) {
      return order.getRemainingQuantity();
    }
    if (positive(order.getBaseQuantity())) {
      return order.getBaseQuantity();
    }
    return positive(order.getLots()) ? order.getLots() : BigDecimal.ZERO;
  }

  private void requireTrailingTickAlignment(
      CreateOrderRequest request,
      BigDecimal tickSize
  ) {
    if (!positive(tickSize)) {
      throw new BusinessException(
          "INVALID_INSTRUMENT_RULES",
          "Trailing stop requires a positive price tick size");
    }
    if ((request.activationPrice() != null
        && request.activationPrice().remainder(tickSize).compareTo(BigDecimal.ZERO) != 0)
        || (request.trailingDelta() != null
        && request.trailingDelta().remainder(tickSize).compareTo(BigDecimal.ZERO) != 0)) {
      throw new BusinessException(
          "PRICE_TICK_MISMATCH",
          "Trailing activation price and absolute callback must match the price tick size");
    }
  }

  private static void requireTrailingStorageContract(
      CreateOrderRequest request,
      BigDecimal authorityMark
  ) {
    boolean authorityMarkFits = ExactNumeric.fits(
        authorityMark, TRAILING_AUTHORITY_MARK_PRECISION, TRAILING_AUTHORITY_MARK_SCALE);
    if (!authorityMarkFits) {
      throw new BusinessException(
          "INVALID_INSTRUMENT_RULES",
          "Trailing stop MARK exceeds the persisted numeric width");
    }

    // CreateOrderNumericBoundary already limits activation/delta to NUMERIC(30,12) and rate to
    // NUMERIC(18,10); MARK is narrower and rate is below one by the trailing public contract.
    // The exact threshold stays below 2e18 in magnitude; directional scale-10 rounding is at
    // most 2e18. NUMERIC(31,10) therefore covers every first/later Tick, not just this snapshot.
    BigDecimal initialExtreme = request.activationPrice() == null
        ? authorityMark
        : request.activationPrice();
    BigDecimal exactThreshold;
    if (request.trailingDelta() != null) {
      exactThreshold = request.side() == com.fxplatform.trading.enums.OrderSide.SELL
          ? initialExtreme.subtract(request.trailingDelta())
          : initialExtreme.add(request.trailingDelta());
    } else {
      exactThreshold = request.side() == com.fxplatform.trading.enums.OrderSide.SELL
          ? initialExtreme.multiply(BigDecimal.ONE.subtract(request.trailingRate()))
          : initialExtreme.multiply(BigDecimal.ONE.add(request.trailingRate()));
    }
    RoundingMode rounding = request.side() == com.fxplatform.trading.enums.OrderSide.SELL
        ? RoundingMode.FLOOR
        : RoundingMode.CEILING;
    BigDecimal persistedThreshold = exactThreshold.setScale(
        TRAILING_TRIGGER_PRICE_SCALE,
        rounding);
    if (!ExactNumeric.fits(
        persistedThreshold,
        TRAILING_TRIGGER_PRICE_PRECISION,
        TRAILING_TRIGGER_PRICE_SCALE)) {
      throw new BusinessException(
          "INVALID_INSTRUMENT_RULES",
          "Trailing stop callback produces an unstorable trigger threshold");
    }
  }

  private OrderResponse createP0PerpetualDepthWithRetry(
      OrderCommand identityCommand,
      CreateOrderRequest request,
      DemoExecutionPolicy initialPolicy
  ) {
    validateP0PerpetualContract(request);
    SymbolEntity symbol = symbolRepository.findBySymbol(identityCommand.symbol())
        .orElseThrow(() -> new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found"));
    InstrumentRules rules = instrumentRulesEngine.rules(symbol);
    BusinessException lastStale = null;
    for (int attempt = 0; attempt < MAX_MARKET_ATTEMPTS; attempt++) {
      DemoExecutionPolicy policy = attempt == 0
          ? initialPolicy
          : depthOrderExecutionService.currentPolicy();
      if (!depthOrderExecutionService.isDepth(policy)) {
        throw new BusinessException(
            ErrorCode.MARKET_DATA_STALE,
            "DEPTH execution policy changed before Perpetual order planning");
      }
      try {
        ExecutableMarketSnapshot snapshot = resolveExecutableSnapshot(
            identityCommand.symbol(), ProductType.LINEAR_PERP);
        QuantityConversionService.Conversion conversion = quantityConversionService.convertPerpetual(
            request.quantityUnit(),
            request.quantity(),
            rules.stepSize(),
            symbol.getContractSize(),
            symbol.getContractMultiplier(),
            snapshot.mark(),
            rules.minNotional());
        DepthOrderExecutionService.DepthMatchPlan match = depthPerpetualMatchPlan(
            identityCommand,
            request,
            conversion.baseQuantity(),
            policy,
            snapshot);
        depthOrderExecutionService.requireQuantityStep(
            match,
            QuantityConversionService.storageCompatibleStep(rules.stepSize()));
        PerpetualRiskPricing pricing = depthOrderExecutionService.perpetualRiskPricing(match);
        instrumentRulesEngine.validateCanonicalDepthExecution(
            symbol,
            rules,
            match.matchingResult().fills(),
            match.matchingResult().remainingQuantity(),
            pricing.marginAndFeePrice());
        instrumentRulesEngine.validateCanonicalOrder(
            perpetualRuleValidationRequest(request),
            symbol,
            conversion.baseQuantity(),
            depthPerpetualRulePrice(request, match, snapshot));
        PerpetualAccountRiskSnapshotService.PreparedAccountRisk preparedAccountRisk =
            perpetualAccountRiskSnapshotService.prepare(
                identityCommand.accountId(),
                Map.of(identityCommand.symbol(), snapshot));
        return transactionExecutor.execute(() -> persistP0PerpetualDepth(
            identityCommand,
            request,
            conversion,
            match,
            pricing,
            preparedAccountRisk));
      } catch (DepthOrderExecutionService.StalePolicyException exception) {
        lastStale = new BusinessException(
            ErrorCode.MARKET_DATA_STALE,
            "DEPTH execution policy changed during Perpetual order planning",
            exception);
      } catch (BusinessException exception) {
        if (!ErrorCode.MARKET_DATA_STALE.equals(exception.getCode())) {
          throw exception;
        }
        lastStale = exception;
      }
    }
    throw new BusinessException(
        ErrorCode.MARKET_DATA_STALE,
        lastStale == null ? "No fresh DEPTH Perpetual snapshot" : lastStale.getMessage());
  }

  private DepthOrderExecutionService.DepthMatchPlan depthPerpetualMatchPlan(
      OrderCommand identityCommand,
      CreateOrderRequest request,
      BigDecimal baseQuantity,
      DemoExecutionPolicy policy,
      ExecutableMarketSnapshot snapshot
  ) {
    boolean pendingStop = request.orderType() == OrderType.STOP_LIMIT
        || (request.orderType() == OrderType.STOP_MARKET
            && !isPerpetualStopTriggered(request, snapshot));
    if (pendingStop) {
      return depthOrderExecutionService.preparePendingStop(
          policy,
          identityCommand.symbol(),
          ProductType.LINEAR_PERP,
          request.side(),
          request.orderType(),
          request.timeInForce(),
          baseQuantity,
          request.orderType() == OrderType.STOP_LIMIT ? request.price() : null,
          snapshot);
    }
    OrderType executableType = request.orderType() == OrderType.STOP_MARKET
        ? OrderType.MARKET
        : request.orderType();
    return depthOrderExecutionService.prepare(
        policy,
        identityCommand.symbol(),
        ProductType.LINEAR_PERP,
        request.side(),
        request.orderType(),
        executableType,
        request.timeInForce(),
        baseQuantity,
        executableType == OrderType.LIMIT ? request.price() : null,
        Boolean.TRUE.equals(request.postOnly()),
        LiquidityRole.TAKER,
        snapshot);
  }

  private boolean isPerpetualStopTriggered(
      CreateOrderRequest request,
      ExecutableMarketSnapshot snapshot
  ) {
    return request.side() == com.fxplatform.trading.enums.OrderSide.BUY
        ? snapshot.mark().compareTo(request.triggerPrice()) >= 0
        : snapshot.mark().compareTo(request.triggerPrice()) <= 0;
  }

  private BigDecimal depthPerpetualRulePrice(
      CreateOrderRequest request,
      DepthOrderExecutionService.DepthMatchPlan match,
      ExecutableMarketSnapshot snapshot
  ) {
    if (request.orderType() == OrderType.LIMIT
        || request.orderType() == OrderType.STOP_LIMIT) {
      return request.price();
    }
    return match.matchingResult().fills().isEmpty()
        ? snapshot.mark()
        : match.matchingResult().fills().getFirst().price();
  }

  private OrderResponse persistP0PerpetualDepth(
      OrderCommand identityCommand,
      CreateOrderRequest publicRequest,
      QuantityConversionService.Conversion conversion,
      DepthOrderExecutionService.DepthMatchPlan match,
      PerpetualRiskPricing pricing,
      PerpetualAccountRiskSnapshotService.PreparedAccountRisk preparedAccountRisk
  ) {
    ExecutableMarketSnapshot snapshot = match.snapshot();
    TradingAccountEntity account = accountRepository.findByIdAndUserIdForUpdate(
            identityCommand.accountId(), identityCommand.userId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    demoExecutionGuard.requireDemo(account, ProductType.LINEAR_PERP, identityCommand.symbol());
    depthOrderExecutionService.requireApplicable(match);
    AccountSymbolSettingEntity setting = accountSymbolSettingRepository
        .findByAccountIdAndSymbolForUpdate(account.getId(), identityCommand.symbol())
        .orElseThrow(() -> new BusinessException(
            "INVALID_INSTRUMENT_RULES",
            "Locked Perpetual symbol setting is required"));
    perpetualAccountRiskSnapshotService.requireCurrentLeverageWithinLimit(setting);
    List<PositionEntity> accountPositions = positionRepository
        .findOpenLinearPerpByAccountIdForUpdate(account.getId());
    List<OrderEntity> accountActiveOrders = orderRepository
        .findActiveLinearPerpByAccountIdForUpdate(account.getId());
    List<PositionEntity> positions = accountPositions.stream()
        .filter(position -> identityCommand.symbol().equals(position.getSymbol()))
        .toList();
    List<OrderEntity> activeOrders = accountActiveOrders.stream()
        .filter(order -> identityCommand.symbol().equals(order.getSymbol()))
        .toList();
    PerpetualAccountRiskSnapshotService.AccountRiskProjection accountRisk =
        perpetualAccountRiskSnapshotService.project(
            account,
            accountPositions,
            accountActiveOrders,
            preparedAccountRisk);
    PerpetualAccountRiskSnapshotService.PreparedSymbolRisk targetRisk =
        preparedAccountRisk.symbols().get(identityCommand.symbol());
    if (targetRisk == null) {
      throw new BusinessException(
          ErrorCode.MARKET_DATA_UNAVAILABLE,
          "Target Perpetual risk snapshot was not prepared");
    }
    PerpetualOrderRiskService.OrderRisk risk = perpetualOrderRiskService.evaluateDepth(
        account.getPositionMode(),
        setting,
        positions,
        publicRequest.side(),
        publicRequest.positionSide(),
        Boolean.TRUE.equals(publicRequest.reduceOnly()),
        match.sourceOrderType(),
        conversion.baseQuantity(),
        match.limitPrice(),
        snapshot,
        targetRisk.maintenanceMarginRate(),
        pricing);
    PerpetualOrderRiskService.DepthPlanningAuthority planningAuthority =
        new PerpetualOrderRiskService.DepthPlanningAuthority(
            account.getId(),
            account.getPositionMode(),
            risk.positionSide(),
            risk.marginMode(),
            risk.leverage(),
            Boolean.TRUE.equals(publicRequest.reduceOnly()),
            targetRisk.maintenanceMarginRate());
    DepthOrderExecutionService.DepthHoldPlan holds = depthOrderExecutionService.planPerpetual(
        planningAuthority,
        risk,
        risk.holdAmount(),
        match);
    OrderCommand canonicalCommand = canonicalPerpetualCommand(
        identityCommand,
        publicRequest,
        conversion.baseQuantity(),
        risk);
    OrderEntity order = preparedOrder(
        canonicalCommand,
        publicRequest,
        ProductType.LINEAR_PERP,
        risk.leverage());
    order.setPositionMode(risk.positionMode());
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(conversion.baseQuantity());
    order.setHoldAmount(holds.initialHold());
    order.setHoldCurrency(holds.initialHold().signum() > 0 ? risk.holdCurrency() : null);
    PositionEntity isolatedHoldPosition = isolatedCloseHoldPosition(risk, positions);
    if (isolatedHoldPosition != null) {
      order.setParentPositionId(isolatedHoldPosition.getId());
      PerpetualIsolatedCloseHoldValidator.validate(
          isolatedHoldPosition,
          risk,
          holds.initialHold(),
          activeOrders);
    }

    depthOrderExecutionService.requireApplicable(match);
    boolean zeroFillIoc = publicRequest.timeInForce() == TimeInForce.IOC
        && match.matchingResult().fills().isEmpty();
    if (zeroFillIoc) {
      order.setStatus(OrderStatus.CANCELLED);
      order.setFilledQuantity(BigDecimal.ZERO);
      order.setRemainingQuantity(BigDecimal.ZERO);
      order.setHoldAmount(BigDecimal.ZERO);
      order.setHoldCurrency(null);
      order.setParentPositionId(null);
      order.setCanceledAt(Instant.now());
      orderRepository.save(order);
      orderEventService.record(
          order.getId(),
          "ORDER_CANCELED",
          OrderStatus.ACCEPTED,
          OrderStatus.CANCELLED,
          null,
          "DEPTH IOC Perpetual order canceled without an eligible fill");
      return orderResponseMapper.toResponse(order);
    }

    perpetualAccountRiskSnapshotService.applyRevaluation(
        account,
        accountPositions,
        accountRisk);
    if (holds.initialHold().signum() > 0) {
      if (isolatedHoldPosition != null) {
        reserveIsolatedPerpetualOrderHold(account, holds.initialHold());
      } else {
        reservePerpetualOrderHold(account, holds.initialHold());
      }
    } else {
      accountRepository.save(account);
    }
    for (PositionEntity position : accountPositions) {
      positionRepository.save(position);
    }
    boolean hasFills = !match.matchingResult().fills().isEmpty();
    order.setStatus(hasFills ? OrderStatus.ACCEPTED : match.initialOrderStatus());
    orderRepository.save(order);
    if (!publicRequest.attachedProtections().isEmpty()) {
      protectionOrderService.createAttachedLocked(
          order,
          publicRequest.attachedProtections(),
          snapshot.mark());
    }
    if (holds.initialHold().signum() > 0) {
      ledgerService.recordOrderHold(
          account,
          holds.initialHold(),
          order.getId(),
          "Perpetual DEPTH order margin reserved");
    }

    if (!hasFills) {
      OrderStatus pendingStatus = order.getStatus();
      orderEventService.record(
          order.getId(),
          pendingStatus == OrderStatus.PENDING_ACTIVATION
              ? "ORDER_PENDING_ACTIVATION"
              : "ORDER_PENDING",
          OrderStatus.ACCEPTED,
          pendingStatus,
          null,
          pendingStatus == OrderStatus.PENDING_ACTIVATION
              ? "DEPTH Perpetual order accepted and waiting for activation"
              : "DEPTH Perpetual order accepted and waiting");
      return orderResponseMapper.toResponse(order);
    }

    DepthOrderExecutionService.DepthExecutionOutcome outcome =
        depthOrderExecutionService.applyLocked(
            order,
            order,
            account,
            match,
            holds,
            publicRequest.timeInForce() == TimeInForce.IOC);
    if (publicRequest.timeInForce() == TimeInForce.IOC
        && outcome.remainingQuantity().signum() > 0) {
      OrderStatus sourceStatus = order.getStatus();
      BigDecimal tailHold = outcome.remainingHold();
      if (tailHold.signum() > 0) {
        if (isolatedHoldPosition != null) {
          releaseIsolatedPerpetualOrderHold(account, tailHold);
        } else {
          releasePerpetualOrderHold(account, tailHold);
        }
        ledgerService.recordOrderRelease(
            account,
            tailHold,
            order.getId(),
            "DEPTH IOC Perpetual remainder released");
      }
      order.setStatus(OrderStatus.CANCELLED);
      order.setRemainingQuantity(BigDecimal.ZERO);
      order.setHoldAmount(BigDecimal.ZERO);
      order.setCanceledAt(Instant.now());
      orderRepository.save(order);
      orderEventService.record(
          order.getId(),
          "ORDER_CANCELED",
          sourceStatus,
          OrderStatus.CANCELLED,
          null,
          "DEPTH IOC Perpetual unmatched remainder canceled");
    }
    return orderResponseMapper.toResponse(order);
  }

  private OrderResponse persistP0Perpetual(
      OrderCommand identityCommand,
      CreateOrderRequest publicRequest,
      QuantityConversionService.Conversion conversion,
      FullFillExecutionPath path,
      ExecutableMarketSnapshot snapshot,
      PerpetualAccountRiskSnapshotService.PreparedAccountRisk preparedAccountRisk
  ) {
    TradingAccountEntity account = accountRepository.findByIdAndUserIdForUpdate(
            identityCommand.accountId(), identityCommand.userId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    demoExecutionGuard.requireDemo(account, ProductType.LINEAR_PERP, identityCommand.symbol());
    AccountSymbolSettingEntity setting = accountSymbolSettingRepository
        .findByAccountIdAndSymbolForUpdate(account.getId(), identityCommand.symbol())
        .orElseThrow(() -> new BusinessException(
            "INVALID_INSTRUMENT_RULES",
            "Locked Perpetual symbol setting is required"));
    perpetualAccountRiskSnapshotService.requireCurrentLeverageWithinLimit(setting);
    List<PositionEntity> accountPositions = positionRepository
        .findOpenLinearPerpByAccountIdForUpdate(account.getId());
    List<OrderEntity> accountActiveOrders = orderRepository
        .findActiveLinearPerpByAccountIdForUpdate(account.getId());
    List<PositionEntity> positions = accountPositions.stream()
        .filter(position -> identityCommand.symbol().equals(position.getSymbol()))
        .toList();
    List<OrderEntity> activeOrders = accountActiveOrders.stream()
        .filter(order -> identityCommand.symbol().equals(order.getSymbol()))
        .toList();
    PerpetualAccountRiskSnapshotService.PreparedSymbolRisk targetRisk =
        preparedAccountRisk.symbols().get(identityCommand.symbol());
    if (targetRisk == null) {
      throw new BusinessException(
          ErrorCode.MARKET_DATA_UNAVAILABLE,
          "Target Perpetual risk snapshot was not prepared");
    }
    PerpetualOrderRiskService.OrderRisk risk = perpetualOrderRiskService.evaluate(
        account.getPositionMode(),
        setting,
        positions,
        publicRequest.side(),
        publicRequest.positionSide(),
        Boolean.TRUE.equals(publicRequest.reduceOnly()),
        publicRequest.orderType(),
        conversion.baseQuantity(),
        publicRequest.price(),
        snapshot,
        targetRisk.maintenanceMarginRate());
    OrderCommand canonicalCommand = canonicalPerpetualCommand(
        identityCommand,
        publicRequest,
        conversion.baseQuantity(),
        risk);
    OrderEntity order = preparedOrder(
        canonicalCommand,
        publicRequest,
        ProductType.LINEAR_PERP,
        risk.leverage());
    order.setPositionMode(risk.positionMode());
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(conversion.baseQuantity());
    order.setHoldAmount(risk.holdAmount());
    order.setHoldCurrency(risk.holdCurrency());
    PositionEntity isolatedHoldPosition = isolatedCloseHoldPosition(risk, positions);
    if (isolatedHoldPosition != null) {
      order.setParentPositionId(isolatedHoldPosition.getId());
      PerpetualIsolatedCloseHoldValidator.validate(
          isolatedHoldPosition,
          risk,
          activeOrders);
    }

    if (path == null && publicRequest.timeInForce() == TimeInForce.IOC) {
      fullFillCoordinator.requireFresh(snapshot);
      requirePreparedAccountRiskFresh(preparedAccountRisk);
      order.setStatus(OrderStatus.CANCELLED);
      order.setFilledQuantity(BigDecimal.ZERO);
      order.setRemainingQuantity(BigDecimal.ZERO);
      order.setHoldAmount(BigDecimal.ZERO);
      order.setHoldCurrency(null);
      order.setParentPositionId(null);
      order.setCanceledAt(Instant.now());
      orderRepository.save(order);
      orderEventService.record(
          order.getId(),
          "ORDER_CANCELED",
          OrderStatus.ACCEPTED,
          OrderStatus.CANCELLED,
          null,
          "IOC order canceled because it was not immediately marketable");
      return orderResponseMapper.toResponse(order);
    }

    PerpetualAccountRiskSnapshotService.AccountRiskProjection accountRisk =
        perpetualAccountRiskSnapshotService.project(
            account,
            accountPositions,
            accountActiveOrders,
            preparedAccountRisk);
    perpetualAccountRiskSnapshotService.applyRevaluation(
        account,
        accountPositions,
        accountRisk);

    FullFillResult fullFill = null;
    if (path != null) {
      CreateOrderRequest executionIntent = canonicalPerpetualExecutionIntent(
          publicRequest,
          conversion.baseQuantity(),
          risk);
      fullFill = fullFillCoordinator.execute(
          new FullFillRequest(
              executionIntent,
              identityCommand.symbol(),
              ProductType.LINEAR_PERP,
              identityCommand.side(),
              path,
              conversion.baseQuantity(),
              path == FullFillExecutionPath.IMMEDIATE_LIMIT ? publicRequest.price() : null),
          snapshot);
      fullFillCoordinator.requireFresh(fullFill);
    } else {
      fullFillCoordinator.requireFresh(snapshot);
    }

    requirePreparedAccountRiskFresh(preparedAccountRisk);

    if (isolatedHoldPosition != null) {
      reserveIsolatedPerpetualOrderHold(account, risk.holdAmount());
    } else {
      reservePerpetualOrderHold(account, risk.holdAmount());
    }
    for (PositionEntity position : accountPositions) {
      positionRepository.save(position);
    }
    order.setStatus(publicRequest.orderType() == OrderType.STOP_LIMIT
        ? OrderStatus.PENDING_ACTIVATION
        : path == null ? OrderStatus.PENDING : OrderStatus.ACCEPTED);
    orderRepository.save(order);
    if (!publicRequest.attachedProtections().isEmpty()) {
      protectionOrderService.createAttachedLocked(
          order,
          publicRequest.attachedProtections(),
          snapshot.mark());
    }
    ledgerService.recordOrderHold(
        account,
        risk.holdAmount(),
        order.getId(),
        "Perpetual order margin reserved");

    if (fullFill == null) {
      OrderStatus pendingStatus = order.getStatus();
      orderEventService.record(
          order.getId(),
          pendingStatus == OrderStatus.PENDING_ACTIVATION
              ? "ORDER_PENDING_ACTIVATION"
              : "ORDER_PENDING",
          OrderStatus.ACCEPTED,
          pendingStatus,
          null,
          pendingStatus == OrderStatus.PENDING_ACTIVATION
              ? "Perpetual order accepted and waiting for activation"
              : "Perpetual order accepted and waiting");
      return orderResponseMapper.toResponse(order);
    }

    orderFillService.fillPerpetual(
        order,
        account,
        fullFill,
        snapshot.mark(),
        risk.leverage(),
        "Perpetual position margin held");
    orderEventService.record(
        order.getId(),
        "ORDER_FILLED",
        OrderStatus.ACCEPTED,
        OrderStatus.FILLED,
        null,
        "Perpetual order filled");
    return orderResponseMapper.toResponse(order);
  }

  private FullFillExecutionPath perpetualExecutionPath(
      CreateOrderRequest request,
      ExecutableMarketSnapshot snapshot
  ) {
    return switch (request.orderType()) {
      case MARKET -> FullFillExecutionPath.MARKET;
      case LIMIT -> isLimitMarketable(request, snapshot)
          ? FullFillExecutionPath.IMMEDIATE_LIMIT
          : null;
      case STOP_MARKET -> null;
      case STOP_LIMIT -> null;
      default -> throw new BusinessException(
          "INVALID_PERPETUAL_ORDER_TYPE",
          "Unsupported P0 Linear Perpetual order type");
    };
  }

  private FullFillExecutionPath perpetualModificationExecutionPath(
      CreateOrderRequest request,
      OrderStatus sourceStatus,
      ExecutableMarketSnapshot snapshot
  ) {
    if (request.orderType() != OrderType.STOP_LIMIT) {
      return perpetualExecutionPath(request, snapshot);
    }
    if (sourceStatus == OrderStatus.PENDING_ACTIVATION) {
      return null;
    }
    return isLimitMarketable(request, snapshot)
        ? FullFillExecutionPath.IMMEDIATE_LIMIT
        : null;
  }

  private OrderResponse createP0SpotDepthWithRetry(
      OrderCommand identityCommand,
      CreateOrderRequest request,
      DemoExecutionPolicy initialPolicy
  ) {
    validateP0SpotContract(request);
    SymbolEntity symbol = symbolRepository.findBySymbol(identityCommand.symbol())
        .orElseThrow(() -> new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found"));
    InstrumentRules rules = instrumentRulesEngine.rules(symbol);
    BusinessException lastStale = null;
    for (int attempt = 0; attempt < MAX_MARKET_ATTEMPTS; attempt++) {
      DemoExecutionPolicy policy = attempt == 0
          ? initialPolicy
          : depthOrderExecutionService.currentPolicy();
      if (!depthOrderExecutionService.isDepth(policy)) {
        throw new BusinessException(
            ErrorCode.MARKET_DATA_STALE,
            "DEPTH execution policy changed before order planning");
      }
      ExecutableMarketSnapshot snapshot = resolveExecutableSnapshot(
          identityCommand.symbol(), ProductType.CRYPTO_SPOT);
      try {
        QuantityConversionService.Conversion conversion = depthSpotConversion(
            request,
            rules,
            policy);
        DepthOrderExecutionService.DepthMatchPlan match = depthSpotMatchPlan(
            identityCommand,
            request,
            conversion.baseQuantity(),
            policy,
            snapshot);
        depthOrderExecutionService.requireQuantityStep(
            match,
            QuantityConversionService.storageCompatibleStep(rules.stepSize()));
        instrumentRulesEngine.validateCanonicalDepthFills(
            symbol,
            rules,
            match.matchingResult().fills());
        instrumentRulesEngine.validateCanonicalOrder(
            request,
            symbol,
            conversion.baseQuantity(),
            depthSpotRulePrice(request, match, snapshot));
        OrderCommand canonicalCommand = canonicalCommand(
            identityCommand,
            request,
            conversion.baseQuantity());
        BigDecimal currentHold = request.orderType() == OrderType.MARKET
                && request.side() == com.fxplatform.trading.enums.OrderSide.BUY
            ? walletAmount(request.quantity())
            : BigDecimal.ZERO;
        DepthOrderExecutionService.DepthHoldPlan holds =
            depthOrderExecutionService.planSpot(currentHold, match);
        return transactionExecutor.execute(() -> persistP0SpotDepth(
            canonicalCommand,
            request,
            match,
            holds));
      } catch (DepthOrderExecutionService.StalePolicyException exception) {
        lastStale = new BusinessException(
            ErrorCode.MARKET_DATA_STALE,
            "DEPTH execution policy changed during Spot order planning",
            exception);
      } catch (BusinessException exception) {
        if (!ErrorCode.MARKET_DATA_STALE.equals(exception.getCode())) {
          throw exception;
        }
        lastStale = exception;
      }
    }
    throw new BusinessException(
        ErrorCode.MARKET_DATA_STALE,
        lastStale == null ? "No fresh DEPTH Spot snapshot" : lastStale.getMessage());
  }

  private QuantityConversionService.Conversion depthSpotConversion(
      CreateOrderRequest request,
      InstrumentRules rules,
      DemoExecutionPolicy policy
  ) {
    if (request.orderType() == OrderType.MARKET
        && request.side() == com.fxplatform.trading.enums.OrderSide.BUY) {
      DepthOrderExecutionService.SpotDepthConversion conversion =
          depthOrderExecutionService.convertSpotMarketBuy(
              request.quantity(),
              QuantityConversionService.storageCompatibleStep(rules.stepSize()),
              policy);
      return new QuantityConversionService.Conversion(
          request.quantity(),
          request.quantityUnit(),
          conversion.baseQuantity(),
          conversion.projectedQuoteSpend());
    }
    return quantityConversionService.convertSpot(
        request.side(),
        request.orderType(),
        request.quantityUnit(),
        request.quantity(),
        rules.stepSize(),
        null);
  }

  private DepthOrderExecutionService.DepthMatchPlan depthSpotMatchPlan(
      OrderCommand identityCommand,
      CreateOrderRequest request,
      BigDecimal baseQuantity,
      DemoExecutionPolicy policy,
      ExecutableMarketSnapshot snapshot
  ) {
    boolean pendingStop = request.orderType() == OrderType.STOP_LIMIT
        || (request.orderType() == OrderType.STOP_MARKET
            && !isStopTriggered(request, snapshot));
    if (pendingStop) {
      return depthOrderExecutionService.preparePendingStop(
          policy,
          identityCommand.symbol(),
          ProductType.CRYPTO_SPOT,
          request.side(),
          request.orderType(),
          request.timeInForce(),
          baseQuantity,
          request.orderType() == OrderType.STOP_LIMIT ? request.price() : null,
          snapshot);
    }
    OrderType executableType = request.orderType() == OrderType.STOP_MARKET
        ? OrderType.MARKET
        : request.orderType();
    return depthOrderExecutionService.prepare(
        policy,
        identityCommand.symbol(),
        ProductType.CRYPTO_SPOT,
        request.side(),
        request.orderType(),
        executableType,
        request.timeInForce(),
        baseQuantity,
        executableType == OrderType.LIMIT ? request.price() : null,
        Boolean.TRUE.equals(request.postOnly()),
        LiquidityRole.TAKER,
        snapshot);
  }

  private BigDecimal depthSpotRulePrice(
      CreateOrderRequest request,
      DepthOrderExecutionService.DepthMatchPlan match,
      ExecutableMarketSnapshot snapshot
  ) {
    if (request.orderType() == OrderType.LIMIT
        || request.orderType() == OrderType.STOP_LIMIT) {
      return request.price();
    }
    return match.matchingResult().fills().isEmpty()
        ? snapshot.last()
        : match.matchingResult().fills().getFirst().price();
  }

  private OrderResponse persistP0SpotDepth(
      OrderCommand command,
      CreateOrderRequest publicRequest,
      DepthOrderExecutionService.DepthMatchPlan match,
      DepthOrderExecutionService.DepthHoldPlan holds
  ) {
    TradingAccountEntity account = accountRepository.findByIdAndUserIdForUpdate(
            command.accountId(), command.userId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    demoExecutionGuard.requireDemo(account, ProductType.CRYPTO_SPOT, command.symbol());
    depthOrderExecutionService.requireApplicable(match);
    OrderEntity order = preparedOrder(command, publicRequest, ProductType.CRYPTO_SPOT, 1);
    boolean zeroFillIoc = publicRequest.timeInForce() == TimeInForce.IOC
        && match.matchingResult().fills().isEmpty();
    if (zeroFillIoc) {
      lockExistingP0State(account.getId(), ProductType.CRYPTO_SPOT);
      depthOrderExecutionService.requireApplicable(match);
      order.setStatus(OrderStatus.CANCELLED);
      order.setFilledQuantity(BigDecimal.ZERO);
      order.setRemainingQuantity(BigDecimal.ZERO);
      order.setHoldAmount(BigDecimal.ZERO);
      order.setHoldCurrency(null);
      order.setCanceledAt(Instant.now());
      orderRepository.save(order);
      orderEventService.record(
          order.getId(),
          "ORDER_CANCELED",
          OrderStatus.ACCEPTED,
          OrderStatus.CANCELLED,
          null,
          "DEPTH IOC order canceled without an eligible fill");
      return orderResponseMapper.toResponse(order);
    }

    lockExistingP0SpotMutationState(account.getId(), command.symbol());
    depthOrderExecutionService.requireApplicable(match);
    materializeP0SpotMutationState(account.getId(), command.symbol());
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(command.baseQuantity());
    order.setHoldAmount(holds.initialHold());
    order.setHoldCurrency(command.side() == com.fxplatform.trading.enums.OrderSide.BUY
        ? "USDT"
        : p0SpotBaseAsset(command.symbol()));
    if (match.matchingResult().fills().isEmpty()) {
      OrderStatus pendingStatus = match.initialOrderStatus();
      order.setStatus(pendingStatus);
      orderRepository.save(order);
      if (holds.initialHold().signum() > 0) {
        reserveOrderHold(
            account,
            holds.initialHold(),
            order.getHoldCurrency(),
            true,
            order.getId(),
            "Pending order margin reserved",
            "Pending spot order wallet locked");
      }
      orderEventService.record(
          order.getId(),
          pendingStatus == OrderStatus.PENDING_ACTIVATION
              ? "ORDER_PENDING_ACTIVATION"
              : "ORDER_PENDING",
          OrderStatus.ACCEPTED,
          pendingStatus,
          null,
          pendingStatus == OrderStatus.PENDING_ACTIVATION
              ? "DEPTH order accepted and waiting for activation"
              : "DEPTH order accepted and waiting");
      return orderResponseMapper.toResponse(order);
    }

    order.setStatus(OrderStatus.ACCEPTED);
    orderRepository.save(order);
    reserveOrderHold(
        account,
        holds.initialHold(),
        order.getHoldCurrency(),
        true,
        order.getId(),
        "Order margin reserved for DEPTH execution",
        "Spot order wallet locked for DEPTH execution");
    DepthOrderExecutionService.DepthExecutionOutcome outcome =
        depthOrderExecutionService.applyLocked(
            order,
            order,
            account,
            match,
            holds,
            publicRequest.timeInForce() == TimeInForce.IOC);
    if (publicRequest.timeInForce() == TimeInForce.IOC
        && outcome.remainingQuantity().signum() > 0) {
      OrderStatus sourceStatus = order.getStatus();
      BigDecimal tailHold = outcome.remainingHold();
      releaseOrderHold(
          account,
          order,
          tailHold,
          true,
          "DEPTH IOC remainder released",
          "DEPTH IOC Spot remainder released");
      order.setStatus(OrderStatus.CANCELLED);
      order.setRemainingQuantity(BigDecimal.ZERO);
      order.setHoldAmount(BigDecimal.ZERO);
      order.setCanceledAt(Instant.now());
      orderRepository.save(order);
      orderEventService.record(
          order.getId(),
          "ORDER_CANCELED",
          sourceStatus,
          OrderStatus.CANCELLED,
          null,
          "DEPTH IOC unmatched remainder canceled");
    }
    return orderResponseMapper.toResponse(order);
  }

  private void validateP0PerpetualContract(CreateOrderRequest request) {
    if (request.orderType() == null || request.orderType() == OrderType.STOP) {
      throw new BusinessException(
          "INVALID_PERPETUAL_ORDER_TYPE",
          "Unsupported P0 Linear Perpetual order contract");
    }
    if (request.stopLoss() != null
        || request.takeProfit() != null) {
      throw new BusinessException(
          ErrorCode.PRODUCT_NOT_ALLOWED,
          "Legacy scalar Perpetual protections are not accepted for new orders");
    }
    if (!request.attachedProtections().isEmpty() && protectionOrderService == null) {
      throw new BusinessException(
          "EXECUTION_UNAVAILABLE",
          "Perpetual protection service is unavailable");
    }
    if (Boolean.TRUE.equals(request.postOnly())
        && (request.orderType() != OrderType.LIMIT
        || request.timeInForce() != TimeInForce.GTC)) {
      throw new BusinessException(
          "INVALID_PERPETUAL_ORDER_FIELDS",
          "Post Only requires a GTC LIMIT order");
    }
    switch (request.orderType()) {
      case MARKET -> {
        if (request.price() != null
            || request.triggerPrice() != null
            || request.triggerPriceType() != null) {
          throw new BusinessException(
              "INVALID_PERPETUAL_ORDER_FIELDS",
              "MARKET does not accept price or trigger fields");
        }
      }
      case LIMIT -> {
        requirePositiveField(request.price(), ErrorCode.ORDER_PRICE_REQUIRED, "LIMIT price is required");
        if (request.triggerPrice() != null
            || request.triggerPriceType() != null
            || (Boolean.TRUE.equals(request.postOnly())
            && request.timeInForce() != TimeInForce.GTC)) {
          throw new BusinessException(
              "INVALID_PERPETUAL_ORDER_FIELDS",
              "LIMIT does not accept trigger fields and Post Only requires GTC");
        }
      }
      case STOP_MARKET -> {
        requirePositiveField(
            request.triggerPrice(),
            ErrorCode.ORDER_TRIGGER_PRICE_REQUIRED,
            "STOP_MARKET triggerPrice is required");
        if (request.timeInForce() != TimeInForce.GTC
            || Boolean.TRUE.equals(request.postOnly())
            || request.price() != null
            || (request.triggerPriceType() != null
            && request.triggerPriceType() != TriggerPriceType.MARK_PRICE)) {
          throw new BusinessException(
              "INVALID_PERPETUAL_ORDER_FIELDS",
              "STOP_MARKET requires GTC, disallows Post Only, uses MARK_PRICE, and does not accept limit price");
        }
      }
      case STOP_LIMIT -> {
        if (request.timeInForce() != TimeInForce.GTC
            || Boolean.TRUE.equals(request.postOnly())
            || request.price() == null
            || request.price().compareTo(BigDecimal.ZERO) <= 0
            || request.triggerPrice() == null
            || request.triggerPrice().compareTo(BigDecimal.ZERO) <= 0
            || (request.triggerPriceType() != null
            && request.triggerPriceType() != TriggerPriceType.MARK_PRICE)) {
          throw new BusinessException(
              "STOP_LIMIT_CONTRACT_INVALID",
              "Perpetual STOP_LIMIT requires positive trigger and limit prices, GTC, and MARK_PRICE");
        }
      }
      default -> throw new BusinessException(
          "INVALID_PERPETUAL_ORDER_TYPE",
          "Unsupported P0 Linear Perpetual order type");
    }
  }

  private OrderCommand canonicalPerpetualCommand(
      OrderCommand identity,
      CreateOrderRequest request,
      BigDecimal baseQuantity,
      PerpetualOrderRiskService.OrderRisk risk
  ) {
    return new OrderCommand(
        identity.userId(),
        identity.accountId(),
        identity.symbol(),
        identity.side(),
        identity.orderType(),
        baseQuantity,
        identity.price(),
        null,
        null,
        identity.clientOrderId(),
        identity.idempotencyKey(),
        risk.leverage(),
        request.quantity(),
        baseQuantity,
        request.quantityUnit(),
        risk.marginMode(),
        risk.positionSide(),
        request.reduceOnly(),
        request.triggerPrice(),
        request.orderType() == OrderType.STOP_MARKET
            ? TriggerPriceType.MARK_PRICE
            : request.orderType() == OrderType.STOP_LIMIT
                ? TriggerPriceType.MARK_PRICE
            : null,
        List.of(),
        request.timeInForce(),
        request.postOnly(),
        request.activationPrice(),
        request.trailingDelta(),
        request.trailingRate());
  }

  private CreateOrderRequest canonicalPerpetualExecutionIntent(
      CreateOrderRequest publicRequest,
      BigDecimal baseQuantity,
      PerpetualOrderRiskService.OrderRisk risk
  ) {
    boolean stopLimit = publicRequest.orderType() == OrderType.STOP_LIMIT;
    return new CreateOrderRequest(
        publicRequest.accountId(),
        publicRequest.symbol(),
        publicRequest.side(),
        stopLimit ? OrderType.LIMIT : publicRequest.orderType(),
        baseQuantity,
        publicRequest.price(),
        null,
        null,
        publicRequest.idempotencyKey(),
        publicRequest.clientOrderId(),
        baseQuantity,
        publicRequest.price(),
        risk.leverage(),
        risk.positionSide(),
        com.fxplatform.trading.enums.QuantityUnit.BASE,
        risk.marginMode(),
        stopLimit ? null : publicRequest.triggerPrice(),
        publicRequest.orderType() == OrderType.STOP_MARKET
            ? TriggerPriceType.MARK_PRICE
            : null,
        publicRequest.reduceOnly(),
        List.of(),
        publicRequest.timeInForce(),
        publicRequest.postOnly(),
        publicRequest.activationPrice(),
        publicRequest.trailingDelta(),
        publicRequest.trailingRate());
  }

  /** Public leverage is an audit input; exact symbol-setting authority is locked in the mutation. */
  private CreateOrderRequest perpetualRuleValidationRequest(CreateOrderRequest request) {
    return new CreateOrderRequest(
        request.accountId(),
        request.symbol(),
        request.side(),
        request.orderType(),
        request.lots(),
        request.requestedPrice(),
        request.stopLoss(),
        request.takeProfit(),
        request.idempotencyKey(),
        request.clientOrderId(),
        request.quantity(),
        request.price(),
        null,
        request.positionSide(),
        request.quantityUnit(),
        request.marginMode(),
        request.triggerPrice(),
        request.triggerPriceType(),
        request.reduceOnly(),
        request.attachedProtections(),
        request.timeInForce(),
        request.postOnly(),
        request.activationPrice(),
        request.trailingDelta(),
        request.trailingRate());
  }

  private OrderResponse persistP0Spot(
      OrderCommand command,
      CreateOrderRequest publicRequest,
      TradingAccountEntity accountSnapshot,
      FullFillExecutionPath path,
      OrderHoldCalculator.OrderHold hold,
      ExecutableMarketSnapshot snapshot
  ) {
    TradingAccountEntity account = accountRepository.findByIdAndUserIdForUpdate(
            command.accountId(), command.userId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    demoExecutionGuard.requireDemo(account, ProductType.CRYPTO_SPOT, command.symbol());
    // A lock wait can expire the provider snapshot; reject before any order or financial write.
    fullFillCoordinator.requireFresh(snapshot);
    OrderEntity order = preparedOrder(command, publicRequest, ProductType.CRYPTO_SPOT, 1);
    if (path == null && publicRequest.timeInForce() == TimeInForce.IOC) {
      order.setStatus(OrderStatus.CANCELLED);
      order.setFilledQuantity(BigDecimal.ZERO);
      order.setRemainingQuantity(BigDecimal.ZERO);
      order.setHoldAmount(BigDecimal.ZERO);
      order.setHoldCurrency(null);
      order.setCanceledAt(Instant.now());
      orderRepository.save(order);
      orderEventService.record(
          order.getId(),
          "ORDER_CANCELED",
          OrderStatus.ACCEPTED,
          OrderStatus.CANCELLED,
          null,
          "IOC order canceled because it was not immediately marketable");
      return orderResponseMapper.toResponse(order);
    }

    prepareP0SpotMutationState(account.getId(), command.symbol());
    FullFillResult fullFill = null;
    if (path != null) {
      CreateOrderRequest executionIntent = canonicalExecutionIntent(publicRequest, command.baseQuantity());
      fullFill = fullFillCoordinator.execute(
          new FullFillRequest(
              executionIntent,
              command.symbol(),
              ProductType.CRYPTO_SPOT,
              command.side(),
              path,
              command.baseQuantity(),
              path == FullFillExecutionPath.IMMEDIATE_LIMIT ? command.price() : null),
          snapshot);
    }

    if (fullFill != null) {
      order.setStatus(OrderStatus.ACCEPTED);
      fullFillCoordinator.requireFresh(fullFill);
      orderRepository.save(order);
      orderFillService.fill(order, account, fullFill, BigDecimal.ZERO, "Spot order fill");
      orderEventService.record(
          order.getId(),
          "ORDER_FILLED",
          OrderStatus.ACCEPTED,
          OrderStatus.FILLED,
          null,
          "Spot order filled");
      return orderResponseMapper.toResponse(order);
    }

    fullFillCoordinator.requireFresh(snapshot);
    OrderStatus pendingStatus = publicRequest.orderType() == OrderType.STOP_LIMIT
        ? OrderStatus.PENDING_ACTIVATION
        : OrderStatus.PENDING;
    order.setStatus(pendingStatus);
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(command.baseQuantity());
    order.setHoldAmount(hold.amount());
    order.setHoldCurrency(hold.currency());
    orderRepository.save(order);
    reserveOrderHold(
        account,
        hold.amount(),
        hold.currency(),
        true,
        order.getId(),
        "Pending order margin reserved",
        "Pending spot order wallet locked");
    orderEventService.record(
        order.getId(),
        pendingStatus == OrderStatus.PENDING_ACTIVATION
            ? "ORDER_PENDING_ACTIVATION"
            : "ORDER_PENDING",
        OrderStatus.ACCEPTED,
        pendingStatus,
        null,
        pendingStatus == OrderStatus.PENDING_ACTIVATION
            ? "Pending order accepted and waiting for activation"
            : "Pending order accepted and waiting");
    return orderResponseMapper.toResponse(order);
  }

  private FullFillExecutionPath executionPath(
      CreateOrderRequest request,
      ExecutableMarketSnapshot snapshot
  ) {
    return switch (request.orderType()) {
      case MARKET -> FullFillExecutionPath.MARKET;
      case LIMIT -> isLimitMarketable(request, snapshot)
          ? FullFillExecutionPath.IMMEDIATE_LIMIT
          : null;
      case STOP_MARKET -> isStopTriggered(request, snapshot)
          ? FullFillExecutionPath.TRIGGERED_STOP_MARKET
          : null;
      case STOP_LIMIT -> null;
      default -> throw new BusinessException(
          "INVALID_SPOT_ORDER_TYPE",
          "Unsupported P0 Spot order type");
    };
  }

  private boolean isLimitMarketable(CreateOrderRequest request, ExecutableMarketSnapshot snapshot) {
    return request.side() == com.fxplatform.trading.enums.OrderSide.BUY
        ? snapshot.ask().compareTo(request.price()) <= 0
        : snapshot.bid().compareTo(request.price()) >= 0;
  }

  private boolean isStopTriggered(CreateOrderRequest request, ExecutableMarketSnapshot snapshot) {
    return request.side() == com.fxplatform.trading.enums.OrderSide.BUY
        ? snapshot.last().compareTo(request.triggerPrice()) >= 0
        : snapshot.last().compareTo(request.triggerPrice()) <= 0;
  }

  private BigDecimal canonicalRulePrice(
      CreateOrderRequest request,
      FullFillExecutionPath path,
      ExecutableMarketSnapshot snapshot,
      FullFillPricingProjection marketPricing
  ) {
    if (request.orderType() == OrderType.LIMIT
        || request.orderType() == OrderType.STOP_LIMIT) {
      return request.price();
    }
    if (marketPricing != null) {
      return marketPricing.filledPrice();
    }
    FullFillExecutionPath projectionPath = request.orderType() == OrderType.STOP_MARKET
        ? FullFillExecutionPath.TRIGGERED_STOP_MARKET
        : FullFillExecutionPath.MARKET;
    return fullFillCoordinator.project(
        ProductType.CRYPTO_SPOT,
        request.side(),
        projectionPath,
        null,
        snapshot).filledPrice();
  }

  private OrderHoldCalculator.OrderHold pendingHold(
      CreateOrderRequest request,
      BigDecimal baseQuantity,
      FullFillExecutionPath path,
      ExecutableMarketSnapshot snapshot
  ) {
    if (path != null) {
      return null;
    }
    return request.orderType() == OrderType.LIMIT || request.orderType() == OrderType.STOP_LIMIT
        ? orderHoldCalculator.limit(request.side(), baseQuantity, request.price(), snapshot)
        : orderHoldCalculator.stopMarket(
            request.side(), baseQuantity, request.triggerPrice(), snapshot);
  }

  private void validateP0SpotContract(CreateOrderRequest request) {
    if (request.orderType() == OrderType.STOP || request.orderType() == null) {
      throw new BusinessException(
          ErrorCode.INVALID_SPOT_ORDER_TYPE,
          "Unsupported P0 Spot order contract");
    }
    com.fxplatform.trading.enums.QuantityUnit expectedUnit =
        request.orderType() == OrderType.MARKET
            && request.side() == com.fxplatform.trading.enums.OrderSide.BUY
        ? com.fxplatform.trading.enums.QuantityUnit.QUOTE
        : com.fxplatform.trading.enums.QuantityUnit.BASE;
    if (request.quantityUnit() != expectedUnit) {
      throw new BusinessException(
          ErrorCode.INVALID_QUANTITY_UNIT,
          "Spot order quantity unit does not match side and order type");
    }
    if (Boolean.TRUE.equals(request.reduceOnly())
        || !request.attachedProtections().isEmpty()
        || (request.leverage() != null && request.leverage() != 1)) {
      throw new BusinessException(
          ErrorCode.INVALID_SPOT_ORDER_FIELDS,
          "Spot does not accept reduce-only, attached protections, or leverage");
    }
    if (request.stopLoss() != null || request.takeProfit() != null) {
      throw new BusinessException(
          ErrorCode.PRODUCT_NOT_ALLOWED,
          "Legacy scalar Spot protections are not supported");
    }
    if (request.marginMode() != MarginMode.CASH) {
      throw new BusinessException("PRODUCT_NOT_ALLOWED", "Spot margin mode must be CASH");
    }
    if (request.positionSide() != PositionSide.BOTH) {
      throw new BusinessException("PRODUCT_NOT_ALLOWED", "Spot position side must be BOTH");
    }
    if (Boolean.TRUE.equals(request.postOnly())
        && (request.orderType() != OrderType.LIMIT
        || request.timeInForce() != TimeInForce.GTC)) {
      throw new BusinessException(
          ErrorCode.INVALID_SPOT_ORDER_FIELDS,
          "Post Only requires a GTC LIMIT order");
    }
    switch (request.orderType()) {
      case MARKET -> {
        if (request.price() != null
            || request.triggerPrice() != null
            || request.triggerPriceType() != null) {
          throw new BusinessException(
              ErrorCode.INVALID_SPOT_ORDER_FIELDS,
              "MARKET does not accept price or trigger fields");
        }
      }
      case LIMIT -> {
        requirePositiveField(request.price(), "ORDER_PRICE_REQUIRED", "LIMIT price is required");
        if (request.triggerPrice() != null
            || request.triggerPriceType() != null
            || (Boolean.TRUE.equals(request.postOnly())
            && request.timeInForce() != TimeInForce.GTC)) {
          throw new BusinessException(
              ErrorCode.INVALID_SPOT_ORDER_FIELDS,
              "LIMIT does not accept trigger fields and Post Only requires GTC");
        }
      }
      case STOP_MARKET -> {
        requirePositiveField(
            request.triggerPrice(),
            "ORDER_TRIGGER_PRICE_REQUIRED",
            "STOP_MARKET triggerPrice is required");
        if (request.timeInForce() != TimeInForce.GTC
            || Boolean.TRUE.equals(request.postOnly())
            || request.price() != null
            || (request.triggerPriceType() != null
            && request.triggerPriceType() != TriggerPriceType.LAST_PRICE)) {
          throw new BusinessException(
              ErrorCode.INVALID_SPOT_ORDER_FIELDS,
              "STOP_MARKET requires GTC, disallows Post Only, uses LAST_PRICE, and does not accept limit price");
        }
      }
      case STOP_LIMIT -> {
        if (request.timeInForce() != TimeInForce.GTC
            || Boolean.TRUE.equals(request.postOnly())
            || request.price() == null
            || request.price().compareTo(BigDecimal.ZERO) <= 0
            || request.triggerPrice() == null
            || request.triggerPrice().compareTo(BigDecimal.ZERO) <= 0
            || (request.triggerPriceType() != null
            && request.triggerPriceType() != TriggerPriceType.LAST_PRICE)) {
          throw new BusinessException(
              "STOP_LIMIT_CONTRACT_INVALID",
              "Spot STOP_LIMIT requires positive trigger and limit prices, GTC, and LAST_PRICE");
        }
      }
      default -> throw new BusinessException(
          ErrorCode.INVALID_SPOT_ORDER_TYPE,
          "Unsupported P0 Spot order type");
    }
  }

  private static void requirePositiveField(BigDecimal value, String code, String message) {
    if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException(code, message);
    }
  }

  private OrderCommand canonicalCommand(
      OrderCommand identity,
      CreateOrderRequest request,
      BigDecimal baseQuantity
  ) {
    return new OrderCommand(
        identity.userId(),
        identity.accountId(),
        identity.symbol(),
        identity.side(),
        identity.orderType(),
        baseQuantity,
        identity.price(),
        identity.stopLoss(),
        identity.takeProfit(),
        identity.clientOrderId(),
        identity.idempotencyKey(),
        1,
        request.quantity(),
        baseQuantity,
        request.quantityUnit(),
        MarginMode.CASH,
        PositionSide.BOTH,
        false,
        request.triggerPrice(),
        request.orderType() == OrderType.STOP_MARKET
            ? TriggerPriceType.LAST_PRICE
            : request.orderType() == OrderType.STOP_LIMIT
                ? TriggerPriceType.LAST_PRICE
            : request.triggerPriceType(),
        List.of(),
        request.timeInForce(),
        request.postOnly(),
        request.activationPrice(),
        request.trailingDelta(),
        request.trailingRate());
  }

  private CreateOrderRequest canonicalExecutionIntent(
      CreateOrderRequest publicRequest,
      BigDecimal baseQuantity
  ) {
    boolean stopLimit = publicRequest.orderType() == OrderType.STOP_LIMIT;
    return new CreateOrderRequest(
        publicRequest.accountId(),
        publicRequest.symbol(),
        publicRequest.side(),
        stopLimit ? OrderType.LIMIT : publicRequest.orderType(),
        baseQuantity,
        publicRequest.price(),
        publicRequest.stopLoss(),
        publicRequest.takeProfit(),
        publicRequest.idempotencyKey(),
        publicRequest.clientOrderId(),
        baseQuantity,
        publicRequest.price(),
        1,
        PositionSide.BOTH,
        com.fxplatform.trading.enums.QuantityUnit.BASE,
        MarginMode.CASH,
        stopLimit ? null : publicRequest.triggerPrice(),
        stopLimit
            ? null
            : publicRequest.orderType() == OrderType.STOP_MARKET
            ? TriggerPriceType.LAST_PRICE
            : publicRequest.triggerPriceType(),
        false,
        List.of(),
        publicRequest.timeInForce(),
        publicRequest.postOnly(),
        publicRequest.activationPrice(),
        publicRequest.trailingDelta(),
        publicRequest.trailingRate());
  }

  private void requireSimpleCreationMarketability(
      CreateOrderRequest request,
      FullFillExecutionPath path
  ) {
    if (Boolean.TRUE.equals(request.postOnly()) && path != null) {
      throw new BusinessException(
          "POST_ONLY_WOULD_TAKE",
          "Post Only order would execute immediately");
    }
    if (request.timeInForce() == TimeInForce.FOK && path == null) {
      throw new BusinessException(
          "FOK_NOT_FILLABLE",
          "FOK order is not fully executable in simple matching");
    }
  }

  private boolean task6SpotReady() {
    return symbolRepository != null
        && instrumentRulesEngine != null
        && quantityConversionService != null
        && orderHoldCalculator != null
        && marketBundleResolver != null
        && fullFillCoordinator != null
        && transactionExecutor != null;
  }

  private boolean task9PerpetualReady() {
    return symbolRepository != null
        && instrumentRulesEngine != null
        && quantityConversionService != null
        && accountSymbolSettingRepository != null
        && perpetualOrderRiskService != null
        && perpetualAccountRiskSnapshotService != null
        && fullFillCoordinator != null;
  }

  private boolean trailingStopReady() {
    return symbolRepository != null
        && instrumentRulesEngine != null
        && quantityConversionService != null
        && accountSymbolSettingRepository != null
        && perpetualAccountRiskSnapshotService != null
        && fullFillCoordinator != null
        && transactionExecutor != null;
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

  @Autowired
  void setOcoOrderService(OcoOrderService ocoOrderService) {
    this.ocoOrderService = ocoOrderService;
  }

  @Autowired
  void setProtectionOrderService(ProtectionOrderService protectionOrderService) {
    this.protectionOrderService = protectionOrderService;
  }

  @Autowired(required = false)
  void setDepthOrderExecutionService(DepthOrderExecutionService depthOrderExecutionService) {
    this.depthOrderExecutionService = depthOrderExecutionService;
  }

  private OrderResponse createP0MarketWithRetry(
      OrderCommand command,
      CreateOrderRequest request,
      ProductType productType,
      BigDecimal requiredMargin,
      int effectiveLeverage
  ) {
    BusinessException lastStale = null;
    for (int attempt = 0; attempt < MAX_MARKET_ATTEMPTS; attempt++) {
      ExecutableMarketSnapshot snapshot = resolveExecutableSnapshot(command.symbol(), productType);
      try {
        return transactionExecutor.execute(() -> persistP0Market(
            command,
            request,
            productType,
            requiredMargin,
            effectiveLeverage,
            snapshot));
      } catch (BusinessException exception) {
        if (!ErrorCode.MARKET_DATA_STALE.equals(exception.getCode())) {
          throw exception;
        }
        lastStale = exception;
      }
    }
    throw new BusinessException(
        ErrorCode.MARKET_DATA_STALE,
        lastStale == null ? "No fresh executable market snapshot" : lastStale.getMessage());
  }

  private OrderResponse persistP0Market(
      OrderCommand command,
      CreateOrderRequest request,
      ProductType productType,
      BigDecimal requiredMargin,
      int effectiveLeverage,
      ExecutableMarketSnapshot snapshot
  ) {
    TradingAccountEntity account = accountRepository.findByIdAndUserIdForUpdate(command.accountId(), command.userId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    demoExecutionGuard.requireDemo(account, productType, command.symbol());
    lockExistingP0State(account.getId(), productType);

    OrderEntity order = preparedOrder(command, request, productType, effectiveLeverage);
    FullFillResult fullFill = fullFillCoordinator.execute(
        new FullFillRequest(
            request,
            command.symbol(),
            productType,
            command.side(),
            FullFillExecutionPath.MARKET,
            command.quantity(),
            null),
        snapshot);

    ensureP0MutationState(account.getId(), productType, command.symbol());
    order.setStatus(orderStatusPolicy.acceptedStatus(command.orderType()));
    fullFillCoordinator.requireFresh(fullFill);
    orderRepository.save(order);
    orderFillService.fill(order, account, fullFill, requiredMargin, "Market order margin hold");
    orderEventService.record(
        order.getId(),
        "ORDER_FILLED",
        OrderStatus.ACCEPTED,
        OrderStatus.FILLED,
        null,
        "Market order filled");
    return orderResponseMapper.toResponse(order);
  }

  private OrderResponse persistPreparedOrder(
      OrderCommand command,
      CreateOrderRequest request,
      ProductType productType,
      BigDecimal requiredMargin,
      int effectiveLeverage,
      boolean spotWalletHold,
      String holdCurrency,
      ExecutionResult execution
  ) {

    TradingAccountEntity account = accountRepository.findByIdAndUserIdForUpdate(command.accountId(), command.userId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    demoExecutionGuard.requireDemo(account, productType, command.symbol());
    lockMutationState(account.getId(), productType, command.symbol());

    OrderEntity order = preparedOrder(command, request, productType, effectiveLeverage);

    if (command.orderType() != OrderType.MARKET) {
      order.setStatus(orderStatusPolicy.acceptedStatus(command.orderType()));
      order.setFilledQuantity(BigDecimal.ZERO);
      order.setRemainingQuantity(command.quantity());
      order.setHoldAmount(requiredMargin);
      order.setHoldCurrency(holdCurrency);
      orderRepository.save(order);
      reserveOrderHold(account, requiredMargin, holdCurrency, spotWalletHold, order.getId(),
          "Pending order margin reserved", "Pending spot order wallet locked");
      orderEventService.record(
          order.getId(),
          "ORDER_PENDING",
          OrderStatus.ACCEPTED,
          OrderStatus.PENDING,
          null,
          "Pending order accepted and waiting");
      return orderResponseMapper.toResponse(order);
    }

    order.setStatus(orderStatusPolicy.acceptedStatus(command.orderType()));
    orderRepository.save(order);
    if (execution.rejected()) {
      return rejectOrder(order, execution);
    }

    orderFillService.fill(order, account, execution, requiredMargin, "Market order margin hold");
    orderEventService.record(
        order.getId(),
        "ORDER_FILLED",
        OrderStatus.ACCEPTED,
        OrderStatus.FILLED,
        null,
        "Market order filled");
    return orderResponseMapper.toResponse(order);
  }

  private OrderEntity preparedOrder(
      OrderCommand command,
      CreateOrderRequest request,
      ProductType productType,
      int effectiveLeverage
  ) {
    OrderEntity order = orderEntityFactory.createReceived(command);
    order.setRequestFingerprint(OrderRequestFingerprint.calculate(request));
    order.setProductType(productType);
    order.setLeverage(effectiveLeverage);
    order.setPositionSide(command.positionSide());
    order.setMarginMode(command.marginMode());
    order.setQuantityUnit(command.quantityUnit());
    order.setOriginalQuantity(command.originalQuantity());
    order.setBaseQuantity(command.baseQuantity());
    order.setReduceOnly(command.reduceOnly());
    order.setTriggerPrice(command.triggerPrice());
    order.setTriggerPriceType(command.triggerPriceType());
    order.setTriggerExecutionType(switch (command.orderType()) {
      case STOP_MARKET -> TriggerExecutionType.MARKET;
      case STOP_LIMIT -> TriggerExecutionType.LIMIT;
      default -> null;
    });
    return order;
  }

  private ExecutableMarketSnapshot resolveExecutableSnapshot(String symbol, ProductType productType) {
    if (marketBundleResolver == null) {
      throw new BusinessException("MARKET_DATA_UNAVAILABLE", "Market bundle resolver is unavailable");
    }
    Instant to = Instant.now();
    CandleRequest candles = new CandleRequest("1m", to.minus(Duration.ofMinutes(30)), to);
    return productType == ProductType.CRYPTO_SPOT
        ? ExecutableMarketSnapshot.from(marketBundleResolver.resolveSpot(symbol, candles))
        : ExecutableMarketSnapshot.from(marketBundleResolver.resolvePerp(symbol, candles));
  }

  private void requireFullLegacyExecution(BigDecimal requestedQuantity, ExecutionResult execution) {
    if (execution.filledQuantity() == null
        || execution.remainingQuantity() == null
        || execution.filledQuantity().compareTo(requestedQuantity) != 0
        || execution.remainingQuantity().compareTo(BigDecimal.ZERO) != 0) {
      throw new BusinessException(
          ErrorCode.PARTIAL_FILL_NOT_SUPPORTED,
          "Demo execution supports one full fill only");
    }
  }

  private boolean isP0Symbol(String symbol) {
    return symbol != null && P0_SYMBOLS.contains(SymbolNormalizer.normalize(symbol));
  }

  private OrderResponse createNewOrderOrReturnExisting(
      OrderCommand command,
      CreateOrderRequest request,
      String requestFingerprint
  ) {
    try {
      return createNewOrder(command, request);
    } catch (DataIntegrityViolationException ex) {
      return findExistingOrder(command)
          .map(order -> requireMatchingReplay(
              order,
              command,
              request,
              requestFingerprint))
          .map(orderResponseMapper::toResponse)
          .orElseThrow(() -> ex);
    }
  }

  private java.util.Optional<OrderEntity> findExistingOrder(OrderCommand command) {
    return orderRepository.findByUserIdAndIdempotencyKey(
            command.userId(), command.idempotencyKey())
        .or(() -> orderRepository.findByUserIdAndAccountIdAndClientOrderId(
                command.userId(), command.accountId(), command.clientOrderId())
            .filter(order -> ACTIVE_CLIENT_ORDER_STATUSES.contains(order.getStatus())));
  }

  private OrderEntity requireMatchingReplay(
      OrderEntity existing,
      OrderCommand command,
      CreateOrderRequest request,
      String requestFingerprint
  ) {
    String storedFingerprint = existing.getRequestFingerprint();
    boolean matches = storedFingerprint == null || storedFingerprint.isBlank()
        ? OrderRequestFingerprint.matchesLegacy(existing, command, request)
        : storedFingerprint.trim().equals(requestFingerprint);
    if (!matches) {
      throw new BusinessException(
          ErrorCode.DUPLICATE_CLIENT_ORDER_ID,
          "clientOrderId is already used by a different order request");
    }
    return existing;
  }

  private OrderResponse rejectOrder(OrderEntity order, ExecutionResult execution) {
    order.setStatus(OrderStatus.REJECTED);
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(currentQuantity(order));
    order.setRejectCode(execution.rejectCode());
    order.setRejectMessage(execution.rejectMessage());
    order.setFee(orZero(execution.fee()));
    order.setSlippage(orZero(execution.slippage()));
    orderRepository.save(order);
    orderEventService.record(
        order.getId(),
        "ORDER_REJECTED",
        OrderStatus.ACCEPTED,
        OrderStatus.REJECTED,
        execution.rejectCode(),
        execution.rejectMessage());
    return orderResponseMapper.toResponse(order);
  }

  private OrderEntity requireOwnedOrder(UUID userId, UUID orderId) {
    return orderRepository.findByUserIdAndId(userId, orderId)
        .orElseThrow(() -> new AuthorizationException("ORDER_NOT_FOUND", "Order not found"));
  }

  private OrderEntity requireOwnedOrderForUpdate(UUID userId, UUID orderId) {
    OrderEntity order = orderRepository.findByIdForUpdate(orderId)
        .orElseThrow(() -> new AuthorizationException("ORDER_NOT_FOUND", "Order not found"));
    if (!userId.equals(order.getUserId())) {
      throw new AuthorizationException("ORDER_NOT_FOUND", "Order not found");
    }
    return order;
  }

  private TradingAccountEntity requireOwnedAccount(UUID userId, UUID accountId) {
    return accountRepository.findByIdAndUserId(accountId, userId)
        .orElseThrow(() -> new AuthorizationException("ACCOUNT_NOT_FOUND", "Account not found"));
  }

  private void reserveOrderHold(
      TradingAccountEntity account,
      BigDecimal amount,
      String holdCurrency,
      boolean spotWalletHold,
      UUID orderId,
      String marginDescription,
      String spotDescription
  ) {
    if (spotWalletHold) {
      walletService.lockAvailableWithEntryType(
          account.getId(),
          holdCurrency,
          amount,
          "ORDER",
          orderId,
          spotDescription,
          "SPOT_ORDER_LOCK");
      return;
    }
    if (accountRepository.reserveMarginIfAvailable(account.getId(), amount) != 1) {
      throw new BusinessException("INSUFFICIENT_MARGIN", "Free margin is not enough");
    }
    account.setUsedMargin(orZero(account.getUsedMargin()).add(amount));
    account.setFreeMargin(accountEquity(account).subtract(account.getUsedMargin()));
    ledgerService.recordOrderHold(account, amount, orderId, marginDescription);
  }

  private void reservePerpetualOrderHold(
      TradingAccountEntity account,
      BigDecimal amount
  ) {
    BigDecimal hold = orZero(amount);
    if (hold.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException(ErrorCode.ORDER_HOLD_INVALID, "Perpetual order hold must be positive");
    }
    if (orZero(account.getFreeMargin()).compareTo(hold) < 0) {
      throw new BusinessException(ErrorCode.INSUFFICIENT_MARGIN, "Free margin is not enough");
    }
    account.setUsedMargin(orZero(account.getUsedMargin()).add(hold));
    account.setFreeMargin(orZero(account.getFreeMargin()).subtract(hold));
    accountRepository.save(account);
  }

  private void reserveIsolatedPerpetualOrderHold(
      TradingAccountEntity account,
      BigDecimal amount
  ) {
    BigDecimal hold = orZero(amount);
    if (hold.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "Isolated Perpetual order hold must be positive");
    }
    account.setUsedMargin(orZero(account.getUsedMargin()).add(hold));
    accountRepository.save(account);
  }

  private PositionEntity isolatedCloseHoldPosition(
      PerpetualOrderRiskService.OrderRisk risk,
      List<PositionEntity> positions
  ) {
    if (risk.marginMode() != MarginMode.ISOLATED
        || risk.openingBase().compareTo(BigDecimal.ZERO) != 0
        || risk.closingBase().compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    return positions.stream()
        .filter(position -> position.getId() != null)
        .filter(position -> position.getPositionMode() == risk.positionMode())
        .filter(position -> position.getPositionSide() == risk.positionSide())
        .findFirst()
        .orElseThrow(() -> new BusinessException(
            ErrorCode.REDUCE_ONLY_EXCEEDS_POSITION,
            "Isolated close requires one locked position slot"));
  }

  private void releasePerpetualOrderHold(
      TradingAccountEntity account,
      BigDecimal amount
  ) {
    BigDecimal hold = orZero(amount);
    if (hold.compareTo(BigDecimal.ZERO) <= 0
        || orZero(account.getUsedMargin()).compareTo(hold) < 0) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "Perpetual order hold cannot be released from the locked account state");
    }
    account.setUsedMargin(account.getUsedMargin().subtract(hold));
    account.setFreeMargin(orZero(account.getFreeMargin()).add(hold));
    accountRepository.save(account);
  }

  private void releaseIsolatedPerpetualOrderHold(
      TradingAccountEntity account,
      BigDecimal amount
  ) {
    BigDecimal hold = orZero(amount);
    if (hold.compareTo(BigDecimal.ZERO) <= 0
        || orZero(account.getUsedMargin()).compareTo(hold) < 0) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "Isolated Perpetual order hold cannot be released from the locked account state");
    }
    account.setUsedMargin(account.getUsedMargin().subtract(hold));
    accountRepository.save(account);
  }

  private void releaseOrderHold(
      TradingAccountEntity account,
      OrderEntity order,
      BigDecimal amount,
      boolean spotWalletHold,
      String marginDescription,
      String spotDescription
  ) {
    if (spotWalletHold) {
      walletService.releaseLockedWithEntryType(
          account.getId(),
          order.getHoldCurrency(),
          amount,
          "ORDER",
          order.getId(),
          spotDescription,
          "SPOT_ORDER_RELEASE");
      return;
    }
    account.setUsedMargin(orZero(account.getUsedMargin()).subtract(amount).max(BigDecimal.ZERO));
    account.setFreeMargin(accountEquity(account).subtract(account.getUsedMargin()));
    accountRepository.save(account);
    ledgerService.recordOrderRelease(account, amount, order.getId(), marginDescription);
  }

  private TradingAccountEntity accountForMarginCheck(TradingAccountEntity account, BigDecimal existingHold) {
    TradingAccountEntity copy = new TradingAccountEntity();
    copy.setId(account.getId());
    copy.setUserId(account.getUserId());
    copy.setBaseCurrency(account.getBaseCurrency());
    copy.setBalance(account.getBalance());
    copy.setEquity(accountEquity(account));
    copy.setUsedMargin(orZero(account.getUsedMargin()).subtract(existingHold).max(BigDecimal.ZERO));
    copy.setFreeMargin(orZero(account.getFreeMargin()).add(existingHold));
    copy.setLeverage(account.getLeverage());
    copy.setAccountType(account.getAccountType());
    copy.setStatus(account.getStatus());
    return copy;
  }

  private BigDecimal currentQuantity(OrderEntity order) {
    return order.getQuantity() != null ? order.getQuantity() : order.getLots();
  }

  private BigDecimal originalQuantity(OrderEntity order) {
    if (order.getOriginalQuantity() != null) {
      return order.getOriginalQuantity();
    }
    if (order.getQuantity() != null) {
      return order.getQuantity();
    }
    return order.getBaseQuantity() != null ? order.getBaseQuantity() : order.getLots();
  }

  private BigDecimal currentPrice(OrderEntity order) {
    return order.getPrice() != null ? order.getPrice() : order.getRequestedPrice();
  }

  private ProductType requestedProduct(String canonicalSymbol) {
    return canonicalSymbol.endsWith("-PERP") ? ProductType.LINEAR_PERP : ProductType.CRYPTO_SPOT;
  }

  private void lockMutationState(UUID accountId, ProductType productType, String canonicalSymbol) {
    if (productType == ProductType.CRYPTO_SPOT) {
      if (canonicalSymbol != null && canonicalSymbol.endsWith("USDT") && canonicalSymbol.length() > 4) {
        String baseAsset = canonicalSymbol.substring(0, canonicalSymbol.length() - 4);
        walletService.lockBalancesInOrder(
            accountId,
            List.of(baseAsset, "USDT"));
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

  /** Completes the wallet phase before taking any Spot-position lock. */
  private void prepareP0SpotMutationState(UUID accountId, String canonicalSymbol) {
    if (canonicalSymbol == null
        || !canonicalSymbol.endsWith("USDT")
        || canonicalSymbol.length() <= 4) {
      throw new BusinessException(
          ErrorCode.MARKET_BUNDLE_INCOMPLETE,
          "P0 Spot symbol does not expose a USDT base asset");
    }
    String baseAsset = canonicalSymbol.substring(0, canonicalSymbol.length() - 4);
    walletBalanceRepository.findByAccountIdForUpdate(accountId);
    walletService.lockBalancesInOrder(accountId, List.of(baseAsset, "USDT"));
    spotPositionService.lockExisting(accountId);
    spotPositionService.lockOrCreate(accountId, baseAsset, "USDT");
  }

  /** Locks every existing Spot child row without materializing a missing wallet or position slot. */
  private void lockExistingP0SpotMutationState(UUID accountId, String canonicalSymbol) {
    requireP0SpotBaseAsset(canonicalSymbol);
    walletBalanceRepository.findByAccountIdForUpdate(accountId);
    spotPositionService.lockExisting(accountId);
  }

  /** Materializes missing Spot child rows only after the final volatile-authority gate. */
  private void materializeP0SpotMutationState(UUID accountId, String canonicalSymbol) {
    String baseAsset = requireP0SpotBaseAsset(canonicalSymbol);
    walletService.lockBalancesInOrder(accountId, List.of(baseAsset, "USDT"));
    spotPositionService.lockOrCreate(accountId, baseAsset, "USDT");
  }

  private String requireP0SpotBaseAsset(String canonicalSymbol) {
    if (canonicalSymbol == null
        || !canonicalSymbol.endsWith("USDT")
        || canonicalSymbol.length() <= 4) {
      throw new BusinessException(
          ErrorCode.MARKET_BUNDLE_INCOMPLETE,
          "P0 Spot symbol does not expose a USDT base asset");
    }
    return canonicalSymbol.substring(0, canonicalSymbol.length() - 4);
  }

  private record PerpetualHoldAdjustment(
      BigDecimal usedMargin,
      BigDecimal freeMargin
  ) {
  }

  private record PendingSpotVersion(
      UUID id,
      UUID userId,
      UUID accountId,
      String symbol,
      OrderType orderType,
      com.fxplatform.trading.enums.OrderSide side,
      OrderStatus status,
      BigDecimal baseQuantity,
      BigDecimal holdAmount,
      String holdCurrency,
      BigDecimal price,
      BigDecimal triggerPrice,
      TriggerPriceType triggerPriceType,
      TriggerExecutionType triggerExecutionType,
      TimeInForce timeInForce,
      Boolean postOnly,
      OrderOrigin orderOrigin,
      String clientOrderId,
      String idempotencyKey,
      Long version
  ) {

    private static PendingSpotVersion from(OrderEntity order) {
      return new PendingSpotVersion(
          order.getId(),
          order.getUserId(),
          order.getAccountId(),
          order.getSymbol(),
          order.getOrderType(),
          order.getSide(),
          order.getStatus(),
          order.getBaseQuantity(),
          orZero(order.getHoldAmount()),
          order.getHoldCurrency(),
          currentVersionPrice(order),
          order.getTriggerPrice(),
          order.getTriggerPriceType(),
          order.getTriggerExecutionType(),
          order.getTimeInForce(),
          order.getPostOnly(),
          order.getOrderOrigin(),
          order.getClientOrderId(),
          order.getIdempotencyKey(),
          order.getVersion());
    }

    private boolean matches(OrderEntity order) {
      return order != null
          && java.util.Objects.equals(id, order.getId())
          && java.util.Objects.equals(userId, order.getUserId())
          && java.util.Objects.equals(accountId, order.getAccountId())
          && java.util.Objects.equals(symbol, order.getSymbol())
          && orderType == order.getOrderType()
          && side == order.getSide()
          && status == order.getStatus()
          && sameDecimal(baseQuantity, order.getBaseQuantity())
          && sameDecimal(holdAmount, orZero(order.getHoldAmount()))
          && java.util.Objects.equals(holdCurrency, order.getHoldCurrency())
          && sameDecimal(price, currentVersionPrice(order))
          && sameDecimal(triggerPrice, order.getTriggerPrice())
          && triggerPriceType == order.getTriggerPriceType()
          && triggerExecutionType == order.getTriggerExecutionType()
          && timeInForce == order.getTimeInForce()
          && java.util.Objects.equals(postOnly, order.getPostOnly())
          && orderOrigin == order.getOrderOrigin()
          && java.util.Objects.equals(clientOrderId, order.getClientOrderId())
          && java.util.Objects.equals(idempotencyKey, order.getIdempotencyKey())
          && java.util.Objects.equals(version, order.getVersion())
          && order.getProductType() == ProductType.CRYPTO_SPOT
          && order.getMarginMode() == MarginMode.CASH
          && order.getPositionSide() == PositionSide.BOTH
          && order.getQuantityUnit() == com.fxplatform.trading.enums.QuantityUnit.BASE
          && Boolean.FALSE.equals(order.getReduceOnly());
    }

    private static BigDecimal currentVersionPrice(OrderEntity order) {
      return order.getPrice() != null ? order.getPrice() : order.getRequestedPrice();
    }

    private static boolean sameDecimal(BigDecimal first, BigDecimal second) {
      return first == null ? second == null : second != null && first.compareTo(second) == 0;
    }
  }

  private record PendingPerpetualVersion(
      UUID id,
      UUID userId,
      UUID accountId,
      String symbol,
      OrderType orderType,
      com.fxplatform.trading.enums.OrderSide side,
      OrderStatus status,
      com.fxplatform.trading.enums.PositionMode positionMode,
      PositionSide positionSide,
      MarginMode marginMode,
      com.fxplatform.trading.enums.QuantityUnit quantityUnit,
      Boolean reduceOnly,
      BigDecimal originalQuantity,
      BigDecimal baseQuantity,
      BigDecimal holdAmount,
      String holdCurrency,
      BigDecimal price,
      BigDecimal triggerPrice,
      TriggerPriceType triggerPriceType,
      TriggerExecutionType triggerExecutionType,
      TimeInForce timeInForce,
      Boolean postOnly,
      OrderOrigin orderOrigin,
      UUID parentPositionId,
      String clientOrderId,
      String idempotencyKey,
      Long version
  ) {

    private static PendingPerpetualVersion from(OrderEntity order) {
      return new PendingPerpetualVersion(
          order.getId(),
          order.getUserId(),
          order.getAccountId(),
          order.getSymbol(),
          order.getOrderType(),
          order.getSide(),
          order.getStatus(),
          order.getPositionMode(),
          order.getPositionSide(),
          order.getMarginMode(),
          order.getQuantityUnit(),
          order.getReduceOnly(),
          originalVersionQuantity(order),
          order.getBaseQuantity(),
          orZero(order.getHoldAmount()),
          order.getHoldCurrency(),
          currentVersionPrice(order),
          order.getTriggerPrice(),
          order.getTriggerPriceType(),
          order.getTriggerExecutionType(),
          order.getTimeInForce(),
          order.getPostOnly(),
          order.getOrderOrigin(),
          order.getParentPositionId(),
          order.getClientOrderId(),
          order.getIdempotencyKey(),
          order.getVersion());
    }

    private boolean matches(OrderEntity order) {
      return order != null
          && java.util.Objects.equals(id, order.getId())
          && java.util.Objects.equals(userId, order.getUserId())
          && java.util.Objects.equals(accountId, order.getAccountId())
          && java.util.Objects.equals(symbol, order.getSymbol())
          && orderType == order.getOrderType()
          && side == order.getSide()
          && status == order.getStatus()
          && positionMode == order.getPositionMode()
          && positionSide == order.getPositionSide()
          && marginMode == order.getMarginMode()
          && quantityUnit == order.getQuantityUnit()
          && java.util.Objects.equals(reduceOnly, order.getReduceOnly())
          && sameDecimal(originalQuantity, originalVersionQuantity(order))
          && sameDecimal(baseQuantity, order.getBaseQuantity())
          && sameDecimal(holdAmount, orZero(order.getHoldAmount()))
          && java.util.Objects.equals(holdCurrency, order.getHoldCurrency())
          && sameDecimal(price, currentVersionPrice(order))
          && sameDecimal(triggerPrice, order.getTriggerPrice())
          && triggerPriceType == order.getTriggerPriceType()
          && triggerExecutionType == order.getTriggerExecutionType()
          && timeInForce == order.getTimeInForce()
          && java.util.Objects.equals(postOnly, order.getPostOnly())
          && orderOrigin == order.getOrderOrigin()
          && java.util.Objects.equals(parentPositionId, order.getParentPositionId())
          && java.util.Objects.equals(clientOrderId, order.getClientOrderId())
          && java.util.Objects.equals(idempotencyKey, order.getIdempotencyKey())
          && java.util.Objects.equals(version, order.getVersion())
          && order.getProductType() == ProductType.LINEAR_PERP
          && order.getProtectionType() == null
          && orZero(order.getFilledQuantity()).compareTo(BigDecimal.ZERO) == 0;
    }

    private static BigDecimal originalVersionQuantity(OrderEntity order) {
      if (order.getOriginalQuantity() != null) {
        return order.getOriginalQuantity();
      }
      if (order.getQuantity() != null) {
        return order.getQuantity();
      }
      return order.getBaseQuantity() != null ? order.getBaseQuantity() : order.getLots();
    }

    private static BigDecimal currentVersionPrice(OrderEntity order) {
      return order.getPrice() != null ? order.getPrice() : order.getRequestedPrice();
    }

    private static boolean sameDecimal(BigDecimal first, BigDecimal second) {
      return first == null ? second == null : second != null && first.compareTo(second) == 0;
    }
  }

}
