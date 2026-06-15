package com.fxplatform.common.mybatis;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

/**
 * PostgreSQL UUID 字段处理器。
 *
 * <p>平台实体统一使用 Java UUID，数据库字段统一使用 PostgreSQL uuid。该处理器避免每个 Mapper
 * 重复指定 jdbcType，也让 MyBatis-Plus 的 insert/update/selectById 参数映射保持一致。
 */
@MappedTypes(UUID.class)
@MappedJdbcTypes(JdbcType.OTHER)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, UUID parameter, JdbcType jdbcType)
      throws SQLException {
    ps.setObject(i, parameter, Types.OTHER);
  }

  @Override
  public UUID getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return toUuid(rs.getObject(columnName));
  }

  @Override
  public UUID getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return toUuid(rs.getObject(columnIndex));
  }

  @Override
  public UUID getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return toUuid(cs.getObject(columnIndex));
  }

  private UUID toUuid(Object value) {
    if (value == null) {
      return null;
    }
    return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
  }
}
