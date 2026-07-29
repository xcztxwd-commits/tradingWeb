package com.fxplatform.tradinglab.admin;

import com.fxplatform.admin.service.AdminPermissionCatalog;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.common.web.RequestIdFilter;
import com.fxplatform.tradinglab.admin.report.TradingLabAdminRequestContext;
import com.fxplatform.tradinglab.admin.report.TradingLabPermanentRequest;
import com.fxplatform.tradinglab.admin.report.TradingLabPermanentResponse;
import com.fxplatform.tradinglab.admin.report.TradingLabPrintConfirmationResponse;
import com.fxplatform.tradinglab.admin.report.TradingLabPrintInfoResponse;
import com.fxplatform.tradinglab.admin.report.TradingLabReportAdminService;
import com.fxplatform.tradinglab.admin.report.TradingLabReportResponse;
import com.fxplatform.tradinglab.report.TradingLabReportReadTicket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/admin/trading-lab/reports")
public class TradingLabReportController {

  private static final String PRINT_CONFIRMATION_HEADER =
      "X-Trading-Lab-Print-Confirmation";
  private static final MediaType PRINT_MEDIA_TYPE =
      new MediaType("text", "plain", StandardCharsets.UTF_8);
  private static final long PRINT_ASYNC_TIMEOUT_MILLIS =
      Duration.ofMinutes(6).toMillis();

  private final TradingLabReportAdminService service;

  public TradingLabReportController(TradingLabReportAdminService service) {
    this.service = service;
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('ROLE_ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.TRADING_LAB_VIEW + "')")
  public ApiResponse<TradingLabReportResponse> detail(@PathVariable UUID id) {
    return ApiResponse.success(service.detail(id));
  }

  @GetMapping("/{id}/download")
  @PreAuthorize("hasAuthority('ROLE_ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.TRADING_LAB_VIEW + "')")
  public ResponseEntity<StreamingResponseBody> download(@PathVariable UUID id) {
    TradingLabReportReadTicket ticket = service.prepareDownload(id);
    StreamingResponseBody body =
        output -> service.streamCompact(ticket, output);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .body(body);
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('ROLE_ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.TRADING_LAB_EXECUTE + "')")
  public ApiResponse<Void> delete(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id,
      HttpServletRequest request
  ) {
    service.delete(requestContext(principal, request), id);
    return ApiResponse.success(null);
  }

  @PostMapping("/{id}/permanent")
  @PreAuthorize("hasAuthority('ROLE_ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.TRADING_LAB_EXECUTE + "')")
  public ApiResponse<TradingLabPermanentResponse> permanent(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id,
      @Valid @RequestBody TradingLabPermanentRequest body,
      HttpServletRequest request
  ) {
    return ApiResponse.success(service.setPermanent(
        requestContext(principal, request),
        id,
        body.permanent()));
  }

  @GetMapping("/{id}/print-info")
  @PreAuthorize("hasAuthority('ROLE_ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.TRADING_LAB_VIEW + "')")
  public ApiResponse<TradingLabPrintInfoResponse> printInfo(
      @PathVariable UUID id
  ) {
    return ApiResponse.success(service.printInfo(id));
  }

  @PostMapping("/{id}/print-confirmation")
  @PreAuthorize("hasAuthority('ROLE_ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.SUPER_ADMIN + "')")
  public ApiResponse<TradingLabPrintConfirmationResponse> printConfirmation(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id
  ) {
    return ApiResponse.success(
        service.issuePrintConfirmation(principal.id(), id));
  }

  @GetMapping("/{id}/print")
  @PreAuthorize("hasAuthority('ROLE_ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.TRADING_LAB_VIEW + "')")
  public ResponseEntity<StreamingResponseBody> print(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id,
      @RequestHeader(
          value = PRINT_CONFIRMATION_HEADER,
          required = false) String confirmation,
      HttpServletRequest request
  ) {
    WebAsyncUtils.getAsyncManager(request)
        .getAsyncWebRequest()
        .setTimeout(PRINT_ASYNC_TIMEOUT_MILLIS);
    TradingLabReportReadTicket ticket =
        service.preparePrint(principal.id(), id, confirmation);
    StreamingResponseBody body =
        output -> service.streamPretty(ticket, output);
    return ResponseEntity.ok()
        .contentType(PRINT_MEDIA_TYPE)
        .body(body);
  }

  private TradingLabAdminRequestContext requestContext(
      UserPrincipal principal,
      HttpServletRequest request
  ) {
    String header = request.getHeader(RequestIdFilter.HEADER);
    UUID requestId;
    if (header == null || header.isBlank()) {
      String generatedByFilter = MDC.get("requestId");
      requestId = generatedByFilter == null || generatedByFilter.isBlank()
          ? UUID.randomUUID()
          : parseRequestId(generatedByFilter);
    } else {
      requestId = parseRequestId(header);
    }
    return new TradingLabAdminRequestContext(
        principal.id(),
        request.getRemoteAddr(),
        requestId);
  }

  private UUID parseRequestId(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "Trading Lab request ID must be a UUID");
    }
  }
}
