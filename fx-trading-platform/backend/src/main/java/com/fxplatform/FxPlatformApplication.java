package com.fxplatform;

import com.fxplatform.admin.service.AdminBootstrapProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动 FX/CFD 交易平台后端应用，并启用调度任务和配置属性。
 */
@EnableScheduling
@EnableConfigurationProperties(AdminBootstrapProperties.class)
@SpringBootApplication
@MapperScan("com.fxplatform.**.repository")
public class FxPlatformApplication {

  /**
   * 启动 Spring Boot 应用进程。
   */
  public static void main(String[] args) {
    SpringApplication.run(FxPlatformApplication.class, args);
  }
}
