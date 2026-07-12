package com.fxplatform.trading.service;

import static com.fxplatform.common.money.MoneyAmount.accountEquity;
import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.execution.ExecutionAdapter;
import com.fxplatform.execution.DemoExecutionGuard;
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
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

@Service
public class OrderService {

  private static final int MAX_MARKET_ATTEMPTS = 2;
  private static final int SPOT_WALLET_SCALE = 8;
  private static final Set<String> P0_SYMBOLS = Set.of(
      "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
      "BTCUSDT-PERP", "ETHUSDT-PERP", "BNBUSDT-PERP", "SOLUSDT-PERP", "XRPUSDT-PERP");

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
  private OcoOrderService ocoOrderService;

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
      OrderHoldCalculator orderHoldCalculator
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
    OrderCommand command = orderCommandFactory.from(principal, request);
    return findExistingOrder(command)
        .map(orderResponseMapper::toResponse)
        .orElseGet(() -> createNewOrderOrReturnExisting(command, request));
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
    if (orderSnapshot.getStatus() != OrderStatus.PENDING) {
      throw new BusinessException("ORDER_NOT_CANCELABLE", "Only pending orders can be canceled");
    }

    TradingAccountEntity accountSnapshot = requireOwnedAccount(principal.id(), orderSnapshot.getAccountId());
    ProductType productType = requestedProduct(orderSnapshot.getSymbol());
    boolean spotWalletHold = riskCheckService.isSpotSymbol(orderSnapshot.getSymbol());
    demoExecutionGuard.requireDemo(accountSnapshot, productType, orderSnapshot.getSymbol());

    TradingAccountEntity account = accountRepository
        .findByIdAndUserIdForUpdate(orderSnapshot.getAccountId(), principal.id())
        .orElseThrow(() -> new AuthorizationException("ACCOUNT_NOT_FOUND", "Account not found"));
    demoExecutionGuard.requireDemo(account, productType, orderSnapshot.getSymbol());
    lockMutationState(account.getId(), productType, orderSnapshot.getSymbol());
    OrderEntity order = requireOwnedOrderForUpdate(principal.id(), orderId);
    if (order.getStatus() != OrderStatus.PENDING) {
      throw new BusinessException("ORDER_NOT_CANCELABLE", "Only pending orders can be canceled");
    }
    BigDecimal holdAmount = orZero(order.getHoldAmount());

    order.setStatus(OrderStatus.CANCELED);
    order.setCanceledAt(Instant.now());
    order.setRemainingQuantity(BigDecimal.ZERO);
    if (orderRepository.cancelPending(order) != 1) {
      throw new BusinessException("ORDER_NOT_CANCELABLE", "Only pending orders can be canceled");
    }
    if (holdAmount.compareTo(BigDecimal.ZERO) > 0) {
      releaseOrderHold(
          account,
          order,
          holdAmount,
          spotWalletHold,
          "Pending order canceled",
          "Pending spot order canceled");
      order.setHoldAmount(BigDecimal.ZERO);
    }
    orderEventService.record(
        order.getId(),
        "ORDER_CANCELED",
        OrderStatus.PENDING,
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
    if (requestedProduct(orderSnapshot.getSymbol()) == ProductType.CRYPTO_SPOT
        && isP0Symbol(orderSnapshot.getSymbol())
        && task6SpotReady()) {
      return modifyP0SpotWithRetry(principal, orderSnapshot, update);
    }
    return modifyLegacyOrder(principal, orderId, orderSnapshot, update);
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
    if (orderSnapshot.getStatus() != OrderStatus.PENDING) {
      throw new BusinessException("ORDER_NOT_MODIFIABLE", "Only pending orders can be modified");
    }
    if (update.stopLoss() != null || update.takeProfit() != null) {
      throw new BusinessException(
          ErrorCode.PRODUCT_NOT_ALLOWED,
          "P0 Spot pending orders do not support protection fields");
    }
    if (orderSnapshot.getOrderType() != OrderType.LIMIT
        && orderSnapshot.getOrderType() != OrderType.STOP_MARKET) {
      throw new BusinessException(
          ErrorCode.INVALID_SPOT_ORDER_TYPE,
          "Only pending LIMIT and STOP_MARKET Spot orders can be modified");
    }

    BigDecimal publicQuantity = update.quantity() != null
        ? update.quantity()
        : orderSnapshot.getOriginalQuantity() != null
            ? orderSnapshot.getOriginalQuantity()
            : orderSnapshot.getBaseQuantity();
    requirePositiveField(publicQuantity, "BAD_QUANTITY", "Modified quantity must be positive");
    BigDecimal limitPrice = orderSnapshot.getOrderType() == OrderType.LIMIT
        ? update.price() != null ? update.price() : currentPrice(orderSnapshot)
        : null;
    BigDecimal triggerPrice = orderSnapshot.getOrderType() == OrderType.STOP_MARKET
        ? update.price() != null ? update.price() : orderSnapshot.getTriggerPrice()
        : null;
    if (orderSnapshot.getOrderType() == OrderType.LIMIT) {
      requirePositiveField(limitPrice, ErrorCode.ORDER_PRICE_REQUIRED, "LIMIT price is required");
    } else {
      requirePositiveField(
          triggerPrice,
          ErrorCode.ORDER_TRIGGER_PRICE_REQUIRED,
          "STOP_MARKET trigger price is required");
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
        orderSnapshot.getOrderType() == OrderType.STOP_MARKET
            ? TriggerPriceType.LAST_PRICE
            : null,
        false,
        List.of());
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
        FullFillExecutionPath path = executionPath(modifiedRequest, snapshot);
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
        OrderStatus.PENDING,
        OrderStatus.PENDING,
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
        ? walletAmount(baseQuantity.multiply(fullFill.filledPrice()))
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
        OrderStatus.PENDING,
        OrderStatus.PENDING,
        null,
        "Pending Spot order modified for immediate fill");
    orderFillService.fill(order, account, fullFill, BigDecimal.ZERO, "Spot order fill after modification");
    orderEventService.record(
        order.getId(),
        "ORDER_FILLED",
        OrderStatus.PENDING,
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
        ? modifiedRequest.price() : null);
    order.setPrice(modifiedRequest.orderType() == OrderType.LIMIT
        ? modifiedRequest.price() : null);
    order.setTriggerPrice(modifiedRequest.orderType() == OrderType.STOP_MARKET
        ? modifiedRequest.triggerPrice() : null);
    order.setTriggerPriceType(modifiedRequest.orderType() == OrderType.STOP_MARKET
        ? TriggerPriceType.LAST_PRICE : null);
    order.setTriggerExecutionType(modifiedRequest.orderType() == OrderType.STOP_MARKET
        ? TriggerExecutionType.MARKET : null);
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

  private OrderResponse createNewOrder(OrderCommand command, CreateOrderRequest request) {
    TradingAccountEntity accountSnapshot = accountRepository.findByIdAndUserId(command.accountId(), command.userId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    ProductType productType = requestedProduct(command.symbol());
    demoExecutionGuard.requireDemo(accountSnapshot, productType, command.symbol());

    if (productType == ProductType.CRYPTO_SPOT
        && isP0Symbol(command.symbol())
        && task6SpotReady()) {
      return createP0SpotWithRetry(command, request, accountSnapshot);
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
        OrderCommand canonicalCommand = canonicalCommand(
            identityCommand,
            request,
            conversion.baseQuantity());
        OrderHoldCalculator.OrderHold hold = pendingHold(request, conversion.baseQuantity(), path, snapshot);
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
    // A lock wait can expire the provider snapshot; reject before an ensure can insert state.
    fullFillCoordinator.requireFresh(snapshot);
    prepareP0SpotMutationState(account.getId(), command.symbol());

    OrderEntity order = preparedOrder(command, publicRequest, ProductType.CRYPTO_SPOT, 1);
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
    order.setStatus(OrderStatus.PENDING);
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
        "ORDER_PENDING",
        OrderStatus.ACCEPTED,
        OrderStatus.PENDING,
        null,
        "Pending order accepted and waiting");
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
    if (request.orderType() == OrderType.LIMIT) {
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
    return request.orderType() == OrderType.LIMIT
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
        || request.stopLoss() != null
        || request.takeProfit() != null
        || !request.attachedProtections().isEmpty()) {
      throw new BusinessException("PRODUCT_NOT_ALLOWED", "Spot protections and reduce-only are not supported");
    }
    if (request.marginMode() != MarginMode.CASH) {
      throw new BusinessException("PRODUCT_NOT_ALLOWED", "Spot margin mode must be CASH");
    }
    if (request.positionSide() != PositionSide.BOTH) {
      throw new BusinessException("PRODUCT_NOT_ALLOWED", "Spot position side must be BOTH");
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
        if (request.triggerPrice() != null || request.triggerPriceType() != null) {
          throw new BusinessException(
              ErrorCode.INVALID_SPOT_ORDER_FIELDS,
              "LIMIT does not accept trigger fields");
        }
      }
      case STOP_MARKET -> {
        requirePositiveField(
            request.triggerPrice(),
            "ORDER_TRIGGER_PRICE_REQUIRED",
            "STOP_MARKET triggerPrice is required");
        if (request.price() != null
            || (request.triggerPriceType() != null
            && request.triggerPriceType() != TriggerPriceType.LAST_PRICE)) {
          throw new BusinessException(
              ErrorCode.INVALID_SPOT_ORDER_FIELDS,
              "STOP_MARKET uses LAST_PRICE and does not accept limit price");
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
            : request.triggerPriceType(),
        List.of());
  }

  private CreateOrderRequest canonicalExecutionIntent(
      CreateOrderRequest publicRequest,
      BigDecimal baseQuantity
  ) {
    return new CreateOrderRequest(
        publicRequest.accountId(),
        publicRequest.symbol(),
        publicRequest.side(),
        publicRequest.orderType(),
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
        publicRequest.triggerPrice(),
        publicRequest.orderType() == OrderType.STOP_MARKET
            ? TriggerPriceType.LAST_PRICE
            : publicRequest.triggerPriceType(),
        false,
        List.of());
  }

  private boolean task6SpotReady() {
    return symbolRepository != null
        && instrumentRulesEngine != null
        && quantityConversionService != null
        && orderHoldCalculator != null;
  }

  @Autowired
  void setOcoOrderService(OcoOrderService ocoOrderService) {
    this.ocoOrderService = ocoOrderService;
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
    order.setTriggerExecutionType(command.orderType() == OrderType.STOP_MARKET
        ? TriggerExecutionType.MARKET
        : null);
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
      CreateOrderRequest request
  ) {
    try {
      return createNewOrder(command, request);
    } catch (DataIntegrityViolationException ex) {
      return findExistingOrder(command)
          .map(orderResponseMapper::toResponse)
          .orElseThrow(() -> ex);
    }
  }

  private java.util.Optional<OrderEntity> findExistingOrder(OrderCommand command) {
    return orderRepository.findByUserIdAndAccountIdAndClientOrderId(
            command.userId(), command.accountId(), command.clientOrderId())
        .or(() -> orderRepository.findByUserIdAndIdempotencyKey(command.userId(), command.idempotencyKey()));
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
      BigDecimal triggerPrice
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
          order.getTriggerPrice());
    }

    private boolean matches(OrderEntity order) {
      return order != null
          && java.util.Objects.equals(id, order.getId())
          && java.util.Objects.equals(userId, order.getUserId())
          && java.util.Objects.equals(accountId, order.getAccountId())
          && java.util.Objects.equals(symbol, order.getSymbol())
          && orderType == order.getOrderType()
          && side == order.getSide()
          && status == OrderStatus.PENDING
          && order.getStatus() == OrderStatus.PENDING
          && sameDecimal(baseQuantity, order.getBaseQuantity())
          && sameDecimal(holdAmount, orZero(order.getHoldAmount()))
          && java.util.Objects.equals(holdCurrency, order.getHoldCurrency())
          && sameDecimal(price, currentVersionPrice(order))
          && sameDecimal(triggerPrice, order.getTriggerPrice())
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

}
