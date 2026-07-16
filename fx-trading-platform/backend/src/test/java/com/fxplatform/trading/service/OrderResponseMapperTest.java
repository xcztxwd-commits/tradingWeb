package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.enums.LiquidityRole;
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
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderResponseMapperTest {

  @Test
  void mapsEveryTask6QuantityFeeTriggerAndOcoField() {
    UUID groupId = UUID.randomUUID();
    UUID holdOwnerId = UUID.randomUUID();
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setAccountId(UUID.randomUUID());
    order.setSymbol("BTCUSDT");
    order.setProductType(ProductType.CRYPTO_SPOT);
    order.setPositionMode(PositionMode.ONE_WAY);
    order.setPositionSide(PositionSide.BOTH);
    order.setMarginMode(MarginMode.CASH);
    order.setSide(OrderSide.BUY);
    order.setOrderType(OrderType.STOP_MARKET);
    order.setStatus(OrderStatus.PENDING);
    order.setLots(new BigDecimal("0.0019"));
    order.setQuantity(new BigDecimal("100.1235"));
    order.setQuantityUnit(QuantityUnit.QUOTE);
    order.setOriginalQuantity(new BigDecimal("100.12345678"));
    order.setBaseQuantity(new BigDecimal("0.0019"));
    order.setTimeInForce(TimeInForce.GTC);
    order.setReduceOnly(false);
    order.setOrderOrigin(OrderOrigin.OCO);
    order.setFee(new BigDecimal("0.00000095"));
    order.setFeeAsset("BTC");
    order.setLiquidityRole(LiquidityRole.TAKER);
    order.setTriggerPrice(new BigDecimal("51000"));
    order.setTriggerPriceType(TriggerPriceType.LAST_PRICE);
    order.setTriggerExecutionType(TriggerExecutionType.MARKET);
    order.setContingencyGroupId(groupId);
    order.setHoldOwnerOrderId(holdOwnerId);
    order.setVersion(7L);

    OrderResponse response = new OrderResponseMapper().toResponse(order);

    assertThat(response.productType()).isEqualTo(ProductType.CRYPTO_SPOT);
    assertThat(response.positionMode()).isEqualTo(PositionMode.ONE_WAY);
    assertThat(response.positionSide()).isEqualTo(PositionSide.BOTH);
    assertThat(response.marginMode()).isEqualTo(MarginMode.CASH);
    assertThat(response.quantity()).isEqualByComparingTo("100.12345678");
    assertThat(response.quantityUnit()).isEqualTo(QuantityUnit.QUOTE);
    assertThat(response.originalQuantity()).isEqualByComparingTo("100.12345678");
    assertThat(response.baseQuantity()).isEqualByComparingTo("0.0019");
    assertThat(response.timeInForce()).isEqualTo(TimeInForce.GTC);
    assertThat(response.reduceOnly()).isFalse();
    assertThat(response.origin()).isEqualTo(OrderOrigin.OCO);
    assertThat(response.feeAsset()).isEqualTo("BTC");
    assertThat(response.liquidityRole()).isEqualTo(LiquidityRole.TAKER);
    assertThat(response.triggerPrice()).isEqualByComparingTo("51000");
    assertThat(response.triggerPriceType()).isEqualTo(TriggerPriceType.LAST_PRICE);
    assertThat(response.triggerExecutionType()).isEqualTo(TriggerExecutionType.MARKET);
    assertThat(response.contingencyGroupId()).isEqualTo(groupId);
    assertThat(response.holdOwnerOrderId()).isEqualTo(holdOwnerId);
    assertThat(response.version()).isEqualTo(7L);
  }

  @Test
  void legacyCompatibilityConstructorDefaultsVersionToNull() {
    OrderResponse response = new OrderResponse(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "BTCUSDT-PERP",
        "BUY",
        "MARKET",
        10,
        "FILLED",
        BigDecimal.ONE,
        BigDecimal.ONE,
        null,
        null,
        BigDecimal.ONE,
        BigDecimal.ZERO,
        null,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        "USDT",
        null,
        null,
        null,
        null,
        null,
        null);

    assertThat(response.version()).isNull();
    assertThat(response.origin()).isEqualTo(OrderOrigin.USER);
  }
}
