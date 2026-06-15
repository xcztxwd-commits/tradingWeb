package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 后台取消涨跌/价格调整任务请求。
 */
public record AdminPriceAdjustmentCancelRequest(
    @NotBlank String reason
) {
}
