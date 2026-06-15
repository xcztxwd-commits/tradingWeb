package com.fxplatform.admin.service;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.dto.response.AdminDepartmentResponse;
import com.fxplatform.admin.dto.response.AdminMenuResponse;
import com.fxplatform.admin.dto.response.AdminPostResponse;
import com.fxplatform.admin.dto.response.AdminRoleResponse;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminRbacCatalogService {

  private static final Map<String, String> ROLE_SORT_COLUMNS = Map.of(
      "name", "role_name",
      "code", "role_code",
      "enabled", "enabled",
      "status", "enabled",
      "sortOrder", "sort_order",
      "createdAt", "created_at");
  private static final Map<String, String> MENU_SORT_COLUMNS = Map.of(
      "name", "menu_name",
      "permissionKey", "permission_key",
      "path", "path",
      "menuType", "menu_type",
      "enabled", "enabled",
      "status", "enabled",
      "sortOrder", "sort_order",
      "createdAt", "created_at");
  private static final Map<String, String> DEPARTMENT_SORT_COLUMNS = Map.of(
      "name", "department_name",
      "leader", "leader",
      "phone", "phone",
      "enabled", "enabled",
      "status", "enabled",
      "sortOrder", "sort_order",
      "createdAt", "created_at");
  private static final Map<String, String> POST_SORT_COLUMNS = Map.of(
      "name", "post_name",
      "code", "post_code",
      "enabled", "enabled",
      "status", "enabled",
      "sortOrder", "sort_order",
      "createdAt", "created_at");

  private final AdminRoleRepository roleRepository;
  private final AdminMenuRepository menuRepository;
  private final AdminDepartmentRepository departmentRepository;
  private final AdminPostRepository postRepository;

  public AdminPageResponse<AdminRoleResponse> roles(AdminFeaturePageQuery query) {
    Page<AdminRoleEntity> page = roleRepository.selectPage(
        AdminPageRequests.page(query.page(), query.size()),
        roleQuery(query));
    return AdminPageResponse.from(page.convert(AdminRoleResponse::from));
  }

  public List<AdminRoleResponse> roles() {
    return roles(AdminFeaturePageQuery.from(0, 500, "sortOrder", "asc", Map.of())).items();
  }

  public AdminPageResponse<AdminMenuResponse> menus(AdminFeaturePageQuery query) {
    Page<AdminMenuEntity> page = menuRepository.selectPage(
        AdminPageRequests.page(query.page(), query.size()),
        menuQuery(query));
    return AdminPageResponse.from(page.convert(AdminMenuResponse::from));
  }

  public List<AdminMenuResponse> menus() {
    return menus(AdminFeaturePageQuery.from(0, 500, "sortOrder", "asc", Map.of())).items();
  }

  public AdminPageResponse<AdminDepartmentResponse> departments(AdminFeaturePageQuery query) {
    Page<AdminDepartmentEntity> page = departmentRepository.selectPage(
        AdminPageRequests.page(query.page(), query.size()),
        departmentQuery(query));
    return AdminPageResponse.from(page.convert(AdminDepartmentResponse::from));
  }

  public List<AdminDepartmentResponse> departments() {
    return departments(AdminFeaturePageQuery.from(0, 500, "sortOrder", "asc", Map.of())).items();
  }

  public AdminPageResponse<AdminPostResponse> posts(AdminFeaturePageQuery query) {
    Page<AdminPostEntity> page = postRepository.selectPage(
        AdminPageRequests.page(query.page(), query.size()),
        postQuery(query));
    return AdminPageResponse.from(page.convert(AdminPostResponse::from));
  }

  public List<AdminPostResponse> posts() {
    return posts(AdminFeaturePageQuery.from(0, 500, "sortOrder", "asc", Map.of())).items();
  }

  private QueryWrapper<AdminRoleEntity> roleQuery(AdminFeaturePageQuery query) {
    QueryWrapper<AdminRoleEntity> wrapper = new QueryWrapper<>();
    likeIfPresent(wrapper, "role_name", AdminRbacServiceSupport.firstNotBlank(query.filter("name"), query.filter("roleName")));
    likeIfPresent(wrapper, "role_code", AdminRbacServiceSupport.firstNotBlank(query.filter("code"), query.filter("roleCode")));
    eqBooleanIfPresent(wrapper, AdminRbacServiceSupport.firstNotBlank(query.filter("enabled"), query.filter("status")));
    applyOrder(wrapper, query, ROLE_SORT_COLUMNS, "sort_order");
    return wrapper;
  }

  private QueryWrapper<AdminMenuEntity> menuQuery(AdminFeaturePageQuery query) {
    QueryWrapper<AdminMenuEntity> wrapper = new QueryWrapper<>();
    likeIfPresent(wrapper, "menu_name", AdminRbacServiceSupport.firstNotBlank(query.filter("name"), query.filter("menuName")));
    likeIfPresent(wrapper, "permission_key", query.filter("permissionKey"));
    likeIfPresent(wrapper, "path", query.filter("path"));
    eqIfPresent(wrapper, "menu_type", query.filter("menuType"));
    eqBooleanIfPresent(wrapper, AdminRbacServiceSupport.firstNotBlank(query.filter("enabled"), query.filter("status")));
    applyOrder(wrapper, query, MENU_SORT_COLUMNS, "sort_order");
    return wrapper;
  }

  private QueryWrapper<AdminDepartmentEntity> departmentQuery(AdminFeaturePageQuery query) {
    QueryWrapper<AdminDepartmentEntity> wrapper = new QueryWrapper<>();
    likeIfPresent(wrapper, "department_name", AdminRbacServiceSupport.firstNotBlank(query.filter("name"), query.filter("departmentName")));
    likeIfPresent(wrapper, "leader", query.filter("leader"));
    likeIfPresent(wrapper, "phone", query.filter("phone"));
    eqBooleanIfPresent(wrapper, AdminRbacServiceSupport.firstNotBlank(query.filter("enabled"), query.filter("status")));
    applyOrder(wrapper, query, DEPARTMENT_SORT_COLUMNS, "sort_order");
    return wrapper;
  }

  private QueryWrapper<AdminPostEntity> postQuery(AdminFeaturePageQuery query) {
    QueryWrapper<AdminPostEntity> wrapper = new QueryWrapper<>();
    likeIfPresent(wrapper, "post_name", AdminRbacServiceSupport.firstNotBlank(query.filter("name"), query.filter("postName")));
    likeIfPresent(wrapper, "post_code", AdminRbacServiceSupport.firstNotBlank(query.filter("code"), query.filter("postCode")));
    eqBooleanIfPresent(wrapper, AdminRbacServiceSupport.firstNotBlank(query.filter("enabled"), query.filter("status")));
    applyOrder(wrapper, query, POST_SORT_COLUMNS, "sort_order");
    return wrapper;
  }

  private void applyOrder(
      QueryWrapper<?> wrapper,
      AdminFeaturePageQuery query,
      Map<String, String> sortColumns,
      String defaultColumn
  ) {
    String column = MapUtil.getStr(sortColumns, query.sortField(), defaultColumn);
    wrapper.orderBy(true, query.ascending(), column);
  }

  private void likeIfPresent(QueryWrapper<?> wrapper, String column, String value) {
    if (StrUtil.isNotBlank(value)) {
      wrapper.like(column, value);
    }
  }

  private void eqIfPresent(QueryWrapper<?> wrapper, String column, String value) {
    if (StrUtil.isNotBlank(value)) {
      wrapper.eq(column, value);
    }
  }

  private void eqBooleanIfPresent(QueryWrapper<?> wrapper, String value) {
    if (StrUtil.isBlank(value)) {
      return;
    }
    if (StrUtil.equalsAnyIgnoreCase(value, "true", "1", "姝ｅ父", "鍚敤", "enabled", "active")) {
      wrapper.eq("enabled", true);
      return;
    }
    if (StrUtil.equalsAnyIgnoreCase(value, "false", "0", "鍋滅敤", "绂佺敤", "disabled", "inactive")) {
      wrapper.eq("enabled", false);
    }
  }
}
