package com.fxplatform.market.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.market.entity.UserFavoriteSymbolEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserFavoriteSymbolRepository extends FxBaseMapper<UserFavoriteSymbolEntity> {

  default List<UserFavoriteSymbolEntity> findByUserIdOrderByCreatedAtAsc(UUID userId) {
    return selectList(new LambdaQueryWrapper<UserFavoriteSymbolEntity>()
        .eq(UserFavoriteSymbolEntity::getUserId, userId)
        .orderByAsc(UserFavoriteSymbolEntity::getCreatedAt));
  }

  default Optional<UserFavoriteSymbolEntity> findByUserIdAndSymbol(UUID userId, String symbol) {
    return Optional.ofNullable(selectOne(new LambdaQueryWrapper<UserFavoriteSymbolEntity>()
        .eq(UserFavoriteSymbolEntity::getUserId, userId)
        .eq(UserFavoriteSymbolEntity::getSymbol, symbol)));
  }
}
