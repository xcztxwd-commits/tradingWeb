package com.fxplatform.admin.service;

import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.response.AdminAccountResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * AdminAccountQueryService 提供后台账户的只读分页查询能力。
 */
@Service
@RequiredArgsConstructor
public class AdminAccountQueryService {

  /** 账户 Mapper，查询后立即映射为 DTO，避免 Controller 暴露实体。 */
  private final TradingAccountRepository accountRepository;

  /** 分页查询全部交易账户。 */
  public AdminPageResponse<AdminAccountResponse> accounts(int page, int size) {
    return AdminPageResponse.from(accountRepository
        .findAll(AdminPageRequests.page(page, size), Map.of("createdAt", "created_at"), "createdAt", false)
        .convert(AdminAccountResponse::from));
  }
}
