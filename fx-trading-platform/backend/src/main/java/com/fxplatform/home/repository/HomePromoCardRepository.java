package com.fxplatform.home.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fxplatform.home.entity.HomePromoCardEntity;
import java.util.List;

public interface HomePromoCardRepository extends BaseMapper<HomePromoCardEntity> {

  default List<HomePromoCardEntity> findVisibleCards() {
    return selectList(new LambdaQueryWrapper<HomePromoCardEntity>()
        .eq(HomePromoCardEntity::getEnabled, true)
        .orderByAsc(HomePromoCardEntity::getDisplayOrder)
        .orderByAsc(HomePromoCardEntity::getSlot));
  }
}
