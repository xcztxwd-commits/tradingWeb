package com.fxplatform.tradinglab.admin;

import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.tradinglab.admin.dto.TradingLabConfigResponse;
import com.fxplatform.tradinglab.admin.dto.TradingLabRunCreateRequest;
import com.fxplatform.tradinglab.admin.dto.TradingLabRunResponse;
import com.fxplatform.tradinglab.admin.dto.TradingLabScenarioResponse;
import com.fxplatform.tradinglab.admin.dto.TradingLabScenarioWriteRequest;
import com.fxplatform.tradinglab.state.TradingLabRunControlResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/trading-lab")
@Validated
@RequiredArgsConstructor
public class TradingLabAdminController {

  private static final String VIEW =
      "hasAuthority('ROLE_ADMIN') and hasAuthority('TRADING_LAB_VIEW')";
  private static final String EXECUTE =
      "hasAuthority('ROLE_ADMIN') and hasAuthority('TRADING_LAB_EXECUTE')";

  private final TradingLabAdminService service;
  private final TradingLabAdminRunControlFacade controls;

  @GetMapping("/config")
  @PreAuthorize(VIEW)
  public ApiResponse<TradingLabConfigResponse> config() {
    return ApiResponse.success(service.config());
  }

  @GetMapping("/scenarios")
  @PreAuthorize(VIEW)
  public ApiResponse<AdminPageResponse<TradingLabScenarioResponse>> scenarios(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return ApiResponse.success(service.scenarios(page, size));
  }

  @GetMapping("/scenarios/{id}")
  @PreAuthorize(VIEW)
  public ApiResponse<TradingLabScenarioResponse> scenario(@PathVariable UUID id) {
    return ApiResponse.success(service.scenario(id));
  }

  @PostMapping("/scenarios")
  @PreAuthorize(EXECUTE)
  public ApiResponse<TradingLabScenarioResponse> createScenario(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestHeader(name = "X-Request-Id", required = false) UUID requestId,
      HttpServletRequest http,
      @Valid @RequestBody TradingLabScenarioWriteRequest request) {
    return ApiResponse.success(service.createScenario(
        requireActor(principal), clientIp(http), requestId(requestId), request));
  }

  @PutMapping("/scenarios/{id}")
  @PreAuthorize(EXECUTE)
  public ApiResponse<TradingLabScenarioResponse> updateScenario(
      @PathVariable UUID id,
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestHeader(name = "X-Request-Id", required = false) UUID requestId,
      HttpServletRequest http,
      @Valid @RequestBody TradingLabScenarioWriteRequest request) {
    return ApiResponse.success(service.updateScenario(
        id, requireActor(principal), clientIp(http), requestId(requestId), request));
  }

  @DeleteMapping("/scenarios/{id}")
  @PreAuthorize(EXECUTE)
  public ApiResponse<Void> deleteScenario(
      @PathVariable UUID id,
      @RequestParam long expectedVersion,
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestHeader(name = "X-Request-Id", required = false) UUID requestId,
      HttpServletRequest http) {
    service.deleteScenario(
        id, requireActor(principal), clientIp(http), requestId(requestId), expectedVersion);
    return ApiResponse.success(null);
  }

  @PostMapping("/scenarios/{id}/runs")
  @PreAuthorize(EXECUTE)
  public ApiResponse<TradingLabRunResponse> createRun(
      @PathVariable UUID id,
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestHeader(name = "X-Request-Id", required = false) UUID requestId,
      HttpServletRequest http,
      @Valid @RequestBody TradingLabRunCreateRequest request) {
    return ApiResponse.success(service.createRun(
        id, requireActor(principal), clientIp(http), requestId(requestId), request));
  }

  @GetMapping("/runs/{id}")
  @PreAuthorize(VIEW)
  public ApiResponse<TradingLabRunResponse> run(@PathVariable UUID id) {
    return ApiResponse.success(service.run(id));
  }

  @PostMapping("/runs/{id}/pause")
  @PreAuthorize(EXECUTE)
  public ApiResponse<TradingLabRunControlResult> pause(
      @PathVariable UUID id,
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestHeader(name = "X-Request-Id", required = false) UUID requestId,
      HttpServletRequest http) {
    return ApiResponse.success(controls.execute(
        TradingLabRunControlAction.PAUSE,
        id,
        requireActor(principal),
        clientIp(http),
        requestId(requestId)));
  }

  @PostMapping("/runs/{id}/resume")
  @PreAuthorize(EXECUTE)
  public ApiResponse<TradingLabRunControlResult> resume(
      @PathVariable UUID id,
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestHeader(name = "X-Request-Id", required = false) UUID requestId,
      HttpServletRequest http) {
    return ApiResponse.success(controls.execute(
        TradingLabRunControlAction.RESUME,
        id,
        requireActor(principal),
        clientIp(http),
        requestId(requestId)));
  }

  @PostMapping("/runs/{id}/cancel")
  @PreAuthorize(EXECUTE)
  public ApiResponse<TradingLabRunControlResult> cancel(
      @PathVariable UUID id,
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestHeader(name = "X-Request-Id", required = false) UUID requestId,
      HttpServletRequest http) {
    return ApiResponse.success(controls.execute(
        TradingLabRunControlAction.CANCEL,
        id,
        requireActor(principal),
        clientIp(http),
        requestId(requestId)));
  }

  private static UUID requireActor(UserPrincipal principal) {
    if (principal == null || principal.id() == null) {
      throw new IllegalArgumentException("Trading Lab actor is required");
    }
    return principal.id();
  }

  private static String clientIp(HttpServletRequest request) {
    String address = request == null ? null : request.getRemoteAddr();
    return address == null || address.isBlank() ? "unknown" : address;
  }

  private static UUID requestId(UUID value) {
    return value == null ? UUID.randomUUID() : value;
  }
}
