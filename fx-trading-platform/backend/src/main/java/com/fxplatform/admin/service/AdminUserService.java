package com.fxplatform.admin.service;

import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.AdminUserResponse;
import com.fxplatform.admin.dto.request.AdminKycReviewRequest;
import com.fxplatform.admin.dto.request.AdminRiskLevelRequest;
import com.fxplatform.admin.dto.request.AdminUserNoteRequest;
import com.fxplatform.admin.dto.request.AdminUserStatusRequest;
import com.fxplatform.admin.dto.response.AdminUserNoteResponse;
import com.fxplatform.admin.entity.AdminUserNoteEntity;
import com.fxplatform.admin.repository.AdminUserNoteRepository;
import com.fxplatform.audit.service.AuditDetailsBuilder;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.auth.repository.UserRepository;
import com.fxplatform.common.exception.BusinessException;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AdminUserService 提供后台用户管理的查询和写操作能力。
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

  /** 用户 Mapper，用于加载和保存认证用户。 */
  private final UserRepository userRepository;
  /** 用户备注 Mapper，用于保存后台协作备注。 */
  private final AdminUserNoteRepository userNoteRepository;
  /** 审计服务，用于记录所有后台用户写操作。 */
  private final AuditLogService auditLogService;

  /** 分页查询后台用户列表。 */
  public AdminPageResponse<AdminUserResponse> users(int page, int size) {
    return AdminPageResponse.from(userRepository
        .findAll(AdminPageRequests.page(page, size), Map.of("createdAt", "created_at"), "createdAt", false)
        .convert(AdminUserResponse::from));
  }

  /** 更新用户状态并写入审计。 */
  @Transactional
  public AdminUserResponse updateStatus(UUID actorUserId, UUID userId, AdminUserStatusRequest request) {
    UserEntity user = findUser(userId);
    String before = user.getStatus().name();
    user.setStatus(request.status());
    userRepository.save(user);
    auditLogService.record(
        actorUserId,
        "ADMIN_USER_STATUS_UPDATE",
        "USER",
        userId.toString(),
        details(request.reason(), before, request.status().name(), null));
    return AdminUserResponse.from(user);
  }

  /** 审核用户 KYC 状态并写入审计。 */
  @Transactional
  public AdminUserResponse reviewKyc(UUID actorUserId, UUID userId, AdminKycReviewRequest request) {
    UserEntity user = findUser(userId);
    String before = user.getKycStatus();
    user.setKycStatus(request.kycStatus());
    userRepository.save(user);
    auditLogService.record(
        actorUserId,
        "ADMIN_USER_KYC_REVIEW",
        "USER",
        userId.toString(),
        details(request.reason(), before, request.kycStatus(), request.reviewNote()));
    return AdminUserResponse.from(user);
  }

  /** 更新用户风险等级并写入审计。 */
  @Transactional
  public AdminUserResponse updateRiskLevel(UUID actorUserId, UUID userId, AdminRiskLevelRequest request) {
    UserEntity user = findUser(userId);
    String before = user.getRiskLevel();
    user.setRiskLevel(request.riskLevel());
    userRepository.save(user);
    auditLogService.record(
        actorUserId,
        "ADMIN_USER_RISK_LEVEL_UPDATE",
        "USER",
        userId.toString(),
        details(request.reason(), before, request.riskLevel(), null));
    return AdminUserResponse.from(user);
  }

  /** 新增用户备注并写入审计。 */
  @Transactional
  public AdminUserNoteResponse addNote(UUID actorUserId, UUID userId, AdminUserNoteRequest request) {
    findUser(userId);
    AdminUserNoteEntity note = new AdminUserNoteEntity();
    note.setUserId(userId);
    note.setAdminUserId(actorUserId);
    note.setNote(request.note());
    AdminUserNoteEntity saved = userNoteRepository.save(note);
    auditLogService.record(
        actorUserId,
        "ADMIN_USER_NOTE_CREATE",
        "USER",
        userId.toString(),
        details("user note", null, request.note(), null));
    return AdminUserNoteResponse.from(saved);
  }

  /**
   * 记录后台强制退出用户的审计事件。
   *
   * <p>当前系统尚未引入 token 黑名单，因此本方法先形成审计链路；后续 session 控制模块可接入实际失效能力。</p>
   */
  public void forceLogout(UUID actorUserId, UUID userId, String reason) {
    findUser(userId);
    auditLogService.record(
        actorUserId,
        "ADMIN_USER_FORCE_LOGOUT",
        "USER",
        userId.toString(),
        details(reason, null, "FORCE_LOGOUT_REQUESTED", null));
  }

  /** 加载用户，不存在时返回统一业务异常。 */
  private UserEntity findUser(UUID userId) {
    return userRepository.findById(userId)
        .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User not found"));
  }

  /** 使用共享 builder 构造审计 JSON 明细，保持后台写操作 details 字段一致。 */
  private String details(String reason, String before, String after, String note) {
    return AuditDetailsBuilder.create()
        .put("reason", reason)
        .put("before", before)
        .put("after", after)
        .put("note", note)
        .toJson();
  }
}
