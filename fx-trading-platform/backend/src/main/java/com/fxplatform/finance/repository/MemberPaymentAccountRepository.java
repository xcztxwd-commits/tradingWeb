package com.fxplatform.finance.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.finance.entity.MemberPaymentAccountEntity;
import java.util.List;
import java.util.UUID;

/**
 * 会员收付款账户 Mapper，覆盖银行卡和链上钱包两类资料。
 */
public interface MemberPaymentAccountRepository extends FxBaseMapper<MemberPaymentAccountEntity> {

  /** 查询会员的银行卡和钱包账户，后台会员详情页使用。 */
  default List<MemberPaymentAccountEntity> findByUserId(UUID userId) {
    return selectList(new LambdaQueryWrapper<MemberPaymentAccountEntity>()
        .eq(MemberPaymentAccountEntity::getUserId, userId)
        .orderByDesc(MemberPaymentAccountEntity::getCreatedAt));
  }

  /** 查询最近会员银行卡/钱包账户，后台用户银行卡页使用。 */
  default List<MemberPaymentAccountEntity> findRecent(int size) {
    return selectPage(new Page<>(1, Math.max(1, Math.min(size, 200))),
        new LambdaQueryWrapper<MemberPaymentAccountEntity>().orderByDesc(MemberPaymentAccountEntity::getCreatedAt))
        .getRecords();
  }
}
