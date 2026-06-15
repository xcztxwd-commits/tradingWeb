package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.audit.repository.AuditLogRepository;
import com.fxplatform.auth.repository.UserRepository;
import com.fxplatform.ledger.repository.LedgerEntryRepository;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminDashboardServiceTest {

  @Mock
  private UserRepository userRepository;

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private OrderRepository orderRepository;

  @Mock
  private PositionRepository positionRepository;

  @Mock
  private TradeRepository tradeRepository;

  @Mock
  private LedgerEntryRepository ledgerEntryRepository;

  @Mock
  private SymbolRepository symbolRepository;

  @Mock
  private AuditLogRepository auditLogRepository;

  @Test
  void summaryAggregatesCoreAdminCounts() {
    when(userRepository.count()).thenReturn(7L);
    when(accountRepository.count()).thenReturn(5L);
    when(orderRepository.count()).thenReturn(11L);
    when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of());
    when(positionRepository.count()).thenReturn(3L);
    when(positionRepository.findByStatus(PositionStatus.OPEN)).thenReturn(List.of());
    when(tradeRepository.count()).thenReturn(13L);
    when(ledgerEntryRepository.count()).thenReturn(17L);
    when(symbolRepository.count()).thenReturn(19L);
    when(auditLogRepository.count()).thenReturn(23L);

    AdminDashboardService service = new AdminDashboardService(
        userRepository,
        accountRepository,
        orderRepository,
        positionRepository,
        tradeRepository,
        ledgerEntryRepository,
        symbolRepository,
        auditLogRepository);

    var summary = service.summary();

    assertThat(summary.userCount()).isEqualTo(7);
    assertThat(summary.accountCount()).isEqualTo(5);
    assertThat(summary.orderCount()).isEqualTo(11);
    assertThat(summary.pendingOrderCount()).isZero();
    assertThat(summary.positionCount()).isEqualTo(3);
    assertThat(summary.openPositionCount()).isZero();
    assertThat(summary.tradeCount()).isEqualTo(13);
    assertThat(summary.ledgerEntryCount()).isEqualTo(17);
    assertThat(summary.symbolCount()).isEqualTo(19);
    assertThat(summary.auditLogCount()).isEqualTo(23);
  }
}
