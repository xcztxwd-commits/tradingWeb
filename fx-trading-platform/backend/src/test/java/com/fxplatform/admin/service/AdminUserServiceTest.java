package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.dto.request.AdminKycReviewRequest;
import com.fxplatform.admin.dto.request.AdminRiskLevelRequest;
import com.fxplatform.admin.dto.request.AdminUserNoteRequest;
import com.fxplatform.admin.dto.request.AdminUserStatusRequest;
import com.fxplatform.admin.entity.AdminUserNoteEntity;
import com.fxplatform.admin.repository.AdminUserNoteRepository;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.auth.enums.UserRole;
import com.fxplatform.auth.enums.UserStatus;
import com.fxplatform.auth.repository.UserRepository;
import com.fxplatform.common.exception.BusinessException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

  @Mock
  private UserRepository userRepository;

  @Mock
  private AdminUserNoteRepository userNoteRepository;

  @Mock
  private AuditLogService auditLogService;

  @Test
  void updatesUserStatusAndWritesAuditReason() {
    UUID actorUserId = UUID.randomUUID();
    UserEntity user = user(UUID.randomUUID());
    when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

    AdminUserService service = new AdminUserService(userRepository, userNoteRepository, auditLogService);

    var response = service.updateStatus(actorUserId, user.getId(), new AdminUserStatusRequest(UserStatus.FROZEN, "risk review"));

    assertThat(response.status()).isEqualTo("FROZEN");
    verify(userRepository).save(user);
    verify(auditLogService).record(
        eq(actorUserId),
        eq("ADMIN_USER_STATUS_UPDATE"),
        eq("USER"),
        eq(user.getId().toString()),
        contains("risk review"));
  }

  @Test
  void reviewsKycAndRiskLevelWithAudit() {
    UUID actorUserId = UUID.randomUUID();
    UserEntity user = user(UUID.randomUUID());
    when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

    AdminUserService service = new AdminUserService(userRepository, userNoteRepository, auditLogService);

    var kyc = service.reviewKyc(
        actorUserId,
        user.getId(),
        new AdminKycReviewRequest("APPROVED", "documents verified", "passport matched"));
    var risk = service.updateRiskLevel(
        actorUserId,
        user.getId(),
        new AdminRiskLevelRequest("HIGH", "large exposure"));

    assertThat(kyc.kycStatus()).isEqualTo("APPROVED");
    assertThat(risk.riskLevel()).isEqualTo("HIGH");
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_USER_KYC_REVIEW"), eq("USER"), eq(user.getId().toString()), contains("passport matched"));
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_USER_RISK_LEVEL_UPDATE"), eq("USER"), eq(user.getId().toString()), contains("large exposure"));
  }

  @Test
  void addsUserNoteAndForceLogoutAudit() {
    UUID actorUserId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId)));
    when(userNoteRepository.save(any(AdminUserNoteEntity.class))).thenAnswer(invocation -> {
      AdminUserNoteEntity note = invocation.getArgument(0);
      note.setId(UUID.randomUUID());
      return note;
    });

    AdminUserService service = new AdminUserService(userRepository, userNoteRepository, auditLogService);

    var note = service.addNote(actorUserId, userId, new AdminUserNoteRequest("manual follow-up needed"));
    service.forceLogout(actorUserId, userId, "account security review");

    assertThat(note.userId()).isEqualTo(userId);
    assertThat(note.adminUserId()).isEqualTo(actorUserId);
    assertThat(note.note()).isEqualTo("manual follow-up needed");
    ArgumentCaptor<AdminUserNoteEntity> noteCaptor = ArgumentCaptor.forClass(AdminUserNoteEntity.class);
    verify(userNoteRepository).save(noteCaptor.capture());
    assertThat(noteCaptor.getValue().getNote()).isEqualTo("manual follow-up needed");
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_USER_NOTE_CREATE"), eq("USER"), eq(userId.toString()), contains("manual follow-up needed"));
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_USER_FORCE_LOGOUT"), eq("USER"), eq(userId.toString()), contains("account security review"));
  }

  @Test
  void rejectsMissingUserBeforeWritingAudit() {
    UUID actorUserId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.empty());

    AdminUserService service = new AdminUserService(userRepository, userNoteRepository, auditLogService);

    assertThatThrownBy(() -> service.updateStatus(actorUserId, userId, new AdminUserStatusRequest(UserStatus.DISABLED, "fraud")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("User not found");
  }

  private UserEntity user(UUID userId) {
    UserEntity user = new UserEntity();
    user.setId(userId);
    user.setEmail("trader@example.com");
    user.setPasswordHash("hash");
    user.setStatus(UserStatus.ACTIVE);
    user.setRole(UserRole.USER);
    user.setKycStatus("NOT_SUBMITTED");
    user.setRiskLevel("NORMAL");
    return user;
  }
}
