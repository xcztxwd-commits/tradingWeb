package com.fxplatform.admin.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AdminBootstrapProperties 承载后台管理模块的数据结构。
 */
@ConfigurationProperties(prefix = "admin.bootstrap")
public record AdminBootstrapProperties(
    boolean enabled,
    String email,
    String password
) {
}
