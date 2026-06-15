package com.fxplatform.admin.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.admin.entity.AdminRoleMenuPermissionEntity;
import com.fxplatform.common.mybatis.FxBaseMapper;
import java.util.Optional;
import java.util.UUID;

/**
 * 角色菜单按钮权限 Mapper。
 */
public interface AdminRoleMenuPermissionRepository extends FxBaseMapper<AdminRoleMenuPermissionEntity> {

  default Optional<AdminRoleMenuPermissionEntity> findByRoleIdAndMenuId(UUID roleId, UUID menuId) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<AdminRoleMenuPermissionEntity>()
        .eq(AdminRoleMenuPermissionEntity::getRoleId, roleId)
        .eq(AdminRoleMenuPermissionEntity::getMenuId, menuId)));
  }
}
