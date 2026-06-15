package com.fxplatform.admin.controller;

import com.fxplatform.admin.dto.response.AdminDashboardSummaryResponse;
import com.fxplatform.admin.service.AdminDashboardService;
import com.fxplatform.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AdminDashboardController 提供后台首页统计接口。
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminDashboardController {

  /** 后台首页统计服务。 */
  private final AdminDashboardService adminDashboardService;

  /**
   * 查询后台首页核心统计。
   */
  @GetMapping("/summary")
  public ApiResponse<AdminDashboardSummaryResponse> summary() {
    return ApiResponse.success(adminDashboardService.summary());
  }
}
