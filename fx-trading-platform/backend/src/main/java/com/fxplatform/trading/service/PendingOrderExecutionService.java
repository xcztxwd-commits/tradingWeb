package com.fxplatform.trading.service;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
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
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
      FullFillCoordinator fullFillCoordinator
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
        null);
  }

  @Scheduled(fixedDelayString = "${trading.pending-order-scan-ms:1000}")
  public int executePendingOrders() {
    int filled = 0;
    for (OrderEntity order : orderRepository.findByStatus(OrderStatus.PENDING)) {
      try {
        boolean executed = isP0Order(order)
            ? prepareAndExecuteP0(order)
            : prepareAndExecuteLegacy(order);
        if (executed) {
          filled++;
        }
      } catch (BusinessException exception) {
        log.debug(
            "Pending order {} remains pending after business rejection {}",
            order.getId(),
            exception.getCode());
      } catch (RuntimeException exception) {
        log.warn(
            "Pending order {} failed without aborting later candidates: {}: {}",
            order.getId(),
            exception.getClass().getSimpleName(),
            exception.getMessage());
      }
    }
    return filled;
  }

  @Autowired
  void setPendingOrderExecutionProcessor(
      PendingOrderExecutionProcessor pendingOrderExecutionProcessor
  ) {
    this.pendingOrderExecutionProcessor = pendingOrderExecutionProcessor;
  }

  private boolean prepareAndExecuteP0(OrderEntity order) {
    TradingAccountEntity accountSnapshot = accountRepository.findById(order.getAccountId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    ProductType productType = requestedProduct(order.getSymbol());
    demoExecutionGuard.requireDemo(accountSnapshot, productType, order.getSymbol());

    if (productType == ProductType.CRYPTO_SPOT && pendingOrderExecutionProcessor != null) {
      return prepareAndExecuteSpot(order);
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
        snapshot);
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

  private boolean prepareAndExecuteLegacy(OrderEntity order) {
    if (order.getRequestedPrice() == null) {
      return false;
    }
    ProductType productType = requestedProduct(order.getSymbol());
    TradingAccountEntity accountSnapshot = accountRepository.findById(order.getAccountId())
        .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Account not found"));
    demoExecutionGuard.requireDemo(accountSnapshot, productType, order.getSymbol());
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
    return marketBundleResolver != null
        && fullFillCoordinator != null
        && order.getSymbol() != null
        && P0_SYMBOLS.contains(SymbolNormalizer.normalize(order.getSymbol()));
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
      ExecutableMarketSnapshot snapshot
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
