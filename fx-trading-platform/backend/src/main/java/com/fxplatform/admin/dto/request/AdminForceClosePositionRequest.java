package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * AdminForceClosePositionRequest 是后台强制平仓的请求 DTO。
 *
 * @param accountId 持仓所属交易账户 ID，用于领域服务校验持仓归属。
 * @param reason 管理员强制平仓的业务原因，用于审计和风险复盘。
 * @param idempotencyKey 前端生成的幂等键，用于后续接入命令幂等表。
 */
public record AdminForceClosePositionRequest(
    @NotNull UUID accountId,
    @NotBlank String reason,
    String idempotencyKey
) {
}
