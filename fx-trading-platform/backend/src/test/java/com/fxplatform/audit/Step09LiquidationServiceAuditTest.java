package com.fxplatform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.dto.AccountSnapshot;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.account.service.AccountSnapshotService;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.repository.RiskConfigRepository;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.service.LiquidationService;
import com.fxplatform.trading.service.PositionService;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Step09LiquidationServiceAuditTest {

  @Mock
  private AccountSnapshotService accountSnapshotService;

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private PositionRepository positionRepository;

  @Mock
  private PositionService positionService;

  @Mock
  private SymbolRepository symbolRepository;

  @Mock
  private RiskConfigRepository riskConfigRepository;

  @Mock
  private LedgerService ledgerService;

  @Mock
  private QuoteService quoteService;

  @Mock
  private WalletService walletService;

  @Mock
  private DemoExecutionGuard demoExecutionGuard;

  @BeforeEach
  void accountRowsAreAvailableToTheGuard() {
    org.mockito.Mockito.lenient()
        .when(accountRepository.findById(org.mockito.ArgumentMatchers.any(UUID.class)))
        .thenAnswer(invocation -> {
          TradingAccountEntity account = new TradingAccountEntity();
          account.setId(invocation.getArgument(0));
          account.setBaseCurrency("USD");
          account.setBalance(new BigDecimal("10000.00000000"));
          account.setEquity(new BigDecimal("10000.00000000"));
          account.setUsedMargin(BigDecimal.ZERO);
          account.setFreeMargin(new BigDecimal("10000.00000000"));
          account.setLeverage(10);
          return Optional.of(account);
        });
    org.mockito.Mockito.lenient()
        .when(accountRepository.findByIdForUpdate(org.mockito.ArgumentMatchers.any(UUID.class)))
        .thenAnswer(invocation -> accountRepository.findById(invocation.getArgument(0)));
  }

  @Test
  void fxAccountBelowStopOutClosesLargestLossFirstWithoutRetryingStaleRows() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity riskAccount = new TradingAccountEntity();
    riskAccount.setId(accountId);
    riskAccount.setBaseCurrency("USD");
    riskAccount.setBalance(new BigDecimal("1000.00000000"));
    riskAccount.setEquity(new BigDecimal("100.00000000"));
    riskAccount.setUsedMargin(new BigDecimal("1000.00000000"));
    riskAccount.setFreeMargin(new BigDecimal("-900.00000000"));
    riskAccount.setLeverage(10);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(riskAccount));
    PositionEntity lowRisk = openForex(accountId, "EURUSD", "-5.00000000");
    PositionEntity highRisk = openForex(accountId, "GBPUSD", "-75.00000000");

    when(riskConfigRepository.findFirstEnabledWithStopOutLevel()).thenReturn(Optional.empty());
    when(accountSnapshotService.snapshot(accountId)).thenReturn(snapshot(accountId, "100.00000000", "1000.00000000", "160.00000000"));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(lowRisk, highRisk));
    int closed = service().scanAccount(accountId);

    assertThat(closed).isEqualTo(2);
    InOrder order = inOrder(positionService);
    order.verify(positionService).closeSystemPosition(accountId, highRisk.getId(), "FX_MARGIN_STOP_OUT");
    order.verify(positionService).closeSystemPosition(accountId, lowRisk.getId(), "FX_MARGIN_STOP_OUT");
  }

  @Test
  void samePositionIsNotLiquidatedTwiceAfterStatusLeavesOpen() {
    UUID accountId = UUID.randomUUID();
    PositionEntity position = openForex(accountId, "EURUSD", "-100.00000000");
    when(positionService.closeSystemPosition(accountId, position.getId(), "FX_MARGIN_STOP_OUT")).thenAnswer(invocation -> {
      position.setStatus(PositionStatus.CLOSED);
      return null;
    });

    LiquidationService service = service();

    assertThat(service.liquidatePosition(position, "FX_MARGIN_STOP_OUT")).isTrue();
    assertThat(service.liquidatePosition(position, "FX_MARGIN_STOP_OUT")).isFalse();
    verify(positionService, times(1)).closeSystemPosition(accountId, position.getId(), "FX_MARGIN_STOP_OUT");
  }

  private LiquidationService service() {
    return new LiquidationService(
        accountSnapshotService,
        accountRepository,
        positionRepository,
        positionService,
        symbolRepository,
        riskConfigRepository,
        ledgerService,
        quoteService,
        walletService,
        demoExecutionGuard,
        new com.fxplatform.trading.service.TradingTransactionExecutor());
  }

  private static AccountSnapshot snapshot(UUID accountId, String equity, String usedMargin, String maintenanceMargin) {
    BigDecimal equityValue = new BigDecimal(equity);
    BigDecimal usedMarginValue = new BigDecimal(usedMargin);
    return new AccountSnapshot(
        accountId,
        new BigDecimal("1000.00000000"),
        BigDecimal.ZERO,
        equityValue,
        usedMarginValue,
        new BigDecimal(maintenanceMargin),
        equityValue.subtract(usedMarginValue),
        equityValue.multiply(new BigDecimal("100")).divide(usedMarginValue, 8, java.math.RoundingMode.HALF_UP),
        "USD");
  }

  private static PositionEntity openPerp(UUID accountId, String symbol, String maintenanceMargin, String floatingPnl) {
    PositionEntity position = openForex(accountId, symbol, floatingPnl);
    position.setMaintenanceMargin(new BigDecimal(maintenanceMargin));
    position.setNotional(new BigDecimal("50000.00000000"));
    position.setLeverage(10);
    return position;
  }

  private static PositionEntity openForex(UUID accountId, String symbol, String floatingPnl) {
    PositionEntity position = new PositionEntity();
    position.setId(UUID.randomUUID());
    position.setAccountId(accountId);
    position.setSymbol(symbol);
    position.setSide(OrderSide.BUY);
    position.setLots(new BigDecimal("0.10"));
    position.setOpenPrice(new BigDecimal("1.10000"));
    position.setFloatingPnl(new BigDecimal(floatingPnl));
    position.setStatus(PositionStatus.OPEN);
    return position;
  }

  private static SymbolEntity linearPerpSymbol(String symbolCode) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol(symbolCode);
    symbol.setAssetClass("LINEAR_PERPETUAL");
    symbol.setBaseCurrency(symbolCode.substring(0, 3));
    symbol.setQuoteCurrency("USDT");
    symbol.setLotSize(BigDecimal.ONE);
    symbol.setMaintenanceMarginRate(new BigDecimal("0.005"));
    symbol.setLiquidationFeeRate(BigDecimal.ZERO);
    return symbol;
  }

  private static QuoteResponse quote(String symbol, String bid, String ask, String mid) {
    return new QuoteResponse(
        "quote",
        symbol,
        new BigDecimal(bid),
        new BigDecimal(ask),
        new BigDecimal(mid),
        new BigDecimal(ask).subtract(new BigDecimal(bid)),
        "test",
        1L);
  }
}
