package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.execution.FullFillExecutionPath;
import com.fxplatform.execution.FullFillPricingProjection;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.model.SpotMarketBundle;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import com.fxplatform.trading.dto.request.CreateOcoOrderRequest;
import com.fxplatform.trading.dto.response.OcoOrderGroupResponse;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OcoOrderServiceTest {

  @Mock private OrderRepository orderRepository;
  @Mock private TradingAccountRepository accountRepository;
  @Mock private WalletService walletService;
  @Mock private OrderEventService orderEventService;
  @Mock private DemoExecutionGuard demoExecutionGuard;
  @Mock private WalletBalanceRepository walletBalanceRepository;
  @Mock private SpotPositionService spotPositionService;
  @Mock private MarketBundleResolver marketBundleResolver;
  @Mock private SymbolRepository symbolRepository;
  @Mock private InstrumentRulesEngine instrumentRulesEngine;
  @Mock private OrderHoldCalculator orderHoldCalculator;
  @Mock private FullFillCoordinator fullFillCoordinator;
  @Mock private TradingTransactionExecutor transactionExecutor;

  @BeforeEach
  void inlineTransaction() {
    org.mockito.Mockito.lenient().when(transactionExecutor.execute(any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
  }

  @Test
  void createsTwoRowsOneGroupOneHoldOwnerAndOneWalletLock() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UserPrincipal principal = principal(userId);
    CreateOcoOrderRequest request = request(accountId, OrderSide.SELL, "55000", "49000", "oco-create");
    TradingAccountEntity account = account(userId, accountId);
    SpotMarketBundle bundle = bundle();
    List<OrderEntity> saved = new ArrayList<>();

    stubNewGroup(userId, accountId, request, account, bundle);
    when(orderHoldCalculator.oco(
        OrderSide.SELL, new BigDecimal("0.1000"), new BigDecimal("55000"),
        new BigDecimal("49000"), ExecutableMarketSnapshot.from(bundle)))
        .thenReturn(new OrderHoldCalculator.OrderHold(new BigDecimal("0.10000000"), "BTC"));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      saved.add(order);
      return order;
    });

    OcoOrderGroupResponse response = service().create(principal, request);

    assertThat(saved).hasSize(2);
    assertThat(saved).extracting(OrderEntity::getOrderType)
        .containsExactlyInAnyOrder(OrderType.LIMIT, OrderType.STOP_MARKET);
    assertThat(saved).extracting(OrderEntity::getContingencyGroupId).containsOnly(response.contingencyGroupId());
    UUID ownerId = saved.getFirst().getHoldOwnerOrderId();
    assertThat(saved).extracting(OrderEntity::getHoldOwnerOrderId).containsOnly(ownerId);
    assertThat(saved).filteredOn(order -> order.getId().equals(ownerId))
        .singleElement().extracting(OrderEntity::getHoldAmount)
        .isEqualTo(new BigDecimal("0.10000000"));
    assertThat(saved).filteredOn(order -> !order.getId().equals(ownerId))
        .singleElement().extracting(OrderEntity::getHoldAmount)
        .isEqualTo(BigDecimal.ZERO);
    assertThat(saved).allSatisfy(order -> {
      assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
      assertThat(order.getOrderOrigin()).isEqualTo(OrderOrigin.OCO);
      assertThat(order.getMarginMode()).isEqualTo(MarginMode.CASH);
      assertThat(order.getPositionSide()).isEqualTo(PositionSide.BOTH);
      assertThat(order.getQuantityUnit()).isEqualTo(QuantityUnit.BASE);
      assertThat(order.getBaseQuantity()).isEqualByComparingTo("0.1000");
      assertThat(order.getClientOrderId()).hasSizeLessThanOrEqualTo(128);
      assertThat(order.getIdempotencyKey()).hasSizeLessThanOrEqualTo(128);
    });
    verify(walletService).lockAvailableWithEntryType(
        eq(accountId), eq("BTC"), eq(new BigDecimal("0.10000000")), eq("ORDER"),
        eq(ownerId), eq("OCO shared Spot hold locked"), eq("SPOT_ORDER_LOCK"));
    org.mockito.InOrder lockOrder = org.mockito.Mockito.inOrder(
        accountRepository, fullFillCoordinator, walletBalanceRepository,
        walletService, spotPositionService, orderRepository);
    lockOrder.verify(accountRepository).findByIdAndUserIdForUpdate(accountId, userId);
    lockOrder.verify(fullFillCoordinator).requireFresh(ExecutableMarketSnapshot.from(bundle));
    lockOrder.verify(walletBalanceRepository).findByAccountIdForUpdate(accountId);
    lockOrder.verify(walletService).lockBalancesInOrder(accountId, List.of("BTC", "USDT"));
    lockOrder.verify(spotPositionService).lockExisting(accountId);
    lockOrder.verify(spotPositionService).lockOrCreate(accountId, "BTC", "USDT");
    lockOrder.verify(fullFillCoordinator).requireFresh(ExecutableMarketSnapshot.from(bundle));
    lockOrder.verify(orderRepository, org.mockito.Mockito.times(2)).save(any(OrderEntity.class));
  }

  @Test
  void replayReturnsCompleteGroupBeforeProviderResolutionAndDerivedKeysAreStableBounded() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    String root = "x".repeat(128);
    UUID groupId = UUID.randomUUID();
    OrderEntity limit = leg(userId, accountId, groupId, UUID.randomUUID(), OrderType.LIMIT);
    OrderEntity stop = leg(userId, accountId, groupId, limit.getHoldOwnerOrderId(), OrderType.STOP_MARKET);
    limit.setHoldAmount(new BigDecimal("0.10000000"));
    String limitKey = OcoOrderService.deriveLegKey(root, "L");
    String stopKey = OcoOrderService.deriveLegKey(root, "S");
    limit.setClientOrderId(limitKey);
    stop.setClientOrderId(stopKey);
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, limitKey))
        .thenReturn(Optional.of(limit));
    when(orderRepository.findByContingencyGroupId(groupId)).thenReturn(List.of(stop, limit));
    when(accountRepository.findByIdAndUserId(accountId, userId))
        .thenReturn(Optional.of(account(userId, accountId)));

    OcoOrderGroupResponse response = service().create(
        principal(userId),
        request(accountId, OrderSide.SELL, "55000", "49000", root));

    assertThat(response.contingencyGroupId()).isEqualTo(groupId);
    assertThat(limitKey).hasSizeLessThanOrEqualTo(128).isNotEqualTo(stopKey);
    assertThat(OcoOrderService.deriveLegKey(root, "L")).isEqualTo(limitKey);
    verify(marketBundleResolver, never()).resolveSpot(any(), any());
    verify(transactionExecutor, never()).execute(any());
  }

  @Test
  void cancelingEitherLegCancelsWholeGroupAndReleasesOwnerExactlyOnce() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    OrderEntity owner = leg(userId, accountId, groupId, ownerId, OrderType.LIMIT);
    owner.setId(ownerId);
    owner.setHoldAmount(new BigDecimal("5002.50000000"));
    owner.setHoldCurrency("USDT");
    OrderEntity peer = leg(userId, accountId, groupId, ownerId, OrderType.STOP_MARKET);
    TradingAccountEntity account = account(userId, accountId);
    List<OrderEntity> ordered = List.of(owner, peer).stream()
        .sorted(Comparator.comparing(OrderEntity::getId)).toList();
    when(orderRepository.findByUserIdAndId(userId, peer.getId())).thenReturn(Optional.of(peer));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    org.mockito.Mockito.lenient().when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId))
        .thenReturn(Optional.of(account));
    when(orderRepository.findByContingencyGroupIdForUpdate(groupId)).thenReturn(ordered);
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

    OcoOrderGroupResponse first = service().cancelByLeg(principal(userId), peer.getId());
    OcoOrderGroupResponse replay = service().cancelByLeg(principal(userId), peer.getId());

    assertThat(first.contingencyGroupId()).isEqualTo(groupId);
    assertThat(replay.contingencyGroupId()).isEqualTo(groupId);
    assertThat(ordered).extracting(OrderEntity::getStatus).containsOnly(OrderStatus.CANCELED);
    assertThat(owner.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    verify(walletService).releaseLockedWithEntryType(
        accountId, "USDT", new BigDecimal("5002.50000000"), "ORDER", ownerId,
        "OCO shared Spot hold released", "SPOT_ORDER_RELEASE");
  }

  @Test
  void rejectsInvalidSellPriceMatrixBeforeTransaction() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    CreateOcoOrderRequest request = request(accountId, OrderSide.SELL, "49000", "51000", "bad-matrix");
    TradingAccountEntity account = account(userId, accountId);
    stubNewGroup(userId, accountId, request, account, bundle());

    assertThatThrownBy(() -> service().create(principal(userId), request))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("OCO_PRICE_RELATION_INVALID"));

    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
  }

  @Test
  void acceptsBuyPriceMatrixWithSeparateStableClientAndIdempotencyLegKeys() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    CreateOcoOrderRequest request = request(
        accountId, OrderSide.BUY, "49000", "51000", "oco-buy-idem", "oco-buy-client");
    TradingAccountEntity account = account(userId, accountId);
    SpotMarketBundle bundle = bundle();
    List<OrderEntity> saved = new ArrayList<>();
    stubNewGroup(userId, accountId, request, account, bundle);
    when(orderHoldCalculator.oco(
        OrderSide.BUY, new BigDecimal("0.1000"), new BigDecimal("49000"),
        new BigDecimal("51000"), ExecutableMarketSnapshot.from(bundle)))
        .thenReturn(new OrderHoldCalculator.OrderHold(new BigDecimal("5105.60100000"), "USDT"));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
      OrderEntity order = invocation.getArgument(0);
      saved.add(order);
      return order;
    });

    OcoOrderGroupResponse response = service().create(principal(userId), request);

    assertThat(response.contingencyGroupId()).isNotNull();
    assertThat(saved).hasSize(2);
    assertThat(saved).extracting(OrderEntity::getClientOrderId)
        .containsExactlyInAnyOrder(
            OcoOrderService.deriveLegKey("oco-buy-client", "L"),
            OcoOrderService.deriveLegKey("oco-buy-client", "S"));
    assertThat(saved).extracting(OrderEntity::getIdempotencyKey)
        .containsExactlyInAnyOrder(
            OcoOrderService.deriveLegKey("oco-buy-idem", "L"),
            OcoOrderService.deriveLegKey("oco-buy-idem", "S"));
    assertThat(saved).filteredOn(order -> order.getHoldAmount().signum() > 0)
        .singleElement().extracting(OrderEntity::getHoldCurrency).isEqualTo("USDT");
  }

  @Test
  void rejectsInvalidBuyPriceMatrixBeforeTransaction() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    CreateOcoOrderRequest request = request(
        accountId, OrderSide.BUY, "51000", "49000", "bad-buy", "bad-buy");
    TradingAccountEntity account = account(userId, accountId);
    stubNewGroup(userId, accountId, request, account, bundle());

    assertThatThrownBy(() -> service().create(principal(userId), request))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("OCO_PRICE_RELATION_INVALID"));

    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
  }

  @Test
  void replayByIndependentIdempotencyKeyReturnsOriginalGroupBeforeProviderResolution() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    String idempotencyLimitKey = OcoOrderService.deriveLegKey("same-idem", "L");
    String differentClientLimitKey = OcoOrderService.deriveLegKey("new-client", "L");
    OrderEntity limit = leg(userId, accountId, groupId, UUID.randomUUID(), OrderType.LIMIT);
    OrderEntity stop = leg(userId, accountId, groupId, limit.getHoldOwnerOrderId(), OrderType.STOP_MARKET);
    limit.setHoldAmount(new BigDecimal("0.10000000"));
    limit.setIdempotencyKey(idempotencyLimitKey);
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        userId, accountId, differentClientLimitKey)).thenReturn(Optional.empty());
    when(orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyLimitKey))
        .thenReturn(Optional.of(limit));
    when(orderRepository.findByContingencyGroupId(groupId)).thenReturn(List.of(limit, stop));
    when(accountRepository.findByIdAndUserId(accountId, userId))
        .thenReturn(Optional.of(account(userId, accountId)));

    OcoOrderGroupResponse replay = service().create(
        principal(userId),
        request(accountId, OrderSide.SELL, "55000", "49000", "same-idem", "new-client"));

    assertThat(replay.contingencyGroupId()).isEqualTo(groupId);
    verify(marketBundleResolver, never()).resolveSpot(any(), any());
    verify(transactionExecutor, never()).execute(any());
  }

  @Test
  void accountLockReplayWinsBeforeStateEnsureAndStaleFreshnessGate() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    CreateOcoOrderRequest request = request(
        accountId, OrderSide.SELL, "55000", "49000", "locked-replay", "locked-replay");
    TradingAccountEntity account = account(userId, accountId);
    SpotMarketBundle bundle = bundle();
    String limitKey = OcoOrderService.deriveLegKey(request.clientOrderId(), "L");
    OrderEntity limit = leg(userId, accountId, groupId, UUID.randomUUID(), OrderType.LIMIT);
    OrderEntity stop = leg(userId, accountId, groupId, limit.getHoldOwnerOrderId(), OrderType.STOP_MARKET);
    limit.setHoldAmount(new BigDecimal("0.10000000"));
    stubNewGroup(userId, accountId, request, account, bundle);
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, limitKey))
        .thenReturn(Optional.empty(), Optional.of(limit));
    when(orderRepository.findByContingencyGroupId(groupId)).thenReturn(List.of(stop, limit));
    when(orderHoldCalculator.oco(any(), any(), any(), any(), any()))
        .thenReturn(new OrderHoldCalculator.OrderHold(new BigDecimal("0.10000000"), "BTC"));
    OcoOrderGroupResponse replay = service().create(principal(userId), request);

    assertThat(replay.contingencyGroupId()).isEqualTo(groupId);
    verify(walletService, never()).lockBalancesInOrder(any(), any());
    verify(spotPositionService, never()).lockOrCreate(any(), any(), any());
    verify(fullFillCoordinator, never()).requireFresh(any(ExecutableMarketSnapshot.class));
    verify(orderRepository, never()).save(any());
  }

  @Test
  void staleCreationRetriesExactlyOnceWithANewWholeBundleOutsideTransactions() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    CreateOcoOrderRequest request = request(
        accountId, OrderSide.SELL, "55000", "49000", "stale-retry", "stale-retry");
    TradingAccountEntity account = account(userId, accountId);
    SpotMarketBundle first = bundle();
    SpotMarketBundle second = bundle();
    stubNewGroup(userId, accountId, request, account, first);
    when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any())).thenReturn(first, second);
    when(orderHoldCalculator.oco(any(), any(), any(), any(), any()))
        .thenReturn(new OrderHoldCalculator.OrderHold(new BigDecimal("0.10000000"), "BTC"));
    when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    org.mockito.Mockito.doThrow(new BusinessException("MARKET_DATA_STALE", "first expired"))
        .doNothing()
        .when(fullFillCoordinator).requireFresh(any(ExecutableMarketSnapshot.class));

    OcoOrderGroupResponse response = service().create(principal(userId), request);

    assertThat(response.contingencyGroupId()).isNotNull();
    verify(marketBundleResolver, org.mockito.Mockito.times(2)).resolveSpot(eq("BTCUSDT"), any());
    verify(transactionExecutor, org.mockito.Mockito.times(2)).execute(any());
    verify(orderRepository, org.mockito.Mockito.times(2)).save(any(OrderEntity.class));
  }

  @Test
  void mixedSymbolGroupIsRejectedBeforeCancelReleaseOrMutation() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    OrderEntity owner = leg(userId, accountId, groupId, ownerId, OrderType.LIMIT);
    owner.setHoldAmount(new BigDecimal("0.10000000"));
    owner.setHoldCurrency("BTC");
    OrderEntity peer = leg(userId, accountId, groupId, ownerId, OrderType.STOP_MARKET);
    peer.setSymbol("ETHUSDT");
    TradingAccountEntity account = account(userId, accountId);
    when(orderRepository.findByUserIdAndId(userId, peer.getId())).thenReturn(Optional.of(peer));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(account));
    when(orderRepository.findByContingencyGroupIdForUpdate(groupId)).thenReturn(List.of(owner, peer));

    assertThatThrownBy(() -> service().cancelByLeg(principal(userId), peer.getId()))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("OCO_GROUP_INCOMPLETE"));

    verify(walletService, never()).releaseLockedWithEntryType(
        any(), any(), any(), any(), any(), any(), any(String.class));
    verify(orderRepository, never()).save(any());
    verify(orderEventService, never()).record(any(), any(), any(), any(), any(), any());
  }

  @Test
  void unownedAccountIsRejectedBeforeProviderTransactionOrMutation() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    CreateOcoOrderRequest request = request(
        accountId, OrderSide.SELL, "55000", "49000", "unowned", "unowned");
    UUID groupId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    OrderEntity owner = leg(userId, accountId, groupId, ownerId, OrderType.LIMIT);
    owner.setHoldAmount(new BigDecimal("0.10000000"));
    OrderEntity peer = leg(userId, accountId, groupId, ownerId, OrderType.STOP_MARKET);
    String limitKey = OcoOrderService.deriveLegKey(request.clientOrderId(), "L");
    org.mockito.Mockito.lenient().when(
        orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, limitKey))
        .thenReturn(Optional.of(owner));
    org.mockito.Mockito.lenient().when(orderRepository.findByContingencyGroupId(groupId))
        .thenReturn(List.of(owner, peer));

    assertThatThrownBy(() -> service().create(principal(userId), request))
        .isInstanceOfSatisfying(AuthorizationException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("ACCOUNT_NOT_FOUND"));

    verify(marketBundleResolver, never()).resolveSpot(any(), any());
    verify(transactionExecutor, never()).execute(any());
    verify(orderRepository, never()).save(any());
    verify(walletService, never()).lockAvailableWithEntryType(
        any(), any(), any(), any(), any(), any(), any(String.class));
  }

  private void stubNewGroup(
      UUID userId,
      UUID accountId,
      CreateOcoOrderRequest request,
      TradingAccountEntity account,
      SpotMarketBundle bundle
  ) {
    String limitKey = OcoOrderService.deriveLegKey(request.clientOrderId(), "L");
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(userId, accountId, limitKey))
        .thenReturn(Optional.empty());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    org.mockito.Mockito.lenient().when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId))
        .thenReturn(Optional.of(account));
    org.mockito.Mockito.lenient().when(marketBundleResolver.resolveSpot(eq("BTCUSDT"), any()))
        .thenReturn(bundle);
    org.mockito.Mockito.lenient().when(symbolRepository.findBySymbol("BTCUSDT"))
        .thenReturn(Optional.of(symbol()));
    org.mockito.Mockito.lenient().when(instrumentRulesEngine.rules(any(SymbolEntity.class)))
        .thenReturn(rules());
    org.mockito.Mockito.lenient().when(fullFillCoordinator.project(
        eq(ProductType.CRYPTO_SPOT), eq(request.side()),
        eq(FullFillExecutionPath.TRIGGERED_STOP_MARKET),
        org.mockito.ArgumentMatchers.isNull(), any(ExecutableMarketSnapshot.class)))
        .thenReturn(new FullFillPricingProjection(
            new BigDecimal("50000"), BigDecimal.ZERO, new BigDecimal("0.0001"),
            new BigDecimal("0.0005"), new BigDecimal("0.0005"), LiquidityRole.TAKER));
  }

  private OcoOrderService service() {
    return new OcoOrderService(
        orderRepository, accountRepository, walletService, orderEventService,
        new OrderResponseMapper(), demoExecutionGuard, walletBalanceRepository,
        spotPositionService, marketBundleResolver, symbolRepository, instrumentRulesEngine,
        new QuantityConversionService(), orderHoldCalculator, fullFillCoordinator,
        transactionExecutor);
  }

  private static CreateOcoOrderRequest request(
      UUID accountId,
      OrderSide side,
      String limit,
      String stop,
      String key
  ) {
    return request(accountId, side, limit, stop, key, key);
  }

  private static CreateOcoOrderRequest request(
      UUID accountId,
      OrderSide side,
      String limit,
      String stop,
      String idempotencyKey,
      String clientOrderId
  ) {
    return new CreateOcoOrderRequest(
        accountId, "BTCUSDT", side, new BigDecimal("0.1000"), QuantityUnit.BASE,
        new BigDecimal(limit), new BigDecimal(stop), TriggerPriceType.LAST_PRICE,
        idempotencyKey, clientOrderId);
  }

  private static UserPrincipal principal(UUID userId) {
    return new UserPrincipal(userId, "trader@example.com", "TRADER");
  }

  private static TradingAccountEntity account(UUID userId, UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    account.setBaseCurrency("USDT");
    account.setUsedMargin(BigDecimal.ZERO);
    return account;
  }

  private static OrderEntity leg(
      UUID userId,
      UUID accountId,
      UUID groupId,
      UUID ownerId,
      OrderType type
  ) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    if (type == OrderType.LIMIT && ownerId != null) {
      order.setId(ownerId);
    }
    order.setUserId(userId);
    order.setAccountId(accountId);
    order.setSymbol("BTCUSDT");
    order.setProductType(ProductType.CRYPTO_SPOT);
    order.setSide(OrderSide.BUY);
    order.setOrderType(type);
    if (type == OrderType.LIMIT) {
      order.setPrice(new BigDecimal("49000"));
      order.setRequestedPrice(new BigDecimal("49000"));
    } else {
      order.setTriggerPrice(new BigDecimal("51000"));
      order.setTriggerPriceType(TriggerPriceType.LAST_PRICE);
      order.setTriggerExecutionType(TriggerExecutionType.MARKET);
    }
    order.setStatus(OrderStatus.PENDING);
    order.setLots(new BigDecimal("0.1"));
    order.setQuantity(new BigDecimal("0.1"));
    order.setOriginalQuantity(new BigDecimal("0.1"));
    order.setBaseQuantity(new BigDecimal("0.1"));
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setMarginMode(MarginMode.CASH);
    order.setPositionSide(PositionSide.BOTH);
    order.setOrderOrigin(OrderOrigin.OCO);
    order.setTimeInForce(TimeInForce.GTC);
    order.setReduceOnly(false);
    order.setContingencyGroupId(groupId);
    order.setHoldOwnerOrderId(ownerId);
    order.setHoldAmount(BigDecimal.ZERO);
    order.setHoldCurrency("USDT");
    return order;
  }

  private static SpotMarketBundle bundle() {
    Instant now = Instant.now();
    return new SpotMarketBundle(
        "BTCUSDT", "BTCUSDT", "binance", MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("49990"), new BigDecimal("50010"), new BigDecimal("50000"),
        null, List.of(), List.of(), now.minusSeconds(1), now.plusSeconds(30));
  }

  private static SymbolEntity symbol() {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(UUID.randomUUID());
    symbol.setSymbol("BTCUSDT");
    symbol.setProductType(ProductType.CRYPTO_SPOT);
    symbol.setBaseCurrency("BTC");
    symbol.setQuoteCurrency("USDT");
    symbol.setLotSize(BigDecimal.ONE);
    return symbol;
  }

  private static InstrumentRules rules() {
    return new InstrumentRules(
        "BTCUSDT", true, true, true, true, true, true, true,
        ProductType.CRYPTO_SPOT, new BigDecimal("0.1"), new BigDecimal("0.0001"),
        new BigDecimal("0.0001"), new BigDecimal("100"), new BigDecimal("5"), null,
        new BigDecimal("0.0001"), new BigDecimal("100"), 1, 1,
        "USDT", "USDT", BigDecimal.ONE, "DEFAULT", "ALWAYS", "NONE", "NORMAL");
  }
}
