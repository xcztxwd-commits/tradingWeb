package com.fxplatform.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fxplatform.audit.entity.RequestLogEntity;
import com.fxplatform.audit.entity.VerificationCodeLogEntity;
import com.fxplatform.audit.repository.RequestLogRepository;
import com.fxplatform.audit.repository.VerificationCodeLogRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AdminLogServiceTest {

  private final RequestLogRepository requestLogRepository = Mockito.mock(RequestLogRepository.class);
  private final VerificationCodeLogRepository verificationCodeLogRepository = Mockito.mock(VerificationCodeLogRepository.class);

  @Test
  void recordsAndQueriesRequestLogsAndVerificationCodeLogs() {
    UUID requestId = UUID.randomUUID();
    UUID codeId = UUID.randomUUID();
    when(requestLogRepository.save(any(RequestLogEntity.class))).thenAnswer(invocation -> {
      RequestLogEntity log = invocation.getArgument(0);
      log.setId(requestId);
      log.setCreatedAt(Instant.parse("2026-06-10T00:00:00Z"));
      return log;
    });
    when(requestLogRepository.findRecent(20)).thenAnswer(invocation -> {
      RequestLogEntity log = new RequestLogEntity();
      log.setId(requestId);
      log.setRequestId("req-123");
      log.setMethod("POST");
      log.setPath("/api/auth/login");
      log.setStatusCode(200);
      log.setDurationMs(12L);
      log.setCreatedAt(Instant.parse("2026-06-10T00:00:00Z"));
      return List.of(log);
    });
    when(verificationCodeLogRepository.save(any(VerificationCodeLogEntity.class))).thenAnswer(invocation -> {
      VerificationCodeLogEntity log = invocation.getArgument(0);
      log.setId(codeId);
      log.setCreatedAt(Instant.parse("2026-06-10T00:01:00Z"));
      return log;
    });
    when(verificationCodeLogRepository.findRecent(20)).thenAnswer(invocation -> {
      VerificationCodeLogEntity log = new VerificationCodeLogEntity();
      log.setId(codeId);
      log.setScene("LOGIN");
      log.setAccount("member@example.com");
      log.setChannel("EMAIL");
      log.setCode("123456");
      log.setStatus("SENT");
      log.setCreatedAt(Instant.parse("2026-06-10T00:01:00Z"));
      return List.of(log);
    });

    RequestLogService requestLogService = new RequestLogService(requestLogRepository);
    VerificationCodeLogService verificationCodeLogService = new VerificationCodeLogService(verificationCodeLogRepository);

    var requestLog = requestLogService.record("req-123", "POST", "/api/auth/login", "127.0.0.1", "JUnit", 200, 12L, null);
    var codeLog = verificationCodeLogService.record("LOGIN", "member@example.com", "EMAIL", "123456", "SENT", null);
    var requestLogs = requestLogService.recent(20);
    var codeLogs = verificationCodeLogService.recent(20);

    assertThat(requestLog.id()).isEqualTo(requestId);
    assertThat(requestLog.requestId()).isEqualTo("req-123");
    assertThat(codeLog.id()).isEqualTo(codeId);
    assertThat(requestLogs).hasSize(1);
    assertThat(requestLogs.get(0).requestId()).isEqualTo("req-123");
    assertThat(requestLogs.get(0).path()).isEqualTo("/api/auth/login");
    assertThat(codeLogs).hasSize(1);
    assertThat(codeLogs.get(0).account()).isEqualTo("member@example.com");
  }
}
