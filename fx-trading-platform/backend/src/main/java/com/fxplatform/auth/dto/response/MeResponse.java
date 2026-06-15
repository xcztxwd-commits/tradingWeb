package com.fxplatform.auth.dto.response;

import java.util.UUID;

/**
 * MeResponse 承载认证授权模块的数据结构。
 */
public record MeResponse(
    UUID id,
    String email,
    String role,
    String status
) {
}
