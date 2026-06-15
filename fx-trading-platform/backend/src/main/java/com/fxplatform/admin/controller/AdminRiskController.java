package com.fxplatform.admin.controller;

import com.fxplatform.admin.dto.request.AdminReasonRequest;
import com.fxplatform.admin.dto.request.AdminRiskConfigRequest;
import com.fxplatform.admin.dto.response.AdminRiskConfigResponse;
import com.fxplatform.admin.service.AdminRiskCommandService;
import com.fxplatform.admin.service.AdminRiskQueryService;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台风控配置接口，查询和写入都走 MyBatis-Plus Mapper。
 */
@RestController
@RequestMapping("/api/admin/risk")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminRiskController {

  private final AdminRiskQueryService adminRiskQueryService;
  private final AdminRiskCommandService adminRiskCommandService;

  /** 查询当前风控配置列表。 */
  @GetMapping("/configs")
  public ApiResponse<List<AdminRiskConfigResponse>> configs() {
    return ApiResponse.success(adminRiskQueryService.configs());
  }

  /** 创建风控配置。 */
  @PostMapping("/configs")
  public ApiResponse<AdminRiskConfigResponse> createConfig(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminRiskConfigRequest request
  ) {
    return ApiResponse.success(adminRiskCommandService.createConfig(principal.id(), request));
  }

  /** 更新风控配置。 */
  @PutMapping("/configs/{configId}")
  public ApiResponse<AdminRiskConfigResponse> updateConfig(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID configId,
      @Valid @RequestBody AdminRiskConfigRequest request
  ) {
    return ApiResponse.success(adminRiskCommandService.updateConfig(principal.id(), configId, request));
  }

  /** 删除风控配置。 */
  @DeleteMapping("/configs/{configId}")
  public ApiResponse<Void> deleteConfig(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID configId,
      @Valid @RequestBody AdminReasonRequest request
  ) {
    adminRiskCommandService.deleteConfig(principal.id(), configId, request.reason());
    return ApiResponse.success(null);
  }
}
