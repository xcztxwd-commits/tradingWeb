package com.fxplatform.admin.controller;

import com.fxplatform.admin.dto.request.AdminVerificationCodeLogRequest;
import com.fxplatform.admin.dto.response.AdminRequestLogResponse;
import com.fxplatform.admin.dto.response.AdminVerificationCodeLogResponse;
import com.fxplatform.audit.service.RequestLogService;
import com.fxplatform.audit.service.VerificationCodeLogService;
import com.fxplatform.common.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AdminLogController 提供请求日志和验证码发送记录后台 API。
 */
@RestController
@RequestMapping("/api/admin/logs")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminLogController {

  private final RequestLogService requestLogService;
  private final VerificationCodeLogService verificationCodeLogService;

  /** 查询最近请求日志。 */
  @GetMapping("/request-logs")
  public ApiResponse<List<AdminRequestLogResponse>> requestLogs(@RequestParam(defaultValue = "50") int size) {
    return ApiResponse.success(requestLogService.recent(size));
  }

  /** 查询最近验证码发送记录。 */
  @GetMapping("/verification-codes")
  public ApiResponse<List<AdminVerificationCodeLogResponse>> verificationCodes(
      @RequestParam(defaultValue = "50") int size
  ) {
    return ApiResponse.success(verificationCodeLogService.recent(size));
  }

  /** 补录验证码发送记录，供短信/邮箱通道联调时使用。 */
  @PostMapping("/verification-codes")
  public ApiResponse<AdminVerificationCodeLogResponse> recordVerificationCode(
      @Valid @RequestBody AdminVerificationCodeLogRequest request
  ) {
    return ApiResponse.success(verificationCodeLogService.record(
        request.scene(),
        request.account(),
        request.channel(),
        request.code(),
        request.status(),
        request.errorMessage()));
  }
}
