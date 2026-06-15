package com.fxplatform.admin.service;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.fxplatform.admin.entity.AdminMenuEntity;
import com.fxplatform.admin.entity.AdminRoleEntity;
import com.fxplatform.admin.repository.AdminMenuRepository;
import com.fxplatform.admin.repository.AdminRoleRepository;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.exception.BusinessException;
import java.util.UUID;

final class AdminRbacServiceSupport {

  private AdminRbacServiceSupport() {
  }

  static AdminRoleEntity requireRole(AdminRoleRepository roleRepository, UUID roleId) {
    return roleRepository.findById(roleId)
        .orElseThrow(() -> new BusinessException("ADMIN_ROLE_NOT_FOUND", "Admin role not found"));
  }

  static AdminMenuEntity requireMenu(AdminMenuRepository menuRepository, UUID menuId) {
    return menuRepository.findById(menuId)
        .orElseThrow(() -> new BusinessException("ADMIN_MENU_NOT_FOUND", "Admin menu not found"));
  }

  static String firstNotBlank(String first, String second) {
    return StrUtil.isNotBlank(first) ? first : second;
  }

  static void audit(
      AuditLogService auditLogService,
      UUID actorUserId,
      String action,
      String targetType,
      UUID targetId,
      String value
  ) {
    auditLogService.record(actorUserId, action, targetType, targetId.toString(),
        JSONUtil.toJsonStr(MapUtil.builder().put("value", value).build()));
  }
}
