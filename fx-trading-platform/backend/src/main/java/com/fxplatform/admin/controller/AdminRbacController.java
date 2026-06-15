package com.fxplatform.admin.controller;

import com.fxplatform.admin.dto.request.AdminAssignUserRoleRequest;
import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.request.AdminDepartmentRequest;
import com.fxplatform.admin.dto.request.AdminMenuRequest;
import com.fxplatform.admin.dto.request.AdminPostRequest;
import com.fxplatform.admin.dto.request.AdminReasonRequest;
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
import com.fxplatform.admin.service.AdminFeaturePageQuery;
import com.fxplatform.admin.service.AdminRbacService;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AdminRbacController 提供角色、菜单、按钮权限和数据权限 API。
 */
@RestController
@RequestMapping("/api/admin/rbac")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminRbacController {

  private final AdminRbacService adminRbacService;

  /** 查询后台角色列表。 */
  @GetMapping("/roles")
  public ApiResponse<AdminPageResponse<AdminRoleResponse>> roles(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String sortField,
      @RequestParam(defaultValue = "asc") String sortDirection,
      @RequestParam Map<String, String> requestParams
  ) {
    return ApiResponse.success(adminRbacService.roles(
        AdminFeaturePageQuery.from(page, size, sortField, sortDirection, requestParams)));
  }

  /** 创建后台角色。 */
  @PostMapping("/roles")
  public ApiResponse<AdminRoleResponse> createRole(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminRoleRequest request
  ) {
    return ApiResponse.success(adminRbacService.createRole(principal.id(), request));
  }

  /** 更新后台角色。 */
  @PutMapping("/roles/{roleId}")
  public ApiResponse<AdminRoleResponse> updateRole(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID roleId,
      @Valid @RequestBody AdminRoleRequest request
  ) {
    return ApiResponse.success(adminRbacService.updateRole(principal.id(), roleId, request));
  }

  /** 删除后台角色。 */
  @DeleteMapping("/roles/{roleId}")
  public ApiResponse<Void> deleteRole(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID roleId,
      @Valid @RequestBody AdminReasonRequest request
  ) {
    adminRbacService.deleteRole(principal.id(), roleId, request.reason());
    return ApiResponse.success(null);
  }

  /** 查询后台菜单列表。 */
  @GetMapping("/menus")
  public ApiResponse<AdminPageResponse<AdminMenuResponse>> menus(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String sortField,
      @RequestParam(defaultValue = "asc") String sortDirection,
      @RequestParam Map<String, String> requestParams
  ) {
    return ApiResponse.success(adminRbacService.menus(
        AdminFeaturePageQuery.from(page, size, sortField, sortDirection, requestParams)));
  }

  /** 查询后台部门列表。 */
  @GetMapping("/departments")
  public ApiResponse<AdminPageResponse<AdminDepartmentResponse>> departments(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String sortField,
      @RequestParam(defaultValue = "asc") String sortDirection,
      @RequestParam Map<String, String> requestParams
  ) {
    return ApiResponse.success(adminRbacService.departments(
        AdminFeaturePageQuery.from(page, size, sortField, sortDirection, requestParams)));
  }

  /** 创建后台部门。 */
  @PostMapping("/departments")
  public ApiResponse<AdminDepartmentResponse> createDepartment(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminDepartmentRequest request
  ) {
    return ApiResponse.success(adminRbacService.createDepartment(principal.id(), request));
  }

  /** 更新后台部门。 */
  @PutMapping("/departments/{departmentId}")
  public ApiResponse<AdminDepartmentResponse> updateDepartment(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID departmentId,
      @Valid @RequestBody AdminDepartmentRequest request
  ) {
    return ApiResponse.success(adminRbacService.updateDepartment(principal.id(), departmentId, request));
  }

  /** 删除后台部门。 */
  @DeleteMapping("/departments/{departmentId}")
  public ApiResponse<Void> deleteDepartment(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID departmentId,
      @Valid @RequestBody AdminReasonRequest request
  ) {
    adminRbacService.deleteDepartment(principal.id(), departmentId, request.reason());
    return ApiResponse.success(null);
  }

  /** 查询后台岗位列表。 */
  @GetMapping("/posts")
  public ApiResponse<AdminPageResponse<AdminPostResponse>> posts(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String sortField,
      @RequestParam(defaultValue = "asc") String sortDirection,
      @RequestParam Map<String, String> requestParams
  ) {
    return ApiResponse.success(adminRbacService.posts(
        AdminFeaturePageQuery.from(page, size, sortField, sortDirection, requestParams)));
  }

  /** 创建后台岗位。 */
  @PostMapping("/posts")
  public ApiResponse<AdminPostResponse> createPost(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminPostRequest request
  ) {
    return ApiResponse.success(adminRbacService.createPost(principal.id(), request));
  }

  /** 更新后台岗位。 */
  @PutMapping("/posts/{postId}")
  public ApiResponse<AdminPostResponse> updatePost(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID postId,
      @Valid @RequestBody AdminPostRequest request
  ) {
    return ApiResponse.success(adminRbacService.updatePost(principal.id(), postId, request));
  }

  /** 删除后台岗位。 */
  @DeleteMapping("/posts/{postId}")
  public ApiResponse<Void> deletePost(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID postId,
      @Valid @RequestBody AdminReasonRequest request
  ) {
    adminRbacService.deletePost(principal.id(), postId, request.reason());
    return ApiResponse.success(null);
  }

  /** 创建后台菜单或权限入口。 */
  @PostMapping("/menus")
  public ApiResponse<AdminMenuResponse> createMenu(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminMenuRequest request
  ) {
    return ApiResponse.success(adminRbacService.createMenu(principal.id(), request));
  }

  /** 更新后台菜单或权限入口。 */
  @PutMapping("/menus/{menuId}")
  public ApiResponse<AdminMenuResponse> updateMenu(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID menuId,
      @Valid @RequestBody AdminMenuRequest request
  ) {
    return ApiResponse.success(adminRbacService.updateMenu(principal.id(), menuId, request));
  }

  /** 删除后台菜单或权限入口。 */
  @DeleteMapping("/menus/{menuId}")
  public ApiResponse<Void> deleteMenu(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID menuId,
      @Valid @RequestBody AdminReasonRequest request
  ) {
    adminRbacService.deleteMenu(principal.id(), menuId, request.reason());
    return ApiResponse.success(null);
  }

  /** 保存角色菜单和按钮权限。 */
  @PutMapping("/roles/{roleId}/menu-permissions")
  public ApiResponse<AdminRoleMenuPermissionResponse> saveRoleMenuPermission(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID roleId,
      @Valid @RequestBody AdminRoleMenuPermissionRequest request
  ) {
    return ApiResponse.success(adminRbacService.saveRoleMenuPermission(principal.id(), roleId, request));
  }

  /** 给用户分配后台角色。 */
  @PostMapping("/user-roles")
  public ApiResponse<AdminUserRoleResponse> assignUserRole(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AdminAssignUserRoleRequest request
  ) {
    return ApiResponse.success(adminRbacService.assignUserRole(principal.id(), request));
  }

  /** 保存角色数据权限范围。 */
  @PutMapping("/roles/{roleId}/data-scope")
  public ApiResponse<AdminRoleDataScopeResponse> saveDataScope(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID roleId,
      @Valid @RequestBody AdminRoleDataScopeRequest request
  ) {
    return ApiResponse.success(adminRbacService.saveDataScope(principal.id(), roleId, request));
  }
}
