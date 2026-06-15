package com.fxplatform.common.mybatis;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import java.util.UUID;
import org.apache.ibatis.type.JdbcType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 统一配置。
 */
@Configuration
public class MybatisPlusConfig {

  /**
   * 注册 PostgreSQL 分页插件，后台列表接口统一走 MyBatis-Plus 分页能力。
   */
  @Bean
  public MybatisPlusInterceptor mybatisPlusInterceptor() {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
    return interceptor;
  }

  /**
   * 只全局注册 UUID 处理器；JSONB 字段通过实体字段注解按需使用，避免普通 String 被误写成 jsonb。
   */
  @Bean
  public ConfigurationCustomizer uuidTypeHandlerCustomizer() {
    return configuration -> {
      configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
      configuration.getTypeHandlerRegistry().register(UUID.class, JdbcType.OTHER, UuidTypeHandler.class);
    };
  }
}
