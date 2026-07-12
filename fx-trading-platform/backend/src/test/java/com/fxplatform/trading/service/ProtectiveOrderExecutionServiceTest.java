package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProtectiveOrderExecutionServiceTest {

  private static final String SYMBOL = "BTCUSDT-PERP";
  private static final BigDecimal TRIGGER = new BigDecimal("50000");

  @Mock
  private OrderRepository orderRepository;

  @Mock
  private MarketBundleResolver marketBundleResolver;

  @Mock
  private ProtectionOrderService protectionOrderService;

  @Mock
  private SystemCloseOrderService systemCloseOrderService;

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private DemoExecutionGuard demoExecutionGuard;

  private ProtectiveOrderExecutionService service;

  @BeforeEach
  void setUp() {
    service = new ProtectiveOrderExecutionService(
        orderRepository,
        marketBundleResolver,
        protectionOrderService,
        systemCloseOrderService,
        accountRepository,
        demoExecutionGuard);
    when(accountRepository.findById(any(UUID.class)))
        .thenAnswer(invocation -> java.util.Optional.of(account(invocation.getArgument(0))));
  }

  @ParameterizedTest(name = "{0} {1} protection uses authority mark")
  @MethodSource("triggerDirections")
  void delegatesLongAndShortTpSlEvaluationWithAuthorityMark(
      OrderSide closeSide,
      ProtectionType protectionType,
      BigDecimal authorityMark
  ) {
    OrderEntity protection = protection(closeSide, protectionType, TriggerExecutionType.MARKET);
    PerpetualMarketBundle bundle = bundle(
        SYMBOL,
        new BigDecimal("49000"),
        authorityMark);
    when(orderRepository.findBoundProtectionsByStatus(OrderStatus.PENDING_ACTIVATION))
        .thenReturn(List.of(protection));
    when(marketBundleResolver.resolvePerp(eq(SYMBOL), any(CandleRequest.class)))
        .thenReturn(bundle);
    when(protectionOrderService.isTriggered(protection, authorityMark)).thenReturn(true);

    int executed = service.executeProtectiveOrders();

    assertThat(executed).isEqualTo(1);
    verify(protectionOrderService).isTriggered(protection, authorityMark);
    verify(systemCloseOrderService).executeProtection(
        eq(protection.getId()),
        org.mockito.ArgumentMatchers.argThat(snapshot ->
            snapshot.productType() == ProductType.LINEAR_PERP
                && snapshot.mark().compareTo(authorityMark) == 0
                && snapshot.last().compareTo(new BigDecimal("49000")) == 0
                && "local-perp".equals(snapshot.providerCode())));
  }

  @Test
  void lastOnlyCrossingDoesNotTriggerWhenAuthorityMarkHasNotCrossed() {
    OrderEntity protection = protection(
        OrderSide.SELL,
        ProtectionType.TAKE_PROFIT,
        TriggerExecutionType.MARKET);
    BigDecimal last = new BigDecimal("51000");
    BigDecimal mark = new BigDecimal("49999");
    when(orderRepository.findBoundProtectionsByStatus(OrderStatus.PENDING_ACTIVATION))
        .thenReturn(List.of(protection));
    when(marketBundleResolver.resolvePerp(eq(SYMBOL), any(CandleRequest.class)))
        .thenReturn(bundle(SYMBOL, last, mark));
    when(protectionOrderService.isTriggered(protection, mark)).thenReturn(false);

    int executed = service.executeProtectiveOrders();

    assertThat(executed).isZero();
    verify(protectionOrderService).isTriggered(protection, mark);
    verify(protectionOrderService, never()).isTriggered(protection, last);
    verifyNoInteractions(systemCloseOrderService);
  }

  @Test
  void scansOnlyBoundPendingActivationProtectionCarriers() {
    OrderEntity bound = protection(
        OrderSide.SELL,
        ProtectionType.TAKE_PROFIT,
        TriggerExecutionType.MARKET);
    OrderEntity task9InternalClose = protection(
        OrderSide.SELL,
        ProtectionType.STOP_LOSS,
        TriggerExecutionType.MARKET);
    task9InternalClose.setProtectionType(null);
    OrderEntity unboundAttached = protection(
        OrderSide.SELL,
        ProtectionType.TAKE_PROFIT,
        TriggerExecutionType.MARKET);
    unboundAttached.setParentPositionId(null);
    unboundAttached.setParentOrderId(UUID.randomUUID());
    OrderEntity alreadyTriggered = protection(
        OrderSide.SELL,
        ProtectionType.TAKE_PROFIT,
        TriggerExecutionType.LIMIT);
    alreadyTriggered.setStatus(OrderStatus.PENDING);
    OrderEntity wrongProduct = protection(
        OrderSide.SELL,
        ProtectionType.TAKE_PROFIT,
        TriggerExecutionType.MARKET);
    wrongProduct.setProductType(ProductType.CRYPTO_SPOT);
    BigDecimal mark = new BigDecimal("51000");
    when(orderRepository.findBoundProtectionsByStatus(OrderStatus.PENDING_ACTIVATION))
        .thenReturn(List.of(
            task9InternalClose,
            unboundAttached,
            alreadyTriggered,
            wrongProduct,
            bound));
    when(marketBundleResolver.resolvePerp(eq(SYMBOL), any(CandleRequest.class)))
        .thenReturn(bundle(SYMBOL, mark, mark));
    when(protectionOrderService.isTriggered(bound, mark)).thenReturn(true);

    int executed = service.executeProtectiveOrders();

    assertThat(executed).isEqualTo(1);
    verify(protectionOrderService).isTriggered(bound, mark);
    verify(protectionOrderService, never()).isTriggered(eq(task9InternalClose), any());
    verify(protectionOrderService, never()).isTriggered(eq(unboundAttached), any());
    verify(protectionOrderService, never()).isTriggered(eq(alreadyTriggered), any());
    verify(protectionOrderService, never()).isTriggered(eq(wrongProduct), any());
    verify(systemCloseOrderService).executeProtection(eq(bound.getId()), any());
    verify(orderRepository, never()).findByStatus(OrderStatus.PENDING_ACTIVATION);
  }

  @Test
  void delegatesMarketAndLimitCarriersWithoutMutatingTheirState() {
    OrderEntity market = protection(
        OrderSide.SELL,
        ProtectionType.TAKE_PROFIT,
        TriggerExecutionType.MARKET);
    OrderEntity limit = protection(
        OrderSide.SELL,
        ProtectionType.TAKE_PROFIT,
        TriggerExecutionType.LIMIT);
    limit.setPrice(new BigDecimal("50500"));
    BigDecimal mark = new BigDecimal("51000");
    when(orderRepository.findBoundProtectionsByStatus(OrderStatus.PENDING_ACTIVATION))
        .thenReturn(List.of(market, limit));
    when(marketBundleResolver.resolvePerp(eq(SYMBOL), any(CandleRequest.class)))
        .thenReturn(bundle(SYMBOL, mark, mark));
    when(protectionOrderService.isTriggered(any(OrderEntity.class), eq(mark))).thenReturn(true);

    int executed = service.executeProtectiveOrders();

    assertThat(executed).isEqualTo(2);
    assertThat(market.getStatus()).isEqualTo(OrderStatus.PENDING_ACTIVATION);
    assertThat(market.getTriggerExecutionType()).isEqualTo(TriggerExecutionType.MARKET);
    assertThat(limit.getStatus()).isEqualTo(OrderStatus.PENDING_ACTIVATION);
    assertThat(limit.getTriggerExecutionType()).isEqualTo(TriggerExecutionType.LIMIT);
    verify(systemCloseOrderService).executeProtection(eq(market.getId()), any());
    verify(systemCloseOrderService).executeProtection(eq(limit.getId()), any());
  }

  @Test
  void providerFailureSkipsOneCandidateAndContinuesWithTheNext() {
    OrderEntity unavailable = protection(
        OrderSide.SELL,
        ProtectionType.TAKE_PROFIT,
        TriggerExecutionType.MARKET);
    unavailable.setSymbol("ETHUSDT-PERP");
    OrderEntity executable = protection(
        OrderSide.SELL,
        ProtectionType.TAKE_PROFIT,
        TriggerExecutionType.MARKET);
    BigDecimal mark = new BigDecimal("51000");
    when(orderRepository.findBoundProtectionsByStatus(OrderStatus.PENDING_ACTIVATION))
        .thenReturn(List.of(unavailable, executable));
    when(marketBundleResolver.resolvePerp(eq("ETHUSDT-PERP"), any(CandleRequest.class)))
        .thenThrow(new BusinessException("MARKET_DATA_UNAVAILABLE", "No complete bundle"));
    when(marketBundleResolver.resolvePerp(eq(SYMBOL), any(CandleRequest.class)))
        .thenReturn(bundle(SYMBOL, mark, mark));
    when(protectionOrderService.isTriggered(executable, mark)).thenReturn(true);

    int executed = service.executeProtectiveOrders();

    assertThat(executed).isEqualTo(1);
    verify(systemCloseOrderService, never()).executeProtection(eq(unavailable.getId()), any());
    verify(systemCloseOrderService).executeProtection(eq(executable.getId()), any());
  }

  @Test
  void triggerRaceFailureDoesNotAbortLaterCandidates() {
    OrderEntity raceLoser = protection(
        OrderSide.SELL,
        ProtectionType.TAKE_PROFIT,
        TriggerExecutionType.MARKET);
    OrderEntity winner = protection(
        OrderSide.SELL,
        ProtectionType.TAKE_PROFIT,
        TriggerExecutionType.MARKET);
    BigDecimal mark = new BigDecimal("51000");
    when(orderRepository.findBoundProtectionsByStatus(OrderStatus.PENDING_ACTIVATION))
        .thenReturn(List.of(raceLoser, winner));
    when(marketBundleResolver.resolvePerp(eq(SYMBOL), any(CandleRequest.class)))
        .thenReturn(bundle(SYMBOL, mark, mark));
    when(protectionOrderService.isTriggered(any(OrderEntity.class), eq(mark))).thenReturn(true);
    when(systemCloseOrderService.executeProtection(eq(raceLoser.getId()), any()))
        .thenThrow(new BusinessException("POSITION_NOT_OPEN", "Position was already closed"));

    int executed = service.executeProtectiveOrders();

    assertThat(executed).isEqualTo(1);
    verify(systemCloseOrderService).executeProtection(eq(raceLoser.getId()), any());
    verify(systemCloseOrderService).executeProtection(eq(winner.getId()), any());
  }

  @Test
  void staleLockWaitRetriesOnceWithANewWholeBundleOutsideTheMutation() {
    OrderEntity protection = protection(
        OrderSide.SELL,
        ProtectionType.TAKE_PROFIT,
        TriggerExecutionType.MARKET);
    BigDecimal firstMark = new BigDecimal("51000");
    BigDecimal secondMark = new BigDecimal("51010");
    when(orderRepository.findBoundProtectionsByStatus(OrderStatus.PENDING_ACTIVATION))
        .thenReturn(List.of(protection));
    when(marketBundleResolver.resolvePerp(eq(SYMBOL), any(CandleRequest.class)))
        .thenReturn(
            bundle(SYMBOL, firstMark, firstMark),
            bundle(SYMBOL, secondMark, secondMark));
    when(protectionOrderService.isTriggered(protection, firstMark)).thenReturn(true);
    when(protectionOrderService.isTriggered(protection, secondMark)).thenReturn(true);
    when(systemCloseOrderService.executeProtection(eq(protection.getId()), any()))
        .thenThrow(new BusinessException(
            com.fxplatform.common.exception.ErrorCode.MARKET_DATA_STALE,
            "lock wait expired snapshot"))
        .thenReturn(null);

    int executed = service.executeProtectiveOrders();

    assertThat(executed).isEqualTo(1);
    verify(marketBundleResolver, times(2))
        .resolvePerp(eq(SYMBOL), any(CandleRequest.class));
    verify(systemCloseOrderService, times(2))
        .executeProtection(eq(protection.getId()), any(ExecutableMarketSnapshot.class));
  }

  @Test
  void demoGuardRunsBeforeProviderAndRejectsOneCandidateWithoutAbortingLaterOnes() {
    OrderEntity liveRejected = protection(
        OrderSide.SELL,
        ProtectionType.TAKE_PROFIT,
        TriggerExecutionType.MARKET);
    liveRejected.setSymbol("ETHUSDT-PERP");
    OrderEntity demoCandidate = protection(
        OrderSide.SELL,
        ProtectionType.TAKE_PROFIT,
        TriggerExecutionType.MARKET);
    TradingAccountEntity liveAccount = account(liveRejected.getAccountId());
    TradingAccountEntity demoAccount = account(demoCandidate.getAccountId());
    when(orderRepository.findBoundProtectionsByStatus(OrderStatus.PENDING_ACTIVATION))
        .thenReturn(List.of(liveRejected, demoCandidate));
    when(accountRepository.findById(liveRejected.getAccountId()))
        .thenReturn(java.util.Optional.of(liveAccount));
    when(accountRepository.findById(demoCandidate.getAccountId()))
        .thenReturn(java.util.Optional.of(demoAccount));
    doThrow(new BusinessException("DEMO_EXECUTION_REQUIRED", "Live account rejected"))
        .when(demoExecutionGuard).requireDemo(
            liveAccount, ProductType.LINEAR_PERP, "ETHUSDT-PERP");
    BigDecimal mark = new BigDecimal("51000");
    when(marketBundleResolver.resolvePerp(eq(SYMBOL), any(CandleRequest.class)))
        .thenReturn(bundle(SYMBOL, mark, mark));
    when(protectionOrderService.isTriggered(demoCandidate, mark)).thenReturn(true);

    int executed = service.executeProtectiveOrders();

    assertThat(executed).isEqualTo(1);
    verify(demoExecutionGuard).requireDemo(
        liveAccount, ProductType.LINEAR_PERP, "ETHUSDT-PERP");
    verify(demoExecutionGuard).requireDemo(
        demoAccount, ProductType.LINEAR_PERP, SYMBOL);
    verify(marketBundleResolver, never()).resolvePerp(eq("ETHUSDT-PERP"), any());
    verify(systemCloseOrderService).executeProtection(eq(demoCandidate.getId()), any());
  }

  private static Stream<Arguments> triggerDirections() {
    return Stream.of(
        Arguments.of(
            OrderSide.SELL,
            ProtectionType.TAKE_PROFIT,
            new BigDecimal("50001")),
        Arguments.of(
            OrderSide.SELL,
            ProtectionType.STOP_LOSS,
            new BigDecimal("49999")),
        Arguments.of(
            OrderSide.BUY,
            ProtectionType.TAKE_PROFIT,
            new BigDecimal("49999")),
        Arguments.of(
            OrderSide.BUY,
            ProtectionType.STOP_LOSS,
            new BigDecimal("50001")));
  }

  private static OrderEntity protection(
      OrderSide closeSide,
      ProtectionType protectionType,
      TriggerExecutionType triggerExecutionType
  ) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setAccountId(UUID.randomUUID());
    order.setSymbol(SYMBOL);
    order.setProductType(ProductType.LINEAR_PERP);
    order.setSide(closeSide);
    order.setStatus(OrderStatus.PENDING_ACTIVATION);
    order.setOrderOrigin(OrderOrigin.PROTECTIVE);
    order.setProtectionType(protectionType);
    order.setTriggerPrice(TRIGGER);
    order.setTriggerPriceType(TriggerPriceType.MARK_PRICE);
    order.setTriggerExecutionType(triggerExecutionType);
    order.setParentPositionId(UUID.randomUUID());
    order.setReduceOnly(true);
    return order;
  }

  private static TradingAccountEntity account(UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    return account;
  }

  private static PerpetualMarketBundle bundle(
      String symbol,
      BigDecimal last,
      BigDecimal mark
  ) {
    Instant asOf = Instant.parse("2026-07-12T12:00:00Z");
    return new PerpetualMarketBundle(
        symbol,
        symbol,
        "local-perp",
        MarketSourceMode.LOCAL_SIMULATED,
        mark.subtract(BigDecimal.ONE),
        mark.add(BigDecimal.ONE),
        last,
        mark,
        mark,
        null,
        List.of(),
        List.of(),
        asOf,
        asOf.plusSeconds(30));
  }
}
