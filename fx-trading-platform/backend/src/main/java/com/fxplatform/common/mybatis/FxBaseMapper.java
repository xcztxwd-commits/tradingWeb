package com.fxplatform.common.mybatis;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyAccessorFactory;

/**
 * 平台统一的 MyBatis-Plus Mapper 基类。
 *
 * <p>迁移前服务层大量依赖 JPA Repository 的 save/findById/count/findAll 语义。这里用
 * MyBatis-Plus BaseMapper 提供等价能力，避免业务服务在迁移中混入重复 SQL 细节。
 */
public interface FxBaseMapper<T> extends BaseMapper<T> {

  /**
   * 按主键查询并返回 Optional，保持服务层原有空值处理方式。
   */
  default Optional<T> findById(UUID id) {
    return Optional.ofNullable(selectById(id));
  }

  /**
   * 插入或更新实体。新增时使用 Hutool 生成 UUID；审计时间字段交由 MyBatis-Plus 自动填充。
   */
  default T save(T entity) {
    Object id = fieldValue(entity, "id");
    boolean insert = id == null;
    if (insert) {
      setFieldValue(entity, "id", UUID.fromString(IdUtil.fastUUID()));
    }

    Object effectiveId = fieldValue(entity, "id");
    if (insert || selectById((Serializable) effectiveId) == null) {
      insert(entity);
    } else {
      updateById(entity);
    }
    return entity;
  }

  /**
   * 返回全量列表。仅用于小表、配置表或测试数据场景；大表应使用分页方法。
   */
  default List<T> findAll() {
    return selectList(new QueryWrapper<>());
  }

  /**
   * 统计当前表总行数。
   */
  default long count() {
    return selectCount(new QueryWrapper<>());
  }

  /**
   * 按白名单 key 排序分页查询，避免调用方把任意数据库列名透传进 order by。
   */
  default Page<T> findAll(Page<T> page, Map<String, String> sortableColumns, String sortKey, boolean asc) {
    QueryWrapper<T> query = new QueryWrapper<>();
    if (StrUtil.isNotBlank(sortKey)) {
      String column = sortableColumns.get(sortKey);
      if (StrUtil.isBlank(column)) {
        throw new IllegalArgumentException("Unsupported sort key: " + sortKey);
      }
      query.orderBy(true, asc, column);
    }
    return selectPage(page, query);
  }

  private Object fieldValue(T entity, String fieldName) {
    BeanWrapper wrapper = PropertyAccessorFactory.forBeanPropertyAccess(entity);
    return wrapper.isReadableProperty(fieldName) ? wrapper.getPropertyValue(fieldName) : null;
  }

  private void setFieldValue(T entity, String fieldName, Object value) {
    BeanWrapper wrapper = PropertyAccessorFactory.forBeanPropertyAccess(entity);
    if (wrapper.isWritableProperty(fieldName)) {
      wrapper.setPropertyValue(fieldName, value);
    }
  }
}
