package com.fxplatform.auth.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * RegisterRequest 承载认证授权模块的数据结构。
 */
public record RegisterRequest(
    @Email
    @Size(max = 255)
    String email,
    @Pattern(regexp = "^\\+?[0-9][0-9\\s-]{5,31}$")
    String phone,
    @NotBlank
    @Size(min = 8, max = 128)
    String password
) {

  @AssertTrue(message = "email or phone is required")
  public boolean isIdentifierPresent() {
    return hasText(email) || hasText(phone);
  }

  private static boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }
}
