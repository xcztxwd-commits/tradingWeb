package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.dto.AccountSnapshot;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.account.service.AccountSnapshotService;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.repository.RiskConfigRepository;
import com.fxplatform.risk.service.PerpMarginCalculator;
import com.fxplatform.risk.service.PerpetualRiskService;
import com.fxplatform.risk.service.PerpetualRiskService.PositionRisk;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class LiquidationServiceTest {

  private static final BigDecimal MAINTENANCE_MARGIN_RATE = new BigDecimal("0.005");
  private static final Instant NOW = Instant.parse("2026-07-12T10:00:00Z");

  @Mock AccountSnapshotService accountSnapshotService;
  @Mock TradingAccountRepository accountRepository;
  @Mock PositionRepository positionRepository;
  @Mock PositionService positionService;
  @Mock SymbolRepository symbolRepository;
  @Mock RiskConfigRepository riskConfigRepository;
  @Mock LedgerService ledgerService;
  @Mock QuoteService quoteService;
  @Mock WalletService walletService;
  @Mock DemoExecutionGuard demoExecutionGuard;
  @Mock TradingTransactionExecutor transactionExecutor;

  private final PerpetualRiskService perpetualRiskService =
      new PerpetualRiskService(new PerpMarginCalculator());

  @BeforeEach
  void setUpCompatibilityPath() {
    lenient().when(accountRepository.findById(any(UUID.class)))
        .thenAnswer(invocation -> Optional.of(account(invocation.getArgument(0), "10000")));
    lenient().when(accountRepository.findByIdForUpdate(any(UUID.class)))
        .thenAnswer(invocation -> accountRepository.findById(invocation.getArgument(0)));
    lenient().when(riskConfigRepository.findFirstEnabledWithStopOutLevel())
        .thenReturn(Optional.empty());
    lenient().when(transactionExecutor.execute(any()))
        .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
  }

  @Test
  void exactMaintenancePlusEstimatedTakerFeeBoundaryIsLiquidatable() {
    PositionRisk risk = isolatedRisk("5.52250000", "95");

    assertThat(risk.maintenanceMargin()).isEqualByComparingTo("0.47500000");
    assertThat(risk.estimatedCloseTakerFee()).isEqualByComparingTo("0.04750000");
    assertThat(risk.isolatedEquity()).isEqualByComparingTo(risk.liquidationThreshold());
    assertThat(risk.liquidatable()).isTrue();
  }

  @Test
  void oneMinimalMoneyUnitAboveMaintenanceAndTakerFeeIsSafe() {
    PositionRisk risk = isolatedRisk("5.52250001", "95");

    assertThat(risk.isolatedEquity().subtract(risk.liquidationThreshold()))
        .isEqualByComparingTo("0.00000001");
    assertThat(risk.liquidatable()).isFalse();
  }

  @Test
  void liquidationFeeRateNeverChangesTheTrigger() {
    SymbolEntity zeroFee = linearPerpSymbol(BigDecimal.ZERO);
    SymbolEntity extremeFee = linearPerpSymbol(new BigDecimal("0.50000000"));

    PositionRisk zeroFeeRisk = isolatedRisk(zeroFee, "5.52250000", "95");
    PositionRisk extremeFeeRisk = isolatedRisk(extremeFee, "5.52250000", "95");

    assertThat(zeroFeeRisk.liquidationThreshold())
        .isEqualByComparingTo(extremeFeeRisk.liquidationThreshold());
    assertThat(zeroFeeRisk.liquidatable()).isEqualTo(extremeFeeRisk.liquidatable()).isTrue();
  }

  @Test
  void markPriceRatherThanLastOrMidDrivesPerpetualRisk() {
    ExecutableMarketSnapshot bundle = new ExecutableMarketSnapshot(
        "BTCUSDT-PERP",
        ProductType.LINEAR_PERP,
        "binance-usdm",
        "BTCUSDT",
        MarketSourceMode.PUBLIC_EXTERNAL,
        decimal("104"),
        decimal("106"),
        decimal("105"),
        decimal("95"),
        decimal("96"),
        NOW.minusSeconds(1),
        NOW.plusSeconds(30));

    PositionRisk markRisk = isolatedRisk("5.52250000", bundle.mark().toPlainString());
    PositionRisk lastRisk = isolatedRisk("5.52250000", bundle.last().toPlainString());

    assertThat(markRisk.liquidatable()).isTrue();
    assertThat(lastRisk.liquidatable()).isFalse();
  }

  @Test
  void fxStopOutCompatibilityStillClosesLargestLossFirst() {
    UUID accountId = UUID.randomUUID();
    PositionEntity smallerLoss = forexPosition(accountId, "EURUSD", "-25");
    PositionEntity biggerLoss = forexPosition(accountId, "GBPUSD", "-80");

    when(accountSnapshotService.snapshot(accountId))
        .thenReturn(snapshot(accountId, "480", "1000"));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(
        accountId, PositionStatus.OPEN))
        .thenReturn(List.of(smallerLoss, biggerLoss));
    when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(forexSymbol("EURUSD")));
    when(symbolRepository.findBySymbol("GBPUSD")).thenReturn(Optional.of(forexSymbol("GBPUSD")));
    when(positionService.closeSystemPosition(
        accountId, biggerLoss.getId(), LiquidationService.FX_MARGIN_STOP_OUT))
        .thenAnswer(invocation -> {
          biggerLoss.setStatus(PositionStatus.CLOSED);
          return null;
        });
    when(positionService.closeSystemPosition(
        accountId, smallerLoss.getId(), LiquidationService.FX_MARGIN_STOP_OUT))
        .thenAnswer(invocation -> {
          smallerLoss.setStatus(PositionStatus.CLOSED);
          return null;
        });

    assertThat(service().scanAccount(accountId)).isEqualTo(2);

    InOrder order = inOrder(positionService);
    order.verify(positionService).closeSystemPosition(
        accountId, biggerLoss.getId(), LiquidationService.FX_MARGIN_STOP_OUT);
    order.verify(positionService).closeSystemPosition(
        accountId, smallerLoss.getId(), LiquidationService.FX_MARGIN_STOP_OUT);
  }

  @Test
  void accountScanFailureDoesNotPreventTheNextAccountFromBeingScanned() {
    UUID failedAccountId = UUID.randomUUID();
    UUID healthyAccountId = UUID.randomUUID();
    when(accountRepository.findDemoLiquidationScanCandidates()).thenReturn(List.of(
        account(failedAccountId, "1000"),
        account(healthyAccountId, "1000")));
    when(accountSnapshotService.snapshot(failedAccountId))
        .thenThrow(new IllegalStateException("provider unavailable"));
    when(accountSnapshotService.snapshot(healthyAccountId))
        .thenReturn(snapshot(healthyAccountId, "1000", "1000"));

    assertThat(service().scanAllAccounts()).isZero();
    verify(accountSnapshotService).snapshot(healthyAccountId);
  }

  @Test
  void scanEntryPointsDoNotHoldOneTransactionAcrossFreshMarketCalls() throws Exception {
    assertThat(LiquidationService.class.getMethod("scanAccount", UUID.class)
        .isAnnotationPresent(Transactional.class)).isFalse();
    assertThat(LiquidationService.class.getMethod("scanAllAccounts")
        .isAnnotationPresent(Transactional.class)).isFalse();
  }

  @Test
  void safeFxAccountDoesNotClosePositions() {
    UUID accountId = UUID.randomUUID();
    PositionEntity position = forexPosition(accountId, "EURUSD", "-10");
    when(accountSnapshotService.snapshot(accountId))
        .thenReturn(snapshot(accountId, "2500", "1000"));

    assertThat(service().scanAccount(accountId)).isZero();
    verify(positionService, never()).closeSystemPosition(
        accountId, position.getId(), LiquidationService.FX_MARGIN_STOP_OUT);
  }

  @Test
  void adminRiskReductionGateSkipsConcurrentLiquidationScan() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity gated = account(accountId, "1000");
    gated.setStatus(AccountStatus.RISK_REDUCTION_PENDING);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(gated));

    assertThat(service().scanAccount(accountId)).isZero();

    verify(accountSnapshotService, never()).snapshot(accountId);
    verify(positionRepository, never()).findOpenLinearPerpByAccountId(accountId);
  }

  private PositionRisk isolatedRisk(String marginHeld, String markPrice) {
    return isolatedRisk(linearPerpSymbol(BigDecimal.ZERO), marginHeld, markPrice);
  }

  private PositionRisk isolatedRisk(
      SymbolEntity symbol,
      String marginHeld,
      String markPrice
  ) {
    return perpetualRiskService.positionRisk(
        OrderSide.BUY,
        BigDecimal.ONE,
        decimal("100"),
        decimal(markPrice),
        10,
        decimal(marginHeld),
        BigDecimal.ZERO,
        symbol.getMaintenanceMarginRate());
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
    account.setBalance(decimal(balance));
    account.setEquity(decimal(balance));
    account.setUsedMargin(decimal("1000"));
    account.setFreeMargin(decimal(balance).subtract(account.getUsedMargin()));
    account.setLeverage(10);
    return account;
  }

  private static AccountSnapshot snapshot(UUID accountId, String equity, String usedMargin) {
    BigDecimal equityValue = decimal(equity);
    BigDecimal usedMarginValue = decimal(usedMargin);
    return new AccountSnapshot(
        accountId,
        decimal("1000"),
        BigDecimal.ZERO,
        equityValue,
        usedMarginValue,
        BigDecimal.ZERO,
        equityValue.subtract(usedMarginValue),
        equityValue.multiply(decimal("100")).divide(usedMarginValue, 8, RoundingMode.HALF_UP),
        "USDT");
  }

  private static PositionEntity forexPosition(UUID accountId, String symbol, String floatingPnl) {
    PositionEntity position = new PositionEntity();
    position.setId(UUID.randomUUID());
    position.setAccountId(accountId);
    position.setSymbol(symbol);
    position.setProductType(ProductType.FX_MARGIN);
    position.setSide(OrderSide.BUY);
    position.setLots(decimal("0.10"));
    position.setOpenPrice(decimal("1.10"));
    position.setFloatingPnl(decimal(floatingPnl));
    position.setStatus(PositionStatus.OPEN);
    return position;
  }

  private static SymbolEntity forexSymbol(String symbolCode) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol(symbolCode);
    symbol.setProductType(ProductType.FX_MARGIN);
    symbol.setAssetClass("FOREX");
    symbol.setBaseCurrency(symbolCode.substring(0, 3));
    symbol.setQuoteCurrency(symbolCode.substring(3));
    return symbol;
  }

  private static SymbolEntity linearPerpSymbol(BigDecimal liquidationFeeRate) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol("BTCUSDT-PERP");
    symbol.setProductType(ProductType.LINEAR_PERP);
    symbol.setAssetClass("CRYPTO_PERPETUAL");
    symbol.setMaintenanceMarginRate(MAINTENANCE_MARGIN_RATE);
    symbol.setLiquidationFeeRate(liquidationFeeRate);
    symbol.setSettlementAsset("USDT");
    symbol.setMarginAsset("USDT");
    return symbol;
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }
}
