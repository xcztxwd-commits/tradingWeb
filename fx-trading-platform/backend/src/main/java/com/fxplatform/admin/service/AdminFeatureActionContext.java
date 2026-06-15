package com.fxplatform.admin.service;

import java.util.Map;
import java.util.UUID;

/**
 * 后台动作处理器的不可变上下文。
 */
public record AdminFeatureActionContext(
    UUID actorUserId,
    String pageKey,
    String action,
    String rowId,
    Map<String, Object> payload
) {
}
