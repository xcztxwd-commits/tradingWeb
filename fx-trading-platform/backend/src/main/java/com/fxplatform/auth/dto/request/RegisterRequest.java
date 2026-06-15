package com.fxplatform.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * RegisterRequest 承载认证授权模块的数据结构。
 */
public record RegisterRequest(
    @Email @NotBlank String email,
    String phone,
    @NotBlank @Size(min = 8, max = 128) String password
) {
}
