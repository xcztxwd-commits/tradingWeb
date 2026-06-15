package com.fxplatform.admin.service;

import static com.fxplatform.admin.service.AdminFeatureCatalogDsl.*;

import com.fxplatform.admin.dto.response.AdminFeaturePageResponse;
import java.util.List;

final class AdminPermissionFeaturePages {

  private AdminPermissionFeaturePages() {
  }

  static List<AdminFeaturePageResponse> pages() {
    return List.of(
        page(
            "system-users",
            "用户管理",
            "权限",
            fields(
                field("department", "搜索部门", "input"),
                field("account", "账户", "input"),
                field("phone", "手机", "input"),
                field("email", "邮箱", "input"),
                select("status", "状态", "正常", "停用"),
                field("registeredAt", "注册时间", "dateRange")),
            columns(
                col("avatar", "头像"),
                col("account", "账户"),
                col("nickname", "昵称"),
                col("phone", "手机"),
                col("email", "邮箱"),
                col("status", "状态"),
                col("registeredAt", "注册时间")),
            actions(action("create", "新增", "modal"), action("delete", "删除", "confirm"),
                action("import", "导入", "upload"), action("export", "导出", "download")),
            actions(action("edit", "编辑", "modal"), action("delete", "删除", "confirm"), action("more", "更多", "menu")),
            rows(
                row("id", "admin-1000", "avatar", "admin", "account", "superAdmin", "nickname", "admin",
                    "phone", "13888888888", "email", "admin@adminmine.com", "status", "正常", "registeredAt", "2024-01-15 18:18:46"),
                row("id", "user-1025", "avatar", "user", "account", "linshi", "nickname", "临时",
                    "phone", "", "email", "", "status", "正常", "registeredAt", "2026-02-13 02:17:46"))),

        page(
            "system-roles",
            "角色管理",
            "权限",
            fields(
                field("roleName", "角色名称", "input"),
                field("roleCode", "角色标识", "input"),
                select("status", "状态", "正常", "停用"),
                field("createdAt", "创建时间", "dateRange")),
            columns(
                col("roleName", "角色名称"),
                col("roleCode", "角色标识"),
                sortableCol("sort", "排序"),
                col("status", "状态"),
                col("createdAt", "创建时间")),
            actions(action("create", "新增", "modal"), action("delete", "删除", "confirm")),
            actions(action("menu-permission", "菜单权限", "drawer"), action("data-permission", "数据权限", "modal"),
                action("edit", "编辑", "modal"), action("delete", "删除", "confirm")),
            rows(
                row("id", "role-super", "roleName", "超级管理员（创始人）", "roleCode", "superAdmin",
                    "sort", 0, "status", "正常", "createdAt", "2024-01-15 18:18:46"),
                row("id", "role-linshi", "roleName", "linshi", "roleCode", "linshi",
                    "sort", 1, "status", "正常", "createdAt", "2025-07-27 12:01:26"))),

        page(
            "system-departments",
            "部门管理",
            "权限",
            fields(
                field("departmentName", "部门名称", "input"),
                field("leader", "负责人", "input"),
                field("phone", "手机", "input"),
                select("status", "状态", "正常", "停用"),
                field("createdAt", "创建时间", "dateRange")),
            columns(
                col("departmentName", "部门名称"),
                col("leader", "负责人"),
                col("phone", "手机"),
                sortableCol("sort", "排序"),
                col("status", "状态"),
                col("createdAt", "创建时间")),
            actions(action("create", "新增", "modal"), action("delete", "删除", "confirm"), action("expand", "展开", "toggle")),
            actions(action("leader-list", "领导列表", "modal"), action("edit", "编辑", "modal"), action("delete", "删除", "confirm")),
            rows(row("id", "dept-main", "departmentName", "总部", "leader", "总部", "phone", "16888888888",
                "sort", 0, "status", "正常", "createdAt", "2024-01-15 18:18:46"))),

        page(
            "system-menus",
            "菜单管理",
            "权限",
            fields(
                field("menuName", "菜单名称", "input"),
                field("menuCode", "菜单标识", "input"),
                select("hidden", "隐藏", "是", "否"),
                select("status", "状态", "正常", "停用"),
                field("createdAt", "创建时间", "dateRange")),
            columns(
                col("menuName", "菜单名称"),
                col("menuType", "菜单类型"),
                col("icon", "图标"),
                col("menuCode", "菜单标识"),
                col("path", "路由地址"),
                col("component", "视图组件"),
                sortableCol("sort", "排序"),
                col("hidden", "隐藏"),
                col("status", "状态"),
                col("createdAt", "创建时间")),
            actions(action("create", "新增", "modal"), action("delete", "删除", "confirm"), action("expand", "展开", "toggle")),
            actions(action("create-child", "新增", "modal"), action("edit", "编辑", "modal"), action("delete", "删除", "confirm")),
            rows(
                row("id", "menu-product", "menuName", "产品管理", "menuType", "菜单", "icon", "list",
                    "menuCode", "product", "path", "product", "component", "", "sort", 1, "hidden", "否", "status", "正常", "createdAt", "2024-01-16 23:45:12"),
                row("id", "menu-finance", "menuName", "财务管理", "menuType", "菜单", "icon", "wallet",
                    "menuCode", "finance", "path", "finance", "component", "", "sort", 1, "hidden", "否", "status", "正常", "createdAt", "2024-01-18 09:45:28"))),

        page(
            "system-posts",
            "岗位管理",
            "权限",
            fields(
                field("postName", "岗位名称", "input"),
                field("postCode", "岗位标识", "input"),
                select("status", "状态", "正常", "停用"),
                field("createdAt", "创建时间", "dateRange")),
            columns(
                col("postName", "岗位名称"),
                col("postCode", "岗位标识"),
                sortableCol("sort", "排序"),
                col("status", "状态"),
                col("createdAt", "创建时间")),
            actions(action("create", "新增", "modal"), action("delete", "删除", "confirm")),
            actions(action("edit", "编辑", "modal"), action("delete", "删除", "confirm")),
            rows(row("id", "post-leader", "postName", "组长", "postCode", "zuzhang", "sort", 1,
                "status", "正常", "createdAt", "2025-12-07 11:27:29")))
    );
  }

}
