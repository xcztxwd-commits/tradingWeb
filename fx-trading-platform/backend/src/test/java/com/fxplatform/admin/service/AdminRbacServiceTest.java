package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.dto.request.AdminAssignUserRoleRequest;
import com.fxplatform.admin.dto.request.AdminRoleDataScopeRequest;
import com.fxplatform.admin.dto.request.AdminRoleMenuPermissionRequest;
import com.fxplatform.admin.dto.request.AdminRoleRequest;
import com.fxplatform.admin.dto.request.AdminMenuRequest;
import com.fxplatform.admin.entity.AdminMenuEntity;
import com.fxplatform.admin.entity.AdminRoleDataScopeEntity;
import com.fxplatform.admin.entity.AdminRoleEntity;
import com.fxplatform.admin.entity.AdminRoleMenuPermissionEntity;
import com.fxplatform.admin.entity.AdminUserRoleEntity;
import com.fxplatform.admin.repository.AdminMenuRepository;
import com.fxplatform.admin.repository.AdminDepartmentRepository;
import com.fxplatform.admin.repository.AdminPostRepository;
import com.fxplatform.admin.repository.AdminRoleDataScopeRepository;
import com.fxplatform.admin.repository.AdminRoleMenuPermissionRepository;
import com.fxplatform.admin.repository.AdminRoleRepository;
import com.fxplatform.admin.repository.AdminUserRoleRepository;
import com.fxplatform.audit.service.AuditLogService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AdminRbacServiceTest {

  private final AdminRoleRepository roleRepository = Mockito.mock(AdminRoleRepository.class);
  private final AdminMenuRepository menuRepository = Mockito.mock(AdminMenuRepository.class);
  private final AdminRoleMenuPermissionRepository roleMenuPermissionRepository =
      Mockito.mock(AdminRoleMenuPermissionRepository.class);
  private final AdminUserRoleRepository userRoleRepository = Mockito.mock(AdminUserRoleRepository.class);
  private final AdminRoleDataScopeRepository dataScopeRepository = Mockito.mock(AdminRoleDataScopeRepository.class);
  private final AdminDepartmentRepository departmentRepository = Mockito.mock(AdminDepartmentRepository.class);
  private final AdminPostRepository postRepository = Mockito.mock(AdminPostRepository.class);
  private final AuditLogService auditLogService = Mockito.mock(AuditLogService.class);

  @Test
  void rolesReturnPagedDtosWithBackendFiltersAndSorting() {
    AdminRoleEntity entity = new AdminRoleEntity();
    entity.setId(UUID.randomUUID());
    entity.setRoleName("运营主管");
    entity.setRoleCode("ops_manager");
    entity.setEnabled(true);
    entity.setSortOrder(10);

    Page<AdminRoleEntity> repositoryPage = Page.of(2, 15);
    repositoryPage.setRecords(List.of(entity));
    repositoryPage.setTotal(31);
    when(roleRepository.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(repositoryPage);

    AdminRbacService service = service();
    var response = service.roles(AdminFeaturePageQuery.from(
        1,
        15,
        "name",
        "desc",
        Map.of("filter.name", "运营", "filter.enabled", "true")));

    assertThat(response.page()).isEqualTo(1);
    assertThat(response.size()).isEqualTo(15);
    assertThat(response.total()).isEqualTo(31);
    assertThat(response.items()).singleElement().satisfies(role -> {
      assertThat(role.name()).isEqualTo("运营主管");
      assertThat(role.code()).isEqualTo("ops_manager");
    });

    ArgumentCaptor<Page<AdminRoleEntity>> pageCaptor = ArgumentCaptor.forClass(Page.class);
    ArgumentCaptor<QueryWrapper<AdminRoleEntity>> queryCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
    verify(roleRepository).selectPage(pageCaptor.capture(), queryCaptor.capture());
    assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(2);
    assertThat(pageCaptor.getValue().getSize()).isEqualTo(15);
    assertThat(queryCaptor.getValue().getSqlSegment())
        .contains("role_name")
        .contains("enabled")
        .contains("ORDER BY role_name DESC");
  }

  @Test
  void createsRoleMenuButtonPermissionUserRoleAndDataScope() {
    UUID actorUserId = UUID.randomUUID();
    UUID roleId = UUID.randomUUID();
    UUID menuId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    when(roleRepository.save(any(AdminRoleEntity.class))).thenAnswer(invocation -> {
      AdminRoleEntity role = invocation.getArgument(0);
      role.setId(roleId);
      return role;
    });
    when(menuRepository.save(any(AdminMenuEntity.class))).thenAnswer(invocation -> {
      AdminMenuEntity menu = invocation.getArgument(0);
      menu.setId(menuId);
      return menu;
    });
    when(roleRepository.findById(roleId)).thenReturn(Optional.of(new AdminRoleEntity()));
    when(menuRepository.findById(menuId)).thenReturn(Optional.of(new AdminMenuEntity()));
    when(roleMenuPermissionRepository.save(any(AdminRoleMenuPermissionEntity.class))).thenAnswer(invocation -> {
      AdminRoleMenuPermissionEntity permission = invocation.getArgument(0);
      permission.setId(UUID.randomUUID());
      return permission;
    });
    when(userRoleRepository.save(any(AdminUserRoleEntity.class))).thenAnswer(invocation -> {
      AdminUserRoleEntity userRole = invocation.getArgument(0);
      userRole.setId(UUID.randomUUID());
      return userRole;
    });
    when(dataScopeRepository.save(any(AdminRoleDataScopeEntity.class))).thenAnswer(invocation -> {
      AdminRoleDataScopeEntity scope = invocation.getArgument(0);
      scope.setId(UUID.randomUUID());
      return scope;
    });

    AdminRbacService service = service();

    var role = service.createRole(actorUserId, new AdminRoleRequest("运营主管", "ops_manager", true, 10, "运营后台"));
    var menu = service.createMenu(actorUserId, new AdminMenuRequest(
        null,
        "充值订单",
        "finance:recharge",
        "/finance/recharge-orders",
        "FeatureCrudPage",
        "MENU",
        true,
        20));
    var permission = service.saveRoleMenuPermission(actorUserId, role.id(), new AdminRoleMenuPermissionRequest(
        menu.id(),
        List.of("view", "review", "export")));
    var userRole = service.assignUserRole(actorUserId, new AdminAssignUserRoleRequest(userId, role.id()));
    var scope = service.saveDataScope(actorUserId, role.id(), new AdminRoleDataScopeRequest(
        "DEPARTMENT_AND_CHILDREN",
        List.of(UUID.randomUUID())));

    assertThat(role.id()).isEqualTo(roleId);
    assertThat(menu.id()).isEqualTo(menuId);
    assertThat(permission.buttons()).containsExactly("view", "review", "export");
    assertThat(userRole.userId()).isEqualTo(userId);
    assertThat(scope.scopeType()).isEqualTo("DEPARTMENT_AND_CHILDREN");

    ArgumentCaptor<AdminRoleMenuPermissionEntity> permissionCaptor =
        ArgumentCaptor.forClass(AdminRoleMenuPermissionEntity.class);
    verify(roleMenuPermissionRepository).save(permissionCaptor.capture());
    assertThat(permissionCaptor.getValue().getButtons()).contains("review");
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_RBAC_ROLE_CREATE"), eq("ADMIN_ROLE"), eq(roleId.toString()), any());
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_RBAC_DATA_SCOPE_SAVE"), eq("ADMIN_ROLE"), eq(roleId.toString()), any());
  }

  @Test
  void updatesAndDeletesRoleWithAuditReason() {
    UUID actorUserId = UUID.randomUUID();
    UUID roleId = UUID.randomUUID();
    AdminRoleEntity existing = new AdminRoleEntity();
    existing.setId(roleId);
    existing.setRoleName("旧角色");
    existing.setRoleCode("old_role");
    existing.setEnabled(true);
    existing.setSortOrder(1);

    when(roleRepository.findById(roleId)).thenReturn(Optional.of(existing));
    when(roleRepository.save(any(AdminRoleEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

    AdminRbacService service = service();
    var updated = service.updateRole(actorUserId, roleId, new AdminRoleRequest("新角色", "new_role", false, 3, "更新"));
    service.deleteRole(actorUserId, roleId, "测试删除");

    assertThat(updated.name()).isEqualTo("新角色");
    assertThat(updated.code()).isEqualTo("new_role");
    assertThat(updated.enabled()).isFalse();

    verify(roleRepository).deleteById(roleId);
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_RBAC_ROLE_UPDATE"), eq("ADMIN_ROLE"), eq(roleId.toString()), any());
    verify(auditLogService).record(eq(actorUserId), eq("ADMIN_RBAC_ROLE_DELETE"), eq("ADMIN_ROLE"), eq(roleId.toString()), any());
  }

  private AdminRbacService service() {
    return new AdminRbacService(
        new AdminRbacCatalogService(roleRepository, menuRepository, departmentRepository, postRepository),
        new AdminRbacResourceManagementService(
            roleRepository,
            menuRepository,
            roleMenuPermissionRepository,
            userRoleRepository,
            dataScopeRepository,
            departmentRepository,
            postRepository,
            auditLogService),
        new AdminRolePermissionService(
            roleRepository,
            menuRepository,
            roleMenuPermissionRepository,
            dataScopeRepository,
            auditLogService),
        new AdminUserRoleAssignmentService(roleRepository, userRoleRepository, auditLogService));
  }
}
