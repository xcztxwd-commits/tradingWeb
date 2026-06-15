package com.fxplatform.admin.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.admin.entity.AdminRoleEntity;
import com.fxplatform.common.mybatis.FxBaseMapper;
import java.util.Optional;

/**
 * 后台角色 Mapper。
 */
public interface AdminRoleRepository extends FxBaseMapper<AdminRoleEntity> {

  default Optional<AdminRoleEntity> findByRoleCode(String roleCode) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<AdminRoleEntity>()
        .eq(AdminRoleEntity::getRoleCode, roleCode)));
  }
}
