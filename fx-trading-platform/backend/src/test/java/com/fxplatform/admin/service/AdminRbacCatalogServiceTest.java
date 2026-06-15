package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fxplatform.admin.entity.AdminDepartmentEntity;
import com.fxplatform.admin.entity.AdminMenuEntity;
import com.fxplatform.admin.entity.AdminPostEntity;
import com.fxplatform.admin.entity.AdminRoleEntity;
import com.fxplatform.admin.repository.AdminDepartmentRepository;
import com.fxplatform.admin.repository.AdminMenuRepository;
import com.fxplatform.admin.repository.AdminPostRepository;
import com.fxplatform.admin.repository.AdminRoleRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AdminRbacCatalogServiceTest {

  private final AdminRoleRepository roleRepository = Mockito.mock(AdminRoleRepository.class);
  private final AdminMenuRepository menuRepository = Mockito.mock(AdminMenuRepository.class);
  private final AdminDepartmentRepository departmentRepository = Mockito.mock(AdminDepartmentRepository.class);
  private final AdminPostRepository postRepository = Mockito.mock(AdminPostRepository.class);

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

    var response = service().roles(AdminFeaturePageQuery.from(
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

    ArgumentCaptor<QueryWrapper<AdminRoleEntity>> queryCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
    verify(roleRepository).selectPage(any(Page.class), queryCaptor.capture());
    assertThat(queryCaptor.getValue().getSqlSegment())
        .contains("role_name")
        .contains("enabled")
        .contains("ORDER BY role_name DESC");
  }

  @Test
  void menusReturnPermissionDtosWithBackendFiltersAndSorting() {
    AdminMenuEntity entity = new AdminMenuEntity();
    entity.setId(UUID.randomUUID());
    entity.setMenuName("充值审核");
    entity.setPermissionKey("finance:recharge:review");
    entity.setPath("/finance/recharge-orders");
    entity.setComponent("FeatureCrudPage");
    entity.setMenuType("BUTTON");
    entity.setEnabled(true);
    entity.setSortOrder(20);

    Page<AdminMenuEntity> repositoryPage = Page.of(1, 20);
    repositoryPage.setRecords(List.of(entity));
    repositoryPage.setTotal(1);
    when(menuRepository.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(repositoryPage);

    var response = service().menus(AdminFeaturePageQuery.from(
        0,
        20,
        "permissionKey",
        "asc",
        Map.of("filter.permissionKey", "finance:recharge", "filter.menuType", "BUTTON")));

    assertThat(response.items()).singleElement().satisfies(permission -> {
      assertThat(permission.name()).isEqualTo("充值审核");
      assertThat(permission.permissionKey()).isEqualTo("finance:recharge:review");
      assertThat(permission.menuType()).isEqualTo("BUTTON");
    });

    ArgumentCaptor<QueryWrapper<AdminMenuEntity>> queryCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
    verify(menuRepository).selectPage(any(Page.class), queryCaptor.capture());
    assertThat(queryCaptor.getValue().getSqlSegment())
        .contains("permission_key")
        .contains("menu_type")
        .contains("ORDER BY permission_key ASC");
  }

  private AdminRbacCatalogService service() {
    return new AdminRbacCatalogService(roleRepository, menuRepository, departmentRepository, postRepository);
  }
}
