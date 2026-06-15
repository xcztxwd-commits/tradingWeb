package com.fxplatform.auth.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.auth.entity.UserProfileEntity;
import com.fxplatform.common.mybatis.FxBaseMapper;
import java.util.Optional;
import java.util.UUID;

/**
 * 会员详情资料 Mapper，通过 MyBatis-Plus 访问 auth.user_profiles。
 */
public interface UserProfileRepository extends FxBaseMapper<UserProfileEntity> {

  /** 按会员 ID 查询唯一资料，用于后台会员详情页回显。 */
  default Optional<UserProfileEntity> findByUserId(UUID userId) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<UserProfileEntity>()
        .eq(UserProfileEntity::getUserId, userId)));
  }
}
