package com.fxplatform.auth.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.auth.entity.UserEntity;
import com.fxplatform.common.mybatis.FxBaseMapper;
import java.util.Optional;

/**
 * UserRepository 通过 MyBatis-Plus 访问认证用户表。
 */
public interface UserRepository extends FxBaseMapper<UserEntity> {

  /** 按邮箱查询用户，登录和 Spring Security 加载用户时使用。 */
  default Optional<UserEntity> findByEmail(String email) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<UserEntity>()
        .eq(UserEntity::getEmail, email)));
  }

  /** 判断邮箱是否已存在，注册时用于唯一性校验。 */
  default boolean existsByEmail(String email) {
    return selectCount(new LambdaQueryWrapper<UserEntity>()
        .eq(UserEntity::getEmail, email)) > 0;
  }
}
