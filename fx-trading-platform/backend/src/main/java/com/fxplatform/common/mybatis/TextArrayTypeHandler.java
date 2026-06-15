package com.fxplatform.common.mybatis;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * PostgreSQL text[] handler for small configuration arrays.
 */
public class TextArrayTypeHandler extends BaseTypeHandler<List<String>> {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType)
      throws SQLException {
    Array array = ps.getConnection().createArrayOf("text", parameter.toArray(String[]::new));
    ps.setArray(i, array);
  }

  @Override
  public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return fromArray(rs.getArray(columnName));
  }

  @Override
  public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return fromArray(rs.getArray(columnIndex));
  }

  @Override
  public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return fromArray(cs.getArray(columnIndex));
  }

  private List<String> fromArray(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    Object value = array.getArray();
    if (value instanceof String[] strings) {
      return Arrays.asList(strings);
    }
    Object[] objects = (Object[]) value;
    return Arrays.stream(objects).map(String::valueOf).toList();
  }
}
