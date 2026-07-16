package com.fxplatform.account.controller;

import com.fxplatform.account.dto.TradingSettingsResponse;
import com.fxplatform.account.dto.UpdatePositionModeRequest;
import com.fxplatform.account.dto.UpdateSymbolSettingsRequest;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.trading.service.TradingSettingsService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountTradingSettingsController {

  private final TradingSettingsService tradingSettingsService;

  @GetMapping("/{accountId}/trading-settings")
  public ApiResponse<TradingSettingsResponse> get(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID accountId
  ) {
    return ApiResponse.success(tradingSettingsService.get(principal.id(), accountId));
  }

  @PatchMapping("/{accountId}/position-mode")
  public ApiResponse<TradingSettingsResponse> updatePositionMode(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID accountId,
      @Valid @RequestBody UpdatePositionModeRequest request
  ) {
    return ApiResponse.success(
        tradingSettingsService.updatePositionMode(principal.id(), accountId, request));
  }

  @PatchMapping("/{accountId}/symbols/{symbol}/settings")
  public ApiResponse<TradingSettingsResponse> updateSymbolSettings(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID accountId,
      @PathVariable String symbol,
      @Valid @RequestBody UpdateSymbolSettingsRequest request
  ) {
    return ApiResponse.success(
        tradingSettingsService.updateSymbolSettings(principal.id(), accountId, symbol, request));
  }
}
