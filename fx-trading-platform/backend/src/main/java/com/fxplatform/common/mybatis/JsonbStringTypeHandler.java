package com.fxplatform.common.mybatis;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

/**
 * PostgreSQL JSONB 字符串字段处理器。
 *
 * <p>MyBatis 默认以 VARCHAR 写入 String，PostgreSQL JSONB 列会拒绝该类型。这里显式使用
 * PGobject 传递 jsonb，保持审计日志 details 字段的数据库类型不变。
 */
public class JsonbStringTypeHandler extends BaseTypeHandler<String> {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
      throws SQLException {
    PGobject json = new PGobject();
    json.setType("jsonb");
    json.setValue(parameter);
    ps.setObject(i, json, Types.OTHER);
  }

  @Override
  public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return rs.getString(columnName);
  }

  @Override
  public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return rs.getString(columnIndex);
  }

  @Override
  public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return cs.getString(columnIndex);
  }
}
