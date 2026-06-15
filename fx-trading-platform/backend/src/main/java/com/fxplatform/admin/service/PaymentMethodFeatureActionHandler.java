package com.fxplatform.admin.service;

import com.fxplatform.admin.dto.request.AdminPaymentMethodRequest;
import com.fxplatform.admin.dto.response.AdminPaymentMethodResponse;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentMethodFeatureActionHandler implements AdminFeatureActionHandler {

  private final AdminFinanceCommandService financeCommandService;

  @Override
  public boolean supports(String pageKey, String action) {
    return "payment-methods".equals(pageKey) && ("create".equals(action) || "edit".equals(action));
  }

  @Override
  public String handle(AdminFeatureActionContext context) {
    Map<String, Object> payload = context.payload();
    AdminPaymentMethodRequest request = new AdminPaymentMethodRequest(
        AdminFeaturePayloads.string(payload, "name", "Payment Method"),
        AdminFeaturePayloads.paymentMethodType(AdminFeaturePayloads.string(
            payload,
            "type",
            AdminFeaturePayloads.string(payload, "methodType", "BANK_TRANSFER"))),
        AdminFeaturePayloads.string(payload, "currency", AdminFeaturePayloads.string(payload, "networkOrCurrency", "USD")),
        !"停用".equals(AdminFeaturePayloads.string(payload, "status", "正常")),
        AdminFeaturePayloads.integer(payload, "sort", 0),
        AdminFeaturePayloads.string(payload, "instructions", AdminFeaturePayloads.string(payload, "cardOrWallet", "")));
    UUID paymentMethodId = "edit".equals(context.action())
        ? AdminFeaturePayloads.requireFirstUuid(
            context.rowId(),
            payload,
            "ADMIN_ACTION_PAYLOAD_INVALID",
            "Payment method edit requires paymentMethodId",
            "paymentMethodId",
            "id")
        : null;
    AdminPaymentMethodResponse response = paymentMethodId == null
        ? financeCommandService.createPaymentMethod(context.actorUserId(), request)
        : financeCommandService.updatePaymentMethod(context.actorUserId(), paymentMethodId, request);
    return response.id().toString();
  }
}
