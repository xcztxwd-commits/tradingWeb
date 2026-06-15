package com.fxplatform.common.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import java.time.Instant;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

/**
 * MyBatis-Plus 审计字段自动填充器。
 */
@Component
public class AuditFieldFillHandler implements MetaObjectHandler {

  @Override
  public void insertFill(MetaObject metaObject) {
    Instant now = Instant.now();
    fillIfNull(metaObject, "createdAt", now);
    fillIfNull(metaObject, "updatedAt", now);
    fillIfNull(metaObject, "executedAt", now);
    fillIfNull(metaObject, "openedAt", now);
  }

  @Override
  public void updateFill(MetaObject metaObject) {
    fill(metaObject, "updatedAt", Instant.now());
  }

  private void fillIfNull(MetaObject metaObject, String fieldName, Instant value) {
    if (metaObject.hasSetter(fieldName) && metaObject.getValue(fieldName) == null) {
      metaObject.setValue(fieldName, value);
    }
  }

  private void fill(MetaObject metaObject, String fieldName, Instant value) {
    if (metaObject.hasSetter(fieldName)) {
      metaObject.setValue(fieldName, value);
    }
  }
}
