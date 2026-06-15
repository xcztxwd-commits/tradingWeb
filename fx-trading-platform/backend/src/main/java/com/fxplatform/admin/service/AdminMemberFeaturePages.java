package com.fxplatform.admin.service;

import static com.fxplatform.admin.service.AdminFeatureCatalogDsl.*;

import com.fxplatform.admin.dto.response.AdminFeaturePageResponse;
import java.util.List;

final class AdminMemberFeaturePages {

  private AdminMemberFeaturePages() {
  }

  static List<AdminFeaturePageResponse> pages() {
    return List.of(
        page(
            "members",
            "用户列表",
            "用户管理",
            fields(
                select("control", "控制", "正常", "禁用"),
                field("uid", "UID", "input"),
                field("remark", "备注", "input"),
                select("accountType", "账号类型", "真实账户", "体验账户"),
                field("userAccount", "用户账号", "input"),
                field("customer", "客服", "input"),
                field("inviteCode", "邀请码", "input"),
                select("status", "状态", "正常", "冻结"),
                select("trade", "交易", "正常", "禁止"),
                field("loginIp", "登录IP", "input"),
                field("registerIp", "注册IP", "input"),
                select("realNameStatus", "实名状态", "未提交", "待审核", "通过", "拒绝"),
                field("realName", "真实姓名", "input"),
                field("idCard", "证件号码", "input"),
                field("createdAt", "创建时间", "date")),
            columns(
                sortableCol("control", "控制"),
                col("uid", "UID"),
                sortableCol("remark", "备注"),
                sortableCol("accountType", "账号类型"),
                col("userAccount", "用户账号"),
                col("customer", "客服"),
                col("phone", "手机号码"),
                col("annualIncome", "年收入"),
                col("avatar", "头像"),
                sortableCol("balance", "余额"),
                col("inviteCode", "邀请码"),
                col("superiorId", "上级ID")),
            actions(action("one-click-profit", "一键控盈利", "confirm"), action("one-click-normal", "一键控正常", "confirm"),
                action("kick-offline", "踢下线", "confirm")),
            actions(action("bank-card", "银行卡", "modal"), action("password", "密码", "modal"), action("real-name", "实名", "modal"),
                action("login", "登陆", "confirm"), action("remark", "备注", "modal"), action("send-message", "发信", "modal"),
                action("edit", "编辑", "modal"), action("delete", "删除", "confirm")),
            rows(
                row("id", "member-303", "control", "正常", "uid", 303, "remark", "天赐-仙石", "accountType", "真实账户",
                    "userAccount", "natsubi...", "customer", "", "phone", "080546...", "annualIncome", "2500000", "avatar", "查看图片",
                    "balance", "0.00", "inviteCode", "aLnmHO", "superiorId", 0),
                row("id", "member-299", "control", "正常", "uid", 299, "remark", "慧慧-松本", "accountType", "真实账户",
                    "userAccount", "sigemi0...", "customer", "", "phone", "090125...", "annualIncome", "300", "avatar", "查看图片",
                    "balance", "22572.49", "inviteCode", "Yn3nut", "superiorId", 0))),

        page(
            "member-payment-accounts",
            "用户银行卡",
            "用户管理",
            fields(
                field("createdAt", "创建时间", "dateRange"),
                field("nameOrNetwork", "姓名/网络", "input"),
                field("bankCardOrWallet", "银行卡号/钱包地址", "input"),
                field("userId", "用户ID", "input"),
                select("walletType", "钱包类型", "银行卡", "数字货币"),
                select("currency", "货币", "USD", "USDT-TRC20", "USDT-ERC20", "BTC", "ETH")),
            columns(
                sortableCol("id", "主键ID"),
                sortableCol("createdAt", "创建时间"),
                col("nameOrNetwork", "姓名/网络"),
                col("bankCardOrWallet", "银行卡号/钱包地址"),
                col("userId", "用户ID"),
                col("bankName", "银行名称/网络"),
                col("branchName", "分行名称/网络"),
                col("code", "代码/网络"),
                col("walletType", "钱包类型"),
                col("currency", "货币")),
            actions(action("create", "新增", "modal"), action("delete", "删除", "confirm")),
            actions(action("edit", "编辑", "modal"), action("delete", "删除", "confirm")),
            rows(
                row("id", 19, "createdAt", "2025-09-18 09:00:00", "nameOrNetwork", "USD", "bankCardOrWallet", "0",
                    "userId", 57, "bankName", "USD", "branchName", "USD", "code", "USD", "walletType", "数字货币", "currency", "USD"),
                row("id", 16, "createdAt", "2024-05-20 10:00:00", "nameOrNetwork", "RM3432", "bankCardOrWallet", "3453534543",
                    "userId", 37, "bankName", "asdasd", "branchName", "sadasd", "code", "asdasd", "walletType", "银行卡", "currency", "")))
    );
  }

}
