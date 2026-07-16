package com.fxplatform.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * AdminBootstrapRunner 是后台管理模块的业务服务。
 */
@Component
@RequiredArgsConstructor
public class AdminBootstrapRunner implements ApplicationRunner {

  private final AdminBootstrapService adminBootstrapService;
  private final AdminBootstrapProperties properties;

  @Override
  public void run(ApplicationArguments args) {
    if (!properties.enabled()) {
      return;
    }
    adminBootstrapService.bootstrap();
  }
}
