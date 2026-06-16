package com.fxplatform.ledger.controller;

import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.ledger.dto.LedgerEntryResponse;
import com.fxplatform.ledger.service.LedgerService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LedgerController 是资金流水模块的 REST API 控制器。
 */
@RestController
@RequestMapping("/api/ledger")
@RequiredArgsConstructor
public class LedgerController {

  private final LedgerService ledgerService;

  /**
   * 处理 entries 查询接口请求。
   */
  @GetMapping
  public ApiResponse<List<LedgerEntryResponse>> entries(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestParam UUID accountId
  ) {
    return ApiResponse.success(ledgerService.visibleEntries(principal.id(), accountId));
  }
}
