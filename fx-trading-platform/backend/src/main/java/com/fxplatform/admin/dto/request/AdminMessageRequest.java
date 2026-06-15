package com.fxplatform.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/**
 * AdminMessageRequest 是后台站内消息请求 DTO。
 *
 * @param targetUserId 目标用户 ID，空值表示全站消息。
 * @param title 消息标题。
 * @param body 消息正文。
 * @param messageType 消息类型，例如 SYSTEM 或 RISK_NOTICE。
 * @param status 消息状态，例如 DRAFT 或 PUBLISHED。
 */
public record AdminMessageRequest(
    UUID targetUserId,
    @NotBlank String title,
    @NotBlank String body,
    @NotBlank String messageType,
    @NotBlank String status
) {
}
