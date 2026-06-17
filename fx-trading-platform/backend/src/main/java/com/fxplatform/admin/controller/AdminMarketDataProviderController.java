package com.fxplatform.admin.controller;

import com.fxplatform.admin.dto.request.AdminDataProviderRequest;
import com.fxplatform.admin.dto.request.AdminSymbolDisplayRequest;
import com.fxplatform.admin.dto.request.AdminSymbolProviderBindingRequest;
import com.fxplatform.admin.dto.response.AdminDataProviderResponse;
import com.fxplatform.admin.dto.response.AdminProviderInstrumentResponse;
import com.fxplatform.admin.dto.response.AdminProviderSyncResponse;
import com.fxplatform.admin.dto.response.AdminSymbolProviderBindingResponse;
import com.fxplatform.admin.dto.response.AdminSymbolResponse;
import com.fxplatform.admin.service.AdminMarketDataProviderService;
import com.fxplatform.common.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/market")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminMarketDataProviderController {

  private final AdminMarketDataProviderService providerService;

  @GetMapping("/data-providers")
  public ApiResponse<List<AdminDataProviderResponse>> providers() {
    return ApiResponse.success(providerService.providers());
  }

  @PostMapping("/data-providers")
  @PreAuthorize("hasAuthority('market:data-provider:update')")
  public ApiResponse<AdminDataProviderResponse> createProvider(
      @Valid @RequestBody AdminDataProviderRequest request
  ) {
    return ApiResponse.success(providerService.createProvider(request));
  }

  @PutMapping("/data-providers/{providerId}")
  @PreAuthorize("hasAuthority('market:data-provider:update')")
  public ApiResponse<AdminDataProviderResponse> updateProvider(
      @PathVariable UUID providerId,
      @Valid @RequestBody AdminDataProviderRequest request
  ) {
    return ApiResponse.success(providerService.updateProvider(providerId, request));
  }

  @PostMapping("/data-providers/{providerId}/test")
  @PreAuthorize("hasAuthority('market:data-provider:update')")
  public ApiResponse<AdminDataProviderResponse> testProvider(@PathVariable UUID providerId) {
    return ApiResponse.success(providerService.testProvider(providerId));
  }

  @PostMapping("/data-providers/{providerId}/sync-instruments")
  @PreAuthorize("hasAuthority('market:data-provider:update')")
  public ApiResponse<AdminProviderSyncResponse> syncInstruments(@PathVariable UUID providerId) {
    return ApiResponse.success(providerService.syncInstruments(providerId));
  }

  @GetMapping("/data-providers/{providerId}/instruments")
  public ApiResponse<List<AdminProviderInstrumentResponse>> instruments(@PathVariable UUID providerId) {
    return ApiResponse.success(providerService.instruments(providerId));
  }

  @GetMapping("/symbols/{symbolId}/provider-bindings")
  public ApiResponse<List<AdminSymbolProviderBindingResponse>> bindings(@PathVariable UUID symbolId) {
    return ApiResponse.success(providerService.bindings(symbolId));
  }

  @PostMapping("/symbols/{symbolId}/provider-bindings")
  public ApiResponse<AdminSymbolProviderBindingResponse> createBinding(
      @PathVariable UUID symbolId,
      @Valid @RequestBody AdminSymbolProviderBindingRequest request
  ) {
    return ApiResponse.success(providerService.createBinding(symbolId, request));
  }

  @PutMapping("/symbols/{symbolId}/provider-bindings/{bindingId}")
  public ApiResponse<AdminSymbolProviderBindingResponse> updateBinding(
      @PathVariable UUID symbolId,
      @PathVariable UUID bindingId,
      @Valid @RequestBody AdminSymbolProviderBindingRequest request
  ) {
    return ApiResponse.success(providerService.updateBinding(symbolId, bindingId, request));
  }

  @PutMapping("/symbols/{symbolId}/display")
  public ApiResponse<AdminSymbolResponse> updateSymbolDisplay(
      @PathVariable UUID symbolId,
      @Valid @RequestBody AdminSymbolDisplayRequest request
  ) {
    return ApiResponse.success(providerService.updateSymbolDisplay(symbolId, request));
  }
}
