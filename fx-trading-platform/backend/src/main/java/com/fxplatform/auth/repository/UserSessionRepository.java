package com.fxplatform.auth.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.auth.entity.UserSessionEntity;
import com.fxplatform.auth.enums.UserSessionStatus;
import com.fxplatform.common.mybatis.FxBaseMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserSessionRepository extends FxBaseMapper<UserSessionEntity> {

  default Optional<UserSessionEntity> findActiveByRefreshTokenHash(
      UUID sessionId,
      UUID userId,
      String refreshTokenHash,
      Instant now
  ) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<UserSessionEntity>()
        .eq(UserSessionEntity::getId, sessionId)
        .eq(UserSessionEntity::getUserId, userId)
        .eq(UserSessionEntity::getRefreshTokenHash, refreshTokenHash)
        .eq(UserSessionEntity::getStatus, UserSessionStatus.ACTIVE)
        .gt(UserSessionEntity::getExpiresAt, now)
        .isNull(UserSessionEntity::getRevokedAt)));
  }

  default Optional<UserSessionEntity> findActiveById(UUID sessionId, Instant now) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<UserSessionEntity>()
        .eq(UserSessionEntity::getId, sessionId)
        .eq(UserSessionEntity::getStatus, UserSessionStatus.ACTIVE)
        .gt(UserSessionEntity::getExpiresAt, now)
        .isNull(UserSessionEntity::getRevokedAt)));
  }

  default List<UserSessionEntity> findActiveByUserId(UUID userId) {
    return selectList(new LambdaQueryWrapper<UserSessionEntity>()
        .eq(UserSessionEntity::getUserId, userId)
        .eq(UserSessionEntity::getStatus, UserSessionStatus.ACTIVE)
        .isNull(UserSessionEntity::getRevokedAt));
  }
}
