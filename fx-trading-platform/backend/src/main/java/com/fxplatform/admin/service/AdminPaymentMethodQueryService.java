package com.fxplatform.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.response.AdminPaymentMethodResponse;
import com.fxplatform.finance.entity.AdminPaymentMethodEntity;
import com.fxplatform.finance.repository.AdminPaymentMethodRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * AdminPaymentMethodQueryService 提供后台支付方式查询能力。
 */
@Service
@RequiredArgsConstructor
public class AdminPaymentMethodQueryService {

  /** 支付方式 Mapper，用于分页读取支付配置。 */
  private final AdminPaymentMethodRepository paymentMethodRepository;

  /** 分页查询支付方式配置。 */
  public AdminPageResponse<AdminPaymentMethodResponse> paymentMethods(int page, int size) {
    return AdminPageResponse.from(paymentMethodRepository
        .findAll(AdminPageRequests.page(page, size), Map.of("displayOrder", "display_order"), "displayOrder", true)
        .convert(AdminPaymentMethodResponse::from));
  }

  /** 按截图列表协议分页筛选收款方式，供 WH 风格收款方式页面使用。 */
  public AdminPageResponse<AdminPaymentMethodResponse> paymentMethods(AdminFeaturePageQuery query) {
    QueryWrapper<AdminPaymentMethodEntity> wrapper = new QueryWrapper<>();
    AdminFeatureQuerySupport.likeIfPresent(wrapper, query, "name", "name");
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "methodType", "method_type");
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "type", "method_type");
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "currency", "currency");
    AdminFeatureQuerySupport.eqBooleanIfPresent(wrapper, query, "enabled", "enabled");
    AdminFeatureQuerySupport.eqBooleanIfPresent(wrapper, query, "status", "enabled");
    AdminFeatureQuerySupport.applyOrder(wrapper, query, Map.of(
        "name", "name",
        "methodType", "method_type",
        "type", "method_type",
        "currency", "currency",
        "enabled", "enabled",
        "status", "enabled",
        "displayOrder", "display_order",
        "createdAt", "created_at",
        "updatedAt", "updated_at"), "display_order", true);
    return AdminPageResponse.from(paymentMethodRepository
        .selectPage(AdminFeatureQuerySupport.page(query), wrapper)
        .convert(AdminPaymentMethodResponse::from));
  }
}
