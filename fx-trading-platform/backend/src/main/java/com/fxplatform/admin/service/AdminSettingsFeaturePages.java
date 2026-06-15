package com.fxplatform.admin.service;

import static com.fxplatform.admin.service.AdminFeatureCatalogDsl.*;

import com.fxplatform.admin.dto.response.AdminFeatureFieldResponse;
import com.fxplatform.admin.dto.response.AdminFeaturePageResponse;
import java.util.List;

final class AdminSettingsFeaturePages {

  private AdminSettingsFeaturePages() {
  }

  static List<AdminFeaturePageResponse> pages() {
    return List.of(
        settingsPage("settings-site", "站点配置", fields(
            field("siteName", "网站名称", "input"),
            field("siteKeywords", "网站关键字", "input"),
            field("siteDesc", "网站描述", "textarea"),
            field("siteCopyright", "版权信息", "textarea"),
            field("siteRecordNumber", "网站备案号", "input"),
            field("pcUrl", "pc端邀请链接", "input"),
            field("wsUrl", "ws链接地址", "input"),
            field("czRate", "充值费率", "number"),
            field("isSdxs", "手动休市", "number"),
            field("pcRate", "平仓手续费", "number"),
            field("txRate", "提现费率", "number"),
            field("orderFeeRate", "下单手续费", "number"),
            field("kefuUrl", "客服链接", "input"),
            field("mobileUrl", "手机端邀请链接", "input"),
            field("shoujiUrl", "后台登陆前端链接", "input"),
            field("complaintEmail", "投诉邮箱", "input"))),

        settingsPage("settings-upload", "上传配置", fields(
            select("uploadMode", "上传模式", "本地上传", "OSS上传"),
            field("fileTypes", "文件类型", "input"),
            field("imageTypes", "图片类型", "input"))),

        settingsPage("settings-sms", "短信配置", fields(
            field("sendSms", "是否真实发送", "number"),
            field("smsPassword", "短信宝密码", "password"),
            field("smsAccount", "短信宝账号", "input"))),

        settingsPage("settings-email", "邮箱配置", fields(
            field("mailPort", "邮箱端口", "number"),
            field("mailHost", "邮件服务器", "input"),
            field("mailSender", "邮箱发送人", "input"),
            field("sendMail", "是否真实发送", "number"),
            field("mailAuthCode", "邮箱授权码", "password"),
            field("mailAccount", "邮箱账号", "input"))),

        settingsPage("settings-footer", "底部导航", fields(
            field("msbCertificate", "MSB证书", "richtext"),
            field("terms", "服务条款", "richtext")))
    );
  }

  private static AdminFeaturePageResponse settingsPage(
      String key,
      String title,
      List<AdminFeatureFieldResponse> fields
  ) {
    return page(
        key,
        title,
        "系统设置",
        fields,
        columns(col("configTitle", "配置标题"), col("configKey", "配置标识"), col("configValue", "配置值"),
            sortableCol("sort", "排序"), col("component", "输入组件"), col("description", "配置说明")),
        actions(action("submit", "提交", "submit"), action("reset", "重置", "reset")),
        actions(action("edit", "编辑", "modal"), action("delete", "删除", "confirm")),
        rows(row("id", key + "-row", "configTitle", title, "configKey", key, "configValue", "FOREX EXCHANGE",
            "sort", 0, "component", "input", "description", title + "配置项")));
  }
}
