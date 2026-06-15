package com.fxplatform.admin.service;

import static com.fxplatform.admin.service.AdminFeatureCatalogDsl.*;

import com.fxplatform.admin.dto.response.AdminFeaturePageResponse;
import java.util.List;

final class AdminProductFeaturePages {

  private AdminProductFeaturePages() {
  }

  static List<AdminFeaturePageResponse> pages() {
    return List.of(
        page(
            "products",
            "产品列表",
            "产品管理",
            fields(
                select("category", "分类", "Metal", "Forex", "Crypto", "Oil", "CFD"),
                field("productName", "产品名称", "input"),
                select("isConfigured", "是否设置", "已设置", "未设置"),
                select("status", "状态", "正常", "停用")),
            columns(
                col("category", "分类"),
                col("productName", "产品名称"),
                col("productCode", "产品代码"),
                col("productCode2", "产品代码2"),
                col("increment", "增量"),
                col("count", "计数"),
                col("settingTime", "设置时间"),
                col("isConfigured", "是否设置"),
                col("pricePrecision", "价格精度"),
                col("latestPrice", "最新价格"),
                col("multiples", "倍数"),
                col("defaultMultiple", "默认倍数"),
                col("hand", "每手"),
                col("rate", "费率")),
            actions(action("create", "新增", "modal"), action("delete", "删除", "confirm")),
            actions(action("risk", "风控", "modal"), action("edit", "编辑", "modal"), action("delete", "删除", "confirm")),
            rows(
                row("id", "product-xauusd", "category", "Metal", "productName", "XAUUSD", "productCode", "XAUUSD",
                    "productCode2", "55", "increment", "0.130000", "count", 0, "settingTime", "2025-10-08", "isConfigured", "0",
                    "pricePrecision", 2, "latestPrice", "0.00", "multiples", "100,200,300,400,500", "defaultMultiple", 100, "hand", 1, "rate", "1.0000"),
                row("id", "product-eurusd", "category", "Forex", "productName", "EURUSD", "productCode", "EURUSD",
                    "productCode2", "1", "increment", "0.000050", "count", 0, "settingTime", "2025-08-01", "isConfigured", "0",
                    "pricePrecision", 5, "latestPrice", "0.00", "multiples", "100,200,300", "defaultMultiple", 100, "hand", 10, "rate", "0.0100"))),

        page(
            "product-categories",
            "产品分类",
            "产品管理",
            fields(field("name", "名称", "input")),
            columns(col("name", "名称")),
            actions(action("create", "新增", "modal"), action("delete", "删除", "confirm")),
            actions(action("edit", "编辑", "modal"), action("delete", "删除", "confirm")),
            rows(row("id", "cat-crypto", "name", "Crypto"), row("id", "cat-forex", "name", "Forex"),
                row("id", "cat-metal", "name", "Metal"), row("id", "cat-oil", "name", "Oil"))),

        page(
            "price-schedules",
            "涨跌设置",
            "产品管理",
            fields(
                field("startAt", "开始时间", "dateRange"),
                field("endAt", "截止时间", "dateRange"),
                field("productId", "产品ID", "input"),
                select("riseFall", "涨跌", "涨", "跌"),
                select("status", "状态", "待生成", "已生成")),
            columns(
                col("startAt", "开始时间"),
                col("startPrice", "开始价格"),
                col("endAt", "截止时间"),
                col("endPrice", "截止价格"),
                col("productId", "产品ID"),
                col("riseFall", "涨跌"),
                col("status", "状态")),
            actions(action("create", "新增", "modal"), action("delete", "删除", "confirm")),
            actions(action("edit", "编辑", "modal"), action("delete", "删除", "confirm")),
            rows(
                row("id", "schedule-1", "startAt", "2024-12-15 13:10:00", "startPrice", "12.00",
                    "endAt", "2024-12-15 13:20:00", "endPrice", "13.00", "productId", 2467, "riseFall", "涨", "status", "已生成"),
                row("id", "schedule-2", "startAt", "2024-12-14 13:00:00", "startPrice", "20.00",
                    "endAt", "2024-12-14 13:10:00", "endPrice", "21.00", "productId", 2467, "riseFall", "涨", "status", "待生成")))
    );
  }

}
