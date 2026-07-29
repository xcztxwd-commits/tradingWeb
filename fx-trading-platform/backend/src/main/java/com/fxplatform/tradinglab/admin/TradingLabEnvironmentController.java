package com.fxplatform.tradinglab.admin;

import com.fxplatform.admin.service.AdminPermissionCatalog;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.common.web.RequestIdFilter;
import com.fxplatform.tradinglab.environment.TradingLabEnvironmentAction;
import com.fxplatform.tradinglab.environment.TradingLabEnvironmentActionResponse;
import com.fxplatform.tradinglab.environment.TradingLabEnvironmentException;
import com.fxplatform.tradinglab.environment.TradingLabEnvironmentService;
import com.fxplatform.tradinglab.environment.TradingLabEnvironmentStatusResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!validation")
@RequestMapping("/api/admin/trading-lab/environment")
@RequiredArgsConstructor
public class TradingLabEnvironmentController {

  private final TradingLabEnvironmentService environmentService;

  @GetMapping
  @PreAuthorize("hasAuthority('ROLE_ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.TRADING_LAB_VIEW + "')")
  public ApiResponse<TradingLabEnvironmentStatusResponse> status(
      @AuthenticationPrincipal UserPrincipal principal,
      HttpServletRequest request
  ) {
    return ApiResponse.success(environmentService.status(
        principal.id(),
        request.getRemoteAddr(),
        auditRequestId(request)));
  }

  @PostMapping("/{action}")
  @PreAuthorize("hasAuthority('ROLE_ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.SUPER_ADMIN + "')")
  public ApiResponse<TradingLabEnvironmentActionResponse> action(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable String action,
      HttpServletRequest request
  ) {
    TradingLabEnvironmentAction typedAction =
        TradingLabEnvironmentAction.fromPath(action);
    String exactPath = "/api/admin/trading-lab/environment/"
        + typedAction.pathValue();
    if (!exactPath.equals(request.getRequestURI())) {
      throw TradingLabEnvironmentException.invalidAction();
    }
    return ApiResponse.success(environmentService.action(
        typedAction,
        principal.id(),
        request.getRemoteAddr(),
        auditRequestId(request)));
  }

  @ExceptionHandler(TradingLabEnvironmentException.class)
  public ResponseEntity<ApiResponse<Void>> environmentFailure(
      TradingLabEnvironmentException failure
  ) {
    return ResponseEntity.status(failure.httpStatus())
        .body(ApiResponse.fail(failure.getCode(), failure.getMessage()));
  }

  private static UUID auditRequestId(HttpServletRequest request) {
    String value = request.getHeader(RequestIdFilter.HEADER);
    if (value == null || value.isBlank()) {
      return UUID.randomUUID();
    }
    String normalized = value.trim();
    if (normalized.matches("[0-9a-fA-F]{32}")) {
      normalized = normalized.substring(0, 8)
          + "-" + normalized.substring(8, 12)
          + "-" + normalized.substring(12, 16)
          + "-" + normalized.substring(16, 20)
          + "-" + normalized.substring(20);
    }
    if (normalized.length() == 36) {
      try {
        return UUID.fromString(normalized);
      } catch (IllegalArgumentException ignored) {
        // A bounded deterministic request correlation is used below.
      }
    }
    if (normalized.length() > 256) {
      return UUID.randomUUID();
    }
    return UUID.nameUUIDFromBytes(
        normalized.getBytes(StandardCharsets.UTF_8));
  }
}
