package com.fxplatform.trading.service;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.execution.FullFillExecutionPath;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import com.fxplatform.trading.dto.request.CreateOcoOrderRequest;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.dto.response.OcoOrderGroupResponse;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Creates, replays and cancels one two-leg P0 Spot OCO contingency group. */
@Service
@RequiredArgsConstructor
public class OcoOrderService {

  private static final int MAX_KEY_LENGTH = 128;
  private static final int MAX_STALE_ATTEMPTS = 2;

  private final OrderRepository orderRepository;
  private final TradingAccountRepository accountRepository;
  private final WalletService walletService;
  private final OrderEventService orderEventService;
  private final OrderResponseMapper orderResponseMapper;
  private final DemoExecutionGuard demoExecutionGuard;
  private final WalletBalanceRepository walletBalanceRepository;
  private final SpotPositionService spotPositionService;
  private final MarketBundleResolver marketBundleResolver;
  private final SymbolRepository symbolRepository;
  private final InstrumentRulesEngine instrumentRulesEngine;
  private final QuantityConversionService quantityConversionService;
  private final OrderHoldCalculator orderHoldCalculator;
  private final FullFillCoordinator fullFillCoordinator;
  private final TradingTransactionExecutor transactionExecutor;

  public OcoOrderGroupResponse create(UserPrincipal principal, CreateOcoOrderRequest request) {
    String symbolCode = SymbolNormalizer.normalize(request.symbol());
    LegKeys keys = legKeys(request);
    TradingAccountEntity accountSnapshot = requireOwnedAccount(
        principal.id(), request.accountId(), false);
    demoExecutionGuard.requireDemo(accountSnapshot, ProductType.CRYPTO_SPOT, symbolCode);
    Optional<OcoOrderGroupResponse> replay = findExisting(
        principal.id(), request.accountId(), keys);
    if (replay.isPresent()) {
      return replay.get();
    }
    validateContract(request);
    SymbolEntity symbol = symbolRepository.findBySymbol(symbolCode)
        .orElseThrow(() -> new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found"));
    InstrumentRules rules = instrumentRulesEngine.rules(symbol);
    QuantityConversionService.Conversion conversion = quantityConversionService.convertSpot(
        request.side(),
        OrderType.LIMIT,
        request.quantityUnit(),
        request.quantity(),
        rules.stepSize(),
        null);
    CreateOrderRequest limitRulesRequest = rulesRequest(
        request, symbolCode, OrderType.LIMIT, request.limitPrice(), null,
        keys.limitClientKey(), keys.limitIdempotencyKey());
    CreateOrderRequest stopRulesRequest = rulesRequest(
        request, symbolCode, OrderType.STOP_MARKET, null, request.stopTriggerPrice(),
        keys.stopClientKey(), keys.stopIdempotencyKey());

    BusinessException lastStale = null;
    for (int attempt = 0; attempt < MAX_STALE_ATTEMPTS; attempt++) {
      try {
        ExecutableMarketSnapshot snapshot = resolveSnapshot(symbolCode);
        validatePriceMatrix(request, snapshot.last());
        instrumentRulesEngine.validateCanonicalOrder(
            limitRulesRequest, symbol, conversion.baseQuantity(), request.limitPrice());
        BigDecimal stopReferencePrice = fullFillCoordinator.project(
            ProductType.CRYPTO_SPOT,
            request.side(),
            FullFillExecutionPath.TRIGGERED_STOP_MARKET,
            null,
            snapshot).filledPrice();
        instrumentRulesEngine.validateCanonicalOrder(
            stopRulesRequest, symbol, conversion.baseQuantity(), stopReferencePrice);
        OrderHoldCalculator.OrderHold hold = orderHoldCalculator.oco(
            request.side(),
            conversion.baseQuantity(),
            request.limitPrice(),
            request.stopTriggerPrice(),
            snapshot);
        PreparedGroup prepared = prepareGroup(
            principal.id(), request, symbolCode, conversion.baseQuantity(), hold, keys);
        try {
          return transactionExecutor.execute(() -> persist(prepared, snapshot, keys));
        } catch (DataIntegrityViolationException exception) {
          return findExisting(principal.id(), request.accountId(), keys)
              .orElseThrow(() -> exception);
        }
      } catch (BusinessException exception) {
        if (!ErrorCode.MARKET_DATA_STALE.equals(exception.getCode())) {
          throw exception;
        }
        lastStale = exception;
      }
    }
    throw new BusinessException(
        ErrorCode.MARKET_DATA_STALE,
        lastStale == null ? "No fresh executable Spot OCO snapshot" : lastStale.getMessage());
  }

  public OcoOrderGroupResponse cancelByLeg(UserPrincipal principal, UUID orderId) {
    OrderEntity snapshot = orderRepository.findByUserIdAndId(principal.id(), orderId)
        .orElseThrow(() -> new AuthorizationException("ORDER_NOT_FOUND", "Order not found"));
    if (snapshot.getContingencyGroupId() == null) {
      throw new BusinessException("ORDER_NOT_CANCELABLE", "Order is not an OCO leg");
    }
    TradingAccountEntity accountSnapshot = requireOwnedAccount(
        principal.id(), snapshot.getAccountId(), false);
    demoExecutionGuard.requireDemo(
        accountSnapshot, ProductType.CRYPTO_SPOT, snapshot.getSymbol());
    return transactionExecutor.execute(() -> cancelLocked(
        principal.id(), snapshot.getAccountId(), snapshot.getSymbol(),
        snapshot.getContingencyGroupId()));
  }

  private OcoOrderGroupResponse persist(
      PreparedGroup prepared,
      ExecutableMarketSnapshot snapshot,
      LegKeys keys
  ) {
    TradingAccountEntity account = requireOwnedAccount(
        prepared.userId(), prepared.accountId(), true);
    demoExecutionGuard.requireDemo(account, ProductType.CRYPTO_SPOT, prepared.symbol());
    Optional<OcoOrderGroupResponse> replay = findExisting(
        prepared.userId(), prepared.accountId(), keys);
    if (replay.isPresent()) {
      return replay.get();
    }
    // Reject a snapshot that expired while waiting for the account lock before any ensure can insert.
    fullFillCoordinator.requireFresh(snapshot);
    ensureState(account.getId(), prepared.symbol());
    // Ensures/lock waits can consume the remaining validity window; gate again next to first order write.
    fullFillCoordinator.requireFresh(snapshot);
    for (OrderEntity leg : prepared.orderedLegs()) {
      orderRepository.save(leg);
    }
    OrderEntity owner = prepared.holdOwner();
    walletService.lockAvailableWithEntryType(
        account.getId(),
        owner.getHoldCurrency(),
        owner.getHoldAmount(),
        "ORDER",
        owner.getId(),
        "OCO shared Spot hold locked",
        "SPOT_ORDER_LOCK");
    for (OrderEntity leg : prepared.orderedLegs()) {
      orderEventService.record(
          leg.getId(), "ORDER_PENDING", OrderStatus.ACCEPTED, OrderStatus.PENDING,
          null, "OCO leg accepted and waiting");
    }
    return response(prepared.orderedLegs());
  }

  private OcoOrderGroupResponse cancelLocked(
      UUID userId,
      UUID accountId,
      String symbol,
      UUID groupId
  ) {
    TradingAccountEntity account = requireOwnedAccount(userId, accountId, true);
    demoExecutionGuard.requireDemo(account, ProductType.CRYPTO_SPOT, symbol);
    lockExistingState(accountId);
    List<OrderEntity> legs = requireGroup(orderRepository.findByContingencyGroupIdForUpdate(groupId));
    if (legs.stream().anyMatch(leg -> !userId.equals(leg.getUserId())
        || !accountId.equals(leg.getAccountId())
        || !SymbolNormalizer.normalize(symbol).equals(SymbolNormalizer.normalize(leg.getSymbol())))) {
      throw new AuthorizationException("ORDER_NOT_FOUND", "Order not found");
    }
    if (legs.stream().allMatch(this::terminal)) {
      return response(legs);
    }
    OrderEntity owner = holdOwner(legs);
    BigDecimal held = owner.getHoldAmount() == null ? BigDecimal.ZERO : owner.getHoldAmount();
    if (held.compareTo(BigDecimal.ZERO) > 0) {
      walletService.releaseLockedWithEntryType(
          accountId,
          owner.getHoldCurrency(),
          held,
          "ORDER",
          owner.getId(),
          "OCO shared Spot hold released",
          "SPOT_ORDER_RELEASE");
      owner.setHoldAmount(BigDecimal.ZERO);
    }
    for (OrderEntity leg : legs.stream().sorted(Comparator.comparing(OrderEntity::getId)).toList()) {
      if (!terminal(leg)) {
        OrderStatus from = leg.getStatus();
        leg.setStatus(OrderStatus.CANCELED);
        leg.setCanceledAt(Instant.now());
        leg.setRemainingQuantity(BigDecimal.ZERO);
        orderRepository.save(leg);
        orderEventService.record(
            leg.getId(), "ORDER_CANCELED", from, OrderStatus.CANCELED,
            null, "OCO group canceled");
      }
    }
    return response(legs);
  }

  private PreparedGroup prepareGroup(
      UUID userId,
      CreateOcoOrderRequest request,
      String symbol,
      BigDecimal baseQuantity,
      OrderHoldCalculator.OrderHold hold,
      LegKeys keys
  ) {
    UUID groupId = UUID.randomUUID();
    OrderEntity limit = leg(
        userId, request, symbol, baseQuantity, groupId,
        UUID.randomUUID(), OrderType.LIMIT,
        keys.limitClientKey(), keys.limitIdempotencyKey());
    OrderEntity stop = leg(
        userId, request, symbol, baseQuantity, groupId,
        UUID.randomUUID(), OrderType.STOP_MARKET,
        keys.stopClientKey(), keys.stopIdempotencyKey());
    List<OrderEntity> ordered = List.of(limit, stop).stream()
        .sorted(Comparator.comparing(OrderEntity::getId)).toList();
    OrderEntity owner = ordered.getFirst();
    for (OrderEntity leg : ordered) {
      leg.setHoldOwnerOrderId(owner.getId());
      leg.setHoldCurrency(hold.currency());
      leg.setHoldAmount(leg == owner ? hold.amount() : BigDecimal.ZERO);
    }
    return new PreparedGroup(
        userId, request.accountId(), symbol, groupId, ordered, owner);
  }

  private OrderEntity leg(
      UUID userId,
      CreateOcoOrderRequest request,
      String symbol,
      BigDecimal baseQuantity,
      UUID groupId,
      UUID id,
      OrderType type,
      String clientKey,
      String idempotencyKey
  ) {
    OrderEntity order = new OrderEntity();
    order.setId(id);
    order.setUserId(userId);
    order.setAccountId(request.accountId());
    order.setSymbol(symbol);
    order.setProductType(ProductType.CRYPTO_SPOT);
    order.setPositionMode(PositionMode.ONE_WAY);
    order.setPositionSide(PositionSide.BOTH);
    order.setMarginMode(MarginMode.CASH);
    order.setSide(request.side());
    order.setOrderType(type);
    order.setStatus(OrderStatus.PENDING);
    order.setLots(baseQuantity);
    order.setQuantity(request.quantity());
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setOriginalQuantity(request.quantity());
    order.setBaseQuantity(baseQuantity);
    order.setTimeInForce(TimeInForce.GTC);
    order.setReduceOnly(false);
    order.setOrderOrigin(OrderOrigin.OCO);
    order.setPrice(type == OrderType.LIMIT ? request.limitPrice() : null);
    order.setRequestedPrice(type == OrderType.LIMIT ? request.limitPrice() : null);
    order.setTriggerPrice(type == OrderType.STOP_MARKET ? request.stopTriggerPrice() : null);
    order.setTriggerPriceType(type == OrderType.STOP_MARKET ? TriggerPriceType.LAST_PRICE : null);
    order.setTriggerExecutionType(type == OrderType.STOP_MARKET ? TriggerExecutionType.MARKET : null);
    order.setFilledQuantity(BigDecimal.ZERO);
    order.setRemainingQuantity(baseQuantity);
    order.setContingencyGroupId(groupId);
    order.setClientOrderId(clientKey);
    order.setIdempotencyKey(idempotencyKey);
    order.setLeverage(1);
    order.setCreatedAt(Instant.now());
    return order;
  }

  private CreateOrderRequest rulesRequest(
      CreateOcoOrderRequest request,
      String symbol,
      OrderType type,
      BigDecimal price,
      BigDecimal trigger,
      String clientKey,
      String idempotencyKey
  ) {
    return new CreateOrderRequest(
        request.accountId(), symbol, request.side(), type,
        request.quantity(), price, null, null, idempotencyKey, clientKey,
        request.quantity(), price, 1, PositionSide.BOTH, QuantityUnit.BASE,
        MarginMode.CASH, trigger, type == OrderType.STOP_MARKET
            ? TriggerPriceType.LAST_PRICE : null, false, List.of());
  }

  private Optional<OcoOrderGroupResponse> findExisting(
      UUID userId,
      UUID accountId,
      LegKeys keys
  ) {
    Optional<OrderEntity> byClient = orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, keys.limitClientKey());
    Optional<OrderEntity> byIdempotency = orderRepository.findByUserIdAndIdempotencyKey(
        userId, keys.limitIdempotencyKey());
    if (byClient.isPresent() && byIdempotency.isPresent()
        && !java.util.Objects.equals(
            byClient.get().getContingencyGroupId(),
            byIdempotency.get().getContingencyGroupId())) {
      throw new BusinessException(
          ErrorCode.DUPLICATE_CLIENT_ORDER_ID,
          "OCO client and idempotency keys resolve to different groups");
    }
    return byClient.or(() -> byIdempotency)
        .filter(order -> userId.equals(order.getUserId()) && accountId.equals(order.getAccountId()))
        .map(OrderEntity::getContingencyGroupId)
        .map(orderRepository::findByContingencyGroupId)
        .map(this::response);
  }

  private OcoOrderGroupResponse response(List<OrderEntity> rawLegs) {
    List<OrderEntity> legs = requireGroup(rawLegs);
    OrderEntity limit = legs.stream().filter(leg -> leg.getOrderType() == OrderType.LIMIT)
        .findFirst().orElseThrow();
    OrderEntity stop = legs.stream().filter(leg -> leg.getOrderType() == OrderType.STOP_MARKET)
        .findFirst().orElseThrow();
    return new OcoOrderGroupResponse(
        limit.getContingencyGroupId(),
        orderResponseMapper.toResponse(limit),
        orderResponseMapper.toResponse(stop));
  }

  private List<OrderEntity> requireGroup(List<OrderEntity> legs) {
    return OcoGroupValidator.requireValid(legs);
  }

  private OrderEntity holdOwner(List<OrderEntity> legs) {
    UUID ownerId = legs.getFirst().getHoldOwnerOrderId();
    if (ownerId == null || legs.stream().anyMatch(leg -> !ownerId.equals(leg.getHoldOwnerOrderId()))) {
      throw new BusinessException("OCO_GROUP_INCOMPLETE", "OCO hold owner is inconsistent");
    }
    return legs.stream().filter(leg -> ownerId.equals(leg.getId()))
        .findFirst()
        .orElseThrow(() -> new BusinessException("OCO_GROUP_INCOMPLETE", "OCO hold owner is missing"));
  }

  private boolean terminal(OrderEntity order) {
    return order.getStatus() == OrderStatus.FILLED
        || order.getStatus() == OrderStatus.CANCELED
        || order.getStatus() == OrderStatus.CANCELLED
        || order.getStatus() == OrderStatus.REJECTED
        || order.getStatus() == OrderStatus.EXPIRED;
  }

  private void validateContract(CreateOcoOrderRequest request) {
    if (request.quantityUnit() != QuantityUnit.BASE) {
      throw new BusinessException(
          ErrorCode.INVALID_QUANTITY_UNIT,
          "Spot OCO quantity unit must be BASE");
    }
    if (request.triggerPriceType() != TriggerPriceType.LAST_PRICE) {
      throw new BusinessException(
          ErrorCode.INVALID_SPOT_ORDER_FIELDS,
          "Spot OCO trigger price type must be LAST_PRICE");
    }
  }

  private LegKeys legKeys(CreateOcoOrderRequest request) {
    String clientRoot = request.clientOrderId();
    String idempotencyRoot = request.idempotencyKey() == null
        || request.idempotencyKey().isBlank()
        ? clientRoot
        : request.idempotencyKey();
    return new LegKeys(
        deriveLegKey(clientRoot, "L"),
        deriveLegKey(clientRoot, "S"),
        deriveLegKey(idempotencyRoot, "L"),
        deriveLegKey(idempotencyRoot, "S"));
  }

  private void validatePriceMatrix(CreateOcoOrderRequest request, BigDecimal last) {
    boolean valid = request.side() == OrderSide.SELL
        ? request.limitPrice().compareTo(last) > 0
            && last.compareTo(request.stopTriggerPrice()) > 0
        : request.limitPrice().compareTo(last) < 0
            && last.compareTo(request.stopTriggerPrice()) < 0;
    if (!valid) {
      throw new BusinessException(
          "OCO_PRICE_RELATION_INVALID",
          "OCO limit, last and stop prices are not ordered for the side");
    }
  }

  private TradingAccountEntity requireOwnedAccount(UUID userId, UUID accountId, boolean lock) {
    return (lock
        ? accountRepository.findByIdAndUserIdForUpdate(accountId, userId)
        : accountRepository.findByIdAndUserId(accountId, userId))
        .orElseThrow(() -> new AuthorizationException("ACCOUNT_NOT_FOUND", "Account not found"));
  }

  private ExecutableMarketSnapshot resolveSnapshot(String symbol) {
    Instant to = Instant.now();
    return ExecutableMarketSnapshot.from(marketBundleResolver.resolveSpot(
        symbol, new CandleRequest("1m", to.minus(Duration.ofMinutes(30)), to)));
  }

  private void lockExistingState(UUID accountId) {
    walletBalanceRepository.findByAccountIdForUpdate(accountId);
    spotPositionService.lockExisting(accountId);
  }

  private void ensureState(UUID accountId, String symbol) {
    String baseAsset = symbol.substring(0, symbol.length() - 4);
    walletBalanceRepository.findByAccountIdForUpdate(accountId);
    walletService.lockBalancesInOrder(accountId, List.of(baseAsset, "USDT"));
    spotPositionService.lockExisting(accountId);
    spotPositionService.lockOrCreate(accountId, baseAsset, "USDT");
  }

  static String deriveLegKey(String root, String leg) {
    String normalizedRoot = root == null ? "" : root.trim();
    String suffix = ":OCO:" + leg;
    if (normalizedRoot.length() + suffix.length() <= MAX_KEY_LENGTH) {
      return normalizedRoot + suffix;
    }
    String digest = sha256(normalizedRoot).substring(0, 24);
    int prefixLength = MAX_KEY_LENGTH - suffix.length() - digest.length() - 1;
    return normalizedRoot.substring(0, prefixLength) + ":" + digest + suffix;
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private record PreparedGroup(
      UUID userId,
      UUID accountId,
      String symbol,
      UUID groupId,
      List<OrderEntity> orderedLegs,
      OrderEntity holdOwner
  ) {
  }

  private record LegKeys(
      String limitClientKey,
      String stopClientKey,
      String limitIdempotencyKey,
      String stopIdempotencyKey
  ) {
  }
}
