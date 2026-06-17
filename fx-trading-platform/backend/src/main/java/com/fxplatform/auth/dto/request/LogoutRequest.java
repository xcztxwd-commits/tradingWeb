package com.fxplatform.auth.dto.request;

public record LogoutRequest(
    String refreshToken
) {
}
