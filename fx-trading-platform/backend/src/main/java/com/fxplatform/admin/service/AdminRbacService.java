package com.fxplatform.admin.service;

import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.request.AdminAssignUserRoleRequest;
import com.fxplatform.admin.dto.request.AdminDepartmentRequest;
import com.fxplatform.admin.dto.request.AdminMenuRequest;
import com.fxplatform.admin.dto.request.AdminPostRequest;
import com.fxplatform.admin.dto.request.AdminRoleDataScopeRequest;
import com.fxplatform.admin.dto.request.AdminRoleMenuPermissionRequest;
import com.fxplatform.admin.dto.request.AdminRoleRequest;
import com.fxplatform.admin.dto.response.AdminDepartmentResponse;
import com.fxplatform.admin.dto.response.AdminMenuResponse;
import com.fxplatform.admin.dto.response.AdminPostResponse;
import com.fxplatform.admin.dto.response.AdminRoleDataScopeResponse;
import com.fxplatform.admin.dto.response.AdminRoleMenuPermissionResponse;
import com.fxplatform.admin.dto.response.AdminRoleResponse;
import com.fxplatform.admin.dto.response.AdminUserRoleResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminRbacService {

  private final AdminRbacCatalogService catalogService;
  private final AdminRbacResourceManagementService resourceManagementService;
  private final AdminRolePermissionService rolePermissionService;
  private final AdminUserRoleAssignmentService userRoleAssignmentService;

  public AdminPageResponse<AdminRoleResponse> roles(AdminFeaturePageQuery query) {
    return catalogService.roles(query);
  }

  public List<AdminRoleResponse> roles() {
    return catalogService.roles();
  }

  public AdminPageResponse<AdminMenuResponse> menus(AdminFeaturePageQuery query) {
    return catalogService.menus(query);
  }

  public List<AdminMenuResponse> menus() {
    return catalogService.menus();
  }

  public AdminPageResponse<AdminDepartmentResponse> departments(AdminFeaturePageQuery query) {
    return catalogService.departments(query);
  }

  public List<AdminDepartmentResponse> departments() {
    return catalogService.departments();
  }

  public AdminPageResponse<AdminPostResponse> posts(AdminFeaturePageQuery query) {
    return catalogService.posts(query);
  }

  public List<AdminPostResponse> posts() {
    return catalogService.posts();
  }

  public AdminRoleResponse createRole(UUID actorUserId, AdminRoleRequest request) {
    return resourceManagementService.createRole(actorUserId, request);
  }

  public AdminRoleResponse updateRole(UUID actorUserId, UUID roleId, AdminRoleRequest request) {
    return resourceManagementService.updateRole(actorUserId, roleId, request);
  }

  public void deleteRole(UUID actorUserId, UUID roleId, String reason) {
    resourceManagementService.deleteRole(actorUserId, roleId, reason);
  }

  public AdminMenuResponse createMenu(UUID actorUserId, AdminMenuRequest request) {
    return resourceManagementService.createMenu(actorUserId, request);
  }

  public AdminMenuResponse updateMenu(UUID actorUserId, UUID menuId, AdminMenuRequest request) {
    return resourceManagementService.updateMenu(actorUserId, menuId, request);
  }

  public void deleteMenu(UUID actorUserId, UUID menuId, String reason) {
    resourceManagementService.deleteMenu(actorUserId, menuId, reason);
  }

  public AdminDepartmentResponse createDepartment(UUID actorUserId, AdminDepartmentRequest request) {
    return resourceManagementService.createDepartment(actorUserId, request);
  }

  public AdminDepartmentResponse updateDepartment(UUID actorUserId, UUID departmentId, AdminDepartmentRequest request) {
    return resourceManagementService.updateDepartment(actorUserId, departmentId, request);
  }

  public void deleteDepartment(UUID actorUserId, UUID departmentId, String reason) {
    resourceManagementService.deleteDepartment(actorUserId, departmentId, reason);
  }

  public AdminPostResponse createPost(UUID actorUserId, AdminPostRequest request) {
    return resourceManagementService.createPost(actorUserId, request);
  }

  public AdminPostResponse updatePost(UUID actorUserId, UUID postId, AdminPostRequest request) {
    return resourceManagementService.updatePost(actorUserId, postId, request);
  }

  public void deletePost(UUID actorUserId, UUID postId, String reason) {
    resourceManagementService.deletePost(actorUserId, postId, reason);
  }

  public AdminRoleMenuPermissionResponse saveRoleMenuPermission(
      UUID actorUserId,
      UUID roleId,
      AdminRoleMenuPermissionRequest request
  ) {
    return rolePermissionService.saveRoleMenuPermission(actorUserId, roleId, request);
  }

  public AdminUserRoleResponse assignUserRole(UUID actorUserId, AdminAssignUserRoleRequest request) {
    return userRoleAssignmentService.assignUserRole(actorUserId, request);
  }

  public AdminRoleDataScopeResponse saveDataScope(UUID actorUserId, UUID roleId, AdminRoleDataScopeRequest request) {
    return rolePermissionService.saveDataScope(actorUserId, roleId, request);
  }
}
