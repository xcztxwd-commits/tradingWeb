package com.fxplatform.admin.service;

/**
 * 后台通用页面动作处理器。
 */
public interface AdminFeatureActionHandler {

  boolean supports(String pageKey, String action);

  String handle(AdminFeatureActionContext context);
}
