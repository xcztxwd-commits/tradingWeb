package com.fxplatform.audit.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.audit.entity.AuditLogEntity;
import com.fxplatform.audit.repository.AuditLogRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * AuditLogService 是审计日志模块的业务服务。
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

  private final AuditLogRepository auditLogRepository;

  public void record(UUID actorUserId, String action, String targetType, String targetId, String details) {
    AuditLogEntity log = new AuditLogEntity();
    log.setActorUserId(actorUserId);
    log.setAction(action);
    log.setTargetType(targetType);
    log.setTargetId(targetId);
    log.setDetails(details);
    auditLogRepository.save(log);
  }

  public void recordWithRequestId(
      UUID actorUserId,
      String action,
      String targetType,
      String targetId,
      UUID requestId,
      String details
  ) {
    AuditLogEntity log = new AuditLogEntity();
    log.setId(UUID.randomUUID());
    log.setActorUserId(actorUserId);
    log.setAction(action);
    log.setTargetType(targetType);
    log.setTargetId(targetId);
    log.setRequestId(requestId == null ? null : requestId.toString());
    log.setDetails(details);
    if (requestId == null) {
      auditLogRepository.save(log);
    } else {
      auditLogRepository.insertRequestLogIfAbsent(log);
    }
  }

  public Optional<Long> findDemoResetGeneration(UUID accountId, UUID requestId) {
    if (accountId == null || requestId == null) {
      return Optional.empty();
    }
    AuditLogEntity log = auditLogRepository.selectOne(
        new LambdaQueryWrapper<AuditLogEntity>()
            .eq(AuditLogEntity::getAction, "DEMO_RESET")
            .eq(AuditLogEntity::getTargetType, "TRADING_ACCOUNT")
            .eq(AuditLogEntity::getTargetId, accountId.toString())
            .eq(AuditLogEntity::getRequestId, requestId.toString()));
    if (log == null || log.getDetails() == null) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(JSONUtil.parseObj(log.getDetails()).getLong("generation"));
    } catch (RuntimeException ignored) {
      return Optional.empty();
    }
  }
}
