package com.fxplatform.audit.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fxplatform.audit.entity.VerificationCodeLogEntity;
import com.fxplatform.common.mybatis.FxBaseMapper;
import java.util.List;

/**
 * 验证码日志 Mapper，通过 MyBatis-Plus 读取 audit.verification_code_logs。
 */
public interface VerificationCodeLogRepository extends FxBaseMapper<VerificationCodeLogEntity> {

  /** 查询最近验证码发送记录。 */
  default List<VerificationCodeLogEntity> findRecent(int size) {
    return selectPage(new Page<>(1, Math.max(1, Math.min(size, 200))),
        new LambdaQueryWrapper<VerificationCodeLogEntity>().orderByDesc(VerificationCodeLogEntity::getCreatedAt))
        .getRecords();
  }
}
