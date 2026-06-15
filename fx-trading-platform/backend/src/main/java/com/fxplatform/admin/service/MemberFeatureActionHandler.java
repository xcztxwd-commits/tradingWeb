package com.fxplatform.admin.service;

import com.fxplatform.admin.dto.request.AdminKycReviewRequest;
import com.fxplatform.admin.dto.request.AdminMessageRequest;
import com.fxplatform.admin.dto.request.AdminRiskLevelRequest;
import com.fxplatform.admin.dto.request.AdminUserNoteRequest;
import com.fxplatform.admin.dto.request.AdminUserStatusRequest;
import com.fxplatform.admin.dto.AdminUserResponse;
import com.fxplatform.admin.dto.response.AdminMessageResponse;
import com.fxplatform.admin.dto.response.AdminUserNoteResponse;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MemberFeatureActionHandler implements AdminFeatureActionHandler {

  private final AdminUserService userService;
  private final AdminContentCommandService contentCommandService;

  @Override
  public boolean supports(String pageKey, String action) {
    return "members".equals(pageKey);
  }

  @Override
  public String handle(AdminFeatureActionContext context) {
    Map<String, Object> payload = context.payload();
    String action = context.action();
    if ("real-name".equals(action)) {
      UUID userId = requireUserId(context, payload);
      AdminUserResponse response = userService.reviewKyc(context.actorUserId(), userId, new AdminKycReviewRequest(
          AdminFeaturePayloads.kycStatus(payload),
          AdminFeaturePayloads.string(payload, "reason", "实名审核"),
          AdminFeaturePayloads.string(payload, "reviewNote", AdminFeaturePayloads.string(payload, "remark", "实名审核"))));
      return response.id().toString();
    }
    if ("remark".equals(action)) {
      UUID userId = requireUserId(context, payload);
      AdminUserNoteResponse response = userService.addNote(
          context.actorUserId(),
          userId,
          new AdminUserNoteRequest(AdminFeaturePayloads.string(payload, "note", AdminFeaturePayloads.string(payload, "remark", "后台备注"))));
      return response.id().toString();
    }
    if ("kick-offline".equals(action)) {
      UUID userId = requireUserId(context, payload);
      userService.forceLogout(context.actorUserId(), userId, AdminFeaturePayloads.string(payload, "reason", "后台踢下线"));
      return userId.toString();
    }
    if ("one-click-profit".equals(action) || "one-click-normal".equals(action)) {
      UUID userId = requireUserId(context, payload);
      AdminUserResponse response = userService.updateRiskLevel(context.actorUserId(), userId, new AdminRiskLevelRequest(
          "one-click-profit".equals(action) ? "CONTROL_PROFIT" : "NORMAL",
          AdminFeaturePayloads.string(payload, "reason", "用户风控调整")));
      return response.id().toString();
    }
    if ("send-message".equals(action)) {
      UUID userId = requireUserId(context, payload);
      AdminMessageResponse message = contentCommandService.createMessage(context.actorUserId(), new AdminMessageRequest(
          userId,
          AdminFeaturePayloads.string(payload, "title", "用户通知"),
          AdminFeaturePayloads.string(payload, "content", "用户通知"),
          "SYSTEM",
          "PUBLISHED"));
      return message.id().toString();
    }
    if ("edit".equals(action) && (payload.containsKey("status") || payload.containsKey("control"))) {
      UUID userId = requireUserId(context, payload);
      AdminUserResponse response = userService.updateStatus(context.actorUserId(), userId, new AdminUserStatusRequest(
          AdminFeaturePayloads.userStatus(payload),
          AdminFeaturePayloads.string(payload, "reason", "用户状态调整")));
      return response.id().toString();
    }
    return null;
  }

  private static UUID requireUserId(AdminFeatureActionContext context, Map<String, Object> payload) {
    return AdminFeaturePayloads.requireFirstUuid(
        context.rowId(),
        payload,
        "ADMIN_ACTION_PAYLOAD_INVALID",
        "Member action requires userId",
        "userId",
        "uid");
  }
}
