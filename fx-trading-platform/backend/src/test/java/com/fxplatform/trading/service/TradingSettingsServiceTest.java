package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.fxplatform.account.dto.UpdatePositionModeRequest;
import com.fxplatform.account.dto.UpdateSymbolSettingsRequest;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.risk.service.PerpMarginCalculator;
import com.fxplatform.risk.service.PerpetualRiskService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradingSettingsServiceTest {

  @Mock TradingAccountRepository accountRepository;
  @Mock SymbolRepository symbolRepository;
  @Mock AccountSymbolSettingRepository settingRepository;
  @Mock PositionRepository positionRepository;
  @Mock OrderRepository orderRepository;
  @Mock DemoExecutionGuard demoExecutionGuard;
  @Mock MarketBundleResolver marketBundleResolver;
  @Mock FullFillCoordinator fullFillCoordinator;
  @Mock LedgerService ledgerService;

  @Test
  void readReturnsOnlyEnabledTradablePerpetualsSortedWithLogicalDefaults() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(userId, accountId, PositionMode.HEDGE);
    SymbolEntity eth = symbol("ETHUSDT-PERP", ProductType.LINEAR_PERP, true, true, 80);
    SymbolEntity btc = symbol("BTCUSDT-PERP", ProductType.LINEAR_PERP, true, true, 120);
    SymbolEntity spot = symbol("BTCUSDT", ProductType.CRYPTO_SPOT, true, true, 100);
    SymbolEntity disabled = symbol("BNBUSDT-PERP", ProductType.LINEAR_PERP, false, true, 100);
    SymbolEntity nonTradable = symbol("SOLUSDT-PERP", ProductType.LINEAR_PERP, true, false, 100);
    AccountSymbolSettingEntity ethSetting = setting(
        accountId, "ETHUSDT-PERP", 20, MarginMode.ISOLATED, QuantityUnit.CONTRACTS, 3L);
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(symbolRepository.findByEnabledTrueOrderBySymbolAsc())
        .thenReturn(List.of(spot, nonTradable, eth, disabled, btc));
    when(settingRepository.findByAccountId(accountId)).thenReturn(List.of(ethSetting));

    var response = service().get(userId, accountId);

    assertThat(response.accountId()).isEqualTo(accountId);
    assertThat(response.positionMode()).isEqualTo(PositionMode.HEDGE);
    assertThat(response.symbols()).extracting(item -> item.symbol())
        .containsExactly("BTCUSDT-PERP", "ETHUSDT-PERP");
    assertThat(response.symbols().get(0).leverage()).isEqualTo(10);
    assertThat(response.symbols().get(0).marginMode()).isEqualTo(MarginMode.CROSS);
    assertThat(response.symbols().get(0).quantityUnit()).isEqualTo(QuantityUnit.BASE);
    assertThat(response.symbols().get(0).version()).isZero();
    assertThat(response.symbols().get(0).maxLeverage()).isEqualTo(100);
    assertThat(response.symbols().get(1).leverage()).isEqualTo(20);
    assertThat(response.symbols().get(1).version()).isEqualTo(3L);
    assertThat(response.symbols().get(1).maxLeverage()).isEqualTo(80);
    verify(settingRepository, never()).insertIfAbsent(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  void readRejectsAnAccountThatDoesNotBelongToTheUser() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.empty());

    assertCode("ACCOUNT_NOT_FOUND", () -> service().get(userId, accountId));
    verify(settingRepository, never()).findByAccountId(accountId);
  }

  @Test
  void positionModeWriterLocksOwnerAndRejectsOpenPerpetualPosition() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(userId, accountId, PositionMode.ONE_WAY);
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(account));
    when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of(new PositionEntity()));

    assertCode("POSITION_MODE_SWITCH_BLOCKED", () -> service().updatePositionMode(
        userId, accountId, new UpdatePositionModeRequest(PositionMode.HEDGE)));

    verify(demoExecutionGuard).requireDemoAccount(account);
    verify(orderRepository, never()).findActiveLinearPerpByAccountIdForUpdate(accountId);
    verify(accountRepository, never()).save(account);
  }

  @Test
  void positionModeWriterRejectsActivePerpetualOrderAndIgnoresTerminalScopeByQueryContract() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(userId, accountId, PositionMode.ONE_WAY);
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(account));
    when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId)).thenReturn(List.of());
    when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of(new OrderEntity()));

    assertCode("POSITION_MODE_SWITCH_BLOCKED", () -> service().updatePositionMode(
        userId, accountId, new UpdatePositionModeRequest(PositionMode.HEDGE)));
    verify(accountRepository, never()).save(account);
  }

  @Test
  void positionModeNoOpDoesNotInspectActiveStateOrWrite() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(userId, accountId, PositionMode.ONE_WAY);
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(account));
    when(symbolRepository.findByEnabledTrueOrderBySymbolAsc()).thenReturn(List.of());
    when(settingRepository.findByAccountId(accountId)).thenReturn(List.of());

    var response = service().updatePositionMode(
        userId, accountId, new UpdatePositionModeRequest(PositionMode.ONE_WAY));

    assertThat(response.positionMode()).isEqualTo(PositionMode.ONE_WAY);
    verify(positionRepository, never()).findOpenLinearPerpByAccountIdForUpdate(accountId);
    verify(orderRepository, never()).findActiveLinearPerpByAccountIdForUpdate(accountId);
    verify(accountRepository, never()).save(account);
  }

  @Test
  void staleSymbolSettingsVersionHasZeroMutation() {
    PatchFixture fixture = patchFixture(3L);

    assertCode("SETTINGS_VERSION_CONFLICT", () -> service().updateSymbolSettings(
        fixture.userId(), fixture.accountId(), "btc-usdt-perp",
        new UpdateSymbolSettingsRequest(20, null, null, 2L)));

    verify(settingRepository, never()).updateIfVersion(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    verify(positionRepository, never()).findOpenLinearPerpBySymbolForUpdate(
        fixture.accountId(), "BTCUSDT-PERP");
  }

  @Test
  void exactSymbolSettingsNoOpDoesNotIncrementOrInspectActiveState() {
    PatchFixture fixture = patchFixture(3L);

    var response = service().updateSymbolSettings(
        fixture.userId(), fixture.accountId(), "BTCUSDT-PERP",
        new UpdateSymbolSettingsRequest(10, MarginMode.CROSS, QuantityUnit.BASE, 3L));

    assertThat(response.symbols()).singleElement().satisfies(item -> {
      assertThat(item.version()).isEqualTo(3L);
      assertThat(item.leverage()).isEqualTo(10);
    });
    verify(settingRepository, never()).updateIfVersion(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    verify(positionRepository, never()).findOpenLinearPerpBySymbolForUpdate(
        fixture.accountId(), "BTCUSDT-PERP");
    verify(orderRepository, never()).findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), "BTCUSDT-PERP");
  }

  @Test
  void marginModeChangeFailsClosedOnTargetOpenPosition() {
    PatchFixture fixture = patchFixture(3L);
    when(positionRepository.findOpenLinearPerpBySymbolForUpdate(
        fixture.accountId(), "BTCUSDT-PERP")).thenReturn(List.of(new PositionEntity()));

    assertCode("MARGIN_MODE_SWITCH_BLOCKED", () -> service().updateSymbolSettings(
        fixture.userId(), fixture.accountId(), "BTCUSDT-PERP",
        new UpdateSymbolSettingsRequest(null, MarginMode.ISOLATED, null, 3L)));
    verify(settingRepository, never()).updateIfVersion(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void leverageIncreaseReleasesMarginAfterVersionWriteAndLeavesActiveOrderSnapshotUntouched() {
    PositionEntity position = position(
        PositionSide.BOTH, OrderSide.BUY, MarginMode.CROSS,
        "0.2", "50000", "1000", "1000", 4L);
    OrderEntity activeOrder = new OrderEntity();
    activeOrder.setId(UUID.randomUUID());
    activeOrder.setLeverage(10);
    activeOrder.setHoldAmount(new BigDecimal("250"));
    activeOrder.setStatus(OrderStatus.PENDING);
    LeverageFixture fixture = leverageFixture(
        MarginMode.CROSS, 20, "1000", "9000", List.of(position), List.of(activeOrder));

    var response = service().updateSymbolSettings(
        fixture.userId(), fixture.accountId(), "BTCUSDT-PERP",
        new UpdateSymbolSettingsRequest(20, null, null, 3L));

    assertThat(response.symbols()).singleElement().satisfies(
        setting -> assertThat(setting.leverage()).isEqualTo(20));
    assertThat(position.getInitialMargin()).isEqualByComparingTo("500.00000000");
    assertThat(position.getMarginHeld()).isEqualByComparingTo("500.00000000");
    assertThat(position.getMaintenanceMargin()).isEqualByComparingTo("50.50000000");
    assertThat(position.getMarkPrice()).isEqualByComparingTo("50500");
    assertThat(position.getLots()).isEqualByComparingTo("0.2");
    assertThat(position.getOpenPrice()).isEqualByComparingTo("50000");
    assertThat(position.getVersion()).isEqualTo(5L);
    assertThat(fixture.account().getUsedMargin()).isEqualByComparingTo("750.00000000");
    assertThat(fixture.account().getEquity()).isEqualByComparingTo("10100.00000000");
    assertThat(fixture.account().getFreeMargin()).isEqualByComparingTo("9350.00000000");
    assertThat(activeOrder.getLeverage()).isEqualTo(10);
    assertThat(activeOrder.getHoldAmount()).isEqualByComparingTo("250");
    verify(orderRepository).findActiveLinearPerpByAccountIdForUpdate(fixture.accountId());
    verify(orderRepository, never()).save(activeOrder);
    verify(ledgerService).recordMarginRelease(
        fixture.account(), new BigDecimal("500.00000000"), position.getId(),
        "Perpetual leverage margin recalculated");
    org.mockito.InOrder writes = org.mockito.Mockito.inOrder(
        settingRepository, accountRepository, positionRepository, ledgerService);
    writes.verify(settingRepository).updateIfVersion(
        fixture.accountId(), "BTCUSDT-PERP", 3L, 20, MarginMode.CROSS, QuantityUnit.BASE);
    writes.verify(accountRepository).save(fixture.account());
    writes.verify(positionRepository).save(position);
    writes.verify(ledgerService).recordMarginRelease(
        fixture.account(), new BigDecimal("500.00000000"), position.getId(),
        "Perpetual leverage margin recalculated");
  }

  @Test
  void leverageDecreaseReservesTheAggregateDifferenceOnce() {
    PositionEntity position = position(
        PositionSide.BOTH, OrderSide.BUY, MarginMode.CROSS,
        "0.2", "50000", "1000", "1000", 4L);
    LeverageFixture fixture = leverageFixture(
        MarginMode.CROSS, 5, "1000", "2000", List.of(position), List.of());

    service().updateSymbolSettings(
        fixture.userId(), fixture.accountId(), "BTCUSDT-PERP",
        new UpdateSymbolSettingsRequest(5, null, null, 3L));

    assertThat(position.getInitialMargin()).isEqualByComparingTo("2000.00000000");
    assertThat(position.getMarginHeld()).isEqualByComparingTo("2000.00000000");
    assertThat(fixture.account().getUsedMargin()).isEqualByComparingTo("2000.00000000");
    assertThat(fixture.account().getEquity()).isEqualByComparingTo("10100.00000000");
    assertThat(fixture.account().getFreeMargin()).isEqualByComparingTo("8100.00000000");
    verify(ledgerService, times(1)).recordMarginHold(
        fixture.account(), new BigDecimal("1000.00000000"), position.getId(),
        "Perpetual leverage margin recalculated");
  }

  @Test
  void leverageDecreaseWithInsufficientMarginHasZeroMutation() {
    PositionEntity position = position(
        PositionSide.BOTH, OrderSide.BUY, MarginMode.CROSS,
        "0.2", "50000", "1000", "1000", 4L);
    LeverageFixture fixture = leverageFixture(
        MarginMode.CROSS, 5, "1000", "500", List.of(position), List.of());
    fixture.account().setBalance(new BigDecimal("1000"));
    fixture.account().setEquity(new BigDecimal("1000"));

    assertCode("INSUFFICIENT_MARGIN", () -> service().updateSymbolSettings(
        fixture.userId(), fixture.accountId(), "BTCUSDT-PERP",
        new UpdateSymbolSettingsRequest(5, null, null, 3L)));

    assertThat(position.getLeverage()).isEqualTo(10);
    assertThat(position.getInitialMargin()).isEqualByComparingTo("1000");
    assertThat(position.getMarginHeld()).isEqualByComparingTo("1000");
    assertThat(fixture.account().getUsedMargin()).isEqualByComparingTo("1000");
    assertThat(fixture.account().getFreeMargin()).isEqualByComparingTo("500");
    verify(settingRepository, never()).updateIfVersion(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    verify(positionRepository, never()).save(position);
    verify(accountRepository, never()).save(fixture.account());
    verifyNoLedgerWrites();
  }

  @Test
  void hedgeLeverageChangePreservesSignedIsolatedAdjustmentsAndWritesOneAggregateRelease() {
    PositionEntity longLeg = position(
        PositionSide.LONG, OrderSide.BUY, MarginMode.ISOLATED,
        "0.2", "50000", "1200", "1000", 4L);
    PositionEntity shortLeg = position(
        PositionSide.SHORT, OrderSide.SELL, MarginMode.ISOLATED,
        "0.1", "50000", "400", "500", 7L);
    LeverageFixture fixture = leverageFixture(
        MarginMode.ISOLATED, 20, "1600", "8400", List.of(longLeg, shortLeg), List.of());

    service().updateSymbolSettings(
        fixture.userId(), fixture.accountId(), "BTCUSDT-PERP",
        new UpdateSymbolSettingsRequest(20, null, null, 3L));

    assertThat(longLeg.getInitialMargin()).isEqualByComparingTo("500.00000000");
    assertThat(longLeg.getMarginHeld()).isEqualByComparingTo("700.00000000");
    assertThat(shortLeg.getInitialMargin()).isEqualByComparingTo("250.00000000");
    assertThat(shortLeg.getMarginHeld()).isEqualByComparingTo("150.00000000");
    assertThat(fixture.account().getUsedMargin()).isEqualByComparingTo("850.00000000");
    assertThat(fixture.account().getEquity()).isEqualByComparingTo("10050.00000000");
    assertThat(fixture.account().getFreeMargin()).isEqualByComparingTo("9150.00000000");
    verify(positionRepository).save(longLeg);
    verify(positionRepository).save(shortLeg);
    verify(ledgerService, times(1)).recordMarginRelease(
        fixture.account(), new BigDecimal("750.00000000"), longLeg.getId(),
        "Perpetual leverage margin recalculated");
  }

  @Test
  void leverageChangeRejectsAnUnsafeSignedIsolatedPoolBeforeAnyWrite() {
    PositionEntity position = position(
        PositionSide.BOTH, OrderSide.BUY, MarginMode.ISOLATED,
        "0.2", "50000", "100", "1000", 4L);
    LeverageFixture fixture = leverageFixture(
        MarginMode.ISOLATED, 20, "100", "9900", List.of(position), List.of());

    assertCode("MARGIN_REDUCTION_UNSAFE", () -> service().updateSymbolSettings(
        fixture.userId(), fixture.accountId(), "BTCUSDT-PERP",
        new UpdateSymbolSettingsRequest(20, null, null, 3L)));

    assertThat(position.getMarginHeld()).isEqualByComparingTo("100");
    assertThat(position.getInitialMargin()).isEqualByComparingTo("1000");
    verify(settingRepository, never()).updateIfVersion(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    verify(positionRepository, never()).save(position);
    verify(accountRepository, never()).save(fixture.account());
    verifyNoLedgerWrites();
  }

  @Test
  void staleLeverageMarkRetriesOutsideLocksAndCommitsOnlyTheSuccessfulAttempt() {
    PositionEntity position = position(
        PositionSide.BOTH, OrderSide.BUY, MarginMode.CROSS,
        "0.2", "50000", "1000", "1000", 4L);
    LeverageFixture fixture = leverageFixture(
        MarginMode.CROSS, 20, "1000", "9000", List.of(position), List.of());
    doThrow(new BusinessException(ErrorCode.MARKET_DATA_STALE, "stale"))
        .doNothing()
        .when(fullFillCoordinator).requireFresh(
            org.mockito.ArgumentMatchers.any(ExecutableMarketSnapshot.class));

    service().updateSymbolSettings(
        fixture.userId(), fixture.accountId(), "BTCUSDT-PERP",
        new UpdateSymbolSettingsRequest(20, null, null, 3L));

    verify(marketBundleResolver, times(2)).resolvePerp(
        org.mockito.ArgumentMatchers.eq("BTCUSDT-PERP"), org.mockito.ArgumentMatchers.any());
    verify(accountRepository, times(2)).findByIdAndUserIdForUpdate(
        fixture.accountId(), fixture.userId());
    verify(settingRepository, times(1)).updateIfVersion(
        fixture.accountId(), "BTCUSDT-PERP", 3L, 20, MarginMode.CROSS, QuantityUnit.BASE);
    verify(positionRepository, times(1)).save(position);
    verify(ledgerService, times(1)).recordMarginRelease(
        fixture.account(), new BigDecimal("500.00000000"), position.getId(),
        "Perpetual leverage margin recalculated");
    org.mockito.InOrder lockOrder = org.mockito.Mockito.inOrder(
        marketBundleResolver, accountRepository);
    lockOrder.verify(marketBundleResolver).resolvePerp(
        org.mockito.ArgumentMatchers.eq("BTCUSDT-PERP"), org.mockito.ArgumentMatchers.any());
    lockOrder.verify(accountRepository).findByIdAndUserIdForUpdate(
        fixture.accountId(), fixture.userId());
    lockOrder.verify(marketBundleResolver).resolvePerp(
        org.mockito.ArgumentMatchers.eq("BTCUSDT-PERP"), org.mockito.ArgumentMatchers.any());
    lockOrder.verify(accountRepository).findByIdAndUserIdForUpdate(
        fixture.accountId(), fixture.userId());
  }

  @Test
  void staleLeverageResolverFailureRetriesBeforeTakingTheAccountLock() {
    PositionEntity position = position(
        PositionSide.BOTH, OrderSide.BUY, MarginMode.CROSS,
        "0.2", "50000", "1000", "1000", 4L);
    LeverageFixture fixture = leverageFixture(
        MarginMode.CROSS, 20, "1000", "9000", List.of(position), List.of());
    when(marketBundleResolver.resolvePerp(
        org.mockito.ArgumentMatchers.eq("BTCUSDT-PERP"), org.mockito.ArgumentMatchers.any()))
        .thenThrow(new BusinessException(ErrorCode.MARKET_DATA_STALE, "resolver stale"))
        .thenReturn(perpetualBundle("50500"));

    service().updateSymbolSettings(
        fixture.userId(), fixture.accountId(), "BTCUSDT-PERP",
        new UpdateSymbolSettingsRequest(20, null, null, 3L));

    verify(marketBundleResolver, times(2)).resolvePerp(
        org.mockito.ArgumentMatchers.eq("BTCUSDT-PERP"), org.mockito.ArgumentMatchers.any());
    verify(accountRepository, times(1)).findByIdAndUserIdForUpdate(
        fixture.accountId(), fixture.userId());
    verify(positionRepository, times(1)).save(position);
  }

  @Test
  void leverageDecreaseRejectsFreshLossFromAnotherCrossSymbolDespitePersistedFreeMargin() {
    PositionEntity btc = position(
        PositionSide.BOTH, OrderSide.BUY, MarginMode.CROSS,
        "0.2", "50000", "1000", "1000", 4L);
    LeverageFixture fixture = leverageFixture(
        MarginMode.CROSS, 5, "2000", "9000", List.of(btc), List.of());
    PositionEntity eth = position(
        PositionSide.BOTH, OrderSide.BUY, MarginMode.CROSS,
        "1", "10000", "1000", "1000", 8L);
    eth.setSymbol("ETHUSDT-PERP");
    eth.setAccountId(fixture.accountId());
    when(positionRepository.findOpenLinearPerpByAccountId(fixture.accountId()))
        .thenReturn(List.of(btc, eth));
    when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(fixture.accountId()))
        .thenReturn(List.of(btc, eth));
    when(symbolRepository.findBySymbol("ETHUSDT-PERP")).thenReturn(Optional.of(
        symbol("ETHUSDT-PERP", ProductType.LINEAR_PERP, true, true, 100)));
    when(marketBundleResolver.resolvePerp(
        org.mockito.ArgumentMatchers.eq("ETHUSDT-PERP"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(perpetualBundle("ETHUSDT-PERP", "1000"));

    assertCode(ErrorCode.INSUFFICIENT_MARGIN, () -> service().updateSymbolSettings(
        fixture.userId(), fixture.accountId(), "BTCUSDT-PERP",
        new UpdateSymbolSettingsRequest(5, null, null, 3L)));

    verify(settingRepository, never()).updateIfVersion(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    verify(positionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    verify(accountRepository, never()).save(fixture.account());
  }

  @Test
  void leverageDecreaseUsesFreshProfitAcrossSymbolsAndResolvesBeforeTheAccountLock() {
    PositionEntity btc = position(
        PositionSide.BOTH, OrderSide.BUY, MarginMode.CROSS,
        "0.2", "50000", "1000", "1000", 4L);
    LeverageFixture fixture = leverageFixture(
        MarginMode.CROSS, 5, "1100", "100", List.of(btc), List.of());
    PositionEntity eth = position(
        PositionSide.BOTH, OrderSide.BUY, MarginMode.CROSS,
        "1", "1000", "100", "100", 8L);
    eth.setSymbol("ETHUSDT-PERP");
    eth.setAccountId(fixture.accountId());
    when(positionRepository.findOpenLinearPerpByAccountId(fixture.accountId()))
        .thenReturn(List.of(btc, eth));
    when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(fixture.accountId()))
        .thenReturn(List.of(btc, eth));
    when(symbolRepository.findBySymbol("ETHUSDT-PERP")).thenReturn(Optional.of(
        symbol("ETHUSDT-PERP", ProductType.LINEAR_PERP, true, true, 100)));
    when(marketBundleResolver.resolvePerp(
        org.mockito.ArgumentMatchers.eq("ETHUSDT-PERP"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(perpetualBundle("ETHUSDT-PERP", "10000"));

    service().updateSymbolSettings(
        fixture.userId(), fixture.accountId(), "BTCUSDT-PERP",
        new UpdateSymbolSettingsRequest(5, null, null, 3L));

    assertThat(fixture.account().getEquity()).isEqualByComparingTo("19100.00000000");
    assertThat(fixture.account().getUsedMargin()).isEqualByComparingTo("2100.00000000");
    assertThat(fixture.account().getFreeMargin()).isEqualByComparingTo("17000.00000000");
    assertThat(btc.getVersion()).isEqualTo(5L);
    assertThat(eth.getVersion()).isEqualTo(9L);
    verify(marketBundleResolver, times(1)).resolvePerp(
        org.mockito.ArgumentMatchers.eq("BTCUSDT-PERP"), org.mockito.ArgumentMatchers.any());
    verify(marketBundleResolver, times(1)).resolvePerp(
        org.mockito.ArgumentMatchers.eq("ETHUSDT-PERP"), org.mockito.ArgumentMatchers.any());
    org.mockito.InOrder providerBeforeLock = org.mockito.Mockito.inOrder(
        marketBundleResolver, accountRepository);
    providerBeforeLock.verify(marketBundleResolver).resolvePerp(
        org.mockito.ArgumentMatchers.eq("BTCUSDT-PERP"), org.mockito.ArgumentMatchers.any());
    providerBeforeLock.verify(marketBundleResolver).resolvePerp(
        org.mockito.ArgumentMatchers.eq("ETHUSDT-PERP"), org.mockito.ArgumentMatchers.any());
    providerBeforeLock.verify(accountRepository).findByIdAndUserIdForUpdate(
        fixture.accountId(), fixture.userId());
  }

  @Test
  void leverageReleaseRejectsWhenInternalIsolatedCloseHoldsReachTheNewSlotCapacity() {
    PositionEntity isolated = position(
        PositionSide.BOTH, OrderSide.BUY, MarginMode.ISOLATED,
        "0.2", "50000", "1000", "1000", 4L);
    OrderEntity internalClose = activePerpetualOrder(
        isolated.getId(), MarginMode.ISOLATED, "545");
    LeverageFixture fixture = leverageFixture(
        MarginMode.ISOLATED, 20, "1545", "9000", List.of(isolated), List.of(internalClose));

    assertCode(ErrorCode.MARGIN_REDUCTION_UNSAFE, () -> service().updateSymbolSettings(
        fixture.userId(), fixture.accountId(), "BTCUSDT-PERP",
        new UpdateSymbolSettingsRequest(20, null, null, 3L)));

    assertThat(isolated.getMarginHeld()).isEqualByComparingTo("1000");
    assertThat(isolated.getVersion()).isEqualTo(4L);
    verify(settingRepository, never()).updateIfVersion(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    verify(positionRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void marginModeChangeLocksPositionsThenRejectsTargetActivePerpetualOrder() {
    PatchFixture fixture = patchFixture(3L);
    when(positionRepository.findOpenLinearPerpBySymbolForUpdate(
        fixture.accountId(), "BTCUSDT-PERP")).thenReturn(List.of());
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), "BTCUSDT-PERP")).thenReturn(List.of(new OrderEntity()));

    assertCode("MARGIN_MODE_SWITCH_BLOCKED", () -> service().updateSymbolSettings(
        fixture.userId(), fixture.accountId(), "BTCUSDT-PERP",
        new UpdateSymbolSettingsRequest(null, MarginMode.ISOLATED, null, 3L)));

    org.mockito.InOrder lockOrder = org.mockito.Mockito.inOrder(
        accountRepository,
        demoExecutionGuard,
        settingRepository,
        positionRepository,
        orderRepository);
    lockOrder.verify(accountRepository).findByIdAndUserIdForUpdate(
        fixture.accountId(), fixture.userId());
    lockOrder.verify(demoExecutionGuard).requireDemoAccount(
        org.mockito.ArgumentMatchers.any(TradingAccountEntity.class));
    lockOrder.verify(settingRepository).findByAccountIdAndSymbolForUpdate(
        fixture.accountId(), "BTCUSDT-PERP");
    lockOrder.verify(positionRepository).findOpenLinearPerpBySymbolForUpdate(
        fixture.accountId(), "BTCUSDT-PERP");
    lockOrder.verify(orderRepository).findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), "BTCUSDT-PERP");
  }

  @Test
  void quantityUnitOnlyUpdateIsAllowedWithActiveStateAndUsesCompositeVersionUpdate() {
    PatchFixture fixture = patchFixture(3L);
    when(settingRepository.updateIfVersion(
        fixture.accountId(), "BTCUSDT-PERP", 3L, 10, MarginMode.CROSS, QuantityUnit.QUOTE))
        .thenReturn(1);
    AccountSymbolSettingEntity updated = setting(
        fixture.accountId(), "BTCUSDT-PERP", 10, MarginMode.CROSS, QuantityUnit.QUOTE, 4L);
    when(settingRepository.findByAccountId(fixture.accountId())).thenReturn(List.of(updated));

    var response = service().updateSymbolSettings(
        fixture.userId(), fixture.accountId(), "BTCUSDT-PERP",
        new UpdateSymbolSettingsRequest(null, null, QuantityUnit.QUOTE, 3L));

    assertThat(response.symbols()).singleElement().satisfies(item -> {
      assertThat(item.quantityUnit()).isEqualTo(QuantityUnit.QUOTE);
      assertThat(item.version()).isEqualTo(4L);
    });
    verify(positionRepository, never()).findOpenLinearPerpBySymbolForUpdate(
        fixture.accountId(), "BTCUSDT-PERP");
    verify(orderRepository, never()).findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), "BTCUSDT-PERP");
  }

  @Test
  void missingVersionZeroRowUsesInsertAndLeverageHonorsEffectiveMaximum() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(userId, accountId, PositionMode.ONE_WAY);
    SymbolEntity symbol = symbol("BTCUSDT-PERP", ProductType.LINEAR_PERP, true, true, 50);
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT-PERP")).thenReturn(Optional.of(symbol));
    when(settingRepository.findByAccountIdAndSymbolForUpdate(accountId, "BTCUSDT-PERP"))
        .thenReturn(Optional.empty());
    when(settingRepository.findByAccountIdAndSymbol(accountId, "BTCUSDT-PERP"))
        .thenReturn(Optional.empty());
    when(marketBundleResolver.resolvePerp(
        org.mockito.ArgumentMatchers.eq("BTCUSDT-PERP"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(perpetualBundle("50500"));
    when(settingRepository.insertIfAbsent(
        accountId, "BTCUSDT-PERP", 50, MarginMode.CROSS, QuantityUnit.BASE)).thenReturn(1);
    when(symbolRepository.findByEnabledTrueOrderBySymbolAsc()).thenReturn(List.of(symbol));
    when(settingRepository.findByAccountId(accountId)).thenReturn(List.of(setting(
        accountId, "BTCUSDT-PERP", 50, MarginMode.CROSS, QuantityUnit.BASE, 1L)));

    var response = service().updateSymbolSettings(
        userId, accountId, "btc/usdt-perp",
        new UpdateSymbolSettingsRequest(50, null, null, 0L));

    assertThat(response.symbols()).singleElement().satisfies(item -> {
      assertThat(item.leverage()).isEqualTo(50);
      assertThat(item.version()).isEqualTo(1L);
      assertThat(item.maxLeverage()).isEqualTo(50);
    });
    assertCode("LEVERAGE_OUT_OF_RANGE", () -> service().updateSymbolSettings(
        userId, accountId, "BTCUSDT-PERP",
        new UpdateSymbolSettingsRequest(51, null, null, 0L)));
    assertCode("LEVERAGE_OUT_OF_RANGE", () -> service().updateSymbolSettings(
        userId, accountId, "BTCUSDT-PERP",
        new UpdateSymbolSettingsRequest(0, null, null, 0L)));
  }

  private PatchFixture patchFixture(long version) {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(userId, accountId, PositionMode.ONE_WAY);
    SymbolEntity symbol = symbol("BTCUSDT-PERP", ProductType.LINEAR_PERP, true, true, 100);
    AccountSymbolSettingEntity current = setting(
        accountId, "BTCUSDT-PERP", 10, MarginMode.CROSS, QuantityUnit.BASE, version);
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT-PERP")).thenReturn(Optional.of(symbol));
    when(symbolRepository.findByEnabledTrueOrderBySymbolAsc()).thenReturn(List.of(symbol));
    when(settingRepository.findByAccountIdAndSymbolForUpdate(accountId, "BTCUSDT-PERP"))
        .thenReturn(Optional.of(current));
    when(settingRepository.findByAccountIdAndSymbol(accountId, "BTCUSDT-PERP"))
        .thenReturn(Optional.of(current));
    when(marketBundleResolver.resolvePerp(
        org.mockito.ArgumentMatchers.eq("BTCUSDT-PERP"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(perpetualBundle("50500"));
    when(settingRepository.findByAccountId(accountId)).thenReturn(List.of(current));
    return new PatchFixture(userId, accountId);
  }

  private LeverageFixture leverageFixture(
      MarginMode marginMode,
      int nextLeverage,
      String usedMargin,
      String freeMargin,
      List<PositionEntity> positions,
      List<OrderEntity> activeOrders
  ) {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(userId, accountId, PositionMode.HEDGE);
    account.setUsedMargin(new BigDecimal(usedMargin));
    account.setFreeMargin(new BigDecimal(freeMargin));
    SymbolEntity symbol = symbol("BTCUSDT-PERP", ProductType.LINEAR_PERP, true, true, 100);
    AccountSymbolSettingEntity current = setting(
        accountId, "BTCUSDT-PERP", 10, marginMode, QuantityUnit.BASE, 3L);
    AccountSymbolSettingEntity updated = setting(
        accountId, "BTCUSDT-PERP", nextLeverage, marginMode, QuantityUnit.BASE, 4L);
    positions.forEach(position -> position.setAccountId(accountId));
    activeOrders.forEach(order -> {
      order.setAccountId(accountId);
      order.setSymbol("BTCUSDT-PERP");
      order.setProductType(ProductType.LINEAR_PERP);
      if (order.getMarginMode() == null) {
        order.setMarginMode(MarginMode.CROSS);
      }
    });
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT-PERP")).thenReturn(Optional.of(symbol));
    when(symbolRepository.findByEnabledTrueOrderBySymbolAsc()).thenReturn(List.of(symbol));
    when(settingRepository.findByAccountIdAndSymbol(accountId, "BTCUSDT-PERP"))
        .thenReturn(Optional.of(current));
    when(settingRepository.findByAccountIdAndSymbolForUpdate(accountId, "BTCUSDT-PERP"))
        .thenReturn(Optional.of(current));
    when(settingRepository.findByAccountId(accountId)).thenReturn(List.of(updated));
    when(positionRepository.findOpenLinearPerpBySymbolForUpdate(accountId, "BTCUSDT-PERP"))
        .thenReturn(positions);
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(accountId, "BTCUSDT-PERP"))
        .thenReturn(activeOrders);
    when(positionRepository.findOpenLinearPerpByAccountId(accountId)).thenReturn(positions);
    when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId)).thenReturn(positions);
    when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId)).thenReturn(activeOrders);
    when(settingRepository.updateIfVersion(
        accountId, "BTCUSDT-PERP", 3L, nextLeverage, marginMode, QuantityUnit.BASE))
        .thenReturn(1);
    when(marketBundleResolver.resolvePerp(
        org.mockito.ArgumentMatchers.eq("BTCUSDT-PERP"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(perpetualBundle("50500"));
    return new LeverageFixture(userId, accountId, account);
  }

  private TradingSettingsService service() {
    return new TradingSettingsService(
        accountRepository,
        symbolRepository,
        settingRepository,
        positionRepository,
        orderRepository,
        demoExecutionGuard,
        marketBundleResolver,
        new PerpetualRiskService(new PerpMarginCalculator()),
        accountRiskSnapshotService(),
        ledgerService,
        new TradingTransactionExecutor());
  }

  private PerpetualAccountRiskSnapshotService accountRiskSnapshotService() {
    return new PerpetualAccountRiskSnapshotService(
        positionRepository,
        symbolRepository,
        marketBundleResolver,
        fullFillCoordinator,
        new PerpetualRiskService(new PerpMarginCalculator()));
  }

  private static TradingAccountEntity account(UUID userId, UUID accountId, PositionMode mode) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    account.setPositionMode(mode);
    account.setBaseCurrency("USDT");
    account.setBalance(new BigDecimal("10000"));
    account.setEquity(new BigDecimal("10000"));
    account.setUsedMargin(BigDecimal.ZERO);
    account.setFreeMargin(new BigDecimal("10000"));
    return account;
  }

  private static SymbolEntity symbol(
      String code,
      ProductType productType,
      boolean enabled,
      boolean tradable,
      int maxLeverage
  ) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(UUID.randomUUID());
    symbol.setSymbol(code);
    symbol.setProductType(productType);
    symbol.setEnabled(enabled);
    symbol.setTradable(tradable);
    symbol.setLeverage(maxLeverage);
    symbol.setMaintenanceMarginRate(new BigDecimal("0.005"));
    return symbol;
  }

  private static PositionEntity position(
      PositionSide positionSide,
      OrderSide side,
      MarginMode marginMode,
      String quantity,
      String entry,
      String marginHeld,
      String initialMargin,
      long version
  ) {
    PositionEntity position = new PositionEntity();
    position.setId(UUID.randomUUID());
    position.setSymbol("BTCUSDT-PERP");
    position.setProductType(ProductType.LINEAR_PERP);
    position.setPositionMode(positionSide == PositionSide.BOTH ? PositionMode.ONE_WAY : PositionMode.HEDGE);
    position.setPositionSide(positionSide);
    position.setMarginMode(marginMode);
    position.setSide(side);
    position.setLots(new BigDecimal(quantity));
    position.setOpenPrice(new BigDecimal(entry));
    position.setMarginHeld(new BigDecimal(marginHeld));
    position.setInitialMargin(new BigDecimal(initialMargin));
    position.setFundingPnl(BigDecimal.ZERO);
    position.setRealizedPnl(new BigDecimal("7"));
    position.setLeverage(10);
    position.setStatus(PositionStatus.OPEN);
    position.setVersion(version);
    return position;
  }

  private static PerpetualMarketBundle perpetualBundle(String mark) {
    return perpetualBundle("BTCUSDT-PERP", mark);
  }

  private static PerpetualMarketBundle perpetualBundle(String symbol, String mark) {
    Instant asOf = Instant.parse("2026-07-12T08:00:00Z");
    return new PerpetualMarketBundle(
        symbol, symbol, "local-perp", MarketSourceMode.LOCAL_SIMULATED,
        new BigDecimal("50499"), new BigDecimal("50501"), new BigDecimal("50500"),
        new BigDecimal(mark), new BigDecimal(mark), null, List.of(), List.of(),
        asOf, asOf.plusSeconds(60));
  }

  private static OrderEntity activePerpetualOrder(
      UUID parentPositionId,
      MarginMode marginMode,
      String hold
  ) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setProductType(ProductType.LINEAR_PERP);
    order.setSymbol("BTCUSDT-PERP");
    order.setMarginMode(marginMode);
    order.setParentPositionId(parentPositionId);
    order.setHoldAmount(new BigDecimal(hold));
    order.setStatus(OrderStatus.PENDING);
    return order;
  }

  private void verifyNoLedgerWrites() {
    verify(ledgerService, never()).recordMarginHold(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    verify(ledgerService, never()).recordMarginRelease(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  private static AccountSymbolSettingEntity setting(
      UUID accountId,
      String symbol,
      int leverage,
      MarginMode marginMode,
      QuantityUnit quantityUnit,
      long version
  ) {
    AccountSymbolSettingEntity setting = new AccountSymbolSettingEntity();
    setting.setAccountId(accountId);
    setting.setSymbol(symbol);
    setting.setLeverage(leverage);
    setting.setMarginMode(marginMode);
    setting.setQuantityUnit(quantityUnit);
    setting.setVersion(version);
    return setting;
  }

  private static void assertCode(
      String code,
      org.assertj.core.api.ThrowableAssert.ThrowingCallable action
  ) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(code));
  }

  private record PatchFixture(UUID userId, UUID accountId) {
  }

  private record LeverageFixture(
      UUID userId,
      UUID accountId,
      TradingAccountEntity account
  ) {
  }
}
