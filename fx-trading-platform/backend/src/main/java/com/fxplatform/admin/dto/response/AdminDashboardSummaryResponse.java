package com.fxplatform.admin.dto.response;

/**
 * AdminDashboardSummaryResponse 是后台仪表盘的核心统计响应。
 *
 * @param userCount 用户总数。
 * @param accountCount 交易账户总数。
 * @param orderCount 订单总数。
 * @param pendingOrderCount 待处理订单数量。
 * @param positionCount 持仓总数。
 * @param openPositionCount 当前打开持仓数量。
 * @param tradeCount 成交记录总数。
 * @param ledgerEntryCount 资金流水总数。
 * @param symbolCount 交易品种总数。
 * @param auditLogCount 审计日志总数。
 */
public record AdminDashboardSummaryResponse(
    long userCount,
    long accountCount,
    long orderCount,
    long pendingOrderCount,
    long positionCount,
    long openPositionCount,
    long tradeCount,
    long ledgerEntryCount,
    long symbolCount,
    long auditLogCount
) {
}
