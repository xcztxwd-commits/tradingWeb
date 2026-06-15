package com.fxplatform.home.repository;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface HomeCounterRepository {

  @Select("SELECT counter_value FROM core.home_counters WHERE counter_key = #{counterKey}")
  Long findValue(@Param("counterKey") String counterKey);

  default long currentValue(String counterKey) {
    Long value = findValue(counterKey);
    return value == null ? 0L : value;
  }

  @Update("""
      UPDATE core.home_counters
      SET counter_value = counter_value + #{increment},
          updated_at = now()
      WHERE counter_key = #{counterKey}
      """)
  int increment(@Param("counterKey") String counterKey, @Param("increment") long increment);
}
