package com.fxplatform.admin.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.admin.entity.AdminUserRoleEntity;
import com.fxplatform.common.mybatis.FxBaseMapper;
import java.util.Optional;
import java.util.UUID;

/**
 * 后台用户角色绑定 Mapper。
 */
public interface AdminUserRoleRepository extends FxBaseMapper<AdminUserRoleEntity> {

  default Optional<AdminUserRoleEntity> findByUserIdAndRoleId(UUID userId, UUID roleId) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<AdminUserRoleEntity>()
        .eq(AdminUserRoleEntity::getUserId, userId)
        .eq(AdminUserRoleEntity::getRoleId, roleId)));
  }
}
