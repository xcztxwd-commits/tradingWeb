package com.fxplatform.admin.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fxplatform.admin.entity.AdminMenuEntity;
import com.fxplatform.admin.entity.AdminRoleEntity;
import com.fxplatform.admin.entity.AdminRoleMenuPermissionEntity;
import com.fxplatform.admin.entity.AdminUserRoleEntity;
import com.fxplatform.admin.repository.AdminMenuRepository;
import com.fxplatform.admin.repository.AdminRoleMenuPermissionRepository;
import com.fxplatform.admin.repository.AdminRoleRepository;
import com.fxplatform.admin.repository.AdminUserRoleRepository;
import com.fxplatform.auth.entity.UserEntity;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminAuthorityService {

  private final AdminUserRoleRepository userRoleRepository;
  private final AdminRoleRepository roleRepository;
  private final AdminMenuRepository menuRepository;
  private final AdminRoleMenuPermissionRepository roleMenuPermissionRepository;

  public List<String> authoritiesFor(UserEntity user) {
    LinkedHashSet<String> authorities = new LinkedHashSet<>();
    if (user == null || user.getRole() == null) {
      return List.copyOf(authorities);
    }
    authorities.add("ROLE_" + user.getRole().name());
    if (user.getId() == null) {
      return List.copyOf(authorities);
    }

    List<UUID> roleIds = enabledRoleIds(user.getId());
    if (roleIds.isEmpty()) {
      return List.copyOf(authorities);
    }
    List<AdminRoleMenuPermissionEntity> permissions = enabledPermissions(roleIds);
    if (permissions.isEmpty()) {
      return List.copyOf(authorities);
    }
    Map<UUID, AdminMenuEntity> menus = enabledMenus(permissions);
    for (AdminRoleMenuPermissionEntity permission : permissions) {
      AdminMenuEntity menu = menus.get(permission.getMenuId());
      if (menu == null) {
        continue;
      }
      add(authorities, menu.getPermissionKey());
      buttonAuthorities(permission.getButtons()).forEach(value -> add(authorities, value));
    }
    return List.copyOf(authorities);
  }

  private List<UUID> enabledRoleIds(UUID userId) {
    List<UUID> assignedRoleIds = userRoleRepository.selectList(new LambdaQueryWrapper<AdminUserRoleEntity>()
            .eq(AdminUserRoleEntity::getUserId, userId))
        .stream()
        .map(AdminUserRoleEntity::getRoleId)
        .filter(roleId -> roleId != null)
        .distinct()
        .toList();
    if (assignedRoleIds.isEmpty()) {
      return List.of();
    }
    Set<UUID> activeRoleIds = roleRepository.selectList(new LambdaQueryWrapper<AdminRoleEntity>()
            .in(AdminRoleEntity::getId, assignedRoleIds)
            .eq(AdminRoleEntity::getEnabled, true))
        .stream()
        .map(AdminRoleEntity::getId)
        .collect(LinkedHashSet::new, Set::add, Set::addAll);
    return assignedRoleIds.stream()
        .filter(activeRoleIds::contains)
        .toList();
  }

  private List<AdminRoleMenuPermissionEntity> enabledPermissions(List<UUID> roleIds) {
    return roleMenuPermissionRepository.selectList(new LambdaQueryWrapper<AdminRoleMenuPermissionEntity>()
        .in(AdminRoleMenuPermissionEntity::getRoleId, roleIds)
        .eq(AdminRoleMenuPermissionEntity::getEnabled, true));
  }

  private Map<UUID, AdminMenuEntity> enabledMenus(List<AdminRoleMenuPermissionEntity> permissions) {
    List<UUID> menuIds = permissions.stream()
        .map(AdminRoleMenuPermissionEntity::getMenuId)
        .filter(menuId -> menuId != null)
        .distinct()
        .toList();
    if (menuIds.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<UUID, AdminMenuEntity> menus = new LinkedHashMap<>();
    for (AdminMenuEntity menu : menuRepository.selectList(new LambdaQueryWrapper<AdminMenuEntity>()
        .in(AdminMenuEntity::getId, menuIds)
        .eq(AdminMenuEntity::getEnabled, true))) {
      menus.put(menu.getId(), menu);
    }
    return menus;
  }

  private List<String> buttonAuthorities(String buttons) {
    if (StrUtil.isBlank(buttons)) {
      return List.of();
    }
    try {
      return JSONUtil.parseArray(buttons).stream()
          .map(String::valueOf)
          .filter(StrUtil::isNotBlank)
          .toList();
    } catch (RuntimeException ex) {
      return List.of();
    }
  }

  private void add(LinkedHashSet<String> authorities, String authority) {
    if (StrUtil.isNotBlank(authority)) {
      authorities.add(authority.trim());
    }
  }
}
