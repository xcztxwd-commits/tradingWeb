package com.fxplatform.engagement.persistence.repository;

import com.fxplatform.auth.entity.UserEntity;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Canonical per-user mutex shared by popup claim and outcome transactions. */
public interface EngagementUserLockRepository {

  @Select("""
      SELECT *
      FROM auth.users
      WHERE id = #{userId}
        AND status = 'ACTIVE'
        AND role = 'USER'
      FOR UPDATE
      """)
  Optional<UserEntity> findActiveBusinessUserForUpdate(@Param("userId") UUID userId);
}
