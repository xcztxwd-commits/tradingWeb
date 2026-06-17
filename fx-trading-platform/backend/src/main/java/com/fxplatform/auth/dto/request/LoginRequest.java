package com.fxplatform.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * LoginRequest 承载认证授权模块的数据结构。
 */
public record LoginRequest(
    @NotBlank
    @Size(max = 255)
    String email,
    @NotBlank
    @Size(min = 8, max = 128)
    String password
) {
}
