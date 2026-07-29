package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.DemoExecutionPolicy;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.execution.FullFillRequest;
import com.fxplatform.execution.FullFillResult;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.LiquidityRole;
import com.fxplatform.trading.enums.MarginMode;
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
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DepthPendingOcoFailClosedTest {

  private static final String DEPTH_OCO_UNSUPPORTED = "DEPTH_OCO_UNSUPPORTED";

  @Mock private OrderRepository orderRepository;
  @Mock private TradingAccountRepository accountRepository;
  @Mock private DemoExecutionGuard demoExecutionGuard;
  @Mock private WalletBalanceRepository walletBalanceRepository;
  @Mock private WalletService walletService;
  @Mock private SpotPositionService spotPositionService;
  @Mock private FullFillCoordinator fullFillCoordinator;
  @Mock private OrderFillService orderFillService;
  @Mock private OrderEventService orderEventService;
  @Mock private TradingTransactionExecutor transactionExecutor;
  @Mock private DepthOrderExecutionService depthOrderExecutionService;

  @BeforeEach
  void inlineRequiresNewExecutor() {
    org.mockito.Mockito.lenient().when(transactionExecutor.execute(any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
  }

  @Test
  void depthOcoFailsClosedBeforeAnyAccountWalletOrderFillOrEventMutation() {
    UUID accountId = UUID.randomUUID();
    OrderEntity candidate = ocoLeg(
        accountId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        OrderType.STOP_MARKET);
    DemoExecutionPolicy policy = DemoExecutionPolicy.defaults();
    PendingOrderExecutionProcessor processor = processor();
    processor.setDepthOrderExecutionService(depthOrderExecutionService);
    when(depthOrderExecutionService.currentPolicy()).thenReturn(policy);
    when(depthOrderExecutionService.isDepth(policy)).thenReturn(true);

    assertThatThrownBy(() -> processor.process(candidate, snapshot("52000")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(DEPTH_OCO_UNSUPPORTED));

    verifyNoInteractions(
        transactionExecutor,
        accountRepository,
        demoExecutionGuard,
        walletBalanceRepository,
        walletService,
        spotPositionService,
        orderRepository,
        fullFillCoordinator,
        orderFillService,
        orderEventService);
  }

  @Test
  void simpleModeStillExecutesTheExistingOcoGroupFullFillPath() {
    UUID accountId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    OrderEntity owner = ocoLeg(accountId, groupId, ownerId, OrderType.LIMIT);
    owner.setId(ownerId);
    owner.setPrice(new BigDecimal("49000"));
    owner.setRequestedPrice(new BigDecimal("49000"));
    owner.setHoldAmount(new BigDecimal("5103.06025500"));
    OrderEntity winner = ocoLeg(accountId, groupId, ownerId, OrderType.STOP_MARKET);
    winner.setTriggerPrice(new BigDecimal("51000"));
    List<OrderEntity> group = List.of(owner, winner).stream()
        .sorted(Comparator.comparing(OrderEntity::getId))
        .toList();
    TradingAccountEntity account = account(accountId);
    ExecutableMarketSnapshot snapshot = snapshot("52000");
    FullFillResult fill = fill("0.1", "52005.2");
    DemoExecutionPolicy policy = DemoExecutionPolicy.defaults();
    PendingOrderExecutionProcessor processor = processor();
    processor.setDepthOrderExecutionService(depthOrderExecutionService);

    when(depthOrderExecutionService.currentPolicy()).thenReturn(policy);
    when(depthOrderExecutionService.isDepth(policy)).thenReturn(false);
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(orderRepository.findByContingencyGroupIdForUpdate(groupId)).thenReturn(group);
    when(fullFillCoordinator.execute(any(FullFillRequest.class), eq(snapshot))).thenReturn(fill);
    when(orderRepository.claimPending(winner.getId())).thenReturn(1);
    when(orderRepository.save(any(OrderEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    assertThat(processor.process(winner, snapshot)).isTrue();

    assertThat(owner.getStatus()).isEqualTo(OrderStatus.CANCELED);
    verify(orderRepository).findByContingencyGroupIdForUpdate(groupId);
    verify(orderRepository).claimPending(winner.getId());
    verify(orderFillService).fill(
        winner,
        owner,
        account,
        fill,
        new BigDecimal("5203.12026000"),
        "Pending OCO order hold");
  }

  @Test
  void declaresDepthOcoUnsupportedAsAnErrorCodeConstant() {
    assertThat(Arrays.stream(ErrorCode.class.getFields()).map(Field::getName))
        .contains(DEPTH_OCO_UNSUPPORTED);
  }

  @Test
  void registersDepthOcoUnsupportedAsAStandardErrorCode() {
    assertThat(ErrorCode.standardCodes()).contains(DEPTH_OCO_UNSUPPORTED);
  }

  private PendingOrderExecutionProcessor processor() {
    return new PendingOrderExecutionProcessor(
        orderRepository,
        accountRepository,
        demoExecutionGuard,
        walletBalanceRepository,
        walletService,
        spotPositionService,
        fullFillCoordinator,
        orderFillService,
        orderEventService,
        transactionExecutor);
  }

  private static OrderEntity ocoLeg(
      UUID accountId,
      UUID groupId,
      UUID ownerId,
      OrderType type
  ) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setUserId(accountId);
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

  private static TradingAccountEntity account(UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(accountId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    return account;
  }

  private static ExecutableMarketSnapshot snapshot(String last) {
    Instant now = Instant.now();
    return new ExecutableMarketSnapshot(
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        "binance",
        "BTCUSDT",
        MarketSourceMode.PUBLIC_EXTERNAL,
        new BigDecimal("51990"),
        new BigDecimal("52000"),
        new BigDecimal(last),
        null,
        null,
        now.minusSeconds(1),
        now.plusSeconds(30));
  }

  private static FullFillResult fill(String quantity, String price) {
    Instant now = Instant.now();
    BigDecimal filledQuantity = new BigDecimal(quantity);
    BigDecimal filledPrice = new BigDecimal(price);
    BigDecimal fee = filledQuantity.multiply(filledPrice)
        .multiply(new BigDecimal("0.0005"))
        .setScale(8, java.math.RoundingMode.HALF_UP);
    return new FullFillResult(
        filledPrice,
        now,
        filledQuantity,
        BigDecimal.ZERO,
        new BigDecimal("0.0005"),
        fee,
        "USDT",
        LiquidityRole.TAKER,
        new BigDecimal("5.2"),
        MarketSourceMode.PUBLIC_EXTERNAL,
        "binance",
        "BTCUSDT",
        now.minusSeconds(1),
        now.plusSeconds(30));
  }
}
