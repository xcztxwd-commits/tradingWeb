package com.fxplatform.market.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.market.entity.SymbolCategoryEntity;
import java.util.Optional;

/**
 * SymbolCategoryRepository 通过 MyBatis-Plus 访问产品分类。
 */
public interface SymbolCategoryRepository extends FxBaseMapper<SymbolCategoryEntity> {

  /** 按分类编码查询，用于后台避免重复编码。 */
  default Optional<SymbolCategoryEntity> findByCode(String code) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<SymbolCategoryEntity>()
        .eq(SymbolCategoryEntity::getCode, code)));
  }
}
