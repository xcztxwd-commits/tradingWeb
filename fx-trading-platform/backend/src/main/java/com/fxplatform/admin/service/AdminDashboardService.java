package com.fxplatform.admin.service;

import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.admin.dto.response.AdminDashboardSummaryResponse;
import com.fxplatform.audit.repository.AuditLogRepository;
import com.fxplatform.auth.repository.UserRepository;
import com.fxplatform.ledger.repository.LedgerEntryRepository;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * AdminDashboardService 聚合后台首页需要的核心运营指标。
 */
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

  /** 用户仓储，用于统计平台注册用户数量。 */
  private final UserRepository userRepository;
  /** 账户仓储，用于统计交易账户数量。 */
  private final TradingAccountRepository accountRepository;
  /** 订单仓储，用于统计订单和待处理订单数量。 */
  private final OrderRepository orderRepository;
  /** 持仓仓储，用于统计持仓和打开持仓数量。 */
  private final PositionRepository positionRepository;
  /** 成交仓储，用于统计成交记录数量。 */
  private final TradeRepository tradeRepository;
  /** 资金流水仓储，用于统计资金流水数量。 */
  private final LedgerEntryRepository ledgerEntryRepository;
  /** 品种仓储，用于统计产品数量。 */
  private final SymbolRepository symbolRepository;
  /** 审计仓储，用于统计后台操作数量。 */
  private final AuditLogRepository auditLogRepository;

  /**
   * 获取后台首页概览统计。
   */
  public AdminDashboardSummaryResponse summary() {
    // 当前 repository 暂无 countByStatus，先复用已有状态查询并取 size，后续可优化为 count 查询。
    long pendingOrderCount = orderRepository.findByStatus(OrderStatus.PENDING).size();
    long openPositionCount = positionRepository.findByStatus(PositionStatus.OPEN).size();
    return new AdminDashboardSummaryResponse(
        userRepository.count(),
        accountRepository.count(),
        orderRepository.count(),
        pendingOrderCount,
        positionRepository.count(),
        openPositionCount,
        tradeRepository.count(),
        ledgerEntryRepository.count(),
        symbolRepository.count(),
        auditLogRepository.count());
  }
}
