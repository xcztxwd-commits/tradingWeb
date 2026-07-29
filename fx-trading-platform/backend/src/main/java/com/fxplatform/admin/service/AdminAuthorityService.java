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
import java.util.ArrayList;
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

    List<AdminRoleEntity> roles = enabledRoles(user.getId());
    if (roles.isEmpty()) {
      return List.copyOf(authorities);
    }
    roles.forEach(role -> addRoleAuthority(authorities, role));
    List<UUID> roleIds = roles.stream()
        .map(AdminRoleEntity::getId)
        .toList();
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
      addPermissionAuthority(authorities, menu.getPermissionKey());
      buttonAuthorities(permission.getButtons())
          .forEach(value -> addPermissionAuthority(authorities, value));
    }
    return List.copyOf(authorities);
  }

  private List<AdminRoleEntity> enabledRoles(UUID userId) {
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
    Set<UUID> assignedRoleIdSet = new LinkedHashSet<>(assignedRoleIds);
    Map<UUID, AdminRoleEntity> enabledRolesById = new LinkedHashMap<>();
    for (AdminRoleEntity role : roleRepository.selectList(new LambdaQueryWrapper<AdminRoleEntity>()
        .in(AdminRoleEntity::getId, assignedRoleIds)
        .eq(AdminRoleEntity::getEnabled, true))) {
      if (role.getId() != null
          && assignedRoleIdSet.contains(role.getId())
          && Boolean.TRUE.equals(role.getEnabled())) {
        enabledRolesById.putIfAbsent(role.getId(), role);
      }
    }
    List<AdminRoleEntity> roles = new ArrayList<>();
    for (UUID roleId : assignedRoleIds) {
      AdminRoleEntity role = enabledRolesById.get(roleId);
      if (role != null) {
        roles.add(role);
      }
    }
    return List.copyOf(roles);
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

  private void addRoleAuthority(
      LinkedHashSet<String> authorities,
      AdminRoleEntity role
  ) {
    if (StrUtil.isBlank(role.getRoleCode())) {
      return;
    }
    String authority = role.getRoleCode().trim();
    if (AdminPermissionCatalog.SUPER_ADMIN.equals(authority)
        && (!AdminPermissionCatalog.SUPER_ADMIN_ROLE_ID.equals(role.getId())
            || !Boolean.TRUE.equals(role.getSystemManaged()))) {
      return;
    }
    add(authorities, authority);
  }

  private void addPermissionAuthority(
      LinkedHashSet<String> authorities,
      String authority
  ) {
    if (StrUtil.isNotBlank(authority)
        && AdminPermissionCatalog.SUPER_ADMIN.equals(authority.trim())) {
      return;
    }
    add(authorities, authority);
  }
}
