package com.fxplatform.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fxplatform.admin.dto.request.AdminDepartmentRequest;
import com.fxplatform.admin.dto.request.AdminMenuRequest;
import com.fxplatform.admin.dto.request.AdminPostRequest;
import com.fxplatform.admin.dto.request.AdminRoleRequest;
import com.fxplatform.admin.dto.response.AdminDepartmentResponse;
import com.fxplatform.admin.dto.response.AdminMenuResponse;
import com.fxplatform.admin.dto.response.AdminPostResponse;
import com.fxplatform.admin.dto.response.AdminRoleResponse;
import com.fxplatform.admin.entity.AdminDepartmentEntity;
import com.fxplatform.admin.entity.AdminMenuEntity;
import com.fxplatform.admin.entity.AdminPostEntity;
import com.fxplatform.admin.entity.AdminRoleDataScopeEntity;
import com.fxplatform.admin.entity.AdminRoleEntity;
import com.fxplatform.admin.entity.AdminRoleMenuPermissionEntity;
import com.fxplatform.admin.entity.AdminUserRoleEntity;
import com.fxplatform.admin.repository.AdminDepartmentRepository;
import com.fxplatform.admin.repository.AdminMenuRepository;
import com.fxplatform.admin.repository.AdminPostRepository;
import com.fxplatform.admin.repository.AdminRoleDataScopeRepository;
import com.fxplatform.admin.repository.AdminRoleMenuPermissionRepository;
import com.fxplatform.admin.repository.AdminRoleRepository;
import com.fxplatform.admin.repository.AdminUserRoleRepository;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.exception.BusinessException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminRbacResourceManagementService {

  private final AdminRoleRepository roleRepository;
  private final AdminMenuRepository menuRepository;
  private final AdminRoleMenuPermissionRepository roleMenuPermissionRepository;
  private final AdminUserRoleRepository userRoleRepository;
  private final AdminRoleDataScopeRepository dataScopeRepository;
  private final AdminDepartmentRepository departmentRepository;
  private final AdminPostRepository postRepository;
  private final AuditLogService auditLogService;

  @Transactional
  @PreAuthorize("hasAuthority('ROLE_ADMIN') and hasAuthority('SUPER_ADMIN')")
  public AdminRoleResponse createRole(UUID actorUserId, AdminRoleRequest request) {
    AdminRoleEntity role = new AdminRoleEntity();
    role.setRoleName(request.name());
    role.setRoleCode(request.code());
    role.setEnabled(request.enabled());
    role.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
    role.setDescription(request.description());
    AdminRoleEntity saved = roleRepository.save(role);
    AdminRbacServiceSupport.audit(auditLogService, actorUserId, "ADMIN_RBAC_ROLE_CREATE", "ADMIN_ROLE", saved.getId(), request.code());
    return AdminRoleResponse.from(saved);
  }

  @Transactional
  @PreAuthorize("hasAuthority('ROLE_ADMIN') and hasAuthority('SUPER_ADMIN')")
  public AdminRoleResponse updateRole(UUID actorUserId, UUID roleId, AdminRoleRequest request) {
    AdminRoleEntity role = AdminRbacServiceSupport.requireRole(roleRepository, roleId);
    role.setRoleName(request.name());
    role.setRoleCode(request.code());
    role.setEnabled(request.enabled());
    role.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
    role.setDescription(request.description());
    AdminRoleEntity saved = roleRepository.save(role);
    AdminRbacServiceSupport.audit(auditLogService, actorUserId, "ADMIN_RBAC_ROLE_UPDATE", "ADMIN_ROLE", saved.getId(), request.code());
    return AdminRoleResponse.from(saved);
  }

  @Transactional
  @PreAuthorize("hasAuthority('ROLE_ADMIN') and hasAuthority('SUPER_ADMIN')")
  public void deleteRole(UUID actorUserId, UUID roleId, String reason) {
    AdminRoleEntity role = AdminRbacServiceSupport.requireRole(roleRepository, roleId);
    roleMenuPermissionRepository.delete(new QueryWrapper<AdminRoleMenuPermissionEntity>().eq("role_id", roleId));
    dataScopeRepository.delete(new QueryWrapper<AdminRoleDataScopeEntity>().eq("role_id", roleId));
    userRoleRepository.delete(new QueryWrapper<AdminUserRoleEntity>().eq("role_id", roleId));
    roleRepository.deleteById(roleId);
    AdminRbacServiceSupport.audit(
        auditLogService,
        actorUserId,
        "ADMIN_RBAC_ROLE_DELETE",
        "ADMIN_ROLE",
        roleId,
        AdminRbacServiceSupport.firstNotBlank(reason, role.getRoleCode()));
  }

  @Transactional
  @PreAuthorize("hasAuthority('ROLE_ADMIN') and hasAuthority('SUPER_ADMIN')")
  public AdminMenuResponse createMenu(UUID actorUserId, AdminMenuRequest request) {
    AdminMenuEntity menu = new AdminMenuEntity();
    applyMenu(menu, request);
    AdminMenuEntity saved = menuRepository.save(menu);
    AdminRbacServiceSupport.audit(auditLogService, actorUserId, "ADMIN_RBAC_MENU_CREATE", "ADMIN_MENU", saved.getId(), request.permissionKey());
    return AdminMenuResponse.from(saved);
  }

  @Transactional
  @PreAuthorize("hasAuthority('ROLE_ADMIN') and hasAuthority('SUPER_ADMIN')")
  public AdminMenuResponse updateMenu(UUID actorUserId, UUID menuId, AdminMenuRequest request) {
    AdminMenuEntity menu = AdminRbacServiceSupport.requireMenu(menuRepository, menuId);
    applyMenu(menu, request);
    AdminMenuEntity saved = menuRepository.save(menu);
    AdminRbacServiceSupport.audit(auditLogService, actorUserId, "ADMIN_RBAC_MENU_UPDATE", "ADMIN_MENU", saved.getId(), request.permissionKey());
    return AdminMenuResponse.from(saved);
  }

  @Transactional
  @PreAuthorize("hasAuthority('ROLE_ADMIN') and hasAuthority('SUPER_ADMIN')")
  public void deleteMenu(UUID actorUserId, UUID menuId, String reason) {
    AdminMenuEntity menu = AdminRbacServiceSupport.requireMenu(menuRepository, menuId);
    menuRepository.deleteById(menuId);
    AdminRbacServiceSupport.audit(
        auditLogService,
        actorUserId,
        "ADMIN_RBAC_MENU_DELETE",
        "ADMIN_MENU",
        menuId,
        AdminRbacServiceSupport.firstNotBlank(reason, menu.getPermissionKey()));
  }

  @Transactional
  public AdminDepartmentResponse createDepartment(UUID actorUserId, AdminDepartmentRequest request) {
    AdminDepartmentEntity department = new AdminDepartmentEntity();
    applyDepartment(department, request);
    AdminDepartmentEntity saved = departmentRepository.save(department);
    AdminRbacServiceSupport.audit(auditLogService, actorUserId, "ADMIN_RBAC_DEPARTMENT_CREATE", "ADMIN_DEPARTMENT", saved.getId(), request.name());
    return AdminDepartmentResponse.from(saved);
  }

  @Transactional
  public AdminDepartmentResponse updateDepartment(UUID actorUserId, UUID departmentId, AdminDepartmentRequest request) {
    AdminDepartmentEntity department = requireDepartment(departmentId);
    applyDepartment(department, request);
    AdminDepartmentEntity saved = departmentRepository.save(department);
    AdminRbacServiceSupport.audit(auditLogService, actorUserId, "ADMIN_RBAC_DEPARTMENT_UPDATE", "ADMIN_DEPARTMENT", saved.getId(), request.name());
    return AdminDepartmentResponse.from(saved);
  }

  @Transactional
  public void deleteDepartment(UUID actorUserId, UUID departmentId, String reason) {
    AdminDepartmentEntity department = requireDepartment(departmentId);
    departmentRepository.deleteById(departmentId);
    AdminRbacServiceSupport.audit(
        auditLogService,
        actorUserId,
        "ADMIN_RBAC_DEPARTMENT_DELETE",
        "ADMIN_DEPARTMENT",
        departmentId,
        AdminRbacServiceSupport.firstNotBlank(reason, department.getDepartmentName()));
  }

  @Transactional
  public AdminPostResponse createPost(UUID actorUserId, AdminPostRequest request) {
    AdminPostEntity post = new AdminPostEntity();
    applyPost(post, request);
    AdminPostEntity saved = postRepository.save(post);
    AdminRbacServiceSupport.audit(auditLogService, actorUserId, "ADMIN_RBAC_POST_CREATE", "ADMIN_POST", saved.getId(), request.code());
    return AdminPostResponse.from(saved);
  }

  @Transactional
  public AdminPostResponse updatePost(UUID actorUserId, UUID postId, AdminPostRequest request) {
    AdminPostEntity post = requirePost(postId);
    applyPost(post, request);
    AdminPostEntity saved = postRepository.save(post);
    AdminRbacServiceSupport.audit(auditLogService, actorUserId, "ADMIN_RBAC_POST_UPDATE", "ADMIN_POST", saved.getId(), request.code());
    return AdminPostResponse.from(saved);
  }

  @Transactional
  public void deletePost(UUID actorUserId, UUID postId, String reason) {
    AdminPostEntity post = requirePost(postId);
    postRepository.deleteById(postId);
    AdminRbacServiceSupport.audit(
        auditLogService,
        actorUserId,
        "ADMIN_RBAC_POST_DELETE",
        "ADMIN_POST",
        postId,
        AdminRbacServiceSupport.firstNotBlank(reason, post.getPostCode()));
  }

  private void applyMenu(AdminMenuEntity menu, AdminMenuRequest request) {
    menu.setParentId(request.parentId());
    menu.setMenuName(request.name());
    menu.setPermissionKey(request.permissionKey());
    menu.setPath(request.path());
    menu.setComponent(request.component());
    menu.setMenuType(request.menuType());
    menu.setEnabled(request.enabled());
    menu.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
  }

  private void applyDepartment(AdminDepartmentEntity department, AdminDepartmentRequest request) {
    department.setDepartmentName(request.name());
    department.setParentId(request.parentId());
    department.setLeader(request.leader());
    department.setPhone(request.phone());
    department.setEnabled(request.enabled() == null || Boolean.TRUE.equals(request.enabled()));
    department.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
  }

  private void applyPost(AdminPostEntity post, AdminPostRequest request) {
    post.setPostName(request.name());
    post.setPostCode(request.code());
    post.setEnabled(request.enabled() == null || Boolean.TRUE.equals(request.enabled()));
    post.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
  }

  private AdminDepartmentEntity requireDepartment(UUID departmentId) {
    return departmentRepository.findById(departmentId)
        .orElseThrow(() -> new BusinessException("ADMIN_DEPARTMENT_NOT_FOUND", "Admin department not found"));
  }

  private AdminPostEntity requirePost(UUID postId) {
    return postRepository.findById(postId)
        .orElseThrow(() -> new BusinessException("ADMIN_POST_NOT_FOUND", "Admin post not found"));
  }
}
