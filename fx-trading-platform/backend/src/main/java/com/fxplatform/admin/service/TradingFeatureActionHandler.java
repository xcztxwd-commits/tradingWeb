package com.fxplatform.admin.service;

import cn.hutool.core.util.IdUtil;
import com.fxplatform.admin.dto.request.AdminCancelOrderRequest;
import com.fxplatform.admin.dto.request.AdminForceClosePositionRequest;
import com.fxplatform.admin.dto.response.AdminOrderResponse;
import com.fxplatform.trading.dto.response.PositionResponse;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TradingFeatureActionHandler implements AdminFeatureActionHandler {

  private final AdminTradingCommandService tradingCommandService;

  @Override
  public boolean supports(String pageKey, String action) {
    return "order-history".equals(pageKey);
  }

  @Override
  public String handle(AdminFeatureActionContext context) {
    Map<String, Object> payload = context.payload();
    if ("cancel".equals(context.action())) {
      UUID orderId = AdminFeaturePayloads.requireFirstUuid(
          context.rowId(),
          payload,
          "ADMIN_ACTION_PAYLOAD_INVALID",
          "Cancel order action requires orderId",
          "orderId");
      AdminOrderResponse response = tradingCommandService.cancelOrder(context.actorUserId(), orderId, new AdminCancelOrderRequest(
          AdminFeaturePayloads.string(payload, "reason", "后台撤销订单"),
          AdminFeaturePayloads.string(payload, "idempotencyKey", IdUtil.fastUUID())));
      return response.id().toString();
    }
    if ("close-position".equals(context.action())) {
      UUID positionId = AdminFeaturePayloads.requireFirstUuid(
          context.rowId(),
          payload,
          "ADMIN_ACTION_PAYLOAD_INVALID",
          "Close position action requires positionId",
          "positionId");
      UUID accountId = AdminFeaturePayloads.requireUuid(
          payload,
          "accountId",
          "ADMIN_ACTION_PAYLOAD_INVALID",
          "Close position action requires accountId");
      PositionResponse response = tradingCommandService.forceClosePosition(
          context.actorUserId(),
          positionId,
          new AdminForceClosePositionRequest(
              accountId,
              AdminFeaturePayloads.string(payload, "reason", "后台平仓"),
              AdminFeaturePayloads.string(payload, "idempotencyKey", IdUtil.fastUUID())));
      return response.id().toString();
    }
    return null;
  }
}
