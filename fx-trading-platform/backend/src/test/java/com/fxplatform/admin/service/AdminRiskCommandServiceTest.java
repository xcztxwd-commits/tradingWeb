package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.dto.request.AdminRiskConfigRequest;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.risk.entity.RiskConfigEntity;
import com.fxplatform.risk.repository.RiskConfigRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AdminRiskCommandServiceTest {

  private final RiskConfigRepository riskConfigRepository = Mockito.mock(RiskConfigRepository.class);
  private final AuditLogService auditLogService = Mockito.mock(AuditLogService.class);

  @Test
  void createsUpdatesAndDeletesRiskConfig() {
    UUID actorUserId = UUID.randomUUID();
    UUID configId = UUID.randomUUID();
    when(riskConfigRepository.save(any(RiskConfigEntity.class))).thenAnswer(invocation -> {
      RiskConfigEntity config = invocation.getArgument(0);
      if (config.getId() == null) {
        config.setId(configId);
      }
      return config;
    });
    when(riskConfigRepository.findById(configId)).thenAnswer(invocation -> {
      RiskConfigEntity config = new RiskConfigEntity();
      config.setId(configId);
      config.setSymbol("EURUSD");
      config.setMaxLeverage(100);
      config.setMaxLots(new BigDecimal("100.0000"));
      config.setMarginCallLevel(new BigDecimal("100.0000"));
      config.setStopOutLevel(new BigDecimal("50.0000"));
      config.setEnabled(true);
      return Optional.of(config);
    });

    AdminRiskCommandService service = new AdminRiskCommandService(riskConfigRepository, auditLogService);

    var created = service.createConfig(actorUserId, new AdminRiskConfigRequest(
        "EURUSD",
        100,
        new BigDecimal("50.0000"),
        new BigDecimal("120.0000"),
        new BigDecimal("60.0000"),
        true,
        "initial"));
    var updated = service.updateConfig(actorUserId, configId, new AdminRiskConfigRequest(
        "EURUSD",
        200,
        new BigDecimal("25.0000"),
        new BigDecimal("130.0000"),
        new BigDecimal("70.0000"),
        true,
        "tighten"));
    service.deleteConfig(actorUserId, configId, "obsolete");

    assertThat(created.id()).isEqualTo(configId);
    assertThat(updated.maxLeverage()).isEqualTo(200);
    assertThat(updated.maxLots()).isEqualByComparingTo("25.0000");
    verify(riskConfigRepository).deleteById(configId);
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_RISK_CONFIG_CREATE"), eq("RISK_CONFIG"), eq(configId.toString()), contains("initial"));
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_RISK_CONFIG_DELETE"), eq("RISK_CONFIG"), eq(configId.toString()), contains("obsolete"));
  }
}
