package com.fxplatform.audit.service;

import cn.hutool.json.JSONUtil;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一构造审计 details JSON，避免各业务服务手写字符串转义。
 */
public final class AuditDetailsBuilder {

  private final Map<String, Object> details = new LinkedHashMap<>();

  private AuditDetailsBuilder() {
  }

  public static AuditDetailsBuilder create() {
    return new AuditDetailsBuilder();
  }

  public AuditDetailsBuilder put(String key, Object value) {
    details.put(key, value);
    return this;
  }

  public String toJson() {
    return JSONUtil.toJsonStr(details);
  }
}
