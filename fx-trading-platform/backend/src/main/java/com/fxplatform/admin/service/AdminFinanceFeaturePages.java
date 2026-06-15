package com.fxplatform.admin.service;

import static com.fxplatform.admin.service.AdminFeatureCatalogDsl.*;

import com.fxplatform.admin.dto.response.AdminFeaturePageResponse;
import java.util.List;

final class AdminFinanceFeaturePages {

  private AdminFinanceFeaturePages() {
  }

  static List<AdminFeaturePageResponse> pages() {
    return List.of(
        page(
            "finance-ledger",
            "资金明细",
            "财务管理",
            fields(
                field("userId", "用户ID", "input"),
                select("direction", "收/支", "收入", "支出"),
                field("createdAt", "创建时间", "date"),
                select("tradeType", "交易类型", "交易平台", "平台手续费", "保证金返还"),
                field("title", "标题", "input")),
            columns(
                col("userId", "用户ID"),
                sortableCol("amount", "变化数量"),
                col("beforeAmount", "变化前数量"),
                col("afterAmount", "变化后数量"),
                col("direction", "收/支"),
                sortableCol("createdAt", "创建时间"),
                col("updatedAt", "更新时间"),
                col("tradeType", "交易类型"),
                col("title", "标题")),
            actions(action("delete", "删除", "confirm"), action("export", "导出", "download")),
            actions(action("delete", "删除", "confirm")),
            rows(
                row("id", "ledger-1", "userId", 187, "amount", "1.20", "beforeAmount", "220.38",
                    "afterAmount", "221.58", "direction", "收入", "createdAt", "2026-06-09 14:30:00", "updatedAt", "2026-06-09 14:30:00",
                    "tradeType", "交易平台", "title", "交易平台"),
                row("id", "ledger-2", "userId", 187, "amount", "0.05", "beforeAmount", "220.43",
                    "afterAmount", "220.38", "direction", "支出", "createdAt", "2026-06-09 14:20:00", "updatedAt", "2026-06-09 14:20:00",
                    "tradeType", "平台手续费", "title", "平台手续费"))),

        page(
            "recharge-orders",
            "充值订单",
            "财务管理",
            fields(
                field("userUid", "用户uid", "input"),
                field("orderNo", "订单号", "input"),
                field("createdAt", "创建时间", "dateRange"),
                select("status", "状态", "待审核", "通过", "拒绝"),
                select("rechargeType", "充值类型", "银行卡", "数字货币"),
                field("walletAddress", "钱包地址", "input"),
                select("addressNetwork", "地址网络", "USDT-TRC20", "USDT-ERC20", "BTC", "ETH")),
            columns(
                col("remark1", "备注"),
                col("userUid", "用户uid"),
                col("orderNo", "订单号"),
                col("amount", "数量"),
                col("receivedAmount", "实到"),
                col("status", "状态"),
                col("rate", "费率"),
                col("fee", "手续费"),
                col("voucher", "凭证"),
                col("remark2", "备注"),
                col("currency", "货币"),
                col("rechargeType", "充值类型"),
                col("walletAddress", "钱包地址"),
                col("addressNetwork", "地址网络")),
            actions(action("create", "新增", "modal"), action("delete", "删除", "confirm"), action("export", "导出", "download")),
            actions(action("review", "审核", "modal"), action("edit", "编辑", "modal"), action("delete", "删除", "confirm")),
            rows(
                row("id", "order-1", "remark1", "天赐-正树", "userUid", 296, "orderNo", "296178...",
                    "amount", "313.12", "receivedAmount", "313.12", "status", "通过", "rate", "0.00", "fee", "0.00",
                    "voucher", "查看图片", "remark2", "首冲5", "currency", "USD", "rechargeType", "银行卡", "walletAddress", "", "addressNetwork", ""),
                row("id", "order-2", "remark1", "慧慧-松本", "userUid", 299, "orderNo", "299178...",
                    "amount", "3135.62", "receivedAmount", "3135.62", "status", "通过", "rate", "0.00", "fee", "0.00",
                    "voucher", "查看图片", "remark2", "三方帮50", "currency", "USD", "rechargeType", "银行卡", "walletAddress", "", "addressNetwork", ""))),

        page(
            "withdrawal-orders",
            "提现订单",
            "财务管理",
            fields(
                field("userId", "用户ID", "input"),
                field("orderNo", "订单号", "input"),
                select("paymentType", "收款类型", "银行卡", "数字货币"),
                field("nameOrNetwork", "姓名/网络", "input"),
                field("bankCardOrWallet", "银行卡号/钱包地址", "input"),
                select("reviewStatus", "审核状态", "待审核", "通过", "拒绝"),
                field("createdAt", "创建时间", "dateRange")),
            columns(
                col("remark", "备注"),
                col("userId", "用户ID"),
                col("orderNo", "订单号"),
                col("amount", "数量"),
                col("arrivedAmount", "到账"),
                col("feeRate", "手续费率"),
                col("fee", "手续费"),
                col("paymentType", "收款类型"),
                col("nameOrNetwork", "姓名/网络"),
                col("bankName", "银行名称"),
                col("branchName", "分行名称"),
                col("bankCode", "银行代码"),
                col("bankCard", "银行卡号"),
                col("paymentInfo", "收款信息")),
            actions(action("create", "新增", "modal"), action("delete", "删除", "confirm")),
            actions(action("review", "审核", "modal"), action("edit", "编辑", "modal"), action("delete", "删除", "confirm")),
            rows(
                row("id", "withdraw-1", "remark", "天赐-正树", "userId", 296, "orderNo", "296178...",
                    "amount", "62.56", "arrivedAmount", "62.56", "feeRate", "0.00", "fee", "0.00", "paymentType", "银行卡",
                    "nameOrNetwork", "", "bankName", "", "branchName", "", "bankCode", "", "bankCard", "", "paymentInfo", "0"))),

        page(
            "payment-methods",
            "收款方式",
            "财务管理",
            fields(
                field("name", "名称", "input"),
                field("sort", "排序", "number"),
                select("status", "状态", "正常", "停用"),
                select("type", "类型", "银行卡", "数字货币"),
                field("cardOrWallet", "卡号/钱包地址", "input"),
                field("swift", "swift", "input"),
                field("payee", "收款人姓名", "input"),
                field("bankAddress", "银行地址", "input"),
                field("networkOrCurrency", "网络/货币", "input")),
            columns(
                col("name", "名称"),
                sortableCol("sort", "排序"),
                sortableCol("status", "状态"),
                sortableCol("type", "类型"),
                col("cardOrWallet", "卡号/钱包地址"),
                col("swift", "swift"),
                col("payee", "收款人姓名"),
                col("bankAddress", "银行地址"),
                col("networkOrCurrency", "网络/货币"),
                sortableCol("createdAt", "创建时间"),
                col("updatedAt", "更新时间")),
            actions(action("create", "新增", "modal"), action("delete", "删除", "confirm")),
            actions(action("edit", "编辑", "modal"), action("delete", "删除", "confirm")),
            rows(
                row("id", "pay-usdt-trc20", "name", "USDT-TRC20", "sort", 4, "status", "正常", "type", "数字货币",
                    "cardOrWallet", "TNHXS3Fw...", "swift", "", "payee", "", "bankAddress", "", "networkOrCurrency", "USDT-TRC20",
                    "createdAt", "2026-02-13 10:00:00", "updatedAt", "2026-02-13 10:00:00"),
                row("id", "pay-btc", "name", "BTC", "sort", 1, "status", "正常", "type", "数字货币",
                    "cardOrWallet", "bc1qx6y96t...", "swift", "", "payee", "", "bankAddress", "", "networkOrCurrency", "BTC",
                    "createdAt", "2026-02-13 10:00:00", "updatedAt", "2026-02-13 10:00:00")))
    );
  }

}
