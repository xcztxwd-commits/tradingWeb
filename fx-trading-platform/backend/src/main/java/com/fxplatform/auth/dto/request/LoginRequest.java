package com.fxplatform.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * LoginRequest 承载认证授权模块的数据结构。
 */
public record LoginRequest(
    @Email @NotBlank String email,
    @NotBlank String password
) {
}
