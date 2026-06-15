package com.fxplatform.admin.dto.response;

import com.fxplatform.admin.entity.AdminMenuEntity;
import java.util.UUID;

/**
 * 后台菜单响应。
 */
public record AdminMenuResponse(
    UUID id,
    UUID parentId,
    String name,
    String permissionKey,
    String path,
    String component,
    String menuType,
    Boolean enabled,
    Integer sortOrder
) {

  public static AdminMenuResponse from(AdminMenuEntity entity) {
    return new AdminMenuResponse(
        entity.getId(),
        entity.getParentId(),
        entity.getMenuName(),
        entity.getPermissionKey(),
        entity.getPath(),
        entity.getComponent(),
        entity.getMenuType(),
        entity.getEnabled(),
        entity.getSortOrder());
  }
}
