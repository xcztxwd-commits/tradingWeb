package com.fxplatform.admin.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.admin.entity.AdminMenuEntity;
import com.fxplatform.common.mybatis.FxBaseMapper;
import java.util.List;

/**
 * 后台菜单 Mapper。
 */
public interface AdminMenuRepository extends FxBaseMapper<AdminMenuEntity> {

  default List<AdminMenuEntity> findEnabledMenus() {
    return selectList(new LambdaQueryWrapper<AdminMenuEntity>()
        .eq(AdminMenuEntity::getEnabled, true)
        .orderByAsc(AdminMenuEntity::getSortOrder)
        .orderByAsc(AdminMenuEntity::getCreatedAt));
  }
}
