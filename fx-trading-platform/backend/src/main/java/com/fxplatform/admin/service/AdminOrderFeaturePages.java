package com.fxplatform.admin.service;

import static com.fxplatform.admin.service.AdminFeatureCatalogDsl.*;

import com.fxplatform.admin.dto.response.AdminFeaturePageResponse;
import java.util.List;

final class AdminOrderFeaturePages {

  private AdminOrderFeaturePages() {
  }

  static List<AdminFeaturePageResponse> pages() {
    return List.of(
        page(
            "order-history",
            "挂单/持仓/历史",
            "订单管理",
            fields(
                field("uid", "UID", "input"),
                field("product", "产品", "input"),
                select("direction", "方向", "涨", "跌"),
                select("status", "状态", "已完成", "持仓中", "已撤销"),
                field("createdAt", "创建时间", "dateRange"),
                select("type", "类型", "持仓", "挂单", "历史")),
            columns(
                col("uid", "UID"),
                col("product", "产品"),
                col("lots", "手数"),
                col("multiple", "倍数"),
                col("openPrice", "开仓价格"),
                col("margin", "保证金"),
                col("fee", "手续费"),
                col("direction", "方向"),
                col("status", "状态"),
                col("openAt", "开仓时间"),
                col("completedAt", "完成时间"),
                col("profitLoss", "盈亏"),
                col("takeProfit", "止盈")),
            actions(action("create", "新增", "modal"), action("delete", "删除", "confirm"), action("export", "导出", "download")),
            actions(action("close-position", "平仓", "confirm"), action("pending-fill", "挂单成交", "confirm"),
                action("cancel", "撤销", "confirm"), action("edit", "编辑", "modal"), action("delete", "删除", "confirm")),
            rows(
                row("id", "trade-1", "uid", 296, "product", "USDJPY", "lots", "0.23", "multiple", 100,
                    "openPrice", "160.17", "margin", "368.40", "fee", "0.23", "direction", "涨", "status", "已完成",
                    "openAt", "2026-06-09", "completedAt", "2026-06-09", "profitLoss", "30.5900", "takeProfit", "0.00")))
    );
  }

}
