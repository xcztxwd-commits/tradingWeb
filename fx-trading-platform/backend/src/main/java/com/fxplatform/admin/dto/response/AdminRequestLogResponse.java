package com.fxplatform.admin.dto.response;

import com.fxplatform.audit.entity.RequestLogEntity;
import java.time.Instant;
import java.util.UUID;

/**
 * 后台请求日志响应。
 */
public record AdminRequestLogResponse(
    UUID id,
    String requestId,
    String method,
    String path,
    String queryString,
    String clientIp,
    String userAgent,
    Integer statusCode,
    Long durationMs,
    String errorMessage,
    Instant createdAt
) {

  public static AdminRequestLogResponse from(RequestLogEntity entity) {
    return new AdminRequestLogResponse(
        entity.getId(),
        entity.getRequestId(),
        entity.getMethod(),
        entity.getPath(),
        entity.getQueryString(),
        entity.getClientIp(),
        entity.getUserAgent(),
        entity.getStatusCode(),
        entity.getDurationMs(),
        entity.getErrorMessage(),
        entity.getCreatedAt());
  }
}
