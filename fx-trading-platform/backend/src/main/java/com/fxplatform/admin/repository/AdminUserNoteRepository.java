package com.fxplatform.admin.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.admin.entity.AdminUserNoteEntity;
import com.fxplatform.common.mybatis.FxBaseMapper;
import java.util.List;
import java.util.UUID;

/**
 * AdminUserNoteRepository 通过 MyBatis-Plus 访问后台用户备注。
 */
public interface AdminUserNoteRepository extends FxBaseMapper<AdminUserNoteEntity> {

  /** 按用户倒序查询备注，供后台用户详情页展示。 */
  default List<AdminUserNoteEntity> findByUserIdOrderByCreatedAtDesc(UUID userId) {
    return selectList(new LambdaQueryWrapper<AdminUserNoteEntity>()
        .eq(AdminUserNoteEntity::getUserId, userId)
        .orderByDesc(AdminUserNoteEntity::getCreatedAt));
  }
}
