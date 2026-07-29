package com.fxplatform.trading.scenario;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.account.dto.UpdateSymbolSettingsRequest;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.admin.dto.request.AdminForceClosePositionRequest;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.model.SpotMarketBundle;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.trading.dto.request.AdjustPositionMarginRequest;
import com.fxplatform.trading.dto.request.ClosePositionRequest;
import com.fxplatform.trading.dto.request.CreateOcoOrderRequest;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.dto.request.CreateOrderRequest.AttachedProtectionRequest;
import com.fxplatform.trading.dto.request.CreateProtectionRequest;
import com.fxplatform.trading.dto.request.UpdateOrderRequest;
import com.fxplatform.trading.dto.response.BatchActionResponse;
import com.fxplatform.trading.dto.response.OcoOrderGroupResponse;
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import com.fxplatform.trading.entity.FundingRateEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.FundingRateRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.Checkpoint;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.EventState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.FailureState;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.Snapshot;
import com.fxplatform.trading.service.CancelAllOrderService;
import com.fxplatform.trading.service.CloseAllPositionService;
import com.fxplatform.trading.service.FundingService;
import com.fxplatform.trading.service.LiquidationService;
import com.fxplatform.trading.service.OcoOrderService;
import com.fxplatform.trading.service.OrderFillService;
import com.fxplatform.trading.service.OrderService;
import com.fxplatform.trading.service.PendingOrderExecutionProcessor;
import com.fxplatform.trading.service.PendingOrderExecutionService;
import com.fxplatform.trading.service.PerpetualAccountRiskSnapshotService;
import com.fxplatform.trading.service.PositionMarginService;
import com.fxplatform.trading.service.ProtectionOrderService;
import com.fxplatform.trading.service.ProtectiveOrderExecutionService;
import com.fxplatform.trading.service.SystemCloseOrderService;
import com.fxplatform.trading.service.TradingSettingsService;
import com.fxplatform.trading.service.TradingTransactionExecutor;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

public class ScenarioExecutor {

  private static final String TRADE_FAILURE_TRIGGER = "scenario_fail_trade_insert";
  private static final String TRADE_FAILURE_FUNCTION = "public.scenario_fail_trade_insert";
  private static final String LEDGER_FAILURE_TRIGGER = "scenario_fail_ledger_insert";
  private static final String LEDGER_FAILURE_FUNCTION = "public.scenario_fail_ledger_insert";
  private static final String POSITION_FAILURE_TRIGGER = "scenario_fail_position_update";
  private static final String POSITION_FAILURE_FUNCTION =
      "public.scenario_fail_position_update";

  private enum RaceStatus {
    COMMITTED,
    LEGAL_LOSS
  }

  private record RaceOutcome(
      RaceStatus status,
      RuntimeException failure
  ) {

    private static final RaceOutcome COMMITTED =
        new RaceOutcome(RaceStatus.COMMITTED, null);
    private static final RaceOutcome LEGAL_LOSS =
        new RaceOutcome(RaceStatus.LEGAL_LOSS, null);

    private static RaceOutcome committed() {
      return COMMITTED;
    }

    private static RaceOutcome legalLoss() {
      return LEGAL_LOSS;
    }

    private static RaceOutcome legalLoss(RuntimeException failure) {
      return new RaceOutcome(RaceStatus.LEGAL_LOSS, Objects.requireNonNull(failure));
    }

    private static RaceOutcome fromCommit(boolean committed) {
      return committed ? COMMITTED : LEGAL_LOSS;
    }
  }

  private final MarketBundleResolver marketBundleResolver;
  private final OrderService orderService;
  private final OrderFillService orderFillService;
  private final OcoOrderService ocoOrderService;
  private final PendingOrderExecutionProcessor pendingOrderExecutionProcessor;
  private final PendingOrderExecutionService pendingOrderExecutionService;
  private final SystemCloseOrderService systemCloseOrderService;
  private final TradingSettingsService tradingSettingsService;
  private final PositionMarginService positionMarginService;
  private final ProtectionOrderService protectionOrderService;
  private final ProtectiveOrderExecutionService protectiveOrderExecutionService;
  private final FundingRateRepository fundingRateRepository;
  private final FundingService fundingService;
  private final LiquidationService liquidationService;
  private final CancelAllOrderService cancelAllOrderService;
  private final CloseAllPositionService closeAllPositionService;
  private final AccountSymbolSettingRepository settingRepository;
  private final TradingAccountRepository accountRepository;
  private final OrderRepository orderRepository;
  private final PositionRepository positionRepository;
  private final PerpetualAccountRiskSnapshotService accountRiskSnapshotService;
  private final TradingTransactionExecutor transactionExecutor;
  private final ScenarioResultReader resultReader;
  private final JdbcTemplate jdbcTemplate;
  private final DataSource dataSource;
  private final MockMvc mockMvc;
  private final ObjectMapper objectMapper;
  private final AtomicReference<CountDownLatch> lockWaitMarketResolved =
      new AtomicReference<>(new CountDownLatch(0));

  public ScenarioExecutor(
      MarketBundleResolver marketBundleResolver,
      OrderService orderService,
      OrderFillService orderFillService,
      OcoOrderService ocoOrderService,
      PendingOrderExecutionProcessor pendingOrderExecutionProcessor,
      PendingOrderExecutionService pendingOrderExecutionService,
      SystemCloseOrderService systemCloseOrderService,
      TradingSettingsService tradingSettingsService,
      PositionMarginService positionMarginService,
      ProtectionOrderService protectionOrderService,
      ProtectiveOrderExecutionService protectiveOrderExecutionService,
      FundingRateRepository fundingRateRepository,
      FundingService fundingService,
      LiquidationService liquidationService,
      CancelAllOrderService cancelAllOrderService,
      CloseAllPositionService closeAllPositionService,
      AccountSymbolSettingRepository settingRepository,
      TradingAccountRepository accountRepository,
      OrderRepository orderRepository,
      PositionRepository positionRepository,
      PerpetualAccountRiskSnapshotService accountRiskSnapshotService,
      TradingTransactionExecutor transactionExecutor,
      ScenarioResultReader resultReader,
      JdbcTemplate jdbcTemplate,
      DataSource dataSource,
      MockMvc mockMvc,
      ObjectMapper objectMapper
  ) {
    this.marketBundleResolver = marketBundleResolver;
    this.orderService = orderService;
    this.orderFillService = orderFillService;
    this.ocoOrderService = ocoOrderService;
    this.pendingOrderExecutionProcessor = pendingOrderExecutionProcessor;
    this.pendingOrderExecutionService = pendingOrderExecutionService;
    this.systemCloseOrderService = systemCloseOrderService;
    this.tradingSettingsService = tradingSettingsService;
    this.positionMarginService = positionMarginService;
    this.protectionOrderService = protectionOrderService;
    this.protectiveOrderExecutionService = protectiveOrderExecutionService;
    this.fundingRateRepository = fundingRateRepository;
    this.fundingService = fundingService;
    this.liquidationService = liquidationService;
    this.cancelAllOrderService = cancelAllOrderService;
    this.closeAllPositionService = closeAllPositionService;
    this.settingRepository = settingRepository;
    this.accountRepository = accountRepository;
    this.orderRepository = orderRepository;
    this.positionRepository = positionRepository;
    this.accountRiskSnapshotService = accountRiskSnapshotService;
    this.transactionExecutor = transactionExecutor;
    this.resultReader = resultReader;
    this.jdbcTemplate = jdbcTemplate;
    this.dataSource = dataSource;
    this.mockMvc = mockMvc;
    this.objectMapper = objectMapper;
  }

  public ActualScenarioResult execute(
      ScenarioContext context,
      ScenarioDefinition scenario
  ) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(scenario, "scenario");
    if (context.scenario() != scenario) {
      throw new IllegalArgumentException("ScenarioContext does not own the supplied scenario");
    }

    AtomicReference<String> resolvedSource = new AtomicReference<>(
        scenario.priceSteps().getFirst().source());
    configureMarket(context, resolvedSource);
    if (scenario.productType() == ProductType.LINEAR_PERP
        && !"NONE".equalsIgnoreCase(scenario.initialPosition())) {
      revalue(context, scenario);
    }
    List<Checkpoint> checkpoints = new ArrayList<>(scenario.actions().size());
    List<SyntheticEvent> syntheticEvents = new ArrayList<>();
    String observedSource = scenario.priceSteps().getFirst().source();

    try {
      for (int actionIndex = 0; actionIndex < scenario.actions().size(); actionIndex++) {
        ScenarioAction action = scenario.actions().get(actionIndex);
        ScenarioPriceStep market = scenario.priceSteps().get(
            Math.min(actionIndex + 1, scenario.priceSteps().size() - 1));
        context.currentPriceStep(market);
        Snapshot before = resultReader.readSnapshot(context);
        int databaseEventsBefore = before.events().size();
        RuntimeException failure = null;
        try {
          invokeAction(context, scenario, action, actionIndex, market);
        } catch (RuntimeException exception) {
          failure = unwrap(exception);
        }
        String actualSource = resolvedSource.get();
        boolean sourceChanged = scenario.productType() == ProductType.LINEAR_PERP
            && !Objects.equals(observedSource, actualSource);
        if (sourceChanged) {
          syntheticEvents.add(new SyntheticEvent(
              databaseEventsBefore,
              new EventState(
                  0,
                  "MARKET_SOURCE_CHANGED",
                  context.symbol(),
                  observedSource,
                  actualSource,
                  "")));
          observedSource = actualSource;
        }

        Snapshot persisted = resultReader.readSnapshot(context);
        FailureState failureState = failure == null
            ? null
            : failureState(failure, before.equals(persisted));
        checkpoints.add(new Checkpoint(
            actionIndex + 1,
            action.parameters().actionId(),
            withEvents(persisted, mergeEvents(persisted.events(), syntheticEvents)),
            failureState));
      }
      return new ActualScenarioResult(scenario.caseId(), checkpoints);
    } finally {
      dropFailureTriggers();
    }
  }

  private void invokeAction(
      ScenarioContext context,
      ScenarioDefinition scenario,
      ScenarioAction action,
      int actionIndex,
      ScenarioPriceStep market
  ) {
    switch (action.type()) {
      case BUY, SELL, PARTIAL_SELL, ADD, REVERSE, PLACE_ORDER ->
          createOrderWithFailureInjection(context, scenario, action);
      case PARTIAL_CLOSE -> closePosition(context, scenario, action, false);
      case FULL_CLOSE -> closePosition(context, scenario, action, true);
      case MODIFY -> modifyOrder(context, action);
      case CANCEL -> cancelOrder(context, action);
      case TRIGGER -> trigger(context, action, market);
      case CREATE_OCO -> createOco(context, action);
      case REPLAY -> replay(context, scenario, action);
      case CHANGE_LEVERAGE -> changeLeverage(context, scenario, action);
      case ADJUST_MARGIN -> adjustMargin(context, scenario, action);
      case SET_PROTECTION -> setProtection(context, scenario, action);
      case SETTLE_FUNDING -> settleFunding(context, action, actionIndex, market);
      case LIQUIDATE -> liquidate(context, action);
      case CANCEL_ALL -> cancelAll(context, action);
      case CLOSE_ALL -> closeAll(context, scenario, action);
      case ADMIN_FORCE_CLOSE -> adminForceClose(context, scenario, action);
      case RACE -> race(context, scenario, action, actionIndex);
      case ROLLBACK -> throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "ROLLBACK must be represented by a concrete production action failure");
      case REVALUE -> revalue(context, scenario);
    }
  }

  private void createOrderWithFailureInjection(
      ScenarioContext context,
      ScenarioDefinition scenario,
      ScenarioAction action
  ) {
    String failure = action.parameters().failureCondition();
    try {
      if ("PARTIALLY_FILLED_INPUT_IS_COMPAT_ONLY".equals(failure)) {
        rejectPartialFillCompatibility(context, scenario, action);
        return;
      }
      if ("INJECT_FAILURE_AFTER_ORDER_BEFORE_TRADE".equals(failure)) {
        installTradeFailureTrigger();
      } else if ("INJECT_FAILURE_AFTER_TRADE_BEFORE_LEDGER".equals(failure)) {
        installLedgerFailureTrigger();
      }
      OrderResponse response = "LOCK_WAIT_EXCEEDS_MARKET_BUNDLE_TTL".equals(failure)
          ? createOrderAfterAccountLockExpiry(context, scenario, action)
          : orderService.createOrder(
              context.principal(),
              createOrderRequest(context, scenario, action));
      bindOrder(context, action.parameters().orderId(), response);
      bindAttachedProtection(context, action, response);
    } finally {
      dropFailureTriggers();
    }
  }

  private OrderResponse createOrderAfterAccountLockExpiry(
      ScenarioContext context,
      ScenarioDefinition scenario,
      ScenarioAction action
  ) {
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    Future<?> lockHolder = workers.submit(
        () -> holdAccountLock(context.accountId(), locked, release));
    Future<OrderResponse> orderAttempt = null;
    try {
      if (!locked.await(10, TimeUnit.SECONDS)) {
        throw new BusinessException(
            ErrorCode.EXECUTION_UNAVAILABLE,
            "Timed out acquiring the scenario account row lock");
      }
      orderAttempt = workers.submit(() -> orderService.createOrder(
          context.principal(),
          createOrderRequest(context, scenario, action)));
      if (!lockWaitMarketResolved.get().await(10, TimeUnit.SECONDS)) {
        throw new BusinessException(
            ErrorCode.EXECUTION_UNAVAILABLE,
            "Timed out waiting for the lock-expiry order to resolve its provider bundle");
      }
      try {
        OrderResponse early = orderAttempt.get(6, TimeUnit.SECONDS);
        throw new BusinessException(
            ErrorCode.EXECUTION_UNAVAILABLE,
            "Order completed before the five-second provider TTL expired: " + early.id());
      } catch (TimeoutException expectedLockWait) {
        release.countDown();
        awaitLockHolder(lockHolder);
        return awaitOrderAttempt(orderAttempt);
      } catch (ExecutionException exception) {
        throw unwrap(exception);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "Interrupted while coordinating the account row lock");
    } finally {
      release.countDown();
      if (orderAttempt != null && !orderAttempt.isDone()) {
        orderAttempt.cancel(true);
      }
      workers.shutdownNow();
    }
  }

  private Void holdAccountLock(
      UUID accountId,
      CountDownLatch locked,
      CountDownLatch release
  ) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement statement = connection.prepareStatement("""
          SELECT id
          FROM core.trading_accounts
          WHERE id = ?
          FOR UPDATE
          """)) {
        statement.setObject(1, accountId);
        try (var result = statement.executeQuery()) {
          if (!result.next()) {
            throw new BusinessException(
                ErrorCode.ACCOUNT_NOT_FOUND,
                "Scenario account row is missing");
          }
        }
        locked.countDown();
        if (!release.await(20, TimeUnit.SECONDS)) {
          throw new BusinessException(
              ErrorCode.EXECUTION_UNAVAILABLE,
              "Timed out waiting to release the scenario account row lock");
        }
        connection.commit();
        return null;
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        rollback(connection);
        throw new BusinessException(
            ErrorCode.EXECUTION_UNAVAILABLE,
            "Interrupted while holding the scenario account row lock");
      } catch (RuntimeException | SQLException exception) {
        rollback(connection);
        throw exception;
      } finally {
        locked.countDown();
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Could not hold the scenario account row lock", exception);
    }
  }

  private static void awaitLockHolder(Future<?> lockHolder) {
    try {
      lockHolder.get(5, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "Interrupted while releasing the scenario account row lock");
    } catch (ExecutionException exception) {
      throw unwrap(exception);
    } catch (TimeoutException exception) {
      throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "Scenario account row lock did not release within five seconds");
    }
  }

  private static OrderResponse awaitOrderAttempt(Future<OrderResponse> orderAttempt) {
    try {
      return orderAttempt.get(20, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "Interrupted while waiting for the lock-expiry order result");
    } catch (ExecutionException exception) {
      throw unwrap(exception);
    } catch (TimeoutException exception) {
      throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "Lock-expiry order did not finish within twenty seconds");
    }
  }

  private static void rollback(Connection connection) {
    try {
      connection.rollback();
    } catch (SQLException ignored) {
      // Closing the connection is the final lock-release fallback.
    }
  }

  private void rejectPartialFillCompatibility(
      ScenarioContext context,
      ScenarioDefinition scenario,
      ScenarioAction action
  ) {
    TradingAccountEntity account = accountRepository.selectById(context.accountId());
    if (account == null) {
      throw new BusinessException(
          ErrorCode.ACCOUNT_NOT_FOUND,
          "Scenario account is missing for the partial-fill guard");
    }
    BigDecimal quantity = action.quantity();
    BigDecimal filledQuantity = quantity.multiply(new BigDecimal("0.4"));
    BigDecimal remainingQuantity = quantity.subtract(filledQuantity);
    OrderEntity canonical = new OrderEntity();
    canonical.setId(UUID.randomUUID());
    canonical.setUserId(context.userId());
    canonical.setAccountId(context.accountId());
    canonical.setSymbol(context.symbol());
    canonical.setProductType(ProductType.LINEAR_PERP);
    canonical.setPositionMode(scenario.positionMode());
    canonical.setPositionSide(scenario.positionMode() == PositionMode.ONE_WAY
        ? PositionSide.BOTH
        : action.direction());
    canonical.setMarginMode(scenario.marginMode());
    canonical.setSide(action.parameters().side());
    canonical.setOrderType(action.parameters().orderType());
    canonical.setStatus(OrderStatus.ACCEPTED);
    canonical.setLots(quantity);
    canonical.setQuantity(quantity);
    canonical.setOriginalQuantity(quantity);
    canonical.setBaseQuantity(quantity);
    canonical.setFilledQuantity(BigDecimal.ZERO);
    canonical.setRemainingQuantity(quantity);
    canonical.setReduceOnly(action.parameters().reduceOnly());
    canonical.setLeverage(scenario.leverage());
    canonical.setClientOrderId(action.parameters().clientOrderId());
    canonical.setIdempotencyKey(action.parameters().clientOrderId());
    Instant now = Instant.now();
    orderFillService.fillPerpetual(
        canonical,
        account,
        new FullFillResult(
            context.currentPriceStep().mark(),
            now,
            filledQuantity,
            remainingQuantity,
            action.parameters().takerFeeRate(),
            BigDecimal.ZERO,
            context.quoteAsset(),
            LiquidityRole.TAKER,
            BigDecimal.ZERO,
            MarketSourceMode.PUBLIC_EXTERNAL,
            providerCode(ProductType.LINEAR_PERP, context.currentPriceStep().source()),
            "BTCUSDT",
            now.minusSeconds(1),
            now.plusSeconds(5)),
        context.currentPriceStep().mark(),
        scenario.leverage(),
        "Scenario partial-fill compatibility guard");
  }

  private CreateOrderRequest createOrderRequest(
      ScenarioContext context,
      ScenarioDefinition scenario,
      ScenarioAction action
  ) {
    return createOrderRequest(
        context,
        scenario,
        action,
        action.parameters().clientOrderId());
  }

  private CreateOrderRequest createOrderRequest(
      ScenarioContext context,
      ScenarioDefinition scenario,
      ScenarioAction action,
      String clientOrderId
  ) {
    ScenarioAction.Parameters parameters = action.parameters();
    PositionSide positionSide = scenario.positionMode() == PositionMode.ONE_WAY
        ? PositionSide.BOTH
        : action.direction() == PositionSide.BOTH
            ? scenario.positionSide()
            : action.direction();
    QuantityUnit quantityUnit = parameters.quantityUnit() == null
        ? scenario.quantityUnit()
        : parameters.quantityUnit();
    MarginMode marginMode = scenario.productType() == ProductType.CRYPTO_SPOT
        ? MarginMode.CASH
        : scenario.marginMode();
    OrderType orderType = parameters.orderType() == null
        ? scenario.orderType()
        : parameters.orderType();
    TriggerPriceType triggerPriceType = parameters.triggerPriceType();
    if (triggerPriceType == null && orderType == OrderType.STOP_MARKET) {
      triggerPriceType = scenario.productType() == ProductType.CRYPTO_SPOT
          ? TriggerPriceType.LAST_PRICE
          : TriggerPriceType.MARK_PRICE;
    }
    int leverage = parameters.leverage() == null
        ? scenario.leverage()
        : parameters.leverage();
    boolean reduceOnly = parameters.reduceOnly() == null
        ? scenario.reduceOnly()
        : parameters.reduceOnly();
    BigDecimal quantity = action.quantity();
    boolean attachedToEntry =
        "ATTACHED_TO_ENTRY".equals(parameters.condition())
            && parameters.protectionType() != null;
    List<AttachedProtectionRequest> attachedProtections =
        attachedToEntry
            ? List.of(new AttachedProtectionRequest(
                parameters.protectionType(),
                parameters.triggerPrice(),
                parameters.triggerPriceType(),
                parameters.triggerExecutionType(),
                parameters.price(),
                quantity,
                quantityUnit))
            : List.of();
    BigDecimal orderPrice = attachedToEntry ? null : parameters.price();
    BigDecimal orderTriggerPrice = attachedToEntry ? null : parameters.triggerPrice();
    TriggerPriceType orderTriggerPriceType = attachedToEntry ? null : triggerPriceType;

    return new CreateOrderRequest(
        context.accountId(),
        context.symbol(),
        parameters.side(),
        orderType,
        null,
        null,
        null,
        null,
        clientOrderId,
        clientOrderId,
        quantity,
        orderPrice,
        leverage,
        positionSide,
        quantityUnit,
        marginMode,
        orderTriggerPrice,
        orderTriggerPriceType,
        reduceOnly,
        attachedProtections);
  }

  private void closePosition(
      ScenarioContext context,
      ScenarioDefinition scenario,
      ScenarioAction action,
      boolean whole
  ) {
    UUID positionId = positionId(context, scenario, action.direction());
    SystemCloseOrderService.CloseResult result = whole
        ? systemCloseOrderService.closeUserWhole(
            context.userId(),
            context.accountId(),
            positionId,
            action.parameters().clientOrderId())
        : systemCloseOrderService.closeUser(
            context.userId(),
            context.accountId(),
            positionId,
            new ClosePositionRequest(
                action.quantity(),
                quantityUnit(action, scenario),
                action.parameters().clientOrderId()));
    bindClose(context, action.parameters().orderId(), result);
  }

  private void modifyOrder(ScenarioContext context, ScenarioAction action) {
    UUID orderId = context.requireRef(action.parameters().orderId());
    List<UUID> modificationReferences =
        context.scenario().productType() == ProductType.CRYPTO_SPOT
            ? ledgerReferenceIds(context.accountId(), "ORDER_MODIFICATION")
            : List.of();
    orderService.modifyOrder(
        context.principal(),
        orderId,
        new UpdateOrderRequest(
            action.quantity(),
            action.parameters().price(),
            null,
            null));
    if (context.scenario().productType() == ProductType.CRYPTO_SPOT) {
      bindOnlyNewReference(
          context,
          action.parameters().actionId(),
          modificationReferences,
          ledgerReferenceIds(context.accountId(), "ORDER_MODIFICATION"),
          "Spot order modification");
    }
  }

  private void cancelOrder(ScenarioContext context, ScenarioAction action) {
    orderService.cancelOrder(
        context.principal(),
        context.requireRef(action.parameters().orderId()));
  }

  private void createOco(ScenarioContext context, ScenarioAction action) {
    ScenarioAction.Parameters parameters = action.parameters();
    OcoOrderGroupResponse response = ocoOrderService.create(
        context.principal(),
        new CreateOcoOrderRequest(
            context.accountId(),
            context.symbol(),
            parameters.side(),
            action.quantity(),
            parameters.quantityUnit(),
            parameters.price(),
            parameters.triggerPrice(),
            parameters.triggerPriceType(),
            parameters.clientOrderId(),
            parameters.clientOrderId()));
    context.putRef(parameters.clientOrderId(), response.contingencyGroupId());
    bindOrder(context, parameters.orderId(), response.limitOrder());
    bindOrder(context, parameters.competingOrderId(), response.stopOrder());
  }

  private void trigger(
      ScenarioContext context,
      ScenarioAction action,
      ScenarioPriceStep market
  ) {
    UUID orderId = context.requireRef(action.parameters().orderId());
    OrderEntity order = requireOrder(orderId);
    if (order.getProtectionType() != null) {
      protectiveOrderExecutionService.executeProtectiveOrders();
      return;
    }
    if (context.scenario().productType() == ProductType.CRYPTO_SPOT) {
      pendingOrderExecutionProcessor.process(
          order,
          ExecutableMarketSnapshot.from(spotBundle(context, market)));
      return;
    }
    pendingOrderExecutionService.executePendingOrders();
  }

  private void replay(
      ScenarioContext context,
      ScenarioDefinition scenario,
      ScenarioAction action
  ) {
    OrderResponse response = orderService.createOrder(
        context.principal(),
        createOrderRequest(context, scenario, action));
    bindOrder(context, action.parameters().orderId(), response);
  }

  private void changeLeverage(
      ScenarioContext context,
      ScenarioDefinition scenario,
      ScenarioAction action
  ) {
    if (scenario.productType() == ProductType.CRYPTO_SPOT) {
      orderService.createOrder(
          context.principal(),
          spotLeverageRejectRequest(context, action));
      return;
    }
    AccountSymbolSettingEntity setting = settingRepository
        .findByAccountIdAndSymbol(context.accountId(), context.symbol())
        .orElseThrow(() -> new BusinessException(
            ErrorCode.POSITION_VERSION_CONFLICT,
            "Scenario symbol setting is missing"));
    tradingSettingsService.updateSymbolSettings(
        context.userId(),
        context.accountId(),
        context.symbol(),
        new UpdateSymbolSettingsRequest(
            action.parameters().leverage(),
            null,
            scenario.productType() == ProductType.CRYPTO_SPOT
                ? QuantityUnit.CONTRACTS
                : null,
            version(setting.getVersion())));
  }

  private CreateOrderRequest spotLeverageRejectRequest(
      ScenarioContext context,
      ScenarioAction action
  ) {
    String clientOrderId = action.parameters().actionId() + "-client";
    return new CreateOrderRequest(
        context.accountId(),
        context.symbol(),
        OrderSide.BUY,
        OrderType.MARKET,
        null,
        null,
        null,
        null,
        clientOrderId,
        clientOrderId,
        new BigDecimal("100"),
        null,
        action.parameters().leverage(),
        PositionSide.BOTH,
        QuantityUnit.QUOTE,
        MarginMode.CASH,
        null,
        null,
        false,
        List.of());
  }

  private void adjustMargin(
      ScenarioContext context,
      ScenarioDefinition scenario,
      ScenarioAction action
  ) {
    UUID positionId = positionId(context, scenario, action.direction());
    PositionEntity position = requirePosition(positionId);
    BigDecimal delta = action.parameters().marginDelta();
    positionMarginService.adjust(
        context.userId(),
        positionId,
        new AdjustPositionMarginRequest(
            delta.signum() >= 0
                ? AdjustPositionMarginRequest.Action.ADD
                : AdjustPositionMarginRequest.Action.REDUCE,
            delta.abs(),
            version(position.getVersion())));
  }

  private void setProtection(
      ScenarioContext context,
      ScenarioDefinition scenario,
      ScenarioAction action
  ) {
    ScenarioAction.Parameters parameters = action.parameters();
    if (scenario.productType() == ProductType.CRYPTO_SPOT) {
      OrderResponse response = orderService.createOrder(
          context.principal(),
          spotProtectionRejectRequest(context, scenario, action));
      bindOrder(context, parameters.orderId(), response);
      return;
    }
    UUID positionId = positionId(context, scenario, action.direction());
    OrderResponse response = protectionOrderService.create(
        context.userId(),
        positionId,
        new CreateProtectionRequest(
            parameters.protectionType(),
            action.quantity(),
            quantityUnit(action, scenario),
            parameters.triggerPrice(),
            parameters.triggerExecutionType(),
            parameters.price(),
            parameters.clientOrderId()));
    bindOrder(context, parameters.orderId(), response);
    if (!parameters.protectionId().isBlank()) {
      context.putRef(parameters.protectionId(), response.id());
    }
  }

  private CreateOrderRequest spotProtectionRejectRequest(
      ScenarioContext context,
      ScenarioDefinition scenario,
      ScenarioAction action
  ) {
    ScenarioAction.Parameters parameters = action.parameters();
    QuantityUnit quantityUnit = quantityUnit(action, scenario);
    OrderSide side = quantityUnit == QuantityUnit.QUOTE
        ? OrderSide.BUY
        : OrderSide.SELL;
    AttachedProtectionRequest protection = new AttachedProtectionRequest(
        parameters.protectionType(),
        parameters.triggerPrice(),
        parameters.triggerPriceType(),
        parameters.triggerExecutionType(),
        parameters.price(),
        action.quantity(),
        quantityUnit);
    return new CreateOrderRequest(
        context.accountId(),
        context.symbol(),
        side,
        OrderType.MARKET,
        null,
        null,
        null,
        null,
        parameters.clientOrderId(),
        parameters.clientOrderId(),
        action.quantity(),
        null,
        1,
        PositionSide.BOTH,
        quantityUnit,
        MarginMode.CASH,
        null,
        null,
        false,
        List.of(protection));
  }

  private void settleFunding(
      ScenarioContext context,
      ScenarioAction action,
      int actionIndex,
      ScenarioPriceStep market
  ) {
    PositionEntity position = findPosition(context, action.direction());
    if (position == null) {
      return;
    }
    List<UUID> settlementsBefore = fundingSettlementIds(context.accountId());
    Instant fundingTime = Instant.now();
    FundingRateEntity rate = new FundingRateEntity();
    rate.setId(UUID.randomUUID());
    rate.setSymbol(context.symbol());
    rate.setFundingRate(action.parameters().fundingRate());
    rate.setFundingTime(fundingTime);
    rate.setNextFundingTime(fundingTime.plus(Duration.ofHours(8)));
    rate.setMarkPrice(market.mark());
    rate.setProviderCode(providerCode(ProductType.LINEAR_PERP, market.source()));
    rate.setSourceMode(MarketSourceMode.PUBLIC_EXTERNAL.name());
    rate.setAsOf(fundingTime.minusSeconds(1));
    rate.setIntervalMinutes(480);
    rate.setRawPayloadHash(
        "scenario-" + context.scenario().caseId().toLowerCase() + "-" + actionIndex);
    fundingRateRepository.insertIfAbsent(rate);
    fundingService.settleFundingForPosition(position, rate);
    bindOnlyNewReference(
        context,
        action.parameters().actionId(),
        settlementsBefore,
        fundingSettlementIds(context.accountId()),
        "Funding settlement");
  }

  private void cancelAll(ScenarioContext context, ScenarioAction action) {
    if ("ONE_ORDER_FILLED_WHILE_BATCH_CANCELS".equals(
        action.parameters().condition())) {
      pendingOrderExecutionService.executePendingOrders();
    }
    BatchActionResponse response = cancelAllOrderService.cancelUser(
        context.userId(),
        context.accountId(),
        action.parameters().actionId());
    throwBatchFailure(response);
  }

  private void liquidate(ScenarioContext context, ScenarioAction action) {
    liquidate(context, action, action.parameters().orderId());
  }

  private RaceOutcome liquidate(
      ScenarioContext context,
      ScenarioAction action,
      String orderRef
  ) {
    List<SystemOrderBinding> ordersBefore =
        liquidationOrderBindings(context.accountId());
    List<UUID> crossSettlementsBefore =
        ledgerReferenceIds(context.accountId(), "CROSS_LIQUIDATION_SETTLEMENT");
    boolean qualifyBySlot = liquidationUsesQualifiedOrderRefs(context, action);
    liquidationService.scanAccount(context.accountId());
    Set<UUID> existingOrderIds = new HashSet<>(
        ordersBefore.stream().map(SystemOrderBinding::orderId).toList());
    List<SystemOrderBinding> createdOrders =
        liquidationOrderBindings(context.accountId()).stream()
            .filter(order -> !existingOrderIds.contains(order.orderId()))
            .toList();
    bindLiquidationOrders(context, orderRef, createdOrders, qualifyBySlot);
    bindOnlyNewReference(
        context,
        action.parameters().actionId(),
        crossSettlementsBefore,
        ledgerReferenceIds(context.accountId(), "CROSS_LIQUIDATION_SETTLEMENT"),
        "Cross liquidation settlement");
    return createdOrders.isEmpty()
        ? RaceOutcome.legalLoss()
        : RaceOutcome.committed();
  }

  private boolean liquidationUsesQualifiedOrderRefs(
      ScenarioContext context,
      ScenarioAction action
  ) {
    if (context.scenario().positionMode() != PositionMode.HEDGE
        || action.direction() != PositionSide.BOTH) {
      return false;
    }
    return positionRepository.findOpenLinearPerpByAccountId(context.accountId()).size() > 1;
  }

  private void bindLiquidationOrders(
      ScenarioContext context,
      String baseRef,
      List<SystemOrderBinding> createdOrders,
      boolean qualifyBySlot
  ) {
    if (baseRef.isBlank()) {
      return;
    }
    for (SystemOrderBinding order : createdOrders) {
      bindPositionSlot(context, order.positionId(), order.slot());
      String logicalRef = qualifyBySlot
          ? baseRef + "-" + order.slot()
          : baseRef;
      context.putRef(logicalRef, order.orderId());
    }
  }

  private void bindNewestSystemOrder(
      ScenarioContext context,
      OrderOrigin origin,
      String logicalRef
  ) {
    jdbcTemplate.query(
            """
            SELECT id
            FROM trading.orders
            WHERE account_id = ? AND order_origin = ?
            ORDER BY created_at DESC, id DESC
            """,
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            context.accountId(),
            origin.name())
        .stream()
        .filter(id -> context.logicalRefs(id).isEmpty())
        .findFirst()
        .ifPresent(id -> context.putRef(logicalRef, id));
  }

  private void closeAll(
      ScenarioContext context,
      ScenarioDefinition scenario,
      ScenarioAction action
  ) {
    if ("ONE_SLOT_VERSION_BECOMES_STALE".equals(action.parameters().condition())) {
      List<PositionEntity> positions =
          positionRepository.findOpenLinearPerpByAccountId(context.accountId());
      if (positions.size() < 2) {
        throw new BusinessException(
            ErrorCode.EXECUTION_UNAVAILABLE,
            "Partial close-all scenario requires two sorted open positions");
      }
      installPositionUpdateFailureTrigger(positions.get(1).getId());
      try {
        BatchActionResponse response = closeAllPositionService.closeUser(
            context.userId(),
            context.accountId(),
            action.parameters().actionId());
        bindBatchOrders(context, response, action.parameters().actionId());
        requireOneBatchSuccessAndOneFailure(response);
        return;
      } finally {
        dropFailureTriggers();
      }
    }
    BatchActionResponse response = closeAllPositionService.closeUser(
        context.userId(),
        context.accountId(),
        action.parameters().actionId());
    bindBatchOrders(context, response, action.parameters().actionId());
    throwBatchFailure(response);
  }

  private void adminForceClose(
      ScenarioContext context,
      ScenarioDefinition scenario,
      ScenarioAction action
  ) {
    UUID positionId = positionId(context, scenario, action.direction());
    boolean rejected = "NON_ADMIN_ATTEMPTS_FORCE_CLOSE".equals(
        action.parameters().condition());
    UserPrincipal principal = rejected
        ? context.principal()
        : new UserPrincipal(
            context.userId(),
            context.email(),
            "ADMIN",
            List.of("ROLE_ADMIN", "trading:position:force-close"));
    AdminForceClosePositionRequest request = new AdminForceClosePositionRequest(
        context.accountId(),
        "Scenario admin force close",
        action.parameters().actionId(),
        "CONFIRM_FORCE_CLOSE");
    MvcResult result;
    try {
      result = mockMvc.perform(post(
              "/api/admin/trading/positions/{positionId}/force-close",
              positionId)
              .with(user(principal))
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsBytes(request)))
          .andReturn();
    } catch (Exception exception) {
      throw new IllegalStateException("Admin force-close request failed", exception);
    }

    int status = result.getResponse().getStatus();
    JsonNode body;
    try {
      body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
    } catch (Exception exception) {
      throw new IllegalStateException("Admin force-close response is not JSON", exception);
    }
    String code = body.path("code").asText();
    if (rejected) {
      if (status != 403 || !ErrorCode.FORBIDDEN.equals(code)) {
        throw new IllegalStateException(
            "USER force-close must return 403/FORBIDDEN but returned "
                + status + "/" + code);
      }
      throw new BusinessException(
          ErrorCode.FORBIDDEN,
          "USER principal cannot force-close an admin position");
    }
    if (status < 200 || status >= 300) {
      throw new BusinessException(
          code.isBlank() ? ErrorCode.EXECUTION_UNAVAILABLE : code,
          "Admin force-close returned HTTP " + status);
    }
    bindNewestSystemOrder(
        context,
        OrderOrigin.ADMIN_FORCE_CLOSE,
        action.parameters().actionId() + "-order");
  }

  private void race(
      ScenarioContext context,
      ScenarioDefinition scenario,
      ScenarioAction action,
      int actionIndex
  ) {
    String condition = action.parameters().condition();
    switch (condition) {
      case "OCO_DUAL_TRIGGER" -> raceOco(context, scenario, action, actionIndex);
      case "TWO_6000_USDT_ORDERS_COMPETE_FOR_10000" ->
          raceSpotBalance(context, scenario, action);
      case "TWO_FULL_CLOSES_COMPETE_FOR_ONE_POSITION" -> {
        UUID positionId = positionId(context, scenario, action.direction());
        runRace(action, List.of(
            allowLegalRaceLoss(action, () -> bindRaceClose(
                    context,
                    action.parameters().orderId(),
                    systemCloseOrderService.closeUserWhole(
                        context.userId(),
                        context.accountId(),
                        positionId,
                        action.parameters().actionId() + "-first"))),
            allowLegalRaceLoss(action, () -> bindRaceClose(
                    context,
                    action.parameters().orderId(),
                    systemCloseOrderService.closeUserWhole(
                        context.userId(),
                        context.accountId(),
                        positionId,
                        action.parameters().actionId() + "-second")))));
      }
      case "SINGLE_CLOSE_COMPETES_WITH_CLOSE_ALL" -> {
        UUID positionId = positionId(context, scenario, action.direction());
        runRace(action, List.of(
            allowLegalRaceLoss(action, () -> bindRaceClose(
                    context,
                    action.parameters().orderId(),
                    systemCloseOrderService.closeUserWhole(
                        context.userId(),
                        context.accountId(),
                        positionId,
                        action.parameters().actionId() + "-single"))),
            allowLegalRaceLoss(action, () -> bindRaceBatchWinnerAndRequireSuccess(
                    context,
                    closeAllPositionService.closeUser(
                        context.userId(),
                        context.accountId(),
                        action.parameters().actionId() + "-batch"),
                    action.parameters().competingOrderId()))));
      }
      case "USER_CLOSE_COMPETES_WITH_LIQUIDATION" -> {
        UUID positionId = positionId(context, scenario, action.direction());
        runRace(action, List.of(
            allowLegalRaceLoss(action, () -> bindRaceClose(
                    context,
                    action.parameters().orderId(),
                    systemCloseOrderService.closeUserWhole(
                        context.userId(),
                        context.accountId(),
                        positionId,
                        action.parameters().actionId() + "-user"))),
            allowLegalRaceLoss(action, () ->
                liquidate(context, action, action.parameters().competingOrderId()))));
      }
      case "USER_CLOSE_COMPETES_WITH_STOP_LOSS" -> {
        UUID positionId = positionId(context, scenario, action.direction());
        UUID protectionId = context.requireRef(action.parameters().competingOrderId());
        runRace(action, List.of(
            allowLegalRaceLoss(action, () -> bindRaceClose(
                    context,
                    action.parameters().orderId(),
                    systemCloseOrderService.closeUserWhole(
                        context.userId(),
                        context.accountId(),
                        positionId,
                        action.parameters().actionId() + "-user"))),
            allowLegalRaceLoss(action, () -> bindRaceClose(
                    context,
                    action.parameters().competingOrderId(),
                    systemCloseOrderService.executeProtection(
                        protectionId,
                        ExecutableMarketSnapshot.from(
                            perpetualBundle(context, context.currentPriceStep())))))));
      }
      default -> throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "Unsupported coordinated scenario race: " + condition);
    }
  }

  private void raceOco(
      ScenarioContext context,
      ScenarioDefinition scenario,
      ScenarioAction action,
      int actionIndex
  ) {
    OrderEntity limit = requireOrder(context.requireRef(action.parameters().orderId()));
    OrderEntity stop = requireOrder(context.requireRef(action.parameters().competingOrderId()));
    ScenarioPriceStep limitMarket = scenario.priceSteps().get(
        Math.max(0, Math.min(actionIndex, scenario.priceSteps().size() - 1)));
    ScenarioPriceStep executableLimitMarket = executableSellLimitStep(limitMarket, limit);
    ScenarioPriceStep executableStopMarket = executableSellStopStep(
        context.currentPriceStep(), stop);
    runRace(action, List.of(
        () -> RaceOutcome.fromCommit(pendingOrderExecutionProcessor.process(
            limit,
            ExecutableMarketSnapshot.from(spotBundle(context, executableLimitMarket)))),
        () -> RaceOutcome.fromCommit(pendingOrderExecutionProcessor.process(
            stop,
            ExecutableMarketSnapshot.from(spotBundle(context, executableStopMarket))))));
  }

  private static ScenarioPriceStep executableSellLimitStep(
      ScenarioPriceStep market,
      OrderEntity limit
  ) {
    BigDecimal limitPrice = limit.getPrice() == null
        ? limit.getRequestedPrice()
        : limit.getPrice();
    if (limitPrice == null) {
      throw new BusinessException(
          ErrorCode.ORDER_PRICE_REQUIRED,
          "OCO LIMIT winner requires a persisted limit price");
    }
    BigDecimal ask = market.ask() == null
        ? null
        : market.ask().max(limitPrice);
    return new ScenarioPriceStep(
        market.path(),
        market.sequence(),
        limitPrice,
        ask,
        market.last(),
        market.mark(),
        market.index(),
        market.source(),
        market.asOf(),
        market.expiresAt(),
        market.missingFields());
  }

  private static ScenarioPriceStep executableSellStopStep(
      ScenarioPriceStep market,
      OrderEntity stop
  ) {
    BigDecimal triggerPrice = stop.getTriggerPrice();
    if (triggerPrice == null) {
      throw new BusinessException(
          ErrorCode.ORDER_TRIGGER_PRICE_REQUIRED,
          "OCO STOP competitor requires a persisted trigger price");
    }
    BigDecimal bid = market.bid() == null
        ? null
        : market.bid().min(triggerPrice);
    BigDecimal executableLast = market.last() == null
        ? triggerPrice
        : market.last().min(triggerPrice);
    return new ScenarioPriceStep(
        market.path(),
        market.sequence(),
        bid,
        market.ask(),
        executableLast,
        market.mark(),
        market.index(),
        market.source(),
        market.asOf(),
        market.expiresAt(),
        market.missingFields());
  }

  private void raceSpotBalance(
      ScenarioContext context,
      ScenarioDefinition scenario,
      ScenarioAction action
  ) {
    ScenarioAction.Parameters parameters = action.parameters();
    if (action.quantity() == null
        || parameters.clientOrderId().isBlank()
        || parameters.orderId().isBlank()
        || parameters.competingOrderId().isBlank()) {
      throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "Balance race requires two request identities and one canonical order reference");
    }
    runRace(action, List.of(
        allowLegalRaceLoss(action, () -> {
          OrderResponse response = orderService.createOrder(
              context.principal(),
              createOrderRequest(
                  context,
                  scenario,
                  action,
                  parameters.clientOrderId()));
          requireRaceOrder(response);
          bindOrder(context, parameters.orderId(), response);
          return RaceOutcome.committed();
        }),
        allowLegalRaceLoss(action, () -> {
          OrderResponse response = orderService.createOrder(
              context.principal(),
              createOrderRequest(
                  context,
                  scenario,
                  action,
                  parameters.competingOrderId()));
          requireRaceOrder(response);
          bindOrder(context, parameters.orderId(), response);
          return RaceOutcome.committed();
        })));
  }

  private static void requireRaceOrder(OrderResponse response) {
    if (response == null || response.id() == null) {
      throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "Race competitor returned no committed order");
    }
  }

  private void revalue(
      ScenarioContext context,
      ScenarioDefinition scenario
  ) {
    if (scenario.productType() == ProductType.CRYPTO_SPOT) {
      return;
    }
    Instant to = Instant.now();
    ExecutableMarketSnapshot snapshot = ExecutableMarketSnapshot.from(
        marketBundleResolver.resolvePerp(
            context.symbol(),
            new CandleRequest("1m", to.minus(Duration.ofMinutes(30)), to)));
    PerpetualAccountRiskSnapshotService.PreparedAccountRisk prepared =
        accountRiskSnapshotService.prepare(
            context.accountId(),
            Map.of(context.symbol(), snapshot));
    transactionExecutor.execute(() -> {
      TradingAccountEntity account = accountRepository
          .findByIdForUpdate(context.accountId())
          .orElseThrow(() -> new BusinessException(
              ErrorCode.ACCOUNT_NOT_FOUND,
              "Scenario account is missing during revaluation"));
      List<PositionEntity> positions =
          positionRepository.findOpenLinearPerpByAccountIdForUpdate(context.accountId());
      List<OrderEntity> orders =
          orderRepository.findActiveLinearPerpByAccountIdForUpdate(context.accountId());
      PerpetualAccountRiskSnapshotService.AccountRiskProjection projection =
          accountRiskSnapshotService.project(account, positions, orders, prepared);
      accountRiskSnapshotService.applyRevaluation(account, positions, projection);
      positions.forEach(positionRepository::save);
      accountRepository.save(account);
      return null;
    });
  }

  private void configureMarket(
      ScenarioContext context,
      AtomicReference<String> resolvedSource
  ) {
    boolean pinLockWaitBundle = context.scenario().actions().stream()
        .map(ScenarioAction::parameters)
        .map(ScenarioAction.Parameters::failureCondition)
        .anyMatch("LOCK_WAIT_EXCEEDS_MARKET_BUNDLE_TTL"::equals);
    CountDownLatch marketResolved = new CountDownLatch(pinLockWaitBundle ? 1 : 0);
    lockWaitMarketResolved.set(marketResolved);
    AtomicReference<RebasedStep> pinnedLockWaitMarket = new AtomicReference<>();
    reset(marketBundleResolver);
    when(marketBundleResolver.resolveSpot(
        eq("BTCUSDT"), any(CandleRequest.class)))
        .thenAnswer(ignored -> {
          ScenarioPriceStep step = context.currentPriceStep();
          resolvedSource.set(step.source());
          return spotBundle(context, step);
        });
    when(marketBundleResolver.resolvePerp(
        eq("BTCUSDT-PERP"), any(CandleRequest.class)))
        .thenAnswer(ignored -> {
          ScenarioPriceStep step = context.currentPriceStep();
          resolvedSource.set(step.source());
          RebasedStep market = pinLockWaitBundle
              ? pinnedLockWaitMarket.updateAndGet(
                  current -> current == null ? rebase(step) : current)
              : rebase(step);
          PerpetualMarketBundle bundle = perpetualBundle(context, step, market);
          marketResolved.countDown();
          return bundle;
        });
  }

  private SpotMarketBundle spotBundle(
      ScenarioContext context,
      ScenarioPriceStep step
  ) {
    RebasedStep market = rebase(step);
    return new SpotMarketBundle(
        context.symbol(),
        "binance".equals(step.source()) ? "BTCUSDT" : "BTC-USDT",
        providerCode(ProductType.CRYPTO_SPOT, step.source()),
        MarketSourceMode.PUBLIC_EXTERNAL,
        step.bid(),
        step.ask(),
        step.last(),
        null,
        List.of(),
        List.of(),
        market.asOf(),
        market.expiresAt());
  }

  private PerpetualMarketBundle perpetualBundle(
      ScenarioContext context,
      ScenarioPriceStep step
  ) {
    return perpetualBundle(context, step, rebase(step));
  }

  private PerpetualMarketBundle perpetualBundle(
      ScenarioContext context,
      ScenarioPriceStep step,
      RebasedStep market
  ) {
    return new PerpetualMarketBundle(
        context.symbol(),
        "binance".equals(step.source()) ? "BTCUSDT" : "BTC-USDT-SWAP",
        providerCode(ProductType.LINEAR_PERP, step.source()),
        MarketSourceMode.PUBLIC_EXTERNAL,
        step.bid(),
        step.ask(),
        step.last(),
        step.mark(),
        step.index(),
        null,
        List.of(),
        List.of(),
        market.asOf(),
        market.expiresAt());
  }

  private static RebasedStep rebase(ScenarioPriceStep step) {
    Instant now = Instant.now();
    Instant asOf = step.asOf() == null ? null : now;
    Instant expiresAt = step.expiresAt() == null
        ? null
        : step.asOf() == null
            ? now.plusSeconds(5)
            : now.plus(Duration.between(step.asOf(), step.expiresAt()));
    return new RebasedStep(asOf, expiresAt);
  }

  private static String providerCode(ProductType productType, String source) {
    if ("okx".equalsIgnoreCase(source)) {
      return productType == ProductType.CRYPTO_SPOT ? "okx" : "okx-swap";
    }
    return productType == ProductType.CRYPTO_SPOT ? "binance" : "binance-usdm";
  }

  private UUID positionId(
      ScenarioContext context,
      ScenarioDefinition scenario,
      PositionSide requested
  ) {
    PositionSide slot = scenario.positionMode() == PositionMode.ONE_WAY
        ? PositionSide.BOTH
        : requested == PositionSide.BOTH
            ? scenario.positionSide()
            : requested;
    String logicalRef = context.symbol() + ":" + slot;
    return context.findRef(logicalRef).orElseGet(() -> {
      PositionEntity position = positionRepository
          .findOpenLinearPerpByAccountId(context.accountId()).stream()
          .filter(candidate -> candidate.getPositionSide() == slot)
          .findFirst()
          .orElseThrow(() -> new BusinessException(
              "POSITION_NOT_FOUND",
              "No open scenario position exists for " + logicalRef));
      context.putRef(logicalRef, position.getId());
      context.putRef("position:" + slot, position.getId());
      return position.getId();
    });
  }

  private PositionEntity findPosition(
      ScenarioContext context,
      PositionSide requested
  ) {
    PositionSide slot = context.scenario().positionMode() == PositionMode.ONE_WAY
        ? PositionSide.BOTH
        : requested;
    return positionRepository.findOpenLinearPerpByAccountId(context.accountId()).stream()
        .filter(position -> position.getPositionSide() == slot)
        .findFirst()
        .orElse(null);
  }

  private static QuantityUnit quantityUnit(
      ScenarioAction action,
      ScenarioDefinition scenario
  ) {
    return action.parameters().quantityUnit() == null
        ? scenario.quantityUnit()
        : action.parameters().quantityUnit();
  }

  private static long version(Long value) {
    return value == null ? 0L : value;
  }

  private OrderEntity requireOrder(UUID orderId) {
    OrderEntity order = orderRepository.selectById(orderId);
    if (order == null) {
      throw new BusinessException("ORDER_NOT_FOUND", "Scenario order not found");
    }
    return order;
  }

  private PositionEntity requirePosition(UUID positionId) {
    PositionEntity position = positionRepository.selectById(positionId);
    if (position == null) {
      throw new BusinessException("POSITION_NOT_FOUND", "Scenario position not found");
    }
    return position;
  }

  private static void bindOrder(
      ScenarioContext context,
      String logicalRef,
      OrderResponse response
  ) {
    if (response != null && logicalRef != null && !logicalRef.isBlank()) {
      context.putRef(logicalRef, response.id());
    }
  }

  private void bindAttachedProtection(
      ScenarioContext context,
      ScenarioAction action,
      OrderResponse parent
  ) {
    ScenarioAction.Parameters parameters = action.parameters();
    if (parent == null
        || !"ATTACHED_TO_ENTRY".equals(parameters.condition())
        || parameters.protectionType() == null) {
      return;
    }
    OrderEntity carrier = orderRepository.findByAccountIdAndStatusIn(
            context.accountId(),
            List.of(
                OrderStatus.PENDING_ACTIVATION,
                OrderStatus.PENDING,
                OrderStatus.WORKING))
        .stream()
        .filter(order -> parent.id().equals(order.getParentOrderId()))
        .filter(order -> parameters.protectionType() == order.getProtectionType())
        .findFirst()
        .orElseThrow(() -> new BusinessException(
            ErrorCode.EXECUTION_UNAVAILABLE,
            "Attached protection carrier was not persisted"));
    if (!parameters.protectionId().isBlank()) {
      context.putRef(parameters.protectionId(), carrier.getId());
    }
    if (!parameters.competingOrderId().isBlank()) {
      context.putRef(parameters.competingOrderId(), carrier.getId());
    }
  }

  private static Void bindClose(
      ScenarioContext context,
      String logicalRef,
      SystemCloseOrderService.CloseResult result
  ) {
    if (result != null && result.order() != null
        && logicalRef != null && !logicalRef.isBlank()) {
      context.putRef(logicalRef, result.order().getId());
    }
    return null;
  }

  private static RaceOutcome bindRaceClose(
      ScenarioContext context,
      String logicalRef,
      SystemCloseOrderService.CloseResult result
  ) {
    if (result == null || result.order() == null || result.order().getId() == null) {
      throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "Race close competitor returned no durable order");
    }
    bindClose(context, logicalRef, result);
    return result.replayed()
        ? RaceOutcome.legalLoss()
        : RaceOutcome.committed();
  }

  private PositionSide positionSlot(UUID positionId) {
    if (positionId == null) {
      throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "Batch item is missing its position id");
    }
    PositionEntity position = positionRepository.selectById(positionId);
    if (position == null || position.getPositionSide() == null) {
      throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "Batch item position is unavailable for deterministic slot binding");
    }
    return position.getPositionSide();
  }

  private static void bindPositionSlot(
      ScenarioContext context,
      UUID positionId,
      PositionSide slot
  ) {
    if (positionId == null || slot == null) {
      return;
    }
    context.putRef(context.symbol() + ":" + slot, positionId);
    context.putRef("position:" + slot, positionId);
  }

  private Void bindBatchOrders(
      ScenarioContext context,
      BatchActionResponse response,
      String logicalPrefix
  ) {
    if (response == null) {
      return null;
    }
    for (BatchActionResponse.Item item : response.items()) {
      PositionSide slot = positionSlot(item.positionId());
      bindPositionSlot(context, item.positionId(), slot);
      if (item.orderId() != null) {
        context.putRef(logicalPrefix + "-" + slot, item.orderId());
      }
    }
    return null;
  }

  private static RaceOutcome bindRaceBatchWinnerAndRequireSuccess(
      ScenarioContext context,
      BatchActionResponse response,
      String canonicalRef
  ) {
    if (response == null) {
      throwBatchFailure(null);
    }
    if (response.items().isEmpty()) {
      return RaceOutcome.legalLoss();
    }
    throwBatchFailure(response);
    UUID orderId = response.items().stream()
        .filter(item -> !"FAILED".equalsIgnoreCase(item.status()))
        .map(BatchActionResponse.Item::orderId)
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow(() -> new BusinessException(
            ErrorCode.EXECUTION_UNAVAILABLE,
            "Race batch competitor returned no durable order"));
    context.putRef(canonicalRef, orderId);
    return RaceOutcome.committed();
  }

  private static void requireOneBatchSuccessAndOneFailure(
      BatchActionResponse response
  ) {
    if (response == null || response.items().size() != 2) {
      throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "Partial close-all must return exactly two batch items");
    }
    BatchActionResponse.Item first = response.items().get(0);
    BatchActionResponse.Item second = response.items().get(1);
    boolean firstSucceeded = !"FAILED".equalsIgnoreCase(first.status())
        && first.orderId() != null;
    boolean secondFailed = "FAILED".equalsIgnoreCase(second.status())
        && second.orderId() == null;
    if (!firstSucceeded || !secondFailed) {
      throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "Partial close-all must preserve one successful first slot and one failed second slot");
    }
  }

  private static void throwBatchFailure(BatchActionResponse response) {
    if (response == null) {
      throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "Batch service returned no response");
    }
    response.items().stream()
        .filter(item -> "FAILED".equalsIgnoreCase(item.status()))
        .findFirst()
        .ifPresent(item -> {
          throw new BusinessException(
              item.errorCode() == null || item.errorCode().isBlank()
                  ? ErrorCode.EXECUTION_UNAVAILABLE
                  : item.errorCode(),
              item.message() == null ? "Batch item failed" : item.message());
        });
  }

  private List<UUID> fundingSettlementIds(UUID accountId) {
    return jdbcTemplate.query(
        """
        SELECT id
        FROM trading.funding_settlements
        WHERE account_id = ?
        ORDER BY funding_time, id
        """,
        (rs, rowNum) -> rs.getObject("id", UUID.class),
        accountId);
  }

  private List<UUID> ledgerReferenceIds(
      UUID accountId,
      String referenceType
  ) {
    return jdbcTemplate.query(
        """
        SELECT reference_id
        FROM (
          SELECT reference_id, created_at
          FROM ledger.asset_ledger_entries
          WHERE account_id = ? AND reference_type = ?
          UNION ALL
          SELECT reference_id, created_at
          FROM ledger.ledger_entries
          WHERE account_id = ? AND reference_type = ?
        ) refs
        WHERE reference_id IS NOT NULL
        GROUP BY reference_id
        ORDER BY MIN(created_at), reference_id
        """,
        (rs, rowNum) -> rs.getObject("reference_id", UUID.class),
        accountId,
        referenceType,
        accountId,
        referenceType);
  }

  private List<SystemOrderBinding> liquidationOrderBindings(UUID accountId) {
    return jdbcTemplate.query(
        """
        SELECT id, parent_position_id, position_side
        FROM trading.orders
        WHERE account_id = ? AND order_origin = 'LIQUIDATION'
        ORDER BY created_at, id
        """,
        (rs, rowNum) -> new SystemOrderBinding(
            rs.getObject("id", UUID.class),
            rs.getObject("parent_position_id", UUID.class),
            PositionSide.valueOf(rs.getString("position_side"))),
        accountId);
  }

  private static void bindOnlyNewReference(
      ScenarioContext context,
      String logicalRef,
      List<UUID> before,
      List<UUID> after,
      String label
  ) {
    Set<UUID> existing = new HashSet<>(before);
    List<UUID> created = after.stream()
        .filter(id -> !existing.contains(id))
        .toList();
    if (created.size() > 1) {
      throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          label + " created more than one durable reference");
    }
    if (!created.isEmpty()) {
      context.putRef(logicalRef, created.getFirst());
    }
  }

  private static Supplier<RaceOutcome> allowLegalRaceLoss(
      ScenarioAction action,
      Supplier<RaceOutcome> competitor
  ) {
    return () -> {
      try {
        return competitor.get();
      } catch (BusinessException failure) {
        if (!isLegalRaceLoss(action.parameters().condition(), failure.getCode())) {
          throw failure;
        }
        return RaceOutcome.legalLoss(failure);
      }
    };
  }

  private static boolean isLegalRaceLoss(String condition, String errorCode) {
    return switch (condition) {
      case "TWO_6000_USDT_ORDERS_COMPETE_FOR_10000" ->
          ErrorCode.INSUFFICIENT_BALANCE.equals(errorCode);
      case "TWO_FULL_CLOSES_COMPETE_FOR_ONE_POSITION",
           "SINGLE_CLOSE_COMPETES_WITH_CLOSE_ALL" ->
          ErrorCode.POSITION_NOT_FOUND.equals(errorCode);
      case "USER_CLOSE_COMPETES_WITH_LIQUIDATION" ->
          ErrorCode.POSITION_NOT_FOUND.equals(errorCode)
              || ErrorCode.MARKET_DATA_STALE.equals(errorCode);
      case "USER_CLOSE_COMPETES_WITH_STOP_LOSS" ->
          ErrorCode.POSITION_NOT_FOUND.equals(errorCode)
              || ErrorCode.PROTECTION_NOT_EXECUTABLE.equals(errorCode);
      default -> false;
    };
  }

  private void runRace(
      ScenarioAction action,
      List<Supplier<RaceOutcome>> competitors
  ) {
    if (competitors == null || competitors.size() != 2) {
      throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "Scenario race requires exactly two competitors");
    }
    CountDownLatch ready = new CountDownLatch(competitors.size());
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(competitors.size());
    List<Future<RaceOutcome>> futures = new ArrayList<>(competitors.size());
    List<RaceOutcome> outcomes = new ArrayList<>(competitors.size());
    try {
      for (Supplier<RaceOutcome> competitor : competitors) {
        futures.add(workers.submit(() -> {
          ready.countDown();
          await(start);
          return competitor.get();
        }));
      }
      if (!ready.await(20, TimeUnit.SECONDS)) {
        throw new BusinessException(
            ErrorCode.EXECUTION_UNAVAILABLE,
            "Scenario race competitors did not become ready within 20 seconds");
      }
      start.countDown();
      for (Future<RaceOutcome> future : futures) {
        try {
          RaceOutcome outcome = future.get(20, TimeUnit.SECONDS);
          if (outcome == null) {
            throw new BusinessException(
                ErrorCode.EXECUTION_UNAVAILABLE,
                "Scenario race competitor returned no outcome");
          }
          outcomes.add(outcome);
        } catch (ExecutionException exception) {
          throw unwrap(exception);
        } catch (TimeoutException exception) {
          throw new BusinessException(
              ErrorCode.EXECUTION_UNAVAILABLE,
              "Scenario race timed out after 20 seconds");
        }
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(exception);
    } finally {
      futures.forEach(future -> future.cancel(true));
      start.countDown();
      workers.shutdownNow();
    }

    long committed = outcomes.stream()
        .filter(outcome -> outcome.status() == RaceStatus.COMMITTED)
        .count();
    long legalLosses = outcomes.stream()
        .filter(outcome -> outcome.status() == RaceStatus.LEGAL_LOSS)
        .count();
    if (committed != 1 || legalLosses != 1) {
      throw new BusinessException(
          ErrorCode.EXECUTION_UNAVAILABLE,
          "Expected one committed race winner and one legal loser but observed "
              + committed + " committed and " + legalLosses + " legal losses");
    }
    if (!action.parameters().failureCondition().isBlank()) {
      List<RuntimeException> classifiedFailures = outcomes.stream()
          .map(RaceOutcome::failure)
          .filter(Objects::nonNull)
          .toList();
      if (classifiedFailures.size() != 1) {
        throw new BusinessException(
            ErrorCode.EXECUTION_UNAVAILABLE,
            "Expected the legal race loser to carry one classified failure");
      }
      throw classifiedFailures.getFirst();
    }
  }

  private static void await(CountDownLatch start) {
    try {
      start.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(exception);
    }
  }

  private static RuntimeException unwrap(Throwable throwable) {
    Throwable current = throwable;
    while ((current instanceof ExecutionException
        || current instanceof java.util.concurrent.CompletionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current instanceof RuntimeException runtime
        ? runtime
        : new RuntimeException(current);
  }

  private static FailureState failureState(
      RuntimeException failure,
      boolean zeroMutation
  ) {
    if (failure instanceof BusinessException business) {
      return new FailureState(
          business.getCode(),
          business.getClass().getSimpleName(),
          zeroMutation);
    }
    return new FailureState(
        ErrorCode.EXECUTION_UNAVAILABLE,
        failure.getClass().getSimpleName(),
        zeroMutation);
  }

  private static Snapshot withEvents(
      Snapshot snapshot,
      List<EventState> events
  ) {
    return new Snapshot(
        snapshot.orders(),
        snapshot.trades(),
        snapshot.positions(),
        snapshot.wallets(),
        snapshot.account(),
        snapshot.ledger(),
        snapshot.protections(),
        events);
  }

  private static List<EventState> mergeEvents(
      List<EventState> databaseEvents,
      List<SyntheticEvent> syntheticEvents
  ) {
    List<SyntheticEvent> ordered = syntheticEvents.stream()
        .sorted(Comparator.comparingInt(SyntheticEvent::databaseEventsBefore))
        .toList();
    List<EventState> merged = new ArrayList<>(
        databaseEvents.size() + ordered.size());
    int syntheticIndex = 0;
    for (int databaseIndex = 0;
         databaseIndex <= databaseEvents.size();
         databaseIndex++) {
      while (syntheticIndex < ordered.size()
          && ordered.get(syntheticIndex).databaseEventsBefore() == databaseIndex) {
        merged.add(ordered.get(syntheticIndex).event());
        syntheticIndex++;
      }
      if (databaseIndex < databaseEvents.size()) {
        merged.add(databaseEvents.get(databaseIndex));
      }
    }
    List<EventState> sequenced = new ArrayList<>(merged.size());
    for (int index = 0; index < merged.size(); index++) {
      EventState event = merged.get(index);
      sequenced.add(new EventState(
          index + 1,
          event.type(),
          event.subjectRef(),
          event.fromStatus(),
          event.toStatus(),
          event.code()));
    }
    return List.copyOf(sequenced);
  }

  private void installTradeFailureTrigger() {
    dropFailureTriggers();
    jdbcTemplate.execute("""
        CREATE FUNCTION public.scenario_fail_trade_insert() RETURNS trigger
        LANGUAGE plpgsql AS $$
        BEGIN
          RAISE EXCEPTION 'scenario injected trade insert failure';
        END;
        $$
        """);
    jdbcTemplate.execute("""
        CREATE TRIGGER scenario_fail_trade_insert
        BEFORE INSERT ON trading.trades
        FOR EACH ROW EXECUTE FUNCTION public.scenario_fail_trade_insert()
        """);
  }

  private void installLedgerFailureTrigger() {
    dropFailureTriggers();
    jdbcTemplate.execute("""
        CREATE FUNCTION public.scenario_fail_ledger_insert() RETURNS trigger
        LANGUAGE plpgsql AS $$
        BEGIN
          IF NEW.reference_type = 'TRADE' THEN
            RAISE EXCEPTION 'scenario injected ledger insert failure';
          END IF;
          RETURN NEW;
        END;
        $$
        """);
    jdbcTemplate.execute("""
        CREATE TRIGGER scenario_fail_ledger_insert
        BEFORE INSERT ON ledger.ledger_entries
        FOR EACH ROW EXECUTE FUNCTION public.scenario_fail_ledger_insert()
        """);
  }

  private void installPositionUpdateFailureTrigger(UUID positionId) {
    dropFailureTriggers();
    jdbcTemplate.execute("""
        CREATE FUNCTION public.scenario_fail_position_update() RETURNS trigger
        LANGUAGE plpgsql AS $$
        BEGIN
          RAISE EXCEPTION 'scenario injected position update failure';
        END;
        $$
        """);
    jdbcTemplate.execute("""
        CREATE TRIGGER scenario_fail_position_update
        BEFORE UPDATE ON trading.positions
        FOR EACH ROW
        WHEN (
          OLD.id = '%s'::uuid
          AND OLD.status IS DISTINCT FROM NEW.status
          AND NEW.status = 'CLOSED'
        )
        EXECUTE FUNCTION public.scenario_fail_position_update()
        """.formatted(positionId));
  }

  private void dropFailureTriggers() {
    jdbcTemplate.execute(
        "DROP TRIGGER IF EXISTS " + TRADE_FAILURE_TRIGGER + " ON trading.trades");
    jdbcTemplate.execute(
        "DROP FUNCTION IF EXISTS " + TRADE_FAILURE_FUNCTION + "()");
    jdbcTemplate.execute(
        "DROP TRIGGER IF EXISTS " + LEDGER_FAILURE_TRIGGER + " ON ledger.ledger_entries");
    jdbcTemplate.execute(
        "DROP FUNCTION IF EXISTS " + LEDGER_FAILURE_FUNCTION + "()");
    jdbcTemplate.execute(
        "DROP TRIGGER IF EXISTS " + POSITION_FAILURE_TRIGGER + " ON trading.positions");
    jdbcTemplate.execute(
        "DROP FUNCTION IF EXISTS " + POSITION_FAILURE_FUNCTION + "()");
  }

  private record RebasedStep(Instant asOf, Instant expiresAt) {
  }

  private record SystemOrderBinding(
      UUID orderId,
      UUID positionId,
      PositionSide slot
  ) {
  }

  private record SyntheticEvent(int databaseEventsBefore, EventState event) {
  }
}
