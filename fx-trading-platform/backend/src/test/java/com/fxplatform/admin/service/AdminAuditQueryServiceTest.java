package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fxplatform.audit.entity.AuditLogEntity;
import com.fxplatform.audit.repository.AuditLogRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminAuditQueryServiceTest {

  @Mock
  private AuditLogRepository auditLogRepository;

  @Test
  void auditLogsReturnPagedDtoWithoutEntityLeakage() {
    UUID id = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    AuditLogEntity entity = new AuditLogEntity();
    entity.setId(id);
    entity.setActorUserId(actorUserId);
    entity.setAction("ADMIN_USER_STATUS_UPDATE");
    entity.setTargetType("USER");
    entity.setTargetId("target-user");
    entity.setRequestId("request-1");
    entity.setDetails("{\"status\":\"FROZEN\"}");
    entity.setCreatedAt(Instant.parse("2026-06-08T10:15:30Z"));

    when(auditLogRepository.findAll(any(), eq(Map.of("createdAt", "created_at")), eq("createdAt"), eq(false)))
        .thenReturn(page(entity));

    AdminAuditQueryService service = new AdminAuditQueryService(auditLogRepository);

    var response = service.auditLogs(0, 20);

    assertThat(response.items()).hasSize(1);
    assertThat(response.page()).isZero();
    assertThat(response.size()).isEqualTo(20);
    assertThat(response.total()).isEqualTo(1);
    assertThat(response.totalPages()).isEqualTo(1);
    assertThat(response.items().get(0).id()).isEqualTo(id);
    assertThat(response.items().get(0).actorUserId()).isEqualTo(actorUserId);
    assertThat(response.items().get(0).action()).isEqualTo("ADMIN_USER_STATUS_UPDATE");
    assertThat(response.items().get(0).targetType()).isEqualTo("USER");
    assertThat(response.items().get(0).targetId()).isEqualTo("target-user");
    assertThat(response.items().get(0).requestId()).isEqualTo("request-1");
    assertThat(response.items().get(0).details()).isEqualTo("{\"status\":\"FROZEN\"}");
  }

  private static <T> Page<T> page(T item) {
    Page<T> page = Page.of(1, 20);
    page.setRecords(List.of(item));
    page.setTotal(1);
    return page;
  }
}
