package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.dto.AccountSnapshot;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.service.AccountSnapshotService;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.entity.RiskConfigEntity;
import com.fxplatform.risk.repository.RiskConfigRepository;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.PositionRepository;
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
class LiquidationServiceTest {

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

  @Mock
  private TradingTransactionExecutor transactionExecutor;

  @BeforeEach
  void accountRowsAreAvailableToTheGuardAndFeeLock() {
    lenient().when(accountRepository.findById(org.mockito.ArgumentMatchers.any(UUID.class)))
        .thenAnswer(invocation -> Optional.of(account(invocation.getArgument(0), "10000.00000000")));
    lenient().when(accountRepository.findByIdForUpdate(org.mockito.ArgumentMatchers.any(UUID.class)))
        .thenAnswer(invocation -> accountRepository.findById(invocation.getArgument(0)));
    lenient().when(transactionExecutor.execute(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
  }

  @Test
  void fxAccountBelowStopOutClosesOpenPositionsByLargestLossFirst() {
    UUID accountId = UUID.randomUUID();
    PositionEntity smallerLoss = openPosition(accountId, UUID.randomUUID(), "EURUSD", "-25.00000000");
    PositionEntity biggerLoss = openPosition(accountId, UUID.randomUUID(), "GBPUSD", "-80.00000000");

    when(riskConfigRepository.findFirstEnabledWithStopOutLevel()).thenReturn(Optional.empty());
    when(accountSnapshotService.snapshot(accountId)).thenReturn(snapshot(accountId, "480.00000000", "1000.00000000", BigDecimal.ZERO));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(smallerLoss, biggerLoss));
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(forexSymbol("EURUSD")));
    when(symbolRepository.findBySymbol("GBPUSD")).thenReturn(Optional.of(forexSymbol("GBPUSD")));
    when(positionService.closeSystemPosition(accountId, biggerLoss.getId(), "FX_MARGIN_STOP_OUT")).thenAnswer(invocation -> {
      biggerLoss.setStatus(PositionStatus.CLOSED);
      return null;
    });
    when(positionService.closeSystemPosition(accountId, smallerLoss.getId(), "FX_MARGIN_STOP_OUT")).thenAnswer(invocation -> {
      smallerLoss.setStatus(PositionStatus.CLOSED);
      return null;
    });

    int closed = service().scanAccount(accountId);

    assertThat(closed).isEqualTo(2);
    assertThat(biggerLoss.getStatus()).isEqualTo(PositionStatus.CLOSED);
    assertThat(smallerLoss.getStatus()).isEqualTo(PositionStatus.CLOSED);
    InOrder order = inOrder(positionService);
    order.verify(positionService).closeSystemPosition(accountId, biggerLoss.getId(), "FX_MARGIN_STOP_OUT");
    order.verify(positionService).closeSystemPosition(accountId, smallerLoss.getId(), "FX_MARGIN_STOP_OUT");
    verify(transactionExecutor, times(2)).execute(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void liquidationScanDoesNotHoldOneTransactionAcrossFreshRiskSnapshots() throws Exception {
    assertThat(LiquidationService.class.getMethod("scanAccount", UUID.class)
        .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class))
        .isFalse();
  }

  @Test
  void fxStopOutUsesRiskConfigWhenConfigured() {
    UUID accountId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, UUID.randomUUID(), "EURUSD", "-30.00000000");
    RiskConfigEntity riskConfig = new RiskConfigEntity();
    riskConfig.setStopOutLevel(new BigDecimal("60"));

    when(riskConfigRepository.findFirstEnabledWithStopOutLevel()).thenReturn(Optional.of(riskConfig));
    when(accountSnapshotService.snapshot(accountId)).thenReturn(snapshot(accountId, "550.00000000", "1000.00000000", BigDecimal.ZERO));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(forexSymbol("EURUSD")));

    int closed = service().scanAccount(accountId);

    assertThat(closed).isEqualTo(1);
    verify(positionService).closeSystemPosition(accountId, position.getId(), "FX_MARGIN_STOP_OUT");
  }

  @Test
  void perpScanRecomputesRiskFromLatestMarkPriceInsteadOfStoredFields() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "1002.00000000");
    PositionEntity position = openPerpPosition(accountId, UUID.randomUUID(), "BTCUSDT", "1.00000000", "0.00000000");
    position.setLots(BigDecimal.ONE);
    position.setOpenPrice(new BigDecimal("2000.00000000"));
    position.setNotional(new BigDecimal("1.00000000"));

    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(riskConfigRepository.findFirstEnabledWithStopOutLevel()).thenReturn(Optional.empty());
    when(accountSnapshotService.snapshot(accountId))
        .thenReturn(snapshot(accountId, "1002.00000000", "1000.00000000", new BigDecimal("1.00000000")));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("BTCUSDT"))
        .thenReturn(Optional.of(linearPerpSymbol("BTCUSDT", BigDecimal.ZERO)));
    when(quoteService.freshQuote("BTCUSDT"))
        .thenReturn(quote("BTCUSDT", "999.00000000", "1001.00000000", "1000.00000000"));
    when(positionService.closeSystemPosition(accountId, position.getId(), "PERP_MAINTENANCE_MARGIN"))
        .thenAnswer(invocation -> {
          position.setStatus(PositionStatus.CLOSED);
          return null;
        });

    int closed = service().scanAccount(accountId);

    assertThat(closed).isEqualTo(1);
    assertThat(position.getMarkPrice()).isEqualByComparingTo("1000.00000000");
    assertThat(position.getFloatingPnl()).isEqualByComparingTo("-1000.00000000");
    assertThat(position.getNotional()).isEqualByComparingTo("1000.00000000");
    assertThat(position.getMaintenanceMargin()).isEqualByComparingTo("5.00000000");
    verify(positionService).closeSystemPosition(accountId, position.getId(), "PERP_MAINTENANCE_MARGIN");
    verify(positionRepository, never()).save(position);
  }

  @Test
  void perpLiquidationStopsAfterOneCloseWhenFreshRescanIsSafe() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "1005.00000000");
    PositionEntity unsafe = openPerpPosition(accountId, UUID.randomUUID(), "BTCUSDT", "1.00000000", "0.00000000");
    unsafe.setLots(BigDecimal.ONE);
    unsafe.setOpenPrice(new BigDecimal("2000.00000000"));
    PositionEntity stillOpen = openPerpPosition(accountId, UUID.randomUUID(), "ETHUSDT", "1.00000000", "0.00000000");
    stillOpen.setLots(BigDecimal.ONE);
    stillOpen.setOpenPrice(new BigDecimal("1000.00000000"));

    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(riskConfigRepository.findFirstEnabledWithStopOutLevel()).thenReturn(Optional.empty());
    when(accountSnapshotService.snapshot(accountId))
        .thenReturn(snapshot(accountId, "5.00000000", "1000.00000000", new BigDecimal("2.00000000")));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(stillOpen, unsafe))
        .thenReturn(List.of(stillOpen));
    when(symbolRepository.findBySymbol("BTCUSDT"))
        .thenReturn(Optional.of(linearPerpSymbol("BTCUSDT", BigDecimal.ZERO)));
    when(symbolRepository.findBySymbol("ETHUSDT"))
        .thenReturn(Optional.of(linearPerpSymbol("ETHUSDT", BigDecimal.ZERO)));
    when(quoteService.freshQuote("BTCUSDT"))
        .thenReturn(quote("BTCUSDT", "999.00000000", "1001.00000000", "1000.00000000"));
    when(quoteService.freshQuote("ETHUSDT"))
        .thenReturn(quote("ETHUSDT", "999.00000000", "1001.00000000", "1000.00000000"));
    when(positionService.closeSystemPosition(accountId, unsafe.getId(), "PERP_MAINTENANCE_MARGIN"))
        .thenAnswer(invocation -> {
          unsafe.setStatus(PositionStatus.CLOSED);
          return null;
        });

    int closed = service().scanAccount(accountId);

    assertThat(closed).isEqualTo(1);
    verify(positionService).closeSystemPosition(accountId, unsafe.getId(), "PERP_MAINTENANCE_MARGIN");
    verify(positionService, never()).closeSystemPosition(accountId, stillOpen.getId(), "PERP_MAINTENANCE_MARGIN");
  }

  @Test
  void liquidationFeeDebitsAccountBalanceAndWritesLedger() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "25.00000000");
    PositionEntity position = openPerpPosition(accountId, UUID.randomUUID(), "BTCUSDT", "0.00000000", "0.00000000");
    position.setLots(BigDecimal.ONE);
    position.setOpenPrice(new BigDecimal("1000.00000000"));

    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(riskConfigRepository.findFirstEnabledWithStopOutLevel()).thenReturn(Optional.empty());
    when(accountSnapshotService.snapshot(accountId))
        .thenReturn(snapshot(accountId, "25.00000000", "1000.00000000", BigDecimal.ZERO));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("BTCUSDT"))
        .thenReturn(Optional.of(linearPerpSymbol("BTCUSDT", new BigDecimal("0.02000000"))));
    when(quoteService.freshQuote("BTCUSDT"))
        .thenReturn(quote("BTCUSDT", "999.00000000", "1001.00000000", "1000.00000000"));
    when(positionService.closeSystemPosition(accountId, position.getId(), "PERP_MAINTENANCE_MARGIN"))
        .thenAnswer(invocation -> {
          position.setStatus(PositionStatus.CLOSED);
          return null;
        });

    int closed = service().scanAccount(accountId);

    assertThat(closed).isEqualTo(1);
    assertThat(account.getBalance()).isEqualByComparingTo("5.00000000");
    verify(accountRepository).save(account);
    verify(ledgerService).recordLiquidationFee(account, new BigDecimal("20.00000000"), position.getId(), "Liquidation fee charged");
  }

  @Test
  void inversePerpLiquidationFeeUsesSettlementAsset() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "2.00050000");
    PositionEntity position = openPerpPosition(accountId, UUID.randomUUID(), "BTCUSD", "0.00000000", "0.00000000");
    position.setLots(BigDecimal.ONE);
    position.setOpenPrice(new BigDecimal("1000.00000000"));
    position.setSettlementAsset("BTC");

    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(riskConfigRepository.findFirstEnabledWithStopOutLevel()).thenReturn(Optional.empty());
    when(accountSnapshotService.snapshot(accountId))
        .thenReturn(snapshot(accountId, "2.00050000", "1000.00000000", BigDecimal.ZERO));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("BTCUSD"))
        .thenReturn(Optional.of(inversePerpSymbol("BTCUSD", new BigDecimal("0.02000000"))));
    when(quoteService.freshQuote("BTCUSD"))
        .thenReturn(quote("BTCUSD", "999.00000000", "1001.00000000", "1000.00000000"));
    when(positionService.closeSystemPosition(accountId, position.getId(), "PERP_MAINTENANCE_MARGIN"))
        .thenAnswer(invocation -> {
          position.setStatus(PositionStatus.CLOSED);
          return null;
        });

    service().scanAccount(accountId);

    verify(walletService).debitAvailableWithEntryType(
        accountId,
        "BTC",
        new BigDecimal("2.00000000"),
        "POSITION",
        position.getId(),
        "Liquidation fee charged",
        "LIQUIDATION_FEE");
  }

  @Test
  void secondScanDoesNotLiquidatePositionAlreadyClosedByFirstScan() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "25.00000000");
    PositionEntity position = openPerpPosition(accountId, UUID.randomUUID(), "BTCUSDT", "0.00000000", "0.00000000");
    position.setLots(BigDecimal.ONE);
    position.setOpenPrice(new BigDecimal("1000.00000000"));

    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(riskConfigRepository.findFirstEnabledWithStopOutLevel()).thenReturn(Optional.empty());
    when(accountSnapshotService.snapshot(accountId))
        .thenReturn(snapshot(accountId, "25.00000000", "1000.00000000", BigDecimal.ZERO));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("BTCUSDT"))
        .thenReturn(Optional.of(linearPerpSymbol("BTCUSDT", new BigDecimal("0.02000000"))));
    when(quoteService.freshQuote("BTCUSDT"))
        .thenReturn(quote("BTCUSDT", "999.00000000", "1001.00000000", "1000.00000000"));
    when(positionService.closeSystemPosition(accountId, position.getId(), "PERP_MAINTENANCE_MARGIN"))
        .thenAnswer(invocation -> {
          position.setStatus(PositionStatus.CLOSED);
          return null;
        });

    assertThat(service().scanAccount(accountId)).isEqualTo(1);
    assertThat(service().scanAccount(accountId)).isZero();
    verify(positionService, times(1)).closeSystemPosition(accountId, position.getId(), "PERP_MAINTENANCE_MARGIN");
  }

  @Test
  void perpAccountBelowMaintenanceMarginClosesHighestRiskContributionFirst() {
    UUID accountId = UUID.randomUUID();
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId, "1000.00000000")));
    PositionEntity lowerRisk = openPerpPosition(accountId, UUID.randomUUID(), "ETHUSDT", "60.00000000", "-5.00000000");
    PositionEntity higherRisk = openPerpPosition(accountId, UUID.randomUUID(), "BTCUSDT", "100.00000000", "-75.00000000");
    lowerRisk.setLots(BigDecimal.ONE);
    lowerRisk.setOpenPrice(new BigDecimal("2000.00000000"));
    higherRisk.setLots(BigDecimal.ONE);
    higherRisk.setOpenPrice(new BigDecimal("3000.00000000"));

    when(riskConfigRepository.findFirstEnabledWithStopOutLevel()).thenReturn(Optional.empty());
    when(accountSnapshotService.snapshot(accountId)).thenReturn(snapshot(accountId, "100.00000000", "1000.00000000", new BigDecimal("160.00000000")));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(lowerRisk, higherRisk));
    when(symbolRepository.findBySymbol("ETHUSDT")).thenReturn(Optional.of(linearPerpSymbol("ETHUSDT", BigDecimal.ZERO)));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(linearPerpSymbol("BTCUSDT", BigDecimal.ZERO)));
    when(quoteService.freshQuote("ETHUSDT"))
        .thenReturn(quote("ETHUSDT", "999.00000000", "1001.00000000", "1000.00000000"));
    when(quoteService.freshQuote("BTCUSDT"))
        .thenReturn(quote("BTCUSDT", "999.00000000", "1001.00000000", "1000.00000000"));
    when(positionService.closeSystemPosition(accountId, higherRisk.getId(), "PERP_MAINTENANCE_MARGIN")).thenAnswer(invocation -> {
      higherRisk.setStatus(PositionStatus.CLOSED);
      return null;
    });
    when(positionService.closeSystemPosition(accountId, lowerRisk.getId(), "PERP_MAINTENANCE_MARGIN")).thenAnswer(invocation -> {
      lowerRisk.setStatus(PositionStatus.CLOSED);
      return null;
    });

    int closed = service().scanAccount(accountId);

    assertThat(closed).isEqualTo(2);
    InOrder order = inOrder(positionService);
    order.verify(positionService).closeSystemPosition(accountId, higherRisk.getId(), "PERP_MAINTENANCE_MARGIN");
    order.verify(positionService).closeSystemPosition(accountId, lowerRisk.getId(), "PERP_MAINTENANCE_MARGIN");
  }

  @Test
  void perpLiquidationIncludesLiquidationFeeBuffer() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "25.00000000");
    PositionEntity position = openPerpPosition(accountId, UUID.randomUUID(), "BTCUSDT", "100.00000000", "0.00000000");
    position.setLots(BigDecimal.ONE);
    position.setOpenPrice(new BigDecimal("1000.00000000"));
    position.setNotional(new BigDecimal("1000.00000000"));

    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(riskConfigRepository.findFirstEnabledWithStopOutLevel()).thenReturn(Optional.empty());
    when(accountSnapshotService.snapshot(accountId)).thenReturn(snapshot(accountId, "25.00000000", "1000.00000000", new BigDecimal("100.00000000")));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(linearPerpSymbol("BTCUSDT", new BigDecimal("0.02000000"))));
    when(quoteService.freshQuote("BTCUSDT"))
        .thenReturn(quote("BTCUSDT", "999.00000000", "1001.00000000", "1000.00000000"));
    when(positionService.closeSystemPosition(accountId, position.getId(), "PERP_MAINTENANCE_MARGIN"))
        .thenAnswer(invocation -> {
          position.setStatus(PositionStatus.CLOSED);
          return null;
        });

    int closed = service().scanAccount(accountId);

    assertThat(closed).isEqualTo(1);
    verify(positionService).closeSystemPosition(accountId, position.getId(), "PERP_MAINTENANCE_MARGIN");
  }

  @Test
  void shouldLiquidateUsesFreshPerpRiskIncludingLiquidationFeeBuffer() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "25.00000000");
    PositionEntity position = openPerpPosition(accountId, UUID.randomUUID(), "BTCUSDT", "0.00000000", "0.00000000");
    position.setLots(BigDecimal.ONE);
    position.setOpenPrice(new BigDecimal("1000.00000000"));

    lenient().when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    lenient().when(riskConfigRepository.findFirstEnabledWithStopOutLevel()).thenReturn(Optional.empty());
    lenient().when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    lenient().when(symbolRepository.findBySymbol("BTCUSDT"))
        .thenReturn(Optional.of(linearPerpSymbol("BTCUSDT", new BigDecimal("0.02000000"))));
    lenient().when(quoteService.freshQuote("BTCUSDT"))
        .thenReturn(quote("BTCUSDT", "999.00000000", "1001.00000000", "1000.00000000"));

    AccountSnapshot staleSnapshot = snapshot(accountId, "25.00000000", "10.00000000", BigDecimal.ZERO);

    assertThat(service().shouldLiquidate(staleSnapshot)).isTrue();
  }

  @Test
  void accountAboveRiskThresholdDoesNotClosePositions() {
    UUID accountId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, UUID.randomUUID(), "EURUSD", "-10.00000000");

    when(riskConfigRepository.findFirstEnabledWithStopOutLevel()).thenReturn(Optional.empty());
    when(accountSnapshotService.snapshot(accountId)).thenReturn(snapshot(accountId, "2500.00000000", "1000.00000000", BigDecimal.ZERO));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(forexSymbol("EURUSD")));

    int closed = service().scanAccount(accountId);

    assertThat(closed).isZero();
    verify(positionService, never()).closeSystemPosition(accountId, position.getId(), "FX_MARGIN_STOP_OUT");
  }

  @Test
  void samePositionIsNotLiquidatedTwiceAfterItLeavesOpenStatus() {
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId, "EURUSD", "-100.00000000");
    when(positionService.closeSystemPosition(accountId, positionId, "FX_MARGIN_STOP_OUT")).thenAnswer(invocation -> {
      position.setStatus(PositionStatus.CLOSED);
      return null;
    });

    LiquidationService service = service();

    assertThat(service.liquidatePosition(position, "FX_MARGIN_STOP_OUT")).isTrue();
    assertThat(service.liquidatePosition(position, "FX_MARGIN_STOP_OUT")).isFalse();
    verify(positionService, times(1)).closeSystemPosition(accountId, positionId, "FX_MARGIN_STOP_OUT");
  }

  @Test
  void alreadyClaimedPositionDoesNotCountAsLiquidated() {
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId, "EURUSD", "-100.00000000");
    when(positionService.closeSystemPosition(accountId, positionId, "FX_MARGIN_STOP_OUT"))
        .thenThrow(new BusinessException("POSITION_NOT_OPEN", "Position is no longer open"));

    boolean liquidated = service().liquidatePosition(position, "FX_MARGIN_STOP_OUT");

    assertThat(liquidated).isFalse();
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
        transactionExecutor);
  }

  private static TradingAccountEntity account(UUID accountId, String balance) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setBaseCurrency("USD");
    account.setBalance(new BigDecimal(balance));
    account.setEquity(new BigDecimal(balance));
    account.setUsedMargin(new BigDecimal("1000.00000000"));
    account.setFreeMargin(new BigDecimal(balance).subtract(account.getUsedMargin()));
    account.setLeverage(10);
    return account;
  }

  private static AccountSnapshot snapshot(
      UUID accountId,
      String equity,
      String usedMargin,
      BigDecimal maintenanceMargin
  ) {
    BigDecimal equityValue = new BigDecimal(equity);
    BigDecimal usedMarginValue = new BigDecimal(usedMargin);
    return new AccountSnapshot(
        accountId,
        new BigDecimal("1000.00000000"),
        BigDecimal.ZERO,
        equityValue,
        usedMarginValue,
        maintenanceMargin,
        equityValue.subtract(usedMarginValue),
        equityValue.multiply(new BigDecimal("100")).divide(usedMarginValue, 8, java.math.RoundingMode.HALF_UP),
        "USD");
  }

  private static PositionEntity openPosition(UUID accountId, UUID positionId, String symbol, String floatingPnl) {
    PositionEntity position = new PositionEntity();
    position.setId(positionId);
    position.setAccountId(accountId);
    position.setSymbol(symbol);
    position.setSide(OrderSide.BUY);
    position.setLots(new BigDecimal("0.10"));
    position.setOpenPrice(new BigDecimal("1.10000"));
    position.setFloatingPnl(new BigDecimal(floatingPnl));
    position.setStatus(PositionStatus.OPEN);
    return position;
  }

  private static PositionEntity openPerpPosition(
      UUID accountId,
      UUID positionId,
      String symbol,
      String maintenanceMargin,
      String floatingPnl
  ) {
    PositionEntity position = openPosition(accountId, positionId, symbol, floatingPnl);
    position.setMaintenanceMargin(new BigDecimal(maintenanceMargin));
    position.setNotional(new BigDecimal("50000.00000000"));
    position.setLeverage(10);
    return position;
  }

  private static SymbolEntity forexSymbol(String symbolCode) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol(symbolCode);
    symbol.setAssetClass("FOREX");
    symbol.setBaseCurrency(symbolCode.substring(0, 3));
    symbol.setQuoteCurrency(symbolCode.substring(3));
    return symbol;
  }

  private static SymbolEntity linearPerpSymbol(String symbolCode, BigDecimal liquidationFeeRate) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol(symbolCode);
    symbol.setAssetClass("LINEAR_PERPETUAL");
    symbol.setBaseCurrency(symbolCode.substring(0, 3));
    symbol.setQuoteCurrency("USDT");
    symbol.setLotSize(BigDecimal.ONE);
    symbol.setMaintenanceMarginRate(new BigDecimal("0.005"));
    symbol.setLiquidationFeeRate(liquidationFeeRate);
    return symbol;
  }

  private static SymbolEntity inversePerpSymbol(String symbolCode, BigDecimal liquidationFeeRate) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol(symbolCode);
    symbol.setAssetClass("INVERSE_PERPETUAL");
    symbol.setBaseCurrency("BTC");
    symbol.setQuoteCurrency("USD");
    symbol.setLotSize(new BigDecimal("100"));
    symbol.setContractSize(new BigDecimal("100"));
    symbol.setContractMultiplier(BigDecimal.ONE);
    symbol.setSettlementAsset("BTC");
    symbol.setMarginAsset("BTC");
    symbol.setMaintenanceMarginRate(new BigDecimal("0.005"));
    symbol.setLiquidationFeeRate(liquidationFeeRate);
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
