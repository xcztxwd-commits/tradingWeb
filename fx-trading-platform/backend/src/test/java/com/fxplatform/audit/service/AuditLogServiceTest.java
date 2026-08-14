package com.fxplatform.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fxplatform.audit.entity.AuditLogEntity;
import com.fxplatform.audit.repository.AuditLogRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

  @Mock
  private AuditLogRepository auditLogRepository;

  @InjectMocks
  private AuditLogService auditLogService;

  @Test
  void readsTheCompletedDemoResetGenerationFromTheDurableAuditReceipt() {
    AuditLogEntity log = new AuditLogEntity();
    log.setDetails("{\"generation\":8}");
    when(auditLogRepository.selectOne(any())).thenReturn(log);

    assertThat(auditLogService.findDemoResetGeneration(UUID.randomUUID(), UUID.randomUUID()))
        .contains(8L);
  }
}
