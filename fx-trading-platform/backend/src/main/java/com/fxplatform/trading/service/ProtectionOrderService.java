package com.fxplatform.trading.service;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.ExecutableMarketSnapshots;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketBundleProducts;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import com.fxplatform.trading.dto.request.CreateOrderRequest.AttachedProtectionRequest;
import com.fxplatform.trading.dto.request.CreateProtectionRequest;
import com.fxplatform.trading.dto.request.UpdateProtectionRequest;
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Owns position-bound protection carrier CRUD and mark-price trigger semantics. */
@Service
public class ProtectionOrderService {

  private static final int MAX_ACTIVE_PROTECTIONS = 10;

  private final TradingAccountRepository accountRepository;
  private final PositionRepository positionRepository;
  private final OrderRepository orderRepository;
  private final AccountSymbolSettingRepository settingRepository;
  private final SymbolRepository symbolRepository;
  private final MarketBundleResolver marketBundleResolver;
  private final FullFillCoordinator fullFillCoordinator;
  private final QuantityConversionService quantityConversionService;
  private final InstrumentRulesEngine instrumentRulesEngine;
  private final DemoExecutionGuard demoExecutionGuard;
  private final LedgerService ledgerService;
  private final OrderEventService orderEventService;
  private final OrderResponseMapper orderResponseMapper;
  private final TradingTransactionExecutor transactionExecutor;

  @Autowired
  public ProtectionOrderService(
      TradingAccountRepository accountRepository,
      PositionRepository positionRepository,
      OrderRepository orderRepository,
      AccountSymbolSettingRepository settingRepository,
      SymbolRepository symbolRepository,
      MarketBundleResolver marketBundleResolver,
      FullFillCoordinator fullFillCoordinator,
      QuantityConversionService quantityConversionService,
      InstrumentRulesEngine instrumentRulesEngine,
      DemoExecutionGuard demoExecutionGuard,
      LedgerService ledgerService,
      OrderEventService orderEventService,
      OrderResponseMapper orderResponseMapper,
      TradingTransactionExecutor transactionExecutor
  ) {
    this.accountRepository = accountRepository;
    this.positionRepository = positionRepository;
    this.orderRepository = orderRepository;
    this.settingRepository = settingRepository;
    this.symbolRepository = symbolRepository;
    this.marketBundleResolver = marketBundleResolver;
    this.fullFillCoordinator = fullFillCoordinator;
    this.quantityConversionService = quantityConversionService;
    this.instrumentRulesEngine = instrumentRulesEngine;
    this.demoExecutionGuard = demoExecutionGuard;
    this.ledgerService = ledgerService;
    this.orderEventService = orderEventService;
    this.orderResponseMapper = orderResponseMapper;
    this.transactionExecutor = transactionExecutor;
  }

  /** Compatibility constructor retained for focused fixtures that never release a real hold. */
  public ProtectionOrderService(
      TradingAccountRepository accountRepository,
      PositionRepository positionRepository,
      OrderRepository orderRepository,
      AccountSymbolSettingRepository settingRepository,
      SymbolRepository symbolRepository,
      MarketBundleResolver marketBundleResolver,
      FullFillCoordinator fullFillCoordinator,
      QuantityConversionService quantityConversionService,
      InstrumentRulesEngine instrumentRulesEngine,
      DemoExecutionGuard demoExecutionGuard,
      OrderEventService orderEventService,
      OrderResponseMapper orderResponseMapper,
      TradingTransactionExecutor transactionExecutor
  ) {
    this(
        accountRepository,
        positionRepository,
        orderRepository,
        settingRepository,
        symbolRepository,
        marketBundleResolver,
        fullFillCoordinator,
        quantityConversionService,
        instrumentRulesEngine,
        demoExecutionGuard,
        null,
        orderEventService,
        orderResponseMapper,
        transactionExecutor);
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public OrderResponse create(
      UUID userId,
      UUID positionId,
      CreateProtectionRequest request
  ) {
    validateCreateRequest(userId, positionId, request);
    String clientOrderId = request.clientOrderId().trim();
    Optional<OrderEntity> replay = orderRepository.findByUserIdAndIdempotencyKey(
        userId, clientOrderId);
    if (replay.isPresent()) {
      return replayOrConflict(replay.get(), userId, positionId, request);
    }
    MutationContext context = requireContext(userId, positionId);
    replay = findCreateReplay(
        userId, context.account().getId(), clientOrderId);
    if (replay.isPresent()) {
      return replayOrConflict(replay.get(), userId, positionId, request);
    }
    BusinessException staleFailure = null;
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        PreparedProtection prepared = prepare(
            context,
            request.protectionType(),
            request.quantity(),
            request.quantityUnit(),
            request.triggerPrice(),
            request.triggerExecutionType(),
            request.price());
        return transactionExecutor.execute(() -> createLocked(
            userId,
            positionId,
            clientOrderId,
            context,
            prepared,
            request));
      } catch (DataIntegrityViolationException exception) {
        Optional<OrderEntity> committed = findCreateReplay(
            userId, context.account().getId(), clientOrderId);
        if (committed.isPresent()) {
          return replayOrConflict(committed.get(), userId, positionId, request);
        }
        throw exception;
      } catch (BusinessException exception) {
        if (!ErrorCode.MARKET_DATA_STALE.equals(exception.getCode()) || attempt > 0) {
          throw exception;
        }
        staleFailure = exception;
      }
    }
    throw staleFailure == null ? staleMarket() : staleFailure;
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public OrderResponse update(
      UUID userId,
      UUID protectionOrderId,
      UpdateProtectionRequest request
  ) {
    validateUpdateRequest(userId, protectionOrderId, request);
    OrderEntity preflight = requireProtectionSnapshot(userId, protectionOrderId);
    requirePendingActivation(preflight);
    MutationContext context = requireContext(userId, preflight.getParentPositionId());
    BusinessException staleFailure = null;
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        PreparedProtection prepared = prepareUpdate(context, preflight, request);
        return transactionExecutor.execute(() -> updateLocked(
            userId,
            protectionOrderId,
            request.expectedVersion(),
            context,
            prepared));
      } catch (BusinessException exception) {
        if (!ErrorCode.MARKET_DATA_STALE.equals(exception.getCode()) || attempt > 0) {
          throw exception;
        }
        staleFailure = exception;
      }
    }
    throw staleFailure == null ? staleMarket() : staleFailure;
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public OrderResponse cancel(UUID userId, UUID protectionOrderId) {
    if (userId == null || protectionOrderId == null) {
      throw protectionNotFound();
    }
    OrderEntity preflight = requireProtectionSnapshot(userId, protectionOrderId);
    requireCancelable(protectionOrderId, preflight);
    PositionEntity position = positionRepository.findById(preflight.getParentPositionId())
        .orElseThrow(ProtectionOrderService::positionNotFound);
    TradingAccountEntity account = accountRepository.findByIdAndUserId(
            position.getAccountId(), userId)
        .orElseThrow(ProtectionOrderService::accountNotFound);
    validatePosition(position);
    demoExecutionGuard.requireDemo(account, ProductType.LINEAR_PERP, position.getSymbol());
    long expectedVersion = version(preflight);
    return transactionExecutor.execute(() -> cancelLocked(
        userId,
        protectionOrderId,
        position,
        expectedVersion));
  }

  /** Creates unbound protection carriers while the parent-order lock scope is already held. */
  public void createAttachedLocked(
      OrderEntity parentOrder,
      List<AttachedProtectionRequest> requests
  ) {
    List<AttachedProtectionRequest> attached = requests == null ? List.of() : List.copyOf(requests);
    if (attached.isEmpty()) {
      return;
    }
    validateAttachedParent(parentOrder);
    if (attached.size() > MAX_ACTIVE_PROTECTIONS) {
      throw new BusinessException(
          ErrorCode.PROTECTION_LIMIT_EXCEEDED,
          "A parent order may attach at most ten protections");
    }
    SymbolEntity symbol = requireSymbol(parentOrder.getSymbol());
    BigDecimal parentQuantity = canonicalQuantity(parentOrder);
    InstrumentRules rules = instrumentRulesEngine.rules(symbol);
    validateRules(rules);
    Map<ProtectionType, BigDecimal> totals = new EnumMap<>(ProtectionType.class);
    for (AttachedProtectionRequest request : attached) {
      validateAttachedRequest(request, rules.tickSize());
      if (request.triggerExecutionType() == TriggerExecutionType.LIMIT) {
        validateNotional(rules, parentQuantity, request.price());
      }
      BigDecimal total = totals.getOrDefault(request.protectionType(), BigDecimal.ZERO)
          .add(parentQuantity);
      if (total.compareTo(parentQuantity) > 0) {
        throw new BusinessException(
            ErrorCode.PROTECTION_QUANTITY_EXCEEDED,
            "Attached protection quantity exceeds the parent order quantity");
      }
      totals.put(request.protectionType(), total);
    }
    List<OrderEntity> existing = safeList(
        orderRepository.findProtectionsByParentOrderIdForUpdate(parentOrder.getId()));
    if (!existing.isEmpty()) {
      throw new BusinessException(
          ErrorCode.DUPLICATE_CLIENT_ORDER_ID,
          "Attached protections already exist for the parent order");
    }
    for (int index = 0; index < attached.size(); index++) {
      OrderEntity carrier = newAttachedCarrier(parentOrder, attached.get(index), index);
      requireWrite(orderRepository.insert(carrier));
      orderEventService.record(
          carrier.getId(),
          "PROTECTION_CREATED",
          null,
          OrderStatus.PENDING_ACTIVATION,
          null,
          null);
    }
  }

  /** Expires still-unbound attached carriers while their canceled parent lock scope is held. */
  public void expireAttachedForCanceledParentLocked(
      OrderEntity parentOrder,
      TradingAccountEntity lockedAccount
  ) {
    if (parentOrder == null || parentOrder.getId() == null) {
      return;
    }
    if (lockedAccount == null
        || !Objects.equals(parentOrder.getAccountId(), lockedAccount.getId())) {
      throw accountNotFound();
    }
    List<OrderEntity> attached = safeList(
        orderRepository.findProtectionsByParentOrderIdForUpdate(parentOrder.getId())).stream()
        .filter(order -> Objects.equals(order.getParentOrderId(), parentOrder.getId()))
        .filter(order -> order.getProtectionType() != null)
        .filter(order -> order.getParentPositionId() == null)
        .filter(order -> order.getStatus() == OrderStatus.PENDING_ACTIVATION)
        .toList();
    for (OrderEntity carrier : attached) {
      releaseProtectionHold(
          lockedAccount,
          carrier,
          "Parent cancellation released attached protection hold");
      carrier.setStatus(OrderStatus.EXPIRED);
      carrier.setQuantity(BigDecimal.ZERO);
      carrier.setOriginalQuantity(BigDecimal.ZERO);
      carrier.setBaseQuantity(BigDecimal.ZERO);
      carrier.setLots(BigDecimal.ZERO);
      carrier.setRemainingQuantity(BigDecimal.ZERO);
      carrier.setHoldAmount(BigDecimal.ZERO);
      carrier.setVersion(version(carrier) + 1L);
      requireWrite(orderRepository.updateById(carrier));
      orderEventService.record(
          carrier.getId(),
          "PROTECTION_EXPIRED",
          OrderStatus.PENDING_ACTIVATION,
          OrderStatus.EXPIRED,
          null,
          null);
    }
  }

  /** Reconciles bound protections after any canonical perpetual fill. */
  public void afterPerpetualFillLocked(
      OrderEntity filledOrder,
      PositionEngine.PositionUpdateResult update,
      BigDecimal authorityMark
  ) {
    if (filledOrder == null
        || filledOrder.getId() == null
        || filledOrder.getAccountId() == null
        || filledOrder.getProductType() != ProductType.LINEAR_PERP
        || update == null) {
      return;
    }
    List<OrderEntity> activeOrders = safeList(
        orderRepository.findActiveLinearPerpBySymbolForUpdate(
            filledOrder.getAccountId(), filledOrder.getSymbol()));
    if (update.reducedPositionId() != null) {
      reconcileReductionLocked(filledOrder, update, activeOrders);
      activeOrders = safeList(orderRepository.findActiveLinearPerpBySymbolForUpdate(
          filledOrder.getAccountId(), filledOrder.getSymbol()));
    }
    bindAttachedLocked(filledOrder, update.position(), authorityMark, activeOrders);
  }

  /** Returns whether one untriggered protection carrier crosses its authority mark condition. */
  public boolean isTriggered(OrderEntity protection, BigDecimal authorityMark) {
    if (protection == null
        || protection.getStatus() != OrderStatus.PENDING_ACTIVATION
        || protection.getProtectionType() == null
        || protection.getSide() == null
        || protection.getTriggerPrice() == null
        || authorityMark == null) {
      return false;
    }
    int comparison = authorityMark.compareTo(protection.getTriggerPrice());
    if (protection.getSide() == OrderSide.SELL) {
      return protection.getProtectionType() == ProtectionType.TAKE_PROFIT
          ? comparison >= 0
          : comparison <= 0;
    }
    return protection.getProtectionType() == ProtectionType.TAKE_PROFIT
        ? comparison <= 0
        : comparison >= 0;
  }

  private OrderResponse createLocked(
      UUID userId,
      UUID positionId,
      String clientOrderId,
      MutationContext context,
      PreparedProtection prepared,
      CreateProtectionRequest request
  ) {
    Optional<OrderEntity> replay = findCreateReplay(
        userId, context.account().getId(), clientOrderId);
    if (replay.isPresent()) {
      return replayOrConflict(replay.get(), userId, positionId, request);
    }
    TradingAccountEntity account = lockAccount(userId, context.account().getId());
    AccountSymbolSettingEntity setting = lockSetting(
        account.getId(), context.position().getSymbol());
    PositionEntity position = lockPosition(
        account.getId(), positionId, context.position().getSymbol());
    List<OrderEntity> activeOrders = orderRepository.findActiveLinearPerpBySymbolForUpdate(
        account.getId(), position.getSymbol());
    replay = findCreateReplay(userId, account.getId(), clientOrderId);
    if (replay.isPresent()) {
      return replayOrConflict(replay.get(), userId, positionId, request);
    }
    validateLockedAuthority(account, setting, position);
    validatePreparedAgainstLockedPosition(account, position, context, prepared);
    List<OrderEntity> protections = boundProtections(activeOrders, positionId);
    if (protections.size() >= MAX_ACTIVE_PROTECTIONS) {
      throw new BusinessException(
          ErrorCode.PROTECTION_LIMIT_EXCEEDED,
          "A position may have at most ten active protections");
    }
    validateQuantityBudget(
        protections,
        null,
        prepared.protectionType(),
        prepared.conversion().baseQuantity(),
        position);
    fullFillCoordinator.requireFresh(prepared.market());
    OrderEntity order = newCarrier(
        userId,
        account,
        position,
        clientOrderId,
        prepared,
        request);
    requireWrite(orderRepository.insert(order));
    orderEventService.record(
        order.getId(),
        "PROTECTION_CREATED",
        null,
        OrderStatus.PENDING_ACTIVATION,
        null,
        null);
    return orderResponseMapper.toResponse(order);
  }

  private OrderResponse updateLocked(
      UUID userId,
      UUID protectionOrderId,
      long expectedVersion,
      MutationContext context,
    PreparedProtection prepared
  ) {
    TradingAccountEntity account = lockAccount(userId, context.account().getId());
    AccountSymbolSettingEntity setting = lockSetting(
        account.getId(), context.position().getSymbol());
    PositionEntity position = lockPosition(
        account.getId(), context.position().getId(), context.position().getSymbol());
    List<OrderEntity> activeOrders = orderRepository.findActiveLinearPerpBySymbolForUpdate(
        account.getId(), position.getSymbol());
    OrderEntity protection = activeOrders.stream()
        .filter(order -> protectionOrderId.equals(order.getId()))
        .filter(order -> order.getProtectionType() != null)
        .filter(order -> position.getId().equals(order.getParentPositionId()))
        .findFirst()
        .orElseThrow(ProtectionOrderService::protectionNotFound);
    requirePendingActivation(protection);
    if (version(protection) != expectedVersion) {
      throw versionConflict();
    }
    validateLockedAuthority(account, setting, position);
    validatePreparedAgainstLockedPosition(account, position, context, prepared);
    validateQuantityBudget(
        boundProtections(activeOrders, position.getId()),
        protectionOrderId,
        prepared.protectionType(),
        prepared.conversion().baseQuantity(),
        position);
    fullFillCoordinator.requireFresh(prepared.market());
    applyPrepared(protection, prepared);
    protection.setVersion(expectedVersion + 1L);
    requireWrite(orderRepository.updateById(protection));
    orderEventService.record(
        protection.getId(),
        "PROTECTION_UPDATED",
        OrderStatus.PENDING_ACTIVATION,
        OrderStatus.PENDING_ACTIVATION,
        null,
        null);
    return orderResponseMapper.toResponse(protection);
  }

  private OrderResponse cancelLocked(
      UUID userId,
      UUID protectionOrderId,
      PositionEntity preflightPosition,
      long expectedVersion
  ) {
    TradingAccountEntity account = lockAccount(userId, preflightPosition.getAccountId());
    demoExecutionGuard.requireDemo(
        account, ProductType.LINEAR_PERP, preflightPosition.getSymbol());
    AccountSymbolSettingEntity setting = lockSetting(
        account.getId(), preflightPosition.getSymbol());
    PositionEntity position = lockPosition(
        account.getId(), preflightPosition.getId(), preflightPosition.getSymbol());
    List<OrderEntity> activeOrders = orderRepository.findActiveLinearPerpBySymbolForUpdate(
        account.getId(), position.getSymbol());
    OrderEntity protection = activeOrders.stream()
        .filter(order -> protectionOrderId.equals(order.getId()))
        .filter(order -> order.getProtectionType() != null)
        .filter(order -> position.getId().equals(order.getParentPositionId()))
        .findFirst()
        .orElseThrow(ProtectionOrderService::protectionNotFound);
    requireCancelable(protectionOrderId, protection);
    if (version(protection) != expectedVersion) {
      throw versionConflict();
    }
    validateLockedAuthority(account, setting, position);
    OrderStatus fromStatus = protection.getStatus();
    releaseProtectionHold(account, protection, "Triggered protection LIMIT canceled");
    protection.setStatus(OrderStatus.CANCELED);
    protection.setCanceledAt(Instant.now());
    protection.setRemainingQuantity(BigDecimal.ZERO);
    protection.setHoldAmount(BigDecimal.ZERO);
    protection.setVersion(expectedVersion + 1L);
    requireWrite(orderRepository.updateById(protection));
    orderEventService.record(
        protection.getId(),
        "PROTECTION_CANCELED",
        fromStatus,
        OrderStatus.CANCELED,
        null,
        null);
    return orderResponseMapper.toResponse(protection);
  }

  private MutationContext requireContext(UUID userId, UUID positionId) {
    if (userId == null || positionId == null) {
      throw positionNotFound();
    }
    PositionEntity position = positionRepository.findById(positionId)
        .orElseThrow(ProtectionOrderService::positionNotFound);
    TradingAccountEntity account = accountRepository.findByIdAndUserId(
            position.getAccountId(), userId)
        .orElseThrow(ProtectionOrderService::positionNotFound);
    validatePosition(position);
    demoExecutionGuard.requireDemo(account, ProductType.LINEAR_PERP, position.getSymbol());
    SymbolEntity symbol = requireSymbol(position.getSymbol());
    return new MutationContext(account, position, symbol);
  }

  private PreparedProtection prepare(
      MutationContext context,
      ProtectionType protectionType,
      BigDecimal quantity,
      QuantityUnit quantityUnit,
      BigDecimal triggerPrice,
      TriggerExecutionType triggerExecutionType,
      BigDecimal price
  ) {
    validateExecutionPrice(triggerExecutionType, price);
    ExecutableMarketSnapshot market = resolveMarket(context.position().getSymbol());
    InstrumentRules rules = instrumentRulesEngine.rules(context.symbol());
    validateRules(rules);
    QuantityConversionService.Conversion conversion = quantityConversionService.convertPerpetual(
        quantityUnit,
        quantity,
        rules.stepSize(),
        rules.contractSize(),
        context.symbol().getContractMultiplier(),
        market.mark(),
        rules.minNotional());
    validateCanonicalQuantity(rules, conversion, market.mark());
    validateTick(triggerPrice, rules.tickSize());
    if (triggerExecutionType == TriggerExecutionType.LIMIT) {
      validateTick(price, rules.tickSize());
      validateNotional(rules, conversion.baseQuantity(), price);
    }
    validateDirection(context.position(), protectionType, triggerPrice, market.mark());
    return new PreparedProtection(
        protectionType,
        conversion,
        triggerPrice,
        triggerExecutionType,
        triggerExecutionType == TriggerExecutionType.MARKET ? null : price,
        rules,
        market);
  }

  private PreparedProtection prepareUpdate(
      MutationContext context,
      OrderEntity existing,
      UpdateProtectionRequest request
  ) {
    BigDecimal trigger = request.triggerPrice() == null
        ? existing.getTriggerPrice()
        : request.triggerPrice();
    TriggerExecutionType executionType = request.triggerExecutionType() == null
        ? existing.getTriggerExecutionType()
        : request.triggerExecutionType();
    BigDecimal price = request.price() == null ? existing.getPrice() : request.price();
    if (executionType == TriggerExecutionType.MARKET) {
      if (request.price() != null) {
        throw new BusinessException(
            "PROTECTION_PRICE_INVALID",
            "MARKET protection execution forbids price");
      }
      price = null;
    }
    if (request.quantity() == null) {
      return preparePreservingQuantity(
          context, existing, trigger, executionType, price);
    }
    QuantityUnit unit = request.quantityUnit() == null
        ? existing.getQuantityUnit()
        : request.quantityUnit();
    return prepare(
        context,
        existing.getProtectionType(),
        request.quantity(),
        unit,
        trigger,
        executionType,
        price);
  }

  private PreparedProtection preparePreservingQuantity(
      MutationContext context,
      OrderEntity existing,
      BigDecimal triggerPrice,
      TriggerExecutionType executionType,
      BigDecimal price
  ) {
    validateExecutionPrice(executionType, price);
    BigDecimal original = firstNonNull(
        existing.getOriginalQuantity(), existing.getQuantity(), existing.getLots());
    QuantityUnit unit = existing.getQuantityUnit();
    BigDecimal base = canonicalQuantity(existing);
    if (!positive(original) || unit == null || !positive(base)) {
      throw new BusinessException(
          "PROTECTION_NOT_MODIFIABLE",
          "Existing protection quantity metadata is invalid");
    }
    ExecutableMarketSnapshot market = resolveMarket(context.position().getSymbol());
    InstrumentRules rules = instrumentRulesEngine.rules(context.symbol());
    validateRules(rules);
    QuantityConversionService.Conversion conversion =
        new QuantityConversionService.Conversion(
            original,
            unit,
            base,
            base.multiply(market.mark()).setScale(8, RoundingMode.HALF_UP));
    validateCanonicalQuantity(rules, conversion, market.mark());
    validateTick(triggerPrice, rules.tickSize());
    if (executionType == TriggerExecutionType.LIMIT) {
      validateTick(price, rules.tickSize());
      validateNotional(rules, base, price);
    }
    validateDirection(
        context.position(), existing.getProtectionType(), triggerPrice, market.mark());
    return new PreparedProtection(
        existing.getProtectionType(),
        conversion,
        triggerPrice,
        executionType,
        executionType == TriggerExecutionType.MARKET ? null : price,
        rules,
        market);
  }

  private void validatePreparedAgainstLockedPosition(
      TradingAccountEntity account,
      PositionEntity position,
      MutationContext context,
      PreparedProtection prepared
  ) {
    validatePosition(position);
    if (!Objects.equals(position.getAccountId(), context.account().getId())
        || !Objects.equals(position.getSymbol(), context.position().getSymbol())) {
      throw staleMarket();
    }
    demoExecutionGuard.requireDemo(
        account, ProductType.LINEAR_PERP, position.getSymbol());
    validateDirection(
        position,
        prepared.protectionType(),
        prepared.triggerPrice(),
        prepared.market().mark());
  }

  private OrderEntity newCarrier(
      UUID userId,
      TradingAccountEntity account,
      PositionEntity position,
      String clientOrderId,
      PreparedProtection prepared,
      CreateProtectionRequest request
  ) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setUserId(userId);
    order.setAccountId(account.getId());
    order.setSymbol(position.getSymbol());
    order.setProductType(ProductType.LINEAR_PERP);
    order.setPositionMode(position.getPositionMode());
    order.setPositionSide(position.getPositionSide());
    order.setMarginMode(position.getMarginMode());
    order.setSide(closeSide(position));
    order.setOrderType(OrderType.STOP_MARKET);
    order.setStatus(OrderStatus.PENDING_ACTIVATION);
    applyPrepared(order, prepared);
    order.setClientOrderId(clientOrderId);
    order.setIdempotencyKey(clientOrderId);
    order.setTimeInForce(TimeInForce.GTC);
    order.setReduceOnly(true);
    order.setOrderOrigin(OrderOrigin.PROTECTIVE);
    order.setParentPositionId(position.getId());
    order.setSystemReason(createFingerprint(position.getId(), request));
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setFee(BigDecimal.ZERO);
    order.setSlippage(BigDecimal.ZERO);
    order.setHoldAmount(BigDecimal.ZERO);
    order.setHoldCurrency(prepared.rules().settlementAsset());
    order.setLeverage(position.getLeverage());
    order.setVersion(0L);
    return order;
  }

  private void applyPrepared(OrderEntity order, PreparedProtection prepared) {
    BigDecimal original = prepared.conversion().originalQuantity();
    BigDecimal base = prepared.conversion().baseQuantity();
    order.setQuantity(original);
    order.setOriginalQuantity(original);
    order.setQuantityUnit(prepared.conversion().originalUnit());
    order.setBaseQuantity(base);
    order.setLots(base);
    order.setRemainingQuantity(base);
    order.setTriggerPrice(prepared.triggerPrice());
    order.setTriggerPriceType(TriggerPriceType.MARK_PRICE);
    order.setTriggerExecutionType(prepared.triggerExecutionType());
    order.setProtectionType(prepared.protectionType());
    order.setPrice(prepared.price());
    order.setRequestedPrice(prepared.price());
  }

  private OrderEntity newAttachedCarrier(
      OrderEntity parent,
      AttachedProtectionRequest request,
      int index
  ) {
    BigDecimal quantity = canonicalQuantity(parent);
    OrderEntity carrier = new OrderEntity();
    carrier.setId(UUID.randomUUID());
    carrier.setUserId(parent.getUserId());
    carrier.setAccountId(parent.getAccountId());
    carrier.setSymbol(parent.getSymbol());
    carrier.setProductType(ProductType.LINEAR_PERP);
    carrier.setPositionMode(parent.getPositionMode());
    carrier.setPositionSide(parent.getPositionSide());
    carrier.setMarginMode(parent.getMarginMode());
    carrier.setSide(opposite(parent.getSide()));
    carrier.setOrderType(OrderType.STOP_MARKET);
    carrier.setStatus(OrderStatus.PENDING_ACTIVATION);
    carrier.setLots(quantity);
    carrier.setQuantity(quantity);
    carrier.setOriginalQuantity(quantity);
    carrier.setBaseQuantity(quantity);
    carrier.setRemainingQuantity(quantity);
    carrier.setQuantityUnit(QuantityUnit.BASE);
    carrier.setPrice(request.triggerExecutionType() == TriggerExecutionType.LIMIT
        ? request.price()
        : null);
    carrier.setRequestedPrice(carrier.getPrice());
    carrier.setTimeInForce(TimeInForce.GTC);
    carrier.setReduceOnly(true);
    carrier.setOrderOrigin(OrderOrigin.PROTECTIVE);
    carrier.setTriggerPrice(request.triggerPrice());
    carrier.setTriggerPriceType(TriggerPriceType.MARK_PRICE);
    carrier.setTriggerExecutionType(request.triggerExecutionType());
    carrier.setProtectionType(request.protectionType());
    carrier.setParentOrderId(parent.getId());
    carrier.setFilledQuantity(BigDecimal.ZERO);
    carrier.setFee(BigDecimal.ZERO);
    carrier.setSlippage(BigDecimal.ZERO);
    carrier.setHoldAmount(BigDecimal.ZERO);
    carrier.setHoldCurrency(parent.getHoldCurrency() == null ? "USDT" : parent.getHoldCurrency());
    carrier.setLeverage(parent.getLeverage());
    carrier.setVersion(0L);
    String childKey = parent.getId() + ":PROTECTION:" + index;
    carrier.setClientOrderId(childKey);
    carrier.setIdempotencyKey(childKey);
    return carrier;
  }

  private void bindAttachedLocked(
      OrderEntity filledOrder,
      PositionEntity position,
      BigDecimal authorityMark,
      List<OrderEntity> activeOrders
  ) {
    List<OrderEntity> attached = safeList(
        orderRepository.findProtectionsByParentOrderIdForUpdate(filledOrder.getId())).stream()
        .filter(order -> order.getProtectionType() != null)
        .filter(order -> order.getParentPositionId() == null)
        .filter(order -> order.getStatus() == OrderStatus.PENDING_ACTIVATION)
        .toList();
    if (attached.isEmpty()) {
      return;
    }
    if (Boolean.TRUE.equals(filledOrder.getReduceOnly())) {
      throw new BusinessException(
          ErrorCode.PRODUCT_NOT_ALLOWED,
          "Reduce-only orders cannot activate attached protections");
    }
    validatePosition(position);
    if (!Objects.equals(position.getAccountId(), filledOrder.getAccountId())
        || !Objects.equals(position.getSymbol(), filledOrder.getSymbol())) {
      throw positionNotFound();
    }
    List<OrderEntity> alreadyBound = boundProtections(activeOrders, position.getId());
    if (alreadyBound.size() + attached.size() > MAX_ACTIVE_PROTECTIONS) {
      throw new BusinessException(
          ErrorCode.PROTECTION_LIMIT_EXCEEDED,
          "Attached protections exceed the position limit");
    }
    Map<ProtectionType, BigDecimal> totals = new EnumMap<>(ProtectionType.class);
    for (OrderEntity existing : alreadyBound) {
      totals.merge(existing.getProtectionType(), canonicalRemaining(existing), BigDecimal::add);
    }
    BigDecimal capacity = position.getLots().abs();
    Map<UUID, BigDecimal> boundQuantities = new java.util.HashMap<>();
    for (OrderEntity carrier : attached) {
      validateDirection(
          position,
          carrier.getProtectionType(),
          carrier.getTriggerPrice(),
          authorityMark);
      BigDecimal boundQuantity = canonicalRemaining(carrier).min(capacity);
      if (!positive(boundQuantity)) {
        throw new BusinessException(
            ErrorCode.PROTECTION_QUANTITY_EXCEEDED,
            "Attached protection has no resulting position quantity");
      }
      boundQuantities.put(carrier.getId(), boundQuantity);
      totals.merge(carrier.getProtectionType(), boundQuantity, BigDecimal::add);
    }
    if (totals.values().stream().anyMatch(total -> total.compareTo(capacity) > 0)) {
      throw new BusinessException(
          ErrorCode.PROTECTION_QUANTITY_EXCEEDED,
          "Attached protection quantity exceeds the filled position");
    }
    for (OrderEntity carrier : attached) {
      BigDecimal oldQuantity = canonicalRemaining(carrier);
      BigDecimal boundQuantity = boundQuantities.get(carrier.getId());
      if (oldQuantity.compareTo(boundQuantity) != 0) {
        resizeQuantities(carrier, oldQuantity, boundQuantity);
      }
      carrier.setParentPositionId(position.getId());
      carrier.setSide(closeSide(position));
      carrier.setPositionMode(position.getPositionMode());
      carrier.setPositionSide(position.getPositionSide());
      carrier.setMarginMode(position.getMarginMode());
      carrier.setLeverage(position.getLeverage());
      carrier.setVersion(version(carrier) + 1L);
      requireWrite(orderRepository.updateById(carrier));
      orderEventService.record(
          carrier.getId(),
          "PROTECTION_ACTIVATED",
          OrderStatus.PENDING_ACTIVATION,
          OrderStatus.PENDING_ACTIVATION,
          null,
          null);
    }
  }

  private void reconcileReductionLocked(
      OrderEntity filledOrder,
      PositionEngine.PositionUpdateResult update,
      List<OrderEntity> activeOrders
  ) {
    if (update.reducedPositionId() == null
        || update.fromQuantity() == null
        || update.toQuantity() == null
        || update.fromQuantity().compareTo(update.toQuantity()) <= 0) {
      return;
    }
    BigDecimal capacity = update.toQuantity().max(BigDecimal.ZERO);
    List<OrderEntity> protections = boundProtections(
        activeOrders, update.reducedPositionId()).stream()
        .filter(this::activeProtectionStatus)
        .toList();
    Comparator<OrderEntity> oldestFirst = Comparator
        .comparing(
            OrderEntity::getCreatedAt,
            Comparator.nullsFirst(Comparator.naturalOrder()))
        .thenComparing(
            OrderEntity::getId,
            Comparator.nullsFirst(Comparator.naturalOrder()));
    TradingAccountEntity account = null;
    for (ProtectionType type : ProtectionType.values()) {
      List<OrderEntity> sameType = protections.stream()
          .filter(order -> order.getProtectionType() == type)
          .sorted(oldestFirst)
          .toList();
      BigDecimal total = sameType.stream()
          .map(this::canonicalRemaining)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal excess = total.subtract(capacity).max(BigDecimal.ZERO);
      for (int index = sameType.size() - 1;
          index >= 0 && excess.compareTo(BigDecimal.ZERO) > 0;
          index--) {
        OrderEntity protection = sameType.get(index);
        BigDecimal oldQuantity = canonicalRemaining(protection);
        BigDecimal removed = oldQuantity.min(excess);
        BigDecimal nextQuantity = oldQuantity.subtract(removed);
        BigDecimal oldHold = orZero(protection.getHoldAmount());
        BigDecimal nextHold = nextQuantity.compareTo(BigDecimal.ZERO) == 0
            ? money(BigDecimal.ZERO)
            : money(oldHold.multiply(nextQuantity)
                .divide(oldQuantity, 16, RoundingMode.HALF_UP));
        BigDecimal release = money(oldHold.subtract(nextHold));
        if (release.compareTo(BigDecimal.ZERO) > 0) {
          if (account == null) {
            account = accountRepository.findByIdForUpdate(filledOrder.getAccountId())
                .orElseThrow(ProtectionOrderService::accountNotFound);
          }
          releaseHoldAmount(
              account,
              protection,
              release,
              "Protection resize released order hold");
        }
        OrderStatus fromStatus = protection.getStatus();
        resizeQuantities(protection, oldQuantity, nextQuantity);
        protection.setHoldAmount(nextHold);
        protection.setVersion(version(protection) + 1L);
        if (nextQuantity.compareTo(BigDecimal.ZERO) == 0) {
          protection.setStatus(OrderStatus.EXPIRED);
        }
        requireWrite(orderRepository.updateById(protection));
        orderEventService.record(
            protection.getId(),
            nextQuantity.compareTo(BigDecimal.ZERO) == 0
                ? "PROTECTION_EXPIRED"
                : "PROTECTION_RESIZED",
            fromStatus,
            protection.getStatus(),
            null,
            null);
        excess = excess.subtract(removed);
      }
    }
  }

  private void resizeQuantities(
      OrderEntity protection,
      BigDecimal oldBase,
      BigDecimal nextBase
  ) {
    BigDecimal original = firstNonNull(
        protection.getOriginalQuantity(), protection.getQuantity(), oldBase);
    BigDecimal nextOriginal = nextBase.compareTo(BigDecimal.ZERO) == 0
        ? BigDecimal.ZERO
        : original.multiply(nextBase).divide(oldBase, 16, RoundingMode.HALF_UP)
            .stripTrailingZeros();
    protection.setQuantity(nextOriginal);
    protection.setOriginalQuantity(nextOriginal);
    protection.setBaseQuantity(nextBase);
    protection.setLots(nextBase);
    protection.setRemainingQuantity(nextBase);
  }

  private void validateQuantityBudget(
      List<OrderEntity> protections,
      UUID excludedOrderId,
      ProtectionType protectionType,
      BigDecimal requestedBase,
      PositionEntity position
  ) {
    BigDecimal existing = protections.stream()
        .filter(order -> protectionType == order.getProtectionType())
        .filter(order -> excludedOrderId == null || !excludedOrderId.equals(order.getId()))
        .map(this::canonicalQuantity)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal capacity = position.getLots() == null
        ? BigDecimal.ZERO
        : position.getLots().abs();
    if (existing.add(requestedBase).compareTo(capacity) > 0) {
      throw new BusinessException(
          ErrorCode.PROTECTION_QUANTITY_EXCEEDED,
          "Protection quantity exceeds the bound position");
    }
  }

  private List<OrderEntity> boundProtections(List<OrderEntity> orders, UUID positionId) {
    return safeList(orders).stream()
        .filter(order -> order.getProtectionType() != null)
        .filter(order -> positionId.equals(order.getParentPositionId()))
        .toList();
  }

  private PositionEntity lockPosition(UUID accountId, UUID positionId, String symbol) {
    return safeList(positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId)).stream()
        .filter(position -> positionId.equals(position.getId()))
        .filter(position -> symbol.equals(position.getSymbol()))
        .findFirst()
        .orElseThrow(ProtectionOrderService::positionNotFound);
  }

  private TradingAccountEntity lockAccount(UUID userId, UUID accountId) {
    return accountRepository.findByIdAndUserIdForUpdate(accountId, userId)
        .orElseThrow(ProtectionOrderService::accountNotFound);
  }

  private AccountSymbolSettingEntity lockSetting(UUID accountId, String symbol) {
    return settingRepository.findByAccountIdAndSymbolForUpdate(accountId, symbol)
        .orElseThrow(() -> new BusinessException(
            "INVALID_INSTRUMENT_RULES",
            "Locked Perpetual symbol setting is required"));
  }

  private OrderEntity requireProtectionSnapshot(UUID userId, UUID protectionOrderId) {
    OrderEntity protection = orderRepository.findByUserIdAndId(userId, protectionOrderId)
        .orElseThrow(ProtectionOrderService::protectionNotFound);
    if (protection.getProtectionType() == null || protection.getParentPositionId() == null) {
      throw protectionNotFound();
    }
    return protection;
  }

  private OrderResponse replayOrConflict(
      OrderEntity existing,
      UUID userId,
      UUID positionId,
      CreateProtectionRequest request
  ) {
    boolean stableFingerprint = existing != null
        && existing.getSystemReason() != null
        && Objects.equals(existing.getSystemReason(), createFingerprint(positionId, request));
    boolean legacyPayload = existing != null
        && existing.getSystemReason() == null
        && existing.getProtectionType() == request.protectionType()
        && existing.getTriggerExecutionType() == request.triggerExecutionType()
        && existing.getQuantityUnit() == request.quantityUnit()
        && sameDecimal(existing.getOriginalQuantity(), request.quantity())
        && sameDecimal(existing.getTriggerPrice(), request.triggerPrice())
        && sameDecimal(existing.getPrice(), request.price());
    if (existing == null
        || !Objects.equals(existing.getUserId(), userId)
        || !Objects.equals(existing.getParentPositionId(), positionId)
        || existing.getProductType() != ProductType.LINEAR_PERP
        || existing.getOrderOrigin() != OrderOrigin.PROTECTIVE
        || !Boolean.TRUE.equals(existing.getReduceOnly())
        || existing.getProtectionType() == null
        || existing.getTriggerPriceType() != TriggerPriceType.MARK_PRICE
        || (!stableFingerprint && !legacyPayload)) {
      throw new BusinessException(
          ErrorCode.DUPLICATE_CLIENT_ORDER_ID,
          "Client order id is already used by another protection payload");
    }
    return orderResponseMapper.toResponse(existing);
  }

  private Optional<OrderEntity> findCreateReplay(
      UUID userId,
      UUID accountId,
      String clientOrderId
  ) {
    return orderRepository.findByUserIdAndIdempotencyKey(userId, clientOrderId)
        .or(() -> orderRepository.findByUserIdAndAccountIdAndClientOrderId(
            userId, accountId, clientOrderId));
  }

  private void requireCancelable(UUID protectionOrderId, OrderEntity protection) {
    if (protection.getStatus() == OrderStatus.PENDING_ACTIVATION) {
      if (orZero(protection.getHoldAmount()).compareTo(BigDecimal.ZERO) != 0) {
        throw new BusinessException(
            ErrorCode.ORDER_HOLD_INVALID,
            "Untriggered protection must not own an order hold");
      }
      return;
    }
    if (protection.getStatus() == OrderStatus.PENDING
        && protection.getOrderType() == OrderType.LIMIT
        && protection.getTriggerExecutionType() == TriggerExecutionType.LIMIT) {
      return;
    }
    throw new BusinessException(
        "PROTECTION_NOT_MODIFIABLE",
        "Protection is not cancelable in its current state: " + protectionOrderId);
  }

  private void validateLockedAuthority(
      TradingAccountEntity account,
      AccountSymbolSettingEntity setting,
      PositionEntity position
  ) {
    if (setting == null
        || !Objects.equals(setting.getAccountId(), account.getId())
        || !Objects.equals(setting.getSymbol(), position.getSymbol())
        || setting.getMarginMode() != position.getMarginMode()
        || setting.getLeverage() == null
        || setting.getLeverage() <= 0
        || position.getLeverage() == null
        || !Objects.equals(setting.getLeverage(), position.getLeverage())
        || account.getPositionMode() != position.getPositionMode()) {
      throw new BusinessException(
          "INVALID_INSTRUMENT_RULES",
          "Locked position authority does not match its symbol setting");
    }
    if (position.getPositionMode() == PositionMode.ONE_WAY) {
      if (position.getPositionSide() != PositionSide.BOTH) {
        throw new BusinessException(
            ErrorCode.INVALID_POSITION_SIDE,
            "ONE_WAY protection requires the BOTH slot");
      }
      return;
    }
    boolean validHedge = position.getPositionMode() == PositionMode.HEDGE
        && ((position.getPositionSide() == PositionSide.LONG
            && position.getSide() == OrderSide.BUY)
            || (position.getPositionSide() == PositionSide.SHORT
            && position.getSide() == OrderSide.SELL));
    if (!validHedge) {
      throw new BusinessException(
          ErrorCode.INVALID_POSITION_SIDE,
          "HEDGE protection direction does not match its position slot");
    }
  }

  private void validateAttachedParent(OrderEntity parent) {
    if (parent == null
        || parent.getId() == null
        || parent.getUserId() == null
        || parent.getAccountId() == null
        || parent.getProductType() != ProductType.LINEAR_PERP
        || parent.getSide() == null
        || parent.getSymbol() == null
        || parent.getSymbol().isBlank()
        || Boolean.TRUE.equals(parent.getReduceOnly())
        || !positive(canonicalQuantity(parent))
        || (parent.getStatus() != OrderStatus.ACCEPTED
            && parent.getStatus() != OrderStatus.PENDING)) {
      throw new BusinessException(
          ErrorCode.PRODUCT_NOT_ALLOWED,
          "Attached protections require an active opening Perpetual parent order");
    }
  }

  private void validateAttachedRequest(
      AttachedProtectionRequest request,
      BigDecimal tickSize
  ) {
    if (request == null
        || request.protectionType() == null
        || !positive(request.triggerPrice())
        || request.triggerExecutionType() == null) {
      throw new BusinessException(
          "PROTECTION_REQUEST_INVALID",
          "Attached protection request is invalid");
    }
    if (request.triggerPriceType() != null
        && request.triggerPriceType() != TriggerPriceType.MARK_PRICE) {
      throw new BusinessException(
          "PROTECTION_PRICE_TYPE_INVALID",
          "Attached protections only support MARK_PRICE");
    }
    validateExecutionPrice(request.triggerExecutionType(), request.price());
    validateTick(request.triggerPrice(), tickSize);
    if (request.triggerExecutionType() == TriggerExecutionType.LIMIT) {
      validateTick(request.price(), tickSize);
    }
  }

  private void releaseProtectionHold(
      TradingAccountEntity account,
      OrderEntity protection,
      String description
  ) {
    BigDecimal hold = orZero(protection.getHoldAmount());
    if (hold.compareTo(BigDecimal.ZERO) > 0) {
      releaseHoldAmount(account, protection, hold, description);
      protection.setHoldAmount(BigDecimal.ZERO);
    }
  }

  private void releaseHoldAmount(
      TradingAccountEntity account,
      OrderEntity protection,
      BigDecimal amount,
      String description
  ) {
    if (ledgerService == null) {
      throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "Protection hold ledger service is unavailable");
    }
    BigDecimal release = money(amount);
    BigDecimal used = orZero(account.getUsedMargin());
    if (release.compareTo(BigDecimal.ZERO) <= 0 || used.compareTo(release) < 0) {
      throw new BusinessException(
          ErrorCode.ORDER_HOLD_INVALID,
          "Protection hold cannot be released from the locked account state");
    }
    account.setUsedMargin(used.subtract(release));
    if (protection.getMarginMode() != com.fxplatform.trading.enums.MarginMode.ISOLATED) {
      account.setFreeMargin(orZero(account.getFreeMargin()).add(release));
    }
    accountRepository.save(account);
    ledgerService.recordOrderRelease(
        account,
        release,
        protection.getId(),
        description);
  }

  private void requirePendingActivation(OrderEntity protection) {
    if (protection.getStatus() != OrderStatus.PENDING_ACTIVATION) {
      throw new BusinessException(
          "PROTECTION_NOT_MODIFIABLE",
          "Only an untriggered protection can be modified here");
    }
  }

  private SymbolEntity requireSymbol(String symbol) {
    SymbolEntity configured = symbolRepository.findBySymbol(symbol)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.SYMBOL_NOT_TRADABLE,
            "Protection symbol is not tradable"));
    if (configured.getProductType() != ProductType.LINEAR_PERP) {
      throw new BusinessException(
          ErrorCode.PRODUCT_NOT_ALLOWED,
          "Protection orders require a Linear Perpetual symbol");
    }
    if (!MarketBundleProducts.isPerpetual(symbol)
        || !Objects.equals(configured.getSymbol(), symbol)) {
      throw new BusinessException(
          ErrorCode.PRODUCT_NOT_ALLOWED,
          "Protection symbol is outside the supported Perpetual universe");
    }
    return configured;
  }

  private ExecutableMarketSnapshot resolveMarket(String symbol) {
    Instant to = Instant.now();
    CandleRequest request = new CandleRequest("1m", to.minus(Duration.ofMinutes(30)), to);
    var bundle = marketBundleResolver.resolvePerp(symbol, request);
    ExecutableMarketSnapshot market = bundle == null ? null : ExecutableMarketSnapshot.from(bundle);
    ExecutableMarketSnapshots.requireComplete(symbol, ProductType.LINEAR_PERP, market);
    return market;
  }

  private void validateCreateRequest(
      UUID userId,
      UUID positionId,
      CreateProtectionRequest request
  ) {
    if (userId == null
        || positionId == null
        || request == null
        || request.protectionType() == null
        || request.quantityUnit() == null
        || request.triggerExecutionType() == null
        || !positive(request.quantity())
        || !positive(request.triggerPrice())
        || request.clientOrderId() == null
        || request.clientOrderId().isBlank()) {
      throw new BusinessException("PROTECTION_REQUEST_INVALID", "Protection request is invalid");
    }
    OrderIdempotencyKeyPolicy.requireUserControlled(request.clientOrderId());
    validateExecutionPrice(request.triggerExecutionType(), request.price());
  }

  private void validateUpdateRequest(
      UUID userId,
      UUID protectionOrderId,
      UpdateProtectionRequest request
  ) {
    if (userId == null
        || protectionOrderId == null
        || request == null
        || request.expectedVersion() == null
        || request.expectedVersion() < 0
        || (request.quantity() == null && request.quantityUnit() != null)
        || (request.quantity() != null && !positive(request.quantity()))
        || (request.triggerPrice() != null && !positive(request.triggerPrice()))) {
      throw new BusinessException("PROTECTION_REQUEST_INVALID", "Protection update is invalid");
    }
  }

  private void validateExecutionPrice(
      TriggerExecutionType executionType,
      BigDecimal price
  ) {
    if (executionType == null) {
      throw new BusinessException("PROTECTION_REQUEST_INVALID", "Execution type is required");
    }
    if (executionType == TriggerExecutionType.MARKET && price != null) {
      throw new BusinessException(
          "PROTECTION_PRICE_INVALID",
          "MARKET protection execution forbids price");
    }
    if (executionType == TriggerExecutionType.LIMIT && !positive(price)) {
      throw new BusinessException(
          ErrorCode.ORDER_PRICE_REQUIRED,
          "LIMIT protection execution requires a positive price");
    }
  }

  private void validatePosition(PositionEntity position) {
    if (position.getProductType() != ProductType.LINEAR_PERP) {
      throw new BusinessException(
          ErrorCode.PRODUCT_NOT_ALLOWED,
          "Protection orders require a Linear Perpetual position");
    }
    if (position.getStatus() != PositionStatus.OPEN || !positive(position.getLots())) {
      throw positionNotFound();
    }
    closeSide(position);
  }

  private void validateRules(InstrumentRules rules) {
    if (rules == null
        || !rules.exists()
        || !rules.enabled()
        || !rules.tradable()
        || !rules.orderEnabled()) {
      throw new BusinessException(ErrorCode.SYMBOL_NOT_TRADABLE, "Symbol is not tradable");
    }
    if (rules.productType() != ProductType.LINEAR_PERP) {
      throw new BusinessException(
          ErrorCode.PRODUCT_NOT_ALLOWED,
          "Protection orders require Linear Perpetual rules");
    }
  }

  private void validateCanonicalQuantity(
      InstrumentRules rules,
      QuantityConversionService.Conversion conversion,
      BigDecimal referencePrice
  ) {
    BigDecimal base = conversion.baseQuantity();
    if (positive(rules.minQty()) && base.compareTo(rules.minQty()) < 0) {
      throw new BusinessException("QUANTITY_TOO_SMALL", "Quantity is below minimum");
    }
    if (positive(rules.maxQty()) && base.compareTo(rules.maxQty()) > 0) {
      throw new BusinessException("QUANTITY_TOO_LARGE", "Quantity is above maximum");
    }
    validateNotional(rules, base, referencePrice);
  }

  private void validateNotional(
      InstrumentRules rules,
      BigDecimal baseQuantity,
      BigDecimal price
  ) {
    BigDecimal notional = baseQuantity.multiply(price);
    if (positive(rules.minNotional()) && notional.compareTo(rules.minNotional()) < 0) {
      throw new BusinessException("ORDER_NOTIONAL_TOO_SMALL", "Order notional is below minimum");
    }
    if (positive(rules.maxNotional()) && notional.compareTo(rules.maxNotional()) > 0) {
      throw new BusinessException("ORDER_NOTIONAL_TOO_LARGE", "Order notional is above maximum");
    }
  }

  private void validateDirection(
      PositionEntity position,
      ProtectionType protectionType,
      BigDecimal triggerPrice,
      BigDecimal authorityMark
  ) {
    if (protectionType == null || !positive(triggerPrice) || !positive(authorityMark)) {
      throw new BusinessException(
          "PROTECTION_DIRECTION_INVALID",
          "Protection direction cannot be evaluated");
    }
    boolean longPosition = position.getSide() == OrderSide.BUY;
    int comparison = triggerPrice.compareTo(authorityMark);
    boolean valid = protectionType == ProtectionType.TAKE_PROFIT
        ? longPosition ? comparison > 0 : comparison < 0
        : longPosition ? comparison < 0 : comparison > 0;
    if (!valid) {
      throw new BusinessException(
          "PROTECTION_DIRECTION_INVALID",
          "Protection trigger is on the wrong side of authority mark");
    }
  }

  private void validateTick(BigDecimal value, BigDecimal tickSize) {
    if (positive(tickSize) && value.remainder(tickSize).compareTo(BigDecimal.ZERO) != 0) {
      throw new BusinessException("PRICE_TICK_MISMATCH", "Price does not match tick size");
    }
  }

  private OrderSide closeSide(PositionEntity position) {
    if (position.getSide() == OrderSide.BUY) {
      return OrderSide.SELL;
    }
    if (position.getSide() == OrderSide.SELL) {
      return OrderSide.BUY;
    }
    throw new BusinessException("POSITION_DIRECTION_INVALID", "Position side is invalid");
  }

  private OrderSide opposite(OrderSide side) {
    if (side == OrderSide.BUY) {
      return OrderSide.SELL;
    }
    if (side == OrderSide.SELL) {
      return OrderSide.BUY;
    }
    throw new BusinessException("INVALID_ORDER_SIDE", "Order side is required");
  }

  private BigDecimal canonicalQuantity(OrderEntity order) {
    return firstNonNull(order.getBaseQuantity(), order.getLots(), BigDecimal.ZERO).abs();
  }

  private BigDecimal canonicalRemaining(OrderEntity order) {
    if (positive(order.getRemainingQuantity())) {
      return order.getRemainingQuantity().abs();
    }
    return canonicalQuantity(order);
  }

  private boolean activeProtectionStatus(OrderEntity order) {
    return order.getStatus() == OrderStatus.PENDING_ACTIVATION
        || order.getStatus() == OrderStatus.PENDING
        || order.getStatus() == OrderStatus.WORKING
        || order.getStatus() == OrderStatus.CANCEL_PENDING;
  }

  private static BigDecimal firstNonNull(BigDecimal... values) {
    for (BigDecimal value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private static boolean positive(BigDecimal value) {
    return value != null && value.compareTo(BigDecimal.ZERO) > 0;
  }

  private static BigDecimal orZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static BigDecimal money(BigDecimal value) {
    return orZero(value).setScale(8, RoundingMode.HALF_UP);
  }

  private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
    if (left == null || right == null) {
      return left == null && right == null;
    }
    return left.compareTo(right) == 0;
  }

  private static String createFingerprint(
      UUID positionId,
      CreateProtectionRequest request
  ) {
    String payload = positionId
        + "|" + request.protectionType().name()
        + "|" + decimalFingerprint(request.quantity())
        + "|" + request.quantityUnit().name()
        + "|" + decimalFingerprint(request.triggerPrice())
        + "|" + request.triggerExecutionType().name()
        + "|" + decimalFingerprint(request.price());
    try {
      String digest = Base64.getUrlEncoder().withoutPadding().encodeToString(
          MessageDigest.getInstance("SHA-256")
              .digest(payload.getBytes(StandardCharsets.UTF_8)));
      return OrderSystemReasonPolicy.PROTECTION_IDEMPOTENCY_PREFIX + digest;
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String decimalFingerprint(BigDecimal value) {
    return value == null ? "-" : value.stripTrailingZeros().toPlainString();
  }

  private static long version(OrderEntity order) {
    return order.getVersion() == null ? 0L : order.getVersion();
  }

  private static <T> List<T> safeList(List<T> values) {
    return values == null ? List.of() : values;
  }

  private static void requireWrite(int changed) {
    if (changed != 1) {
      throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "Protection carrier write did not affect exactly one row");
    }
  }

  private static BusinessException staleMarket() {
    return new BusinessException(
        ErrorCode.MARKET_DATA_STALE,
        "Protection preparation became stale while locks were acquired");
  }

  private static BusinessException accountNotFound() {
    return new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found");
  }

  private static BusinessException positionNotFound() {
    return new BusinessException("POSITION_NOT_FOUND", "Position not found");
  }

  private static BusinessException protectionNotFound() {
    return new BusinessException("PROTECTION_NOT_FOUND", "Protection order not found");
  }

  private static BusinessException versionConflict() {
    return new BusinessException(
        ErrorCode.PROTECTION_VERSION_CONFLICT,
        "Protection version is stale");
  }

  private record MutationContext(
      TradingAccountEntity account,
      PositionEntity position,
      SymbolEntity symbol
  ) {
  }

  private record PreparedProtection(
      ProtectionType protectionType,
      QuantityConversionService.Conversion conversion,
      BigDecimal triggerPrice,
      TriggerExecutionType triggerExecutionType,
      BigDecimal price,
      InstrumentRules rules,
      ExecutableMarketSnapshot market
  ) {
  }
}
