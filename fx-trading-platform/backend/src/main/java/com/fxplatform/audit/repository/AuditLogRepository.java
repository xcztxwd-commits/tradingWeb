package com.fxplatform.audit.repository;

import com.fxplatform.audit.entity.AuditLogEntity;
import com.fxplatform.common.mybatis.FxBaseMapper;

/**
 * AuditLogRepository 通过 MyBatis-Plus 写入和查询审计日志。
 */
public interface AuditLogRepository extends FxBaseMapper<AuditLogEntity> {
}
