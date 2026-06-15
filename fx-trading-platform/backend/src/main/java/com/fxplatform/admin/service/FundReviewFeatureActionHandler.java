package com.fxplatform.admin.service;

import com.fxplatform.admin.dto.response.AdminFundOperationResponse;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FundReviewFeatureActionHandler implements AdminFeatureActionHandler {

  private final AdminFinanceCommandService financeCommandService;

  @Override
  public boolean supports(String pageKey, String action) {
    return ("recharge-orders".equals(pageKey) || "withdrawal-orders".equals(pageKey))
        && "review".equals(action);
  }

  @Override
  public String handle(AdminFeatureActionContext context) {
    Map<String, Object> payload = context.payload();
    if (!AdminFeaturePayloads.approved(payload)) {
      return null;
    }
    UUID accountId = AdminFeaturePayloads.requireUuid(
        payload,
        "accountId",
        "FUND_REVIEW_PAYLOAD_INCOMPLETE",
        "Approved fund review requires accountId and amount");
    AdminFeaturePayloads.requirePositiveDecimal(
        payload,
        "amount",
        "FUND_REVIEW_PAYLOAD_INCOMPLETE",
        "Approved fund review requires accountId and amount");
    AdminFundOperationResponse response = "recharge-orders".equals(context.pageKey())
        ? financeCommandService.deposit(
            context.actorUserId(),
            accountId,
            AdminFeaturePayloads.fundRequest(payload, "充值审核通过"))
        : financeCommandService.withdraw(
            context.actorUserId(),
            accountId,
            AdminFeaturePayloads.fundRequest(payload, "提现审核通过"));
    return response.id().toString();
  }
}
