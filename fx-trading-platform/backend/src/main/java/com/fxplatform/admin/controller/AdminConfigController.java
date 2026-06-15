package com.fxplatform.admin.controller;

import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.request.AdminDictionaryRequest;
import com.fxplatform.admin.dto.request.AdminSystemSettingRequest;
import com.fxplatform.admin.dto.response.AdminDictionaryResponse;
import com.fxplatform.admin.dto.response.AdminSystemSettingResponse;
import com.fxplatform.admin.service.AdminConfigCommandService;
import com.fxplatform.admin.service.AdminConfigQueryService;
import com.fxplatform.admin.service.AdminFeaturePageQuery;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AdminConfigController 提供后台字典和系统设置接口。
 */
@RestController
@RequestMapping("/api/admin/config")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminConfigController {

  /** 配置查询服务。 */
  private final AdminConfigQueryService configQueryService;
  /** 配置写操作服务。 */
  private final AdminConfigCommandService configCommandService;

  /**
   * 分页查询字典项。
   */
  @GetMapping("/dictionaries")
  public ApiResponse<AdminPageResponse<AdminDictionaryResponse>> dictionaries(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String sortField,
      @RequestParam(defaultValue = "asc") String sortDirection,
      @RequestParam Map<String, String> requestParams
  ) {
    return ApiResponse.success(configQueryService.dictionaries(
        AdminFeaturePageQuery.from(page, size, sortField, sortDirection, requestParams)));
  }

  /**
   * 创建或更新字典项。
   */
  @PutMapping("/dictionaries")
  public ApiResponse<AdminDictionaryResponse> upsertDictionary(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminDictionaryRequest request
  ) {
    return ApiResponse.success(configCommandService.upsertDictionary(principal.id(), request));
  }

  /**
   * 分页查询系统设置。
   */
  @GetMapping("/settings")
  public ApiResponse<AdminPageResponse<AdminSystemSettingResponse>> settings(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String sortField,
      @RequestParam(defaultValue = "asc") String sortDirection,
      @RequestParam Map<String, String> requestParams
  ) {
    return ApiResponse.success(configQueryService.settings(
        AdminFeaturePageQuery.from(page, size, sortField, sortDirection, requestParams)));
  }

  /**
   * 创建或更新系统设置。
   */
  @PutMapping("/settings")
  public ApiResponse<AdminSystemSettingResponse> updateSetting(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminSystemSettingRequest request
  ) {
    return ApiResponse.success(configCommandService.updateSetting(principal.id(), request));
  }
}
