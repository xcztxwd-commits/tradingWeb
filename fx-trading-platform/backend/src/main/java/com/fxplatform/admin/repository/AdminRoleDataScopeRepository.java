package com.fxplatform.admin.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.admin.entity.AdminRoleDataScopeEntity;
import com.fxplatform.common.mybatis.FxBaseMapper;
import java.util.Optional;
import java.util.UUID;

/**
 * 角色数据权限 Mapper。
 */
public interface AdminRoleDataScopeRepository extends FxBaseMapper<AdminRoleDataScopeEntity> {

  default Optional<AdminRoleDataScopeEntity> findByRoleId(UUID roleId) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<AdminRoleDataScopeEntity>()
        .eq(AdminRoleDataScopeEntity::getRoleId, roleId)));
  }
}
