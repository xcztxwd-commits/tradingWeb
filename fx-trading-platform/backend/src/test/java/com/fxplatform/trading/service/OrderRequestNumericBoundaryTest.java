package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutionAdapter;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.risk.service.RiskCheckService;
import com.fxplatform.trading.dto.request.CreateOrderRequest;
import com.fxplatform.trading.dto.request.CreateOrderRequest.AttachedProtectionRequest;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderRequestNumericBoundaryTest {

  private static final UserPrincipal PRINCIPAL =
      new UserPrincipal(UUID.randomUUID(), "numeric-boundary@example.com", "TRADER");
  private static final BigDecimal MEDIUM_EXTREME_EXPONENT = new BigDecimal("1E+10000");

  @Mock private OrderRepository orderRepository;
  @Mock private TradingAccountRepository accountRepository;
  @Mock private RiskCheckService riskCheckService;
  @Mock private ExecutionAdapter executionAdapter;
  @Mock private OrderFillService orderFillService;
  @Mock private LedgerService ledgerService;
  @Mock private WalletService walletService;
  @Mock private OrderEventService orderEventService;
  @Mock private OrderCommandFactory orderCommandFactory;
  @Mock private OrderEntityFactory orderEntityFactory;
  @Mock private OrderResponseMapper orderResponseMapper;
  @Mock private OrderStatusPolicy orderStatusPolicy;
  @Mock private DemoExecutionGuard demoExecutionGuard;
  @Mock private WalletBalanceRepository walletBalanceRepository;
  @Mock private PositionRepository positionRepository;
  @Mock private SpotPositionService spotPositionService;

  @Test
  void requestBoundaryPreservesBaseStepAndContractIntegralityErrorSemantics() {
    assertThatThrownBy(() -> CreateOrderNumericBoundary.requireSafe(
        requestWithUnit(new BigDecimal("0.000000001"), QuantityUnit.BASE)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.QUANTITY_STEP_MISMATCH));
    assertThatThrownBy(() -> CreateOrderNumericBoundary.requireSafe(
        requestWithUnit(new BigDecimal("1.000000001"), QuantityUnit.CONTRACTS)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.CONTRACT_QUANTITY_NOT_INTEGRAL));
  }

  @Test
  void rejectsEveryFingerprintDecimalOutsideItsNumericContractBeforeDependencies() {
    BigDecimal stripOverflow = new BigDecimal(BigInteger.TEN, Integer.MIN_VALUE);
    BigDecimal integerDigitOverflow = new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE);
    AttachedProtectionRequest normalProtection = protection(
        new BigDecimal("90"), null, null);

    List<BoundaryCase> cases = List.of(
        new BoundaryCase("quantity strip overflow", request(
            null, null, null, null, stripOverflow, null, null, List.of()), "BAD_QUANTITY"),
        new BoundaryCase("quantity digit-count overflow", request(
            null, null, null, null, integerDigitOverflow, null, null, List.of()), "BAD_QUANTITY"),
        new BoundaryCase("legacy lots", request(
            MEDIUM_EXTREME_EXPONENT, null, null, null, null, null, null, List.of()),
            "BAD_QUANTITY"),
        new BoundaryCase("legacy requestedPrice", request(
            null, MEDIUM_EXTREME_EXPONENT, null, null, new BigDecimal("1"), null, null,
            List.of()), ErrorCode.ORDER_PRICE_REQUIRED),
        new BoundaryCase("price", request(
            null, null, null, null, new BigDecimal("1"), MEDIUM_EXTREME_EXPONENT, null,
            List.of()), ErrorCode.ORDER_PRICE_REQUIRED),
        new BoundaryCase("stop loss", request(
            null, null, MEDIUM_EXTREME_EXPONENT, null, new BigDecimal("1"), null, null,
            List.of()), ErrorCode.ORDER_PRICE_REQUIRED),
        new BoundaryCase("take profit", request(
            null, null, null, MEDIUM_EXTREME_EXPONENT, new BigDecimal("1"), null, null,
            List.of()), ErrorCode.ORDER_PRICE_REQUIRED),
        new BoundaryCase("trigger price", request(
            null, null, null, null, new BigDecimal("1"), null, MEDIUM_EXTREME_EXPONENT,
            List.of()), ErrorCode.ORDER_TRIGGER_PRICE_REQUIRED),
        new BoundaryCase("activation price", request(
            null, null, null, null, new BigDecimal("1"), null, null, List.of(),
            MEDIUM_EXTREME_EXPONENT, null, null), ErrorCode.ORDER_PRICE_REQUIRED),
        new BoundaryCase("trailing delta", request(
            null, null, null, null, new BigDecimal("1"), null, null, List.of(),
            null, MEDIUM_EXTREME_EXPONENT, null), ErrorCode.ORDER_PRICE_REQUIRED),
        new BoundaryCase("trailing rate", request(
            null, null, null, null, new BigDecimal("1"), null, null, List.of(),
            null, null, MEDIUM_EXTREME_EXPONENT), ErrorCode.ORDER_PRICE_REQUIRED),
        new BoundaryCase("attached trigger price", request(
            null, null, null, null, new BigDecimal("1"), null, null,
            List.of(protection(MEDIUM_EXTREME_EXPONENT, null, null))),
            ErrorCode.ORDER_TRIGGER_PRICE_REQUIRED),
        new BoundaryCase("attached limit price", request(
            null, null, null, null, new BigDecimal("1"), null, null,
            List.of(protection(new BigDecimal("90"), MEDIUM_EXTREME_EXPONENT, null))),
            ErrorCode.ORDER_PRICE_REQUIRED),
        new BoundaryCase("attached quantity", request(
            null, null, null, null, new BigDecimal("1"), null, null,
            List.of(protection(
                normalProtection.triggerPrice(), normalProtection.price(), MEDIUM_EXTREME_EXPONENT))),
            "BAD_QUANTITY"));

    OrderService service = service();
    for (BoundaryCase boundaryCase : cases) {
      assertThatThrownBy(() -> service.createOrder(PRINCIPAL, boundaryCase.request()))
          .as(boundaryCase.label())
          .isInstanceOfSatisfying(
              BusinessException.class,
              exception -> assertThat(exception.getCode()).isEqualTo(boundaryCase.code()));
    }

    verifyNoInteractions(orderCommandFactory, orderRepository, accountRepository, riskCheckService);
  }

  private OrderService service() {
    return new OrderService(
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
        spotPositionService);
  }

  private static CreateOrderRequest request(
      BigDecimal lots,
      BigDecimal requestedPrice,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      BigDecimal quantity,
      BigDecimal price,
      BigDecimal triggerPrice,
      List<AttachedProtectionRequest> attachedProtections
  ) {
    return request(
        lots,
        requestedPrice,
        stopLoss,
        takeProfit,
        quantity,
        price,
        triggerPrice,
        attachedProtections,
        null,
        null,
        null);
  }

  private static CreateOrderRequest request(
      BigDecimal lots,
      BigDecimal requestedPrice,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      BigDecimal quantity,
      BigDecimal price,
      BigDecimal triggerPrice,
      List<AttachedProtectionRequest> attachedProtections,
      BigDecimal activationPrice,
      BigDecimal trailingDelta,
      BigDecimal trailingRate
  ) {
    return new CreateOrderRequest(
        UUID.randomUUID(),
        "EURUSD",
        OrderSide.BUY,
        OrderType.MARKET,
        lots,
        requestedPrice,
        stopLoss,
        takeProfit,
        "numeric-boundary-key",
        "numeric-boundary-key",
        quantity,
        price,
        1,
        PositionSide.BOTH,
        QuantityUnit.BASE,
        MarginMode.CROSS,
        triggerPrice,
        triggerPrice == null ? null : TriggerPriceType.LAST_PRICE,
        false,
        attachedProtections,
        TimeInForce.GTC,
        false,
        activationPrice,
        trailingDelta,
        trailingRate);
  }

  private static AttachedProtectionRequest protection(
      BigDecimal triggerPrice,
      BigDecimal price,
      BigDecimal quantity
  ) {
    return new AttachedProtectionRequest(
        ProtectionType.STOP_LOSS,
        triggerPrice,
        TriggerPriceType.MARK_PRICE,
        price == null ? TriggerExecutionType.MARKET : TriggerExecutionType.LIMIT,
        price,
        quantity,
        quantity == null ? null : QuantityUnit.BASE);
  }

  private static CreateOrderRequest requestWithUnit(
      BigDecimal quantity,
      QuantityUnit quantityUnit
  ) {
    return new CreateOrderRequest(
        UUID.randomUUID(),
        quantityUnit == QuantityUnit.CONTRACTS ? "BTCUSDT-PERP" : "BTCUSDT",
        OrderSide.SELL,
        OrderType.MARKET,
        null,
        null,
        null,
        null,
        "quantity-semantic-key",
        "quantity-semantic-key",
        quantity,
        null,
        10,
        PositionSide.BOTH,
        quantityUnit,
        quantityUnit == QuantityUnit.CONTRACTS ? MarginMode.CROSS : MarginMode.CASH,
        null,
        null,
        false,
        List.of());
  }

  private record BoundaryCase(String label, CreateOrderRequest request, String code) {
  }
}
