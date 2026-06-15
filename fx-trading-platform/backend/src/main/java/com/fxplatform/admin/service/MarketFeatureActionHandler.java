package com.fxplatform.admin.service;

import com.fxplatform.admin.dto.request.AdminSymbolStatusRequest;
import com.fxplatform.admin.dto.response.AdminPriceAdjustmentResponse;
import com.fxplatform.admin.dto.response.AdminSymbolResponse;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MarketFeatureActionHandler implements AdminFeatureActionHandler {

  private final AdminMarketCommandService marketCommandService;

  @Override
  public boolean supports(String pageKey, String action) {
    return ("products".equals(pageKey) && "risk".equals(action))
        || ("price-schedules".equals(pageKey) && ("create".equals(action) || "edit".equals(action)));
  }

  @Override
  public String handle(AdminFeatureActionContext context) {
    Map<String, Object> payload = context.payload();
    UUID symbolId = AdminFeaturePayloads.requireFirstUuid(
        context.rowId(),
        payload,
        "ADMIN_ACTION_PAYLOAD_INVALID",
        "Market action requires symbolId",
        "symbolId",
        "productId");

    if ("price-schedules".equals(context.pageKey())) {
      AdminPriceAdjustmentResponse response = marketCommandService.createPriceAdjustment(
          context.actorUserId(),
          symbolId,
          AdminFeaturePayloads.priceAdjustmentRequest(payload, "涨跌设置"));
      return response.id().toString();
    }

    if (payload.containsKey("targetPrice")) {
      AdminPriceAdjustmentResponse response = marketCommandService.createPriceAdjustment(
          context.actorUserId(),
          symbolId,
          AdminFeaturePayloads.priceAdjustmentRequest(payload, "产品风控设置"));
      return response.id().toString();
    }
    if (payload.containsKey("enabled") || payload.containsKey("status")) {
      AdminSymbolResponse response = marketCommandService.updateStatus(
          context.actorUserId(),
          symbolId,
          new AdminSymbolStatusRequest(
              AdminFeaturePayloads.enabled(payload),
              AdminFeaturePayloads.string(payload, "reason", "产品状态调整")));
      return response.id().toString();
    }
    return null;
  }
}
