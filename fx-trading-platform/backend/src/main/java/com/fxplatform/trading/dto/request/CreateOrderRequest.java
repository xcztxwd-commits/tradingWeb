package com.fxplatform.trading.dto.request;

import cn.hutool.core.util.StrUtil;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * CreateOrderRequest 承载交易模块的数据结构。
 */
public record CreateOrderRequest(
    @NotNull UUID accountId,
    @NotBlank String symbol,
    @NotNull OrderSide side,
    @NotNull OrderType orderType,
    @DecimalMin("0.01") BigDecimal lots,
    BigDecimal requestedPrice,
    BigDecimal stopLoss,
    BigDecimal takeProfit,
    String idempotencyKey,
    String clientOrderId,
    @DecimalMin("0.01") BigDecimal quantity,
    BigDecimal price,
    @Min(1) Integer leverage
) {
  public CreateOrderRequest(
      UUID accountId,
      String symbol,
      OrderSide side,
      OrderType orderType,
      BigDecimal lots,
      BigDecimal requestedPrice,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      String idempotencyKey,
      String clientOrderId,
      BigDecimal quantity,
      BigDecimal price
  ) {
    this(
        accountId,
        symbol,
        side,
        orderType,
        lots,
        requestedPrice,
        stopLoss,
        takeProfit,
        idempotencyKey,
        clientOrderId,
        quantity,
        price,
        null);
  }

  /**
   * 执行 clientOrderId 方法逻辑。
   */
  @Override
  public String clientOrderId() {
    return hasText(clientOrderId) ? clientOrderId : idempotencyKey;
  }

  /**
   * 执行 quantity 方法逻辑。
   */
  @Override
  public BigDecimal quantity() {
    return quantity != null ? quantity : lots;
  }

  /**
   * 执行 price 方法逻辑。
   */
  @Override
  public BigDecimal price() {
    return price != null ? price : requestedPrice;
  }

  /**
   * 执行 hasClientOrderId 方法逻辑。
   */
  @AssertTrue(message = "clientOrderId or idempotencyKey is required")
  public boolean hasClientOrderId() {
    return hasText(clientOrderId());
  }

  /**
   * 执行 hasQuantity 方法逻辑。
   */
  @AssertTrue(message = "quantity or lots is required")
  public boolean hasQuantity() {
    return quantity() != null;
  }

  /**
   * 执行 hasText 方法逻辑。
   */
  private static boolean hasText(String value) {
    return StrUtil.isNotBlank(value);
  }
}
