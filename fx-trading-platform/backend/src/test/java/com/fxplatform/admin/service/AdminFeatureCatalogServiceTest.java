package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminFeatureCatalogServiceTest {

  @Test
  void catalogCoversScreenshotBackofficePagesAndActions() {
    AdminFeatureCatalogService service = new AdminFeatureCatalogService();

    var pages = service.pages();

    assertThat(pages).extracting("key")
        .contains(
            "system-users",
            "system-roles",
            "system-departments",
            "system-menus",
            "system-posts",
            "products",
            "product-categories",
            "price-schedules",
            "finance-ledger",
            "recharge-orders",
            "withdrawal-orders",
            "payment-methods",
            "members",
            "member-payment-accounts",
            "order-history",
            "verification-codes",
            "request-logs",
            "notices",
            "news",
            "member-notices",
            "settings-site",
            "settings-upload",
            "settings-sms",
            "settings-email",
            "settings-footer");

    var recharge = service.page("recharge-orders");
    assertThat(recharge.fields()).extracting("label")
        .contains("用户uid", "订单号", "创建时间", "状态", "充值类型", "钱包地址", "地址网络");
    assertThat(recharge.columns()).extracting("label")
        .contains("用户uid", "订单号", "数量", "实到", "状态", "费率", "手续费", "凭证", "充值类型");
    assertThat(recharge.rowActions()).extracting("label")
        .contains("审核", "编辑", "删除");
    assertThat(recharge.toolbarActions()).extracting("label")
        .contains("新增", "删除", "导出");

    var role = service.page("system-roles");
    assertThat(role.rowActions()).extracting("label")
        .contains("菜单权限", "数据权限", "编辑", "删除");
    assertThat(role.columns()).extracting("label")
        .contains("角色名称", "角色标识", "排序", "状态", "创建时间");
  }

  @Test
  void actionExecutionReturnsAuditableResult() {
    AdminFeatureCatalogService service = new AdminFeatureCatalogService();

    var result = service.performAction("recharge-orders", "review", "order-1", "通过审核");

    assertThat(result.pageKey()).isEqualTo("recharge-orders");
    assertThat(result.action()).isEqualTo("review");
    assertThat(result.success()).isTrue();
    assertThat(result.message()).contains("充值订单", "审核");
  }
}
