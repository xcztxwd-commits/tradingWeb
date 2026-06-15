package com.fxplatform.admin.service;

import com.fxplatform.admin.dto.request.AdminRiskConfigRequest;
import com.fxplatform.admin.dto.response.AdminRiskConfigResponse;
import com.fxplatform.audit.service.AuditDetailsBuilder;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.risk.entity.RiskConfigEntity;
import com.fxplatform.risk.repository.RiskConfigRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AdminRiskCommandService 提供后台风控配置新增、修改和删除能力。
 */
@Service
@RequiredArgsConstructor
public class AdminRiskCommandService {

  private final RiskConfigRepository riskConfigRepository;
  private final AuditLogService auditLogService;

  /** 创建风控配置。 */
  @Transactional
  public AdminRiskConfigResponse createConfig(UUID actorUserId, AdminRiskConfigRequest request) {
    RiskConfigEntity config = new RiskConfigEntity();
    apply(config, request);
    RiskConfigEntity saved = riskConfigRepository.save(config);
    audit(actorUserId, "ADMIN_RISK_CONFIG_CREATE", saved, request.reason());
    return AdminRiskConfigResponse.from(saved);
  }

  /** 更新风控配置。 */
  @Transactional
  public AdminRiskConfigResponse updateConfig(UUID actorUserId, UUID configId, AdminRiskConfigRequest request) {
    RiskConfigEntity config = riskConfigRepository.findById(configId)
        .orElseThrow(() -> new BusinessException("RISK_CONFIG_NOT_FOUND", "Risk config not found"));
    apply(config, request);
    RiskConfigEntity saved = riskConfigRepository.save(config);
    audit(actorUserId, "ADMIN_RISK_CONFIG_UPDATE", saved, request.reason());
    return AdminRiskConfigResponse.from(saved);
  }

  /** 删除风控配置，并记录删除原因。 */
  @Transactional
  public void deleteConfig(UUID actorUserId, UUID configId, String reason) {
    RiskConfigEntity config = riskConfigRepository.findById(configId)
        .orElseThrow(() -> new BusinessException("RISK_CONFIG_NOT_FOUND", "Risk config not found"));
    riskConfigRepository.deleteById(configId);
    audit(actorUserId, "ADMIN_RISK_CONFIG_DELETE", config, reason);
  }

  private void apply(RiskConfigEntity config, AdminRiskConfigRequest request) {
    config.setSymbol(request.symbol());
    config.setMaxLeverage(request.maxLeverage());
    config.setMaxLots(request.maxLots());
    config.setMarginCallLevel(request.marginCallLevel());
    config.setStopOutLevel(request.stopOutLevel());
    config.setEnabled(request.enabled());
  }

  private void audit(UUID actorUserId, String action, RiskConfigEntity config, String reason) {
    auditLogService.record(actorUserId, action, "RISK_CONFIG", config.getId().toString(),
        AuditDetailsBuilder.create()
            .put("symbol", config.getSymbol())
            .put("reason", reason)
            .toJson());
  }
}
