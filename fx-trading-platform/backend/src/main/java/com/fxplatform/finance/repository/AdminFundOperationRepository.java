package com.fxplatform.finance.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.finance.entity.AdminFundOperationEntity;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * AdminFundOperationRepository 通过 MyBatis-Plus 访问后台资金操作记录。
 */
public interface AdminFundOperationRepository extends FxBaseMapper<AdminFundOperationEntity> {

  /** 按后台资金写操作幂等键回读已有命令结果。 */
  default Optional<AdminFundOperationEntity> findByAccountIdAndOperationTypeAndIdempotencyKey(
      UUID accountId,
      String operationType,
      String idempotencyKey
  ) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<AdminFundOperationEntity>()
        .eq(AdminFundOperationEntity::getAccountId, accountId)
        .eq(AdminFundOperationEntity::getOperationType, operationType)
        .eq(AdminFundOperationEntity::getIdempotencyKey, idempotencyKey)));
  }

  /**
   * 用数据库唯一索引抢占非空幂等键；返回 0 表示已有同一命令结果，调用方应回读。
   */
  @Insert("""
      INSERT INTO finance.admin_fund_operations (
        id,
        account_id,
        user_id,
        operation_type,
        amount,
        currency,
        before_balance,
        after_balance,
        status,
        admin_user_id,
        reason,
        payment_method_id,
        note,
        idempotency_key
      ) VALUES (
        #{operation.id},
        #{operation.accountId},
        #{operation.userId},
        #{operation.operationType},
        #{operation.amount},
        #{operation.currency},
        #{operation.beforeBalance},
        #{operation.afterBalance},
        #{operation.status},
        #{operation.adminUserId},
        #{operation.reason},
        #{operation.paymentMethodId},
        #{operation.note},
        #{operation.idempotencyKey}
      )
      ON CONFLICT (account_id, operation_type, idempotency_key)
      WHERE idempotency_key IS NOT NULL AND idempotency_key <> ''
      DO NOTHING
      """)
  int insertIfAbsent(@Param("operation") AdminFundOperationEntity operation);
}
