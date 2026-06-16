package com.fxplatform.market.repository;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.market.enums.PriceAdjustmentStatus;
import com.fxplatform.market.entity.PriceAdjustmentEntity;
import java.util.List;

/**
 * PriceAdjustmentRepository 通过 MyBatis-Plus 访问显式行情调整记录。
 */
public interface PriceAdjustmentRepository extends FxBaseMapper<PriceAdjustmentEntity> {

  /** 查询最近涨跌/价格调整任务，状态为空时返回全部。 */
  default List<PriceAdjustmentEntity> findRecent(String status, int size) {
    LambdaQueryWrapper<PriceAdjustmentEntity> query = new LambdaQueryWrapper<PriceAdjustmentEntity>()
        .orderByDesc(PriceAdjustmentEntity::getCreatedAt);
    if (StrUtil.isNotBlank(status)) {
      query.eq(PriceAdjustmentEntity::getStatus, PriceAdjustmentStatus.fromCode(status));
    }
    return selectPage(new Page<>(1, size), query).getRecords();
  }
}
