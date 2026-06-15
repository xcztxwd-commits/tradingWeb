package com.fxplatform.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.response.AdminLedgerEntryResponse;
import com.fxplatform.ledger.entity.LedgerEntryEntity;
import com.fxplatform.ledger.repository.LedgerEntryRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * AdminFinanceQueryService 提供后台财务只读查询能力。
 */
@Service
@RequiredArgsConstructor
public class AdminFinanceQueryService {

  /** 资金流水 Mapper，用于后台资金流水分页查询。 */
  private final LedgerEntryRepository ledgerEntryRepository;

  /** 分页查询全部资金流水，按创建时间倒序返回。 */
  public AdminPageResponse<AdminLedgerEntryResponse> ledger(int page, int size) {
    return AdminPageResponse.from(ledgerEntryRepository
        .findAll(AdminPageRequests.page(page, size), Map.of("createdAt", "created_at"), "createdAt", false)
        .convert(AdminLedgerEntryResponse::from));
  }

  /** 按截图资金明细列表协议分页筛选资金流水。 */
  public AdminPageResponse<AdminLedgerEntryResponse> ledger(AdminFeaturePageQuery query) {
    QueryWrapper<LedgerEntryEntity> wrapper = new QueryWrapper<>();
    AdminFeatureQuerySupport.eqUuidIfPresent(wrapper, query, "accountId", "account_id");
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "entryType", "entry_type");
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "transactionType", "entry_type");
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "currency", "currency");
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "referenceType", "reference_type");
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "description", "description");
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "title", "description");
    AdminFeatureQuerySupport.applyOrder(wrapper, query, Map.of(
        "accountId", "account_id",
        "entryType", "entry_type",
        "transactionType", "entry_type",
        "amount", "amount",
        "balanceAfter", "balance_after",
        "currency", "currency",
        "referenceType", "reference_type",
        "createdAt", "created_at"), "created_at", false);
    return AdminPageResponse.from(ledgerEntryRepository
        .selectPage(AdminFeatureQuerySupport.page(query), wrapper)
        .convert(AdminLedgerEntryResponse::from));
  }
}
