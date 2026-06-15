package com.fxplatform.admin.service;

import static com.fxplatform.admin.service.AdminFeatureCatalogDsl.*;

import com.fxplatform.admin.dto.response.AdminFeaturePageResponse;
import java.util.List;

final class AdminContentFeaturePages {

  private AdminContentFeaturePages() {
  }

  static List<AdminFeaturePageResponse> pages() {
    return List.of(
        page(
            "verification-codes",
            "验证码发送记录",
            "日志",
            fields(
                field("code", "验证码", "input"),
                field("account", "账号", "input"),
                field("content", "验证码内容", "input"),
                field("createdAt", "创建时间", "dateRange")),
            columns(
                col("code", "验证码"),
                col("account", "账号"),
                col("content", "验证码内容"),
                col("createdAt", "创建时间"),
                col("updatedAt", "更新时间")),
            actions(action("delete", "删除", "confirm")),
            actions(action("delete", "删除", "confirm")),
            rows(row("id", "code-642743", "code", "642743", "account", "hky.ndc@gmail.com",
                "content", "認証コードは642743です。5...", "createdAt", "2026-05-26 05:22:33", "updatedAt", "2026-05-26 05:22:33"))),

        page(
            "request-logs",
            "请求日志",
            "日志",
            fields(
                field("requestUrl", "请求URL", "input"),
                field("requestType", "请求类型", "input"),
                field("requestIp", "请求IP", "input"),
                field("userName", "用户名", "input"),
                field("createdAt", "创建时间", "dateRange")),
            columns(
                col("requestUrl", "请求URL"),
                col("requestType", "请求类型"),
                col("requestIp", "请求IP"),
                col("requestUser", "请求用户"),
                col("userName", "用户名")),
            actions(action("delete", "删除", "confirm")),
            actions(action("delete", "删除", "confirm")),
            rows()),

        page(
            "notices",
            "公告列表",
            "内容",
            fields(
                field("sort", "排序", "number"),
                field("title", "标题", "input"),
                field("createdAt", "创建时间", "dateRange"),
                select("status", "状态", "正常", "停用")),
            columns(sortableCol("sort", "排序"), col("title", "标题"), col("status", "状态")),
            actions(action("create", "新增", "modal"), action("delete", "删除", "confirm")),
            actions(action("edit", "编辑", "modal"), action("delete", "删除", "confirm")),
            rows(row("id", "notice-1", "sort", 1, "title", "Notice", "status", "正常"))),

        page(
            "news",
            "新闻列表",
            "内容",
            fields(field("title", "标题", "input"), select("status", "状态", "正常", "停用")),
            columns(
                col("id", "主键ID"),
                col("title", "标题"),
                col("summary", "简介"),
                col("image", "图片"),
                col("link", "链接"),
                sortableCol("sort", "排序"),
                sortableCol("status", "状态")),
            actions(action("create", "新增", "modal"), action("delete", "删除", "confirm")),
            actions(action("edit", "编辑", "modal"), action("delete", "删除", "confirm")),
            rows(
                row("id", 587, "title", "市况", "summary", "ドル、160円台前半＝...", "image", "查看图片",
                    "link", "https://news.yahoo...", "sort", 309, "status", "正常"),
                row("id", 586, "title", "今日の為替", "summary", "上値に慎重ながらも...", "image", "查看图片",
                    "link", "https://fx.minkabu...", "sort", 310, "status", "正常"))),

        page(
            "member-notices",
            "通知表",
            "内容",
            fields(
                field("userId", "用户ID", "input"),
                field("creator", "创建人", "input"),
                field("createdAt", "创建时间", "dateRange")),
            columns(
                col("userId", "用户ID"),
                col("content", "内容"),
                col("creator", "创建人"),
                sortableCol("createdAt", "创建时间"),
                col("updatedAt", "更新时间")),
            actions(action("create", "新增", "modal"), action("delete", "删除", "confirm")),
            actions(action("edit", "编辑", "modal"), action("delete", "删除", "confirm")),
            rows(row("id", "member-notice-1", "userId", 295, "content", "<p>尊敬するユーザーこんに...</p>",
                "creator", 1000, "createdAt", "2026-06-08 13:10:48", "updatedAt", "2026-06-08 13:10:48")))
    );
  }

}
