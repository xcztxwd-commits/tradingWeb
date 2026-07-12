package com.fxplatform.audit.repository;

import com.fxplatform.audit.entity.AuditLogEntity;
import com.fxplatform.common.mybatis.FxBaseMapper;
import org.apache.ibatis.annotations.Insert;

/**
 * AuditLogRepository 通过 MyBatis-Plus 写入和查询审计日志。
 */
public interface AuditLogRepository extends FxBaseMapper<AuditLogEntity> {

  @Insert("""
      INSERT INTO audit.audit_logs (
        id, actor_user_id, action, target_type, target_id, request_id, details
      ) VALUES (
        #{id}, #{actorUserId}, #{action}, #{targetType}, #{targetId}, #{requestId},
        CAST(#{details} AS JSONB)
      )
      ON CONFLICT (action, target_type, target_id, request_id)
        WHERE request_id IS NOT NULL
      DO NOTHING
      """)
  int insertRequestLogIfAbsent(AuditLogEntity auditLog);
}
