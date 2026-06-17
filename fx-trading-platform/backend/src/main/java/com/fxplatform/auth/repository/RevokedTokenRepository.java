package com.fxplatform.auth.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.auth.entity.RevokedTokenEntity;
import com.fxplatform.common.mybatis.FxBaseMapper;
import java.util.Optional;

public interface RevokedTokenRepository extends FxBaseMapper<RevokedTokenEntity> {

  default Optional<RevokedTokenEntity> findByTokenHash(String tokenHash) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<RevokedTokenEntity>()
        .eq(RevokedTokenEntity::getTokenHash, tokenHash)));
  }
}
