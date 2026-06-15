package com.fxplatform.admin.controller;

import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.response.AdminAccountResponse;
import com.fxplatform.admin.service.AdminAccountQueryService;
import com.fxplatform.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AdminAccountController 提供后台账户管理只读接口。
 */
@RestController
@RequestMapping("/api/admin/accounts")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminAccountController {

  /** 后台账户查询服务。 */
  private final AdminAccountQueryService adminAccountQueryService;

  /**
   * 分页查询全部交易账户。
   */
  @GetMapping
  public ApiResponse<AdminPageResponse<AdminAccountResponse>> accounts(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size
  ) {
    return ApiResponse.success(adminAccountQueryService.accounts(page, size));
  }
}
