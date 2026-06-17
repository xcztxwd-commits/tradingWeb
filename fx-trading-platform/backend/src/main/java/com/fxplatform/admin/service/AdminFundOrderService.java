package com.fxplatform.admin.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.request.AdminFundOperationRequest;
import com.fxplatform.admin.dto.request.AdminFundOrderRequest;
import com.fxplatform.admin.dto.request.AdminFundOrderReviewRequest;
import com.fxplatform.admin.dto.response.AdminFundOperationResponse;
import com.fxplatform.admin.dto.response.AdminFundOrderResponse;
import com.fxplatform.audit.service.AuditDetailsBuilder;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.finance.entity.FundOrderEntity;
import com.fxplatform.finance.enums.FundOrderStatus;
import com.fxplatform.finance.enums.FundOrderType;
import com.fxplatform.finance.repository.FundOrderRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AdminFundOrderService 承载充值/提现审核订单状态流。
 */
@Service
@RequiredArgsConstructor
public class AdminFundOrderService {

  private final FundOrderRepository fundOrderRepository;
  private final AdminFinanceCommandService financeCommandService;
  private final AuditLogService auditLogService;

  /** 查询最近资金审核订单。 */
  public List<AdminFundOrderResponse> recentOrders(String orderType, int size) {
    return fundOrderRepository.findRecent(orderType, size).stream()
        .map(AdminFundOrderResponse::from)
        .toList();
  }

  /** 按截图充值/提现订单列表协议分页筛选资金审核订单。 */
  public AdminPageResponse<AdminFundOrderResponse> orders(String orderType, AdminFeaturePageQuery query) {
    QueryWrapper<FundOrderEntity> wrapper = new QueryWrapper<>();
    String effectiveType = StrUtil.blankToDefault(orderType, query.filter("orderType"));
    if (StrUtil.isNotBlank(effectiveType)) {
      wrapper.eq("order_type", FundOrderType.fromCode(effectiveType).code());
    }
    AdminFeatureQuerySupport.eqUuidIfPresent(wrapper, query, "userId", "user_id");
    AdminFeatureQuerySupport.eqUuidIfPresent(wrapper, query, "uid", "user_id");
    AdminFeatureQuerySupport.eqUuidIfPresent(wrapper, query, "accountId", "account_id");
    String effectiveStatus = query.filter("status");
    if (StrUtil.isNotBlank(effectiveStatus)) {
      wrapper.eq("status", FundOrderStatus.fromCode(effectiveStatus).code());
    }
    AdminFeatureQuerySupport.eqIfPresent(wrapper, query, "currency", "currency");
    AdminFeatureQuerySupport.applyOrder(wrapper, query, Map.of(
        "userId", "user_id",
        "uid", "user_id",
        "accountId", "account_id",
        "orderType", "order_type",
        "amount", "amount",
        "currency", "currency",
        "status", "status",
        "createdAt", "created_at",
        "updatedAt", "updated_at"), "created_at", false);
    return AdminPageResponse.from(fundOrderRepository
        .selectPage(AdminFeatureQuerySupport.page(query), wrapper)
        .convert(AdminFundOrderResponse::from));
  }

  /** 创建待审核资金订单。 */
  @Transactional
  public AdminFundOrderResponse createOrder(UUID actorUserId, AdminFundOrderRequest request) {
    FundOrderEntity order = new FundOrderEntity();
    order.setUserId(request.userId());
    order.setAccountId(request.accountId());
    order.setOrderType(FundOrderType.fromCode(request.orderType()));
    order.setAmount(request.amount());
    order.setCurrency(request.currency());
    order.setPaymentMethodId(request.paymentMethodId());
    order.setApplicantNote(request.note());
    order.setStatus(FundOrderStatus.PENDING_REVIEW);
    order.setCreatedBy(actorUserId);
    FundOrderEntity saved = fundOrderRepository.save(order);
    audit(actorUserId, "ADMIN_FUND_ORDER_CREATE", saved, request.note());
    return AdminFundOrderResponse.from(saved);
  }

  /** 审核资金订单，只有通过时才调用真实入金/出金服务。 */
  @Transactional
  public AdminFundOrderResponse reviewOrder(UUID actorUserId, UUID orderId, AdminFundOrderReviewRequest request) {
    FundOrderStatus status = FundOrderStatus.fromReviewCode(request.status());
    AdminActionAuthorization.requireAuthority(status.isApproved()
        ? AdminPermissionCatalog.FINANCE_FUND_ORDER_APPROVE
        : AdminPermissionCatalog.FINANCE_FUND_ORDER_REJECT);
    if (status.isApproved()) {
      AdminActionConfirmation.require(request.confirmationText(), AdminActionConfirmation.CONFIRM_APPROVE);
    }
    FundOrderEntity order = fundOrderRepository.findById(orderId)
        .orElseThrow(() -> new BusinessException("FUND_ORDER_NOT_FOUND", "Fund order not found"));
    if (!isPendingReview(order.getStatus())) {
      throw new BusinessException("FUND_ORDER_ALREADY_REVIEWED", "Fund order already reviewed");
    }

    order.setStatus(status);
    order.setReviewReason(request.reason());
    order.setReviewedBy(actorUserId);
    order.setReviewedAt(DateUtil.date().toInstant());

    if (status.isApproved()) {
      AdminFundOperationResponse operation = applyApprovedOrder(actorUserId, order, request.reason());
      order.setFundOperationId(operation.id());
      audit(actorUserId, "ADMIN_FUND_ORDER_APPROVE", order, request.reason());
    } else {
      audit(actorUserId, "ADMIN_FUND_ORDER_REJECT", order, request.reason());
    }
    return AdminFundOrderResponse.from(fundOrderRepository.save(order));
  }

  private AdminFundOperationResponse applyApprovedOrder(UUID actorUserId, FundOrderEntity order, String reason) {
    AdminFundOperationRequest operationRequest = new AdminFundOperationRequest(
        order.getAmount(),
        StrUtil.blankToDefault(reason, "资金审核通过"),
        order.getPaymentMethodId(),
        order.getApplicantNote(),
        "fund-order-" + order.getId());
    if (order.getOrderType() == FundOrderType.RECHARGE) {
      return financeCommandService.deposit(actorUserId, order.getAccountId(), operationRequest);
    }
    return financeCommandService.withdraw(actorUserId, order.getAccountId(), operationRequest);
  }

  private boolean isPendingReview(FundOrderStatus status) {
    return status != null && status.isPendingReview();
  }

  private void audit(UUID actorUserId, String action, FundOrderEntity order, String reason) {
    auditLogService.record(actorUserId, action, "FUND_ORDER", order.getId().toString(),
        AuditDetailsBuilder.create()
            .put("orderType", order.getOrderType() == null ? null : order.getOrderType().code())
            .put("status", order.getStatus() == null ? null : order.getStatus().code())
            .put("reason", reason)
            .toJson());
  }
}
