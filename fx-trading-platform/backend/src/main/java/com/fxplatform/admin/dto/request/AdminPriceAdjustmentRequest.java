package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * AdminPriceAdjustmentRequest 是后台创建显式行情修正或模拟行情场景的请求。
 *
 * @param mode 调整模式，例如 DEMO_SCENARIO 或 PRICE_REPAIR。
 * @param adjustmentType 调整类型，例如 SET_MID_PRICE。
 * @param targetPrice 目标价格，必须为正数。
 * @param startsAt 生效开始时间。
 * @param endsAt 生效结束时间。
 * @param reason 创建调整记录的原因。
 */
public record AdminPriceAdjustmentRequest(
    @NotBlank String mode,
    @NotBlank String adjustmentType,
    @NotNull @DecimalMin("0.00000001") BigDecimal targetPrice,
    Instant startsAt,
    Instant endsAt,
    @NotBlank String reason
) {
}
