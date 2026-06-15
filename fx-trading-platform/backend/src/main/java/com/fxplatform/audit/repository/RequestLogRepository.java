package com.fxplatform.audit.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fxplatform.audit.entity.RequestLogEntity;
import com.fxplatform.common.mybatis.FxBaseMapper;
import java.util.List;

/**
 * 请求日志 Mapper，通过 MyBatis-Plus 读取 audit.request_logs。
 */
public interface RequestLogRepository extends FxBaseMapper<RequestLogEntity> {

  /** 查询最近请求日志，后台请求日志页使用。 */
  default List<RequestLogEntity> findRecent(int size) {
    return selectPage(new Page<>(1, Math.max(1, Math.min(size, 200))),
        new LambdaQueryWrapper<RequestLogEntity>().orderByDesc(RequestLogEntity::getCreatedAt)).getRecords();
  }
}
