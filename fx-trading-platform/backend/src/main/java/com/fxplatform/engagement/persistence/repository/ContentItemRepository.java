package com.fxplatform.engagement.persistence.repository;

import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.engagement.persistence.entity.ContentItemEntity;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ContentItemRepository extends FxBaseMapper<ContentItemEntity> {

  @Select("SELECT * FROM content.content_items WHERE id = #{id} FOR UPDATE")
  ContentItemEntity selectByIdForUpdate(@Param("id") UUID id);
}
