package com.fxplatform.trading.service;

import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.ExecutableMarketSnapshots;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.execution.FullFillExecutionPath;
import com.fxplatform.execution.FullFillRequest;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketBundleProducts;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import com.fxplatform.trading.dto.request.ClosePositionRequest;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Canonical single-position close orchestrator shared by user and system close callers. */
@Service
public class SystemCloseOrderService {

  private static final int MAX_MARKET_ATTEMPTS = 2;
  private static final int MONEY_SCALE = 8;

  private final OrderRepository orderRepository;
  private final PositionRepository positionRepository;
  private final TradingAccountRepository accountRepository;
  private final AccountSymbolSettingRepository settingRepository;
  private final SymbolRepository symbolRepository;
  private final MarketBundleResolver marketBundleResolver;
  private final QuantityConversionService quantityConversionService;
  private final InstrumentRulesEngine instrumentRulesEngine;
  private final PerpetualOrderRiskService perpetualOrderRiskService;
  private final PerpetualAccountRiskSnapshotService accountRiskSnapshotService;
  private final ProtectionOrderService protectionOrderService;
  private final FullFillCoordinator fullFillCoordinator;
  private final OrderFillService orderFillService;
  private final OrderEntityFactory orderEntityFactory;
  private final OrderEventService orderEventService;
  private final LedgerService ledgerService;
  private final DemoExecutionGuard demoExecutionGuard;
  private final TradingTransactionExecutor transactionExecutor;
  private AuditLogService auditLogService;
  private LiquidationSettlementService liquidationSettlementService;

  public SystemCloseOrderService(
      OrderRepository orderRepository,
      PositionRepository positionRepository,
      TradingAccountRepository accountRepository,
      AccountSymbolSettingRepository settingRepository,
      SymbolRepository symbolRepository,
      MarketBundleResolver marketBundleResolver,
      QuantityConversionService quantityConversionService,
      InstrumentRulesEngine instrumentRulesEngine,
      PerpetualOrderRiskService perpetualOrderRiskService,
      PerpetualAccountRiskSnapshotService accountRiskSnapshotService,
      ProtectionOrderService protectionOrderService,
      FullFillCoordinator fullFillCoordinator,
      OrderFillService orderFillService,
      OrderEntityFactory orderEntityFactory,
      OrderEventService orderEventService,
      LedgerService ledgerService,
      DemoExecutionGuard demoExecutionGuard,
      TradingTransactionExecutor transactionExecutor
  ) {
    this.orderRepository = orderRepository;
    this.positionRepository = positionRepository;
    this.accountRepository = accountRepository;
    this.settingRepository = settingRepository;
    this.symbolRepository = symbolRepository;
    this.marketBundleResolver = marketBundleResolver;
    this.quantityConversionService = quantityConversionService;
    this.instrumentRulesEngine = instrumentRulesEngine;
    this.perpetualOrderRiskService = perpetualOrderRiskService;
    this.accountRiskSnapshotService = accountRiskSnapshotService;
    this.protectionOrderService = protectionOrderService;
    this.fullFillCoordinator = fullFillCoordinator;
    this.orderFillService = orderFillService;
    this.orderEntityFactory = orderEntityFactory;
    this.orderEventService = orderEventService;
    this.ledgerService = ledgerService;
    this.demoExecutionGuard = demoExecutionGuard;
    this.transactionExecutor = transactionExecutor;
  }

  @Autowired(required = false)
  public void setAuditLogService(AuditLogService auditLogService) {
    this.auditLogService = auditLogService;
  }

  @Autowired(required = false)
  public void setLiquidationSettlementService(
      LiquidationSettlementService liquidationSettlementService
  ) {
    this.liquidationSettlementService = liquidationSettlementService;
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public CloseResult closeUser(
      UUID userId,
      UUID accountId,
      UUID positionId,
      ClosePositionRequest request
  ) {
    requireId(userId, "User id is required");
    requireId(accountId, "Account id is required");
    requireId(positionId, "Position id is required");
    requireRequest(request);
    String key = normalizedKey(request.clientOrderId());
    OrderIdempotencyKeyPolicy.requireUserControlled(key);
    return execute(new CloseIntent(
        userId,
        accountId,
        positionId,
        request,
        OrderOrigin.USER,
        null,
        key,
        false,
        true,
        null));
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public CloseResult closeUserWhole(
      UUID userId,
      UUID accountId,
      UUID positionId,
      String idempotencyKey
  ) {
    requireId(userId, "User id is required");
    requireId(accountId, "Account id is required");
    requireId(positionId, "Position id is required");
    String callerRequestId = normalizedKey(idempotencyKey);
    String key = OrderIdempotencyKeyPolicy.systemClose(
        accountId, positionId, OrderOrigin.USER, callerRequestId);
    return execute(new CloseIntent(
        userId,
        accountId,
        positionId,
        null,
        OrderOrigin.USER,
        null,
        key,
        true,
        true,
        null));
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public CloseResult closeWhole(
      UUID accountId,
      UUID positionId,
      OrderOrigin origin,
      String systemReason,
      String idempotencyKey
  ) {
    return closeWhole(
        accountId, positionId, origin, systemReason, idempotencyKey, null);
  }

  /** System close with a guard executed after trading locks and before any item mutation. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public CloseResult closeWhole(
      UUID accountId,
      UUID positionId,
      OrderOrigin origin,
      String systemReason,
      String idempotencyKey,
      Runnable lockedMutationGuard
  ) {
    requireId(accountId, "Account id is required");
    requireId(positionId, "Position id is required");
    if (origin == null || origin == OrderOrigin.USER) {
      throw new BusinessException(
          ErrorCode.PRODUCT_NOT_ALLOWED,
          "System close requires a non-user order origin");
    }
    if (systemReason == null || systemReason.isBlank()) {
      throw new BusinessException("SYSTEM_REASON_REQUIRED", "System close reason is required");
    }
    String callerRequestId = normalizedKey(idempotencyKey);
    String key = OrderIdempotencyKeyPolicy.systemClose(
        accountId, positionId, origin, callerRequestId);
    return execute(new CloseIntent(
        null,
        accountId,
        positionId,
        null,
        origin,
        systemReason.trim(),
        key,
        true,
        false,
        lockedMutationGuard));
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public CloseResult executeProtection(
      UUID protectionOrderId,
      ExecutableMarketSnapshot snapshot
  ) {
    requireId(protectionOrderId, "Protection order id is required");
    OrderEntity carrier = orderRepository.selectById(protectionOrderId);
    requireProtectionCarrier(carrier, protectionOrderId);
    ReadContext context = readProtectionContext(carrier);
    requireProtectionScope(carrier, context.account(), context.position());
    demoExecutionGuard.requireDemo(
        context.account(), ProductType.LINEAR_PERP, carrier.getSymbol());
    if (carrier.getStatus() != OrderStatus.PENDING_ACTIVATION) {
      requireProtectionReplayStatus(carrier);
      return new CloseResult(carrier, context.position(), context.account(), true);
    }

    requireOpenLinearPosition(context.position());
    requireProtectionSnapshot(carrier, snapshot);
    if (!protectionOrderService.isTriggered(carrier, snapshot.mark())) {
      throw protectionNotExecutable("Protection trigger condition is not satisfied");
    }
    BigDecimal baseQuantity = protectionBaseQuantity(carrier);
    requireNotOverClose(baseQuantity, context.position().getLots());
    SymbolEntity symbol = symbolRepository.findBySymbol(carrier.getSymbol())
        .orElseThrow(() -> new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found"));
    requireLinearSymbol(symbol, carrier.getSymbol());
    InstrumentRules rules = instrumentRulesEngine.rules(symbol);
    requireUsableRules(rules);
    OrderType executionType = protectionExecutionOrderType(carrier);
    CreateOrderRequest validationIntent = protectionExecutionRequest(
        carrier,
        executionType,
        baseQuantity,
        carrier.getLeverage(),
        carrier.getMarginMode(),
        carrier.getPositionSide());
    instrumentRulesEngine.validateCanonicalOrder(
        validationIntent,
        symbol,
        baseQuantity,
        executionType == OrderType.LIMIT ? carrier.getPrice() : snapshot.mark());
    fullFillCoordinator.requireFresh(snapshot);
    return transactionExecutor.execute(() -> persistProtection(new PreparedProtectionExecution(
        carrier,
        snapshot,
        symbol.getMaintenanceMarginRate())));
  }

  private CloseResult persistProtection(PreparedProtectionExecution prepared) {
    OrderEntity preflight = prepared.carrier();
    TradingAccountEntity account = accountRepository.findByIdForUpdate(
            preflight.getAccountId())
        .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));
    demoExecutionGuard.requireDemo(account, ProductType.LINEAR_PERP, preflight.getSymbol());
    AccountSymbolSettingEntity setting = settingRepository
        .findByAccountIdAndSymbolForUpdate(account.getId(), preflight.getSymbol())
        .orElseThrow(() -> new BusinessException(
            "INVALID_INSTRUMENT_RULES",
            "Locked Perpetual symbol setting is required"));
    accountRiskSnapshotService.requireCurrentLeverageWithinLimit(setting);
    List<PositionEntity> lockedPositions = positionRepository.findOpenLinearPerpBySymbolForUpdate(
        account.getId(), preflight.getSymbol());
    List<OrderEntity> activeOrders = orderRepository.findActiveLinearPerpBySymbolForUpdate(
        account.getId(), preflight.getSymbol());
    OrderEntity carrier = orderRepository.findByIdForUpdate(preflight.getId())
        .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Protection order not found"));
    requireProtectionCarrier(carrier, preflight.getId());

    PositionEntity current = positionRepository.selectById(carrier.getParentPositionId());
    if (carrier.getStatus() != OrderStatus.PENDING_ACTIVATION) {
      requireProtectionScope(carrier, account, current);
      requireProtectionReplayStatus(carrier);
      return new CloseResult(carrier, current, account, true);
    }

    PositionEntity target = lockedPositions.stream()
        .filter(position -> carrier.getParentPositionId().equals(position.getId()))
        .findFirst()
        .orElseThrow(SystemCloseOrderService::positionNotFound);
    requireLockedPosition(account, setting, target);
    requireProtectionScope(carrier, account, target);
    if (!protectionOrderService.isTriggered(carrier, prepared.snapshot().mark())) {
      throw protectionNotExecutable("Protection trigger condition changed before execution");
    }
    BigDecimal baseQuantity = protectionBaseQuantity(carrier);
    requireNotOverClose(baseQuantity, target.getLots());
    OrderType executionType = protectionExecutionOrderType(carrier);
    BigDecimal limitPrice = executionType == OrderType.LIMIT ? carrier.getPrice() : null;
    PerpetualOrderRiskService.OrderRisk risk = perpetualOrderRiskService.evaluate(
        account.getPositionMode(),
        setting,
        lockedPositions,
        carrier.getSide(),
        target.getPositionSide(),
        true,
        executionType,
        baseQuantity,
        limitPrice,
        prepared.snapshot(),
        prepared.maintenanceMarginRate());
    requirePureClose(risk, baseQuantity);
    if (risk.marginMode() == MarginMode.ISOLATED) {
      PerpetualIsolatedCloseHoldValidator.validate(target, risk, activeOrders);
    }

    FullFillExecutionPath path = executionType == OrderType.MARKET
        ? FullFillExecutionPath.MARKET
        : isLimitMarketable(carrier.getSide(), limitPrice, prepared.snapshot())
            ? FullFillExecutionPath.IMMEDIATE_LIMIT
            : null;
    FullFillResult fullFill = null;
    if (path != null) {
      fullFill = fullFillCoordinator.execute(
          new FullFillRequest(
              protectionExecutionRequest(
                  carrier,
                  executionType,
                  baseQuantity,
                  risk.leverage(),
                  risk.marginMode(),
                  risk.positionSide()),
              carrier.getSymbol(),
              ProductType.LINEAR_PERP,
              carrier.getSide(),
              path,
              baseQuantity,
              path == FullFillExecutionPath.IMMEDIATE_LIMIT ? limitPrice : null),
          prepared.snapshot());
      fullFillCoordinator.requireFresh(fullFill);
    } else {
      fullFillCoordinator.requireFresh(prepared.snapshot());
    }

    carrier.setOrderType(executionType);
    carrier.setStatus(path == null ? OrderStatus.PENDING : OrderStatus.ACCEPTED);
    carrier.setTimeInForce(TimeInForce.GTC);
    carrier.setPositionMode(risk.positionMode());
    carrier.setPositionSide(risk.positionSide());
    carrier.setMarginMode(risk.marginMode());
    carrier.setLeverage(risk.leverage());
    carrier.setRemainingQuantity(baseQuantity);
    carrier.setHoldAmount(risk.holdAmount());
    carrier.setHoldCurrency(risk.holdCurrency());
    reserveHold(account, carrier, risk);
    orderRepository.save(carrier);
    ledgerService.recordOrderHold(
        account,
        risk.holdAmount(),
        carrier.getId(),
        "Perpetual protection order margin reserved");

    if (fullFill == null) {
      orderEventService.record(
          carrier.getId(),
          "PROTECTION_TRIGGERED",
          OrderStatus.PENDING_ACTIVATION,
          OrderStatus.PENDING,
          null,
          "Protection LIMIT triggered and is resting");
      return new CloseResult(carrier, target, account, false);
    }

    orderEventService.record(
        carrier.getId(),
        "PROTECTION_TRIGGERED",
        OrderStatus.PENDING_ACTIVATION,
        OrderStatus.ACCEPTED,
        null,
        "Protection triggered for immediate execution");
    orderFillService.fillPerpetual(
        carrier,
        account,
        fullFill,
        prepared.snapshot().mark(),
        risk.leverage(),
        "Protection order margin");
    orderEventService.record(
        carrier.getId(),
        "ORDER_FILLED",
        OrderStatus.ACCEPTED,
        OrderStatus.FILLED,
        null,
        "Protection order filled");
    PositionEntity resultPosition = positionRepository.selectById(target.getId());
    return new CloseResult(
        carrier,
        resultPosition == null ? target : resultPosition,
        account,
        false);
  }

  private CloseResult execute(CloseIntent intent) {
    BusinessException lastStale = null;
    for (int attempt = 0; attempt < MAX_MARKET_ATTEMPTS; attempt++) {
      try {
        ReadContext context = readContext(intent);
        Optional<CloseResult> replay = findReplay(intent, context);
        if (replay.isPresent()) {
          return replay.get();
        }
        requireOpenLinearPosition(context.position());
        requireCloseGuard(intent, context.account(), context.position().getSymbol());

        SymbolEntity symbol = symbolRepository.findBySymbol(context.position().getSymbol())
            .orElseThrow(() -> new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found"));
        requireLinearSymbol(symbol, context.position().getSymbol());
        InstrumentRules rules = instrumentRulesEngine.rules(symbol);
        requireUsableRules(rules);
        ExecutableMarketSnapshot snapshot = resolveSnapshot(context.position().getSymbol());
        ClosePositionRequest effectiveRequest = effectiveRequest(intent, context.position());
        QuantityConversionService.Conversion conversion = quantityConversionService.convertPerpetual(
            effectiveRequest.quantityUnit(),
            effectiveRequest.quantity(),
            rules.stepSize(),
            symbol.getContractSize(),
            symbol.getContractMultiplier(),
            snapshot.mark(),
            rules.minNotional());
        requireNotOverClose(conversion.baseQuantity(), context.position().getLots());
        CreateOrderRequest validationIntent = closeRequest(
            context.account().getId(),
            context.position(),
            opposite(context.position().getSide()),
            effectiveRequest,
            conversion.baseQuantity(),
            null);
        instrumentRulesEngine.validateCanonicalOrder(
            validationIntent,
            symbol,
            conversion.baseQuantity(),
            snapshot.mark());

        PreparedClose prepared = new PreparedClose(
            context.account(),
            context.position(),
            effectiveRequest,
            conversion,
            snapshot,
            symbol.getMaintenanceMarginRate(),
            symbol.getLiquidationFeeRate());
        try {
          return transactionExecutor.execute(() -> persist(intent, prepared));
        } catch (DataIntegrityViolationException exception) {
          Optional<CloseResult> committed = findReplay(intent, readContext(intent));
          if (committed.isPresent()) {
            return committed.get();
          }
          throw exception;
        }
      } catch (BusinessException exception) {
        if (!ErrorCode.MARKET_DATA_STALE.equals(exception.getCode())
            || attempt + 1 >= MAX_MARKET_ATTEMPTS) {
          throw exception;
        }
        lastStale = exception;
      }
    }
    throw new BusinessException(
        ErrorCode.MARKET_DATA_STALE,
        lastStale == null ? "No fresh executable Perpetual snapshot" : lastStale.getMessage());
  }

  private CloseResult persist(CloseIntent intent, PreparedClose prepared) {
    TradingAccountEntity account = intent.userScoped()
        ? accountRepository.findByIdAndUserIdForUpdate(intent.accountId(), intent.userId())
            .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"))
        : accountRepository.findByIdForUpdate(intent.accountId())
            .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));
    requireCloseGuard(intent, account, prepared.position().getSymbol());
    AccountSymbolSettingEntity setting = settingRepository
        .findByAccountIdAndSymbolForUpdate(account.getId(), prepared.position().getSymbol())
        .orElseThrow(() -> new BusinessException(
            "INVALID_INSTRUMENT_RULES",
            "Locked Perpetual symbol setting is required"));
    accountRiskSnapshotService.requireCurrentLeverageWithinLimit(setting);
    List<PositionEntity> lockedPositions = positionRepository.findOpenLinearPerpBySymbolForUpdate(
        account.getId(), prepared.position().getSymbol());
    List<OrderEntity> activeOrders = orderRepository.findActiveLinearPerpBySymbolForUpdate(
        account.getId(), prepared.position().getSymbol());
    PositionEntity lockedSnapshot = positionRepository.selectById(intent.positionId());
    if (lockedSnapshot == null
        || !Objects.equals(lockedSnapshot.getAccountId(), account.getId())) {
      throw positionNotFound();
    }
    Optional<CloseResult> replay = findReplay(
        intent, new ReadContext(account, lockedSnapshot));
    if (replay.isPresent()) {
      return replay.get();
    }
    if (intent.lockedMutationGuard() != null) {
      intent.lockedMutationGuard().run();
    }
    PositionEntity target = lockedPositions.stream()
        .filter(position -> intent.positionId().equals(position.getId()))
        .findFirst()
        .orElseThrow(SystemCloseOrderService::positionNotFound);
    requireLockedPosition(account, setting, target);
    if (intent.whole()
        && abs(target.getLots()).compareTo(prepared.conversion().baseQuantity()) != 0) {
      throw new BusinessException(
          ErrorCode.MARKET_DATA_STALE,
          "Whole-close quantity changed while the market snapshot was prepared");
    }
    requireNotOverClose(prepared.conversion().baseQuantity(), target.getLots());

    OrderSide closeSide = opposite(target.getSide());
    boolean liquidation = intent.origin() == OrderOrigin.LIQUIDATION;
    PerpetualOrderRiskService.OrderRisk risk = liquidation
        ? perpetualOrderRiskService.evaluateLiquidationClose(
            account.getPositionMode(),
            setting,
            lockedPositions,
            closeSide,
            target.getPositionSide(),
            prepared.conversion().baseQuantity(),
            prepared.snapshot(),
            prepared.maintenanceMarginRate())
        : perpetualOrderRiskService.evaluate(
            account.getPositionMode(),
            setting,
            lockedPositions,
            closeSide,
            target.getPositionSide(),
            true,
            OrderType.MARKET,
            prepared.conversion().baseQuantity(),
            null,
            prepared.snapshot(),
            prepared.maintenanceMarginRate());
    requirePureClose(risk, prepared.conversion().baseQuantity());
    if (!liquidation && risk.marginMode() == MarginMode.ISOLATED) {
      PerpetualIsolatedCloseHoldValidator.validate(target, risk, activeOrders);
    }

    OrderEntity order = closeOrder(
        intent, account.getUserId(), target, prepared.request(), risk);
    FullFillResult fullFill = fullFillCoordinator.execute(
        new FullFillRequest(
            closeRequest(
                account.getId(),
                target,
                closeSide,
                prepared.request(),
                prepared.conversion().baseQuantity(),
                risk),
            target.getSymbol(),
            ProductType.LINEAR_PERP,
            closeSide,
            FullFillExecutionPath.MARKET,
            prepared.conversion().baseQuantity(),
            null),
        prepared.snapshot());
    fullFillCoordinator.requireFresh(fullFill);

    BigDecimal balanceBeforeFill = money(account.getBalance());
    BigDecimal isolatedMarginBeforeFill = money(target.getMarginHeld()).max(BigDecimal.ZERO);
    if (!liquidation) {
      reserveHold(account, order, risk);
    }
    orderRepository.save(order);
    if (!liquidation) {
      ledgerService.recordOrderHold(
          account,
          risk.holdAmount(),
          order.getId(),
          "Perpetual close order margin reserved");
    }
    orderFillService.fillPerpetual(
        order,
        account,
        fullFill,
        prepared.snapshot().mark(),
        risk.leverage(),
        "System close position margin");
    BigDecimal contractualLiquidationFee = contractualLiquidationFee(prepared, order);
    if (liquidation
        && risk.marginMode() == MarginMode.CROSS
        && liquidationSettlementService != null) {
      liquidationSettlementService.recordCrossChargeLocked(
          account.getId(),
          target.getId(),
          order.getId(),
          contractualLiquidationFee);
    } else {
      settleLiquidationDeficit(
          intent,
          account,
          target,
          order,
          risk.marginMode(),
          balanceBeforeFill,
          isolatedMarginBeforeFill,
          contractualLiquidationFee);
    }
    orderEventService.record(
        order.getId(),
        "ORDER_FILLED",
        OrderStatus.ACCEPTED,
        OrderStatus.FILLED,
        null,
        "Perpetual position closed");
    PositionEntity resultPosition = positionRepository.selectById(target.getId());
    return new CloseResult(
        order,
        resultPosition == null ? target : resultPosition,
        account,
        false);
  }

  private ReadContext readProtectionContext(OrderEntity carrier) {
    TradingAccountEntity account = accountRepository.selectById(carrier.getAccountId());
    if (account == null || account.getUserId() == null) {
      throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found");
    }
    PositionEntity position = positionRepository.selectById(carrier.getParentPositionId());
    if (position == null) {
      throw positionNotFound();
    }
    return new ReadContext(account, position);
  }

  private void requireProtectionCarrier(OrderEntity carrier, UUID expectedId) {
    if (carrier == null
        || !Objects.equals(carrier.getId(), expectedId)
        || carrier.getUserId() == null
        || carrier.getAccountId() == null
        || carrier.getSymbol() == null
        || carrier.getSymbol().isBlank()
        || carrier.getProductType() != ProductType.LINEAR_PERP
        || carrier.getStatus() == null
        || carrier.getSide() == null
        || carrier.getPositionMode() == null
        || carrier.getPositionSide() == null
        || carrier.getMarginMode() == null
        || !Boolean.TRUE.equals(carrier.getReduceOnly())
        || carrier.getOrderOrigin() != OrderOrigin.PROTECTIVE
        || carrier.getProtectionType() == null
        || carrier.getParentPositionId() == null
        || carrier.getTriggerPriceType() != TriggerPriceType.MARK_PRICE
        || carrier.getTriggerExecutionType() == null
        || carrier.getTriggerPrice() == null
        || carrier.getTriggerPrice().compareTo(BigDecimal.ZERO) <= 0
        || carrier.getClientOrderId() == null
        || carrier.getClientOrderId().isBlank()
        || carrier.getIdempotencyKey() == null
        || carrier.getIdempotencyKey().isBlank()) {
      throw new BusinessException("ORDER_NOT_FOUND", "Bound protection carrier not found");
    }
    if (carrier.getStatus() == OrderStatus.PENDING_ACTIVATION
        && carrier.getOrderType() != OrderType.STOP_MARKET) {
      throw protectionNotExecutable("Untriggered protection must use the carrier order type");
    }
    if (carrier.getTriggerExecutionType() == TriggerExecutionType.MARKET) {
      if (carrier.getPrice() != null
          || (carrier.getOrderType() != OrderType.STOP_MARKET
          && carrier.getOrderType() != OrderType.MARKET)) {
        throw protectionNotExecutable("MARKET protection carrier fields are inconsistent");
      }
      return;
    }
    if (carrier.getPrice() == null
        || carrier.getPrice().compareTo(BigDecimal.ZERO) <= 0
        || (carrier.getOrderType() != OrderType.STOP_MARKET
        && carrier.getOrderType() != OrderType.LIMIT)) {
      throw protectionNotExecutable("LIMIT protection carrier fields are inconsistent");
    }
  }

  private void requireProtectionScope(
      OrderEntity carrier,
      TradingAccountEntity account,
      PositionEntity position
  ) {
    if (account == null
        || position == null
        || !Objects.equals(carrier.getUserId(), account.getUserId())
        || !Objects.equals(carrier.getAccountId(), account.getId())
        || !Objects.equals(position.getId(), carrier.getParentPositionId())
        || !Objects.equals(position.getAccountId(), account.getId())
        || !Objects.equals(position.getSymbol(), carrier.getSymbol())
        || position.getProductType() != ProductType.LINEAR_PERP
        || position.getPositionMode() != carrier.getPositionMode()
        || position.getPositionSide() != carrier.getPositionSide()
        || position.getMarginMode() != carrier.getMarginMode()
        || account.getPositionMode() != carrier.getPositionMode()
        || carrier.getSide() != opposite(position.getSide())) {
      throw new BusinessException("ORDER_NOT_FOUND", "Protection carrier lock scope changed");
    }
  }

  private void requireProtectionReplayStatus(OrderEntity carrier) {
    OrderStatus status = carrier.getStatus();
    if (status != OrderStatus.PENDING
        && status != OrderStatus.WORKING
        && status != OrderStatus.ACCEPTED
        && status != OrderStatus.FILLED) {
      throw protectionNotExecutable(
          "Protection carrier is no longer eligible for trigger execution");
    }
    if ((status == OrderStatus.PENDING || status == OrderStatus.WORKING)
        && carrier.getOrderType() != OrderType.LIMIT) {
      throw protectionNotExecutable(
          "Only a triggered LIMIT protection may remain pending or working");
    }
  }

  private void requireProtectionSnapshot(
      OrderEntity carrier,
      ExecutableMarketSnapshot snapshot
  ) {
    ExecutableMarketSnapshots.requireComplete(
        carrier.getSymbol(), ProductType.LINEAR_PERP, snapshot);
  }

  private OrderType protectionExecutionOrderType(OrderEntity carrier) {
    return carrier.getTriggerExecutionType() == TriggerExecutionType.MARKET
        ? OrderType.MARKET
        : OrderType.LIMIT;
  }

  private BigDecimal protectionBaseQuantity(OrderEntity carrier) {
    BigDecimal quantity = carrier.getBaseQuantity() != null
        ? carrier.getBaseQuantity()
        : carrier.getLots();
    if (!positive(quantity)) {
      throw protectionNotExecutable("Protection base quantity must be positive");
    }
    if (carrier.getRemainingQuantity() != null
        && carrier.getRemainingQuantity().compareTo(quantity) != 0) {
      throw protectionNotExecutable("Protection remaining quantity is inconsistent");
    }
    return quantity;
  }

  private CreateOrderRequest protectionExecutionRequest(
      OrderEntity carrier,
      OrderType executionType,
      BigDecimal baseQuantity,
      Integer leverage,
      MarginMode marginMode,
      PositionSide positionSide
  ) {
    BigDecimal price = executionType == OrderType.LIMIT ? carrier.getPrice() : null;
    return new CreateOrderRequest(
        carrier.getAccountId(),
        carrier.getSymbol(),
        carrier.getSide(),
        executionType,
        baseQuantity,
        price,
        null,
        null,
        carrier.getIdempotencyKey(),
        carrier.getClientOrderId(),
        baseQuantity,
        price,
        leverage,
        positionSide,
        QuantityUnit.BASE,
        marginMode,
        null,
        null,
        true,
        List.of());
  }

  private boolean isLimitMarketable(
      OrderSide side,
      BigDecimal price,
      ExecutableMarketSnapshot snapshot
  ) {
    if (side == OrderSide.BUY) {
      return snapshot.ask().compareTo(price) <= 0;
    }
    if (side == OrderSide.SELL) {
      return snapshot.bid().compareTo(price) >= 0;
    }
    throw protectionNotExecutable("Protection close side is required");
  }

  private static boolean positive(BigDecimal value) {
    return value != null && value.compareTo(BigDecimal.ZERO) > 0;
  }

  private static BusinessException protectionNotExecutable(String message) {
    return new BusinessException(ErrorCode.PROTECTION_NOT_EXECUTABLE, message);
  }

  private ReadContext readContext(CloseIntent intent) {
    TradingAccountEntity account = intent.userScoped()
        ? accountRepository.findByIdAndUserId(intent.accountId(), intent.userId())
            .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"))
        : accountRepository.selectById(intent.accountId());
    if (account == null || account.getUserId() == null) {
      throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found");
    }
    PositionEntity position = positionRepository.selectById(intent.positionId());
    if (position == null || !intent.accountId().equals(position.getAccountId())) {
      throw positionNotFound();
    }
    return new ReadContext(account, position);
  }

  private Optional<CloseResult> findReplay(CloseIntent intent, ReadContext context) {
    String key = intent.idempotencyKey();
    Optional<OrderEntity> existing = orderRepository
        .findByUserIdAndAccountIdAndClientOrderId(
            context.account().getUserId(), context.account().getId(), key)
        .or(() -> orderRepository.findByUserIdAndIdempotencyKey(
            context.account().getUserId(), key));
    if (existing.isEmpty()) {
      return Optional.empty();
    }
    OrderEntity order = existing.get();
    if (!matchesReplay(intent, context.position(), order)) {
      throw new BusinessException(
          ErrorCode.DUPLICATE_CLIENT_ORDER_ID,
          "Client order id is already used by a different close command");
    }
    return Optional.of(new CloseResult(order, context.position(), context.account(), true));
  }

  private boolean matchesReplay(
      CloseIntent intent,
      PositionEntity position,
    OrderEntity order
  ) {
    if (order == null
        || (intent.userScoped() && !Objects.equals(order.getUserId(), intent.userId()))
        || !Objects.equals(order.getAccountId(), intent.accountId())
        || order.getProductType() != ProductType.LINEAR_PERP
        || order.getOrderType() != OrderType.MARKET
        || order.getStatus() != OrderStatus.FILLED
        || !Boolean.TRUE.equals(order.getReduceOnly())
        || order.getOrderOrigin() != intent.origin()
        || !Objects.equals(order.getSystemReason(), persistedSystemReason(intent))
        || !Objects.equals(order.getParentPositionId(), intent.positionId())
        || position == null
        || !Objects.equals(order.getSymbol(), position.getSymbol())
        || order.getSide() != opposite(position.getSide())
        || order.getPositionMode() != position.getPositionMode()
        || order.getPositionSide() != position.getPositionSide()) {
      return false;
    }
    if (intent.whole()) {
      return position.getStatus() == PositionStatus.CLOSED;
    }
    return order.getQuantityUnit() == intent.request().quantityUnit()
        && sameDecimal(order.getOriginalQuantity(), intent.request().quantity());
  }

  private OrderEntity closeOrder(
      CloseIntent intent,
      UUID userId,
      PositionEntity target,
      ClosePositionRequest request,
      PerpetualOrderRiskService.OrderRisk risk
  ) {
    OrderCommand command = new OrderCommand(
        userId,
        intent.accountId(),
        target.getSymbol(),
        opposite(target.getSide()),
        OrderType.MARKET,
        risk.closingBase(),
        null,
        null,
        null,
        intent.idempotencyKey(),
        intent.idempotencyKey(),
        risk.leverage(),
        request.quantity(),
        risk.closingBase(),
        request.quantityUnit(),
        risk.marginMode(),
        risk.positionSide(),
        true,
        null,
        null,
        List.of());
    OrderEntity order = orderEntityFactory.createReceived(command);
    order.setProductType(ProductType.LINEAR_PERP);
    order.setPositionMode(risk.positionMode());
    order.setPositionSide(risk.positionSide());
    order.setMarginMode(risk.marginMode());
    order.setOrderOrigin(intent.origin());
    order.setSystemReason(persistedSystemReason(intent));
    order.setStatus(OrderStatus.ACCEPTED);
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(risk.closingBase());
    order.setHoldAmount(risk.holdAmount());
    order.setHoldCurrency(risk.holdCurrency());
    order.setParentPositionId(target.getId());
    return order;
  }

  private void reserveHold(
      TradingAccountEntity account,
      OrderEntity order,
      PerpetualOrderRiskService.OrderRisk risk
  ) {
    BigDecimal hold = money(risk.holdAmount());
    if (hold.compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException(ErrorCode.ORDER_HOLD_INVALID, "Close order hold must be positive");
    }
    if (risk.marginMode() == MarginMode.ISOLATED && order.getParentPositionId() != null) {
      account.setUsedMargin(money(orZero(account.getUsedMargin()).add(hold)));
    } else {
      if (orZero(account.getFreeMargin()).compareTo(hold) < 0) {
        throw new BusinessException(ErrorCode.INSUFFICIENT_MARGIN, "Free margin is not enough");
      }
      account.setUsedMargin(money(orZero(account.getUsedMargin()).add(hold)));
      account.setFreeMargin(money(orZero(account.getFreeMargin()).subtract(hold)));
    }
    accountRepository.save(account);
  }

  private CreateOrderRequest closeRequest(
      UUID accountId,
      PositionEntity position,
      OrderSide side,
      ClosePositionRequest request,
      BigDecimal canonicalBaseQuantity,
      PerpetualOrderRiskService.OrderRisk risk
  ) {
    return new CreateOrderRequest(
        accountId,
        position.getSymbol(),
        side,
        OrderType.MARKET,
        canonicalBaseQuantity,
        null,
        null,
        null,
        request.clientOrderId(),
        request.clientOrderId(),
        risk == null ? request.quantity() : canonicalBaseQuantity,
        null,
        risk == null ? null : risk.leverage(),
        position.getPositionSide(),
        risk == null ? request.quantityUnit() : QuantityUnit.BASE,
        risk == null ? position.getMarginMode() : risk.marginMode(),
        null,
        null,
        true,
        List.of());
  }

  private ClosePositionRequest effectiveRequest(CloseIntent intent, PositionEntity position) {
    if (!intent.whole()) {
      return intent.request();
    }
    return new ClosePositionRequest(
        abs(position.getLots()),
        QuantityUnit.BASE,
        intent.idempotencyKey());
  }

  private ExecutableMarketSnapshot resolveSnapshot(String symbol) {
    Instant to = Instant.now();
    CandleRequest candles = new CandleRequest("1m", to.minus(Duration.ofMinutes(30)), to);
    return ExecutableMarketSnapshot.from(marketBundleResolver.resolvePerp(symbol, candles));
  }

  private void requireLockedPosition(
      TradingAccountEntity account,
      AccountSymbolSettingEntity setting,
      PositionEntity position
  ) {
    requireOpenLinearPosition(position);
    if (!Objects.equals(account.getId(), position.getAccountId())
        || account.getPositionMode() != position.getPositionMode()
        || setting.getMarginMode() != position.getMarginMode()) {
      throw positionNotFound();
    }
    if (setting.getLeverage() == null
        || setting.getLeverage() <= 0
        || position.getLeverage() == null
        || !Objects.equals(setting.getLeverage(), position.getLeverage())) {
      throw new BusinessException(
          "INVALID_INSTRUMENT_RULES",
          "Locked position leverage does not match its symbol setting");
    }
    if (position.getPositionMode() == PositionMode.ONE_WAY) {
      if (position.getPositionSide() != PositionSide.BOTH) {
        throw new BusinessException(
            ErrorCode.INVALID_POSITION_SIDE,
            "ONE_WAY close requires the BOTH slot");
      }
      return;
    }
    boolean validHedge = position.getPositionMode() == PositionMode.HEDGE
        && ((position.getPositionSide() == PositionSide.LONG && position.getSide() == OrderSide.BUY)
            || (position.getPositionSide() == PositionSide.SHORT && position.getSide() == OrderSide.SELL));
    if (!validHedge) {
      throw new BusinessException(
          ErrorCode.INVALID_POSITION_SIDE,
          "HEDGE close direction does not match its position slot");
    }
  }

  private void requireOpenLinearPosition(PositionEntity position) {
    if (position == null
        || position.getId() == null
        || position.getProductType() != ProductType.LINEAR_PERP
        || position.getStatus() != PositionStatus.OPEN
        || position.getLots() == null
        || position.getLots().compareTo(BigDecimal.ZERO) <= 0
        || position.getSide() == null
        || position.getPositionMode() == null
        || position.getPositionSide() == null) {
      throw positionNotFound();
    }
  }

  private void requireLinearSymbol(SymbolEntity symbol, String expectedSymbol) {
    if (symbol == null
        || symbol.getProductType() != ProductType.LINEAR_PERP
        || !Objects.equals(symbol.getSymbol(), expectedSymbol)
        || !MarketBundleProducts.isPerpetual(expectedSymbol)
        || symbol.getMaintenanceMarginRate() == null
        || symbol.getMaintenanceMarginRate().compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException(
          "INVALID_INSTRUMENT_RULES",
          "Linear Perpetual symbol configuration is invalid");
    }
  }

  private void requireUsableRules(InstrumentRules rules) {
    if (rules == null
        || !rules.exists()
        || !rules.enabled()
        || !rules.tradable()
        || !rules.orderEnabled()
        || rules.productType() != ProductType.LINEAR_PERP
        || rules.stepSize() == null
        || rules.stepSize().compareTo(BigDecimal.ZERO) <= 0) {
      throw new BusinessException(ErrorCode.SYMBOL_NOT_TRADABLE, "Symbol is not tradable");
    }
  }

  private void requirePureClose(
      PerpetualOrderRiskService.OrderRisk risk,
      BigDecimal expectedQuantity
  ) {
    if (risk == null
        || risk.closingBase() == null
        || risk.closingBase().compareTo(expectedQuantity) != 0
        || risk.openingBase() == null
        || risk.openingBase().compareTo(BigDecimal.ZERO) != 0) {
      throw new BusinessException(
          ErrorCode.REDUCE_ONLY_WOULD_INCREASE,
          "System close must remain a pure reduce-only fill");
    }
  }

  private static void requireNotOverClose(BigDecimal requested, BigDecimal openQuantity) {
    if (requested == null
        || requested.compareTo(BigDecimal.ZERO) <= 0
        || openQuantity == null
        || requested.compareTo(abs(openQuantity)) > 0) {
      throw new BusinessException(
          ErrorCode.REDUCE_ONLY_EXCEEDS_POSITION,
          "Perpetual close quantity exceeds the open slot");
    }
  }

  private static void requireRequest(ClosePositionRequest request) {
    if (request == null
        || request.quantity() == null
        || request.quantity().compareTo(BigDecimal.ZERO) <= 0
        || request.quantityUnit() == null) {
      throw new BusinessException("BAD_QUANTITY", "Close quantity must be positive");
    }
    normalizedKey(request.clientOrderId());
  }

  private static String normalizedKey(String key) {
    if (key == null || key.isBlank()) {
      throw new BusinessException(
          ErrorCode.DUPLICATE_CLIENT_ORDER_ID,
          "Client order id is required");
    }
    return key.trim();
  }

  private static void requireId(UUID id, String message) {
    if (id == null) {
      throw new BusinessException("INVALID_CLOSE_REQUEST", message);
    }
  }

  private static OrderSide opposite(OrderSide side) {
    if (side == OrderSide.BUY) {
      return OrderSide.SELL;
    }
    if (side == OrderSide.SELL) {
      return OrderSide.BUY;
    }
    throw new BusinessException("INVALID_POSITION_SIDE", "Position direction is required");
  }

  private static BigDecimal abs(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value.abs();
  }

  private void requireCloseGuard(
      CloseIntent intent,
      TradingAccountEntity account,
      String symbol
  ) {
    if (intent.origin() == OrderOrigin.LIQUIDATION
        && account.getStatus() == com.fxplatform.account.enums.AccountStatus.RISK_REDUCTION_PENDING) {
      throw new BusinessException(
          "ACCOUNT_CLEANUP_PENDING",
          "Liquidation close is suspended while Admin cleanup owns the account");
    }
    if (intent.origin() == OrderOrigin.USER || intent.origin() == OrderOrigin.BATCH_CLOSE) {
      demoExecutionGuard.requireDemo(account, ProductType.LINEAR_PERP, symbol);
      return;
    }
    demoExecutionGuard.requireDemoRiskReduction(account, ProductType.LINEAR_PERP, symbol);
  }

  private void settleLiquidationDeficit(
      CloseIntent intent,
      TradingAccountEntity account,
      PositionEntity position,
      OrderEntity order,
      MarginMode marginMode,
      BigDecimal balanceBeforeFill,
      BigDecimal isolatedMarginBeforeFill,
      BigDecimal liquidationFee
  ) {
    if (intent.origin() != OrderOrigin.LIQUIDATION) {
      return;
    }

    BigDecimal balanceAfterFill = money(account.getBalance());
    BigDecimal coreDebit = money(balanceBeforeFill.subtract(balanceAfterFill).max(BigDecimal.ZERO));
    BigDecimal coreCapacity = isolatedMarginBeforeFill;
    BigDecimal coreShortfall = marginMode == MarginMode.ISOLATED
        ? money(coreDebit.subtract(coreCapacity).max(BigDecimal.ZERO))
        : money(balanceAfterFill.negate().max(BigDecimal.ZERO));
    if (coreShortfall.signum() > 0) {
      creditCash(account, coreShortfall);
    }
    BigDecimal balanceFloorShortfall = money(orZero(account.getBalance()).negate().max(BigDecimal.ZERO));
    if (balanceFloorShortfall.signum() > 0) {
      creditCash(account, balanceFloorShortfall);
      coreShortfall = money(coreShortfall.add(balanceFloorShortfall));
    }

    BigDecimal remainingCapacity = marginMode == MarginMode.ISOLATED
        ? money(coreCapacity.subtract(coreDebit).max(BigDecimal.ZERO))
        : money(account.getBalance().max(BigDecimal.ZERO));
    BigDecimal chargedFee = money(liquidationFee
        .min(remainingCapacity)
        .min(orZero(account.getBalance()).max(BigDecimal.ZERO)));
    if (chargedFee.signum() > 0) {
      debitCash(account, chargedFee);
      ledgerService.recordLiquidationFee(
          account,
          chargedFee,
          position.getId(),
          "Liquidation fee charged");
    }

    BigDecimal feeShortfall = money(liquidationFee.subtract(chargedFee).max(BigDecimal.ZERO));
    BigDecimal totalShortfall = money(coreShortfall.add(feeShortfall));
    accountRepository.save(account);
    if (totalShortfall.signum() <= 0) {
      return;
    }
    ledgerService.recordBankruptcyShortfall(
        account,
        totalShortfall,
        order.getId(),
        "Liquidation bankruptcy shortfall");
    if (auditLogService != null) {
      auditLogService.record(
          null,
          "BANKRUPTCY_SHORTFALL",
          "ORDER",
          order.getId().toString(),
          "{\"amount\":" + totalShortfall.toPlainString()
              + ",\"positionId\":\"" + position.getId() + "\"}");
    }
  }

  private static void creditCash(TradingAccountEntity account, BigDecimal amount) {
    account.setBalance(money(orZero(account.getBalance()).add(amount)));
    account.setEquity(money(orZero(account.getEquity()).add(amount)));
    account.setFreeMargin(money(orZero(account.getFreeMargin()).add(amount)));
  }

  private static void debitCash(TradingAccountEntity account, BigDecimal amount) {
    account.setBalance(money(orZero(account.getBalance()).subtract(amount)));
    account.setEquity(money(orZero(account.getEquity()).subtract(amount)));
    account.setFreeMargin(money(orZero(account.getFreeMargin()).subtract(amount)));
  }

  private static BigDecimal nonNegative(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
  }

  private static BigDecimal contractualLiquidationFee(
      PreparedClose prepared,
      OrderEntity order
  ) {
    return money(abs(order.getFilledQuantity())
        .multiply(abs(order.getExecutionPrice()))
        .multiply(nonNegative(prepared.liquidationFeeRate())));
  }

  private static BigDecimal money(BigDecimal value) {
    return orZero(value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
    return left == null ? right == null : right != null && left.compareTo(right) == 0;
  }

  private static String persistedSystemReason(CloseIntent intent) {
    return intent.origin() == OrderOrigin.USER && intent.whole()
        ? OrderSystemReasonPolicy.USER_WHOLE_CLOSE
        : intent.systemReason();
  }

  private static BusinessException positionNotFound() {
    return new BusinessException("POSITION_NOT_FOUND", "Position not found");
  }

  public record CloseResult(
      OrderEntity order,
      PositionEntity position,
      TradingAccountEntity account,
      boolean replayed
  ) {
  }

  private record CloseIntent(
      UUID userId,
      UUID accountId,
      UUID positionId,
      ClosePositionRequest request,
      OrderOrigin origin,
      String systemReason,
      String idempotencyKey,
      boolean whole,
      boolean userScoped,
      Runnable lockedMutationGuard
  ) {
  }

  private record ReadContext(
      TradingAccountEntity account,
      PositionEntity position
  ) {
  }

  private record PreparedClose(
      TradingAccountEntity account,
      PositionEntity position,
      ClosePositionRequest request,
      QuantityConversionService.Conversion conversion,
      ExecutableMarketSnapshot snapshot,
      BigDecimal maintenanceMarginRate,
      BigDecimal liquidationFeeRate
  ) {
  }

  private record PreparedProtectionExecution(
      OrderEntity carrier,
      ExecutableMarketSnapshot snapshot,
      BigDecimal maintenanceMarginRate
  ) {
  }
}
