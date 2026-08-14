package com.fxplatform.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.ledger.entity.LedgerEntryEntity;
import com.fxplatform.ledger.enums.LedgerEntryType;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.entity.SpotPositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.event.TradingAccountMutationEvent;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.SpotPositionRepository;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.enums.WalletType;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class DemoAccountLifecycleServiceTest {

  @Mock TradingAccountRepository accountRepository;
  @Mock WalletService walletService;
  @Mock LedgerService ledgerService;
  @Mock AccountSymbolSettingRepository settingRepository;
  @Mock SpotPositionRepository spotPositionRepository;
  @Mock PositionRepository positionRepository;
  @Mock OrderRepository orderRepository;
  @Mock DemoExecutionGuard demoExecutionGuard;
  @Mock AuditLogService auditLogService;
  @Mock ApplicationEventPublisher eventPublisher;

  private DemoAccountLifecycleService service;

  @BeforeEach
  void setUp() {
    service = new DemoAccountLifecycleService(
        accountRepository,
        walletService,
        ledgerService,
        settingRepository,
        spotPositionRepository,
        positionRepository,
        orderRepository,
        demoExecutionGuard,
        auditLogService,
        eventPublisher);
  }

  @Test
  void createsOneV47DemoAccountWithExactSpotPerpTruthAndTenSettings() {
    UUID userId = UUID.randomUUID();
    when(accountRepository.findActiveDemoByUserId(userId)).thenReturn(Optional.empty());
    when(accountRepository.insertActiveDemoIfAbsent(any(UUID.class), eq(userId))).thenReturn(1);

    TradingAccountEntity account = service.getOrCreateDemoAccount(userId);

    assertThat(account.getAccountType()).isEqualTo(AccountType.DEMO);
    assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    assertThat(account.getBaseCurrency()).isEqualTo("USDT");
    assertThat(account.getBalance()).isEqualByComparingTo("50000.00000000");
    assertThat(account.getEquity()).isEqualByComparingTo("50000.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("0.00000000");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("50000.00000000");
    assertThat(account.getLeverage()).isEqualTo(10);
    assertThat(account.getPositionMode()).isEqualTo(PositionMode.ONE_WAY);
    assertThat(account.getDemoGeneration()).isEqualTo(1L);
    verify(walletService).creditAvailableWithEntryType(
        account.getId(), WalletType.SPOT, "USDT", new BigDecimal("50000.00000000"),
        "DEMO_ACCOUNT", account.getId(), "Initialize Demo Spot balance", "DEMO_INIT");
    verify(ledgerService).recordDemoInit(account, new BigDecimal("50000.00000000"));
    ArgumentCaptor<AccountSymbolSettingEntity> settings =
        ArgumentCaptor.forClass(AccountSymbolSettingEntity.class);
    verify(settingRepository, times(10)).save(settings.capture());
    assertThat(settings.getAllValues())
        .extracting(AccountSymbolSettingEntity::getSymbol)
        .containsExactlyInAnyOrder(
            "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
            "BTCUSDT-PERP", "ETHUSDT-PERP", "BNBUSDT-PERP", "SOLUSDT-PERP", "XRPUSDT-PERP");
    assertThat(settings.getAllValues()).allSatisfy(setting -> {
      assertThat(setting.getAccountId()).isEqualTo(account.getId());
      assertThat(setting.getLeverage()).isEqualTo(10);
      assertThat(setting.getMarginMode()).isEqualTo(MarginMode.CROSS);
      assertThat(setting.getQuantityUnit()).isEqualTo(QuantityUnit.BASE);
      assertThat(setting.getVersion()).isZero();
    });
    verify(walletService, never()).creditAvailableWithEntryType(
        eq(account.getId()), eq(WalletType.USDT_PERP), eq("USDT"), any(), any(), any(), any(), any());
    verify(walletService, never()).creditAvailableWithEntryType(
        eq(account.getId()), eq(WalletType.FX_MARGIN), any(), any(), any(), any(), any(), any());
  }

  @Test
  void repeatedAndUniqueIndexRaceCreationReturnTheExistingAccountWithoutDoubleInit() {
    UUID userId = UUID.randomUUID();
    TradingAccountEntity existing = demoAccount(UUID.randomUUID(), userId, 3L);
    when(accountRepository.findActiveDemoByUserId(userId))
        .thenReturn(Optional.empty(), Optional.of(existing));
    when(accountRepository.insertActiveDemoIfAbsent(any(UUID.class), eq(userId))).thenReturn(0);

    assertThat(service.getOrCreateDemoAccount(userId)).isSameAs(existing);

    verify(walletService, never()).creditAvailableWithEntryType(
        any(), any(WalletType.class), any(), any(), any(), any(), any(), any());
    verify(ledgerService, never()).recordDemoInit(any(), any());
    verify(settingRepository, never()).save(any());
  }

  @Test
  void liquidationPendingDemoIsStillTheUsersSingleDemoAndIsNeverRecreated() {
    UUID userId = UUID.randomUUID();
    TradingAccountEntity pending = demoAccount(UUID.randomUUID(), userId, 4L);
    pending.setStatus(AccountStatus.LIQUIDATION_PENDING);
    when(accountRepository.findActiveDemoByUserId(userId)).thenReturn(Optional.of(pending));

    assertThat(service.getOrCreateDemoAccount(userId)).isSameAs(pending);

    verify(accountRepository, never()).insertActiveDemoIfAbsent(any(), any());
    verify(walletService, never()).creditAvailableWithEntryType(
        any(), any(WalletType.class), any(), any(), any(), any(), any(), any());
  }

  @Test
  void activeNormalOcoOrProtectionOrderBlocksResetWithZeroMutation() {
    for (OrderOrigin origin : List.of(OrderOrigin.USER, OrderOrigin.OCO, OrderOrigin.PROTECTIVE)) {
      ResetFixture fixture = resetFixture();
      OrderEntity active = new OrderEntity();
      active.setId(UUID.randomUUID());
      active.setAccountId(fixture.account().getId());
      active.setOrderOrigin(origin);
      active.setStatus(origin == OrderOrigin.PROTECTIVE ? OrderStatus.PENDING_ACTIVATION : OrderStatus.PENDING);
      when(orderRepository.findActiveByAccountIdForUpdate(fixture.account().getId())).thenReturn(List.of(active));

      assertThatThrownBy(() -> service.reset(
          fixture.account().getUserId(), fixture.account().getId(), fixture.requestId()))
          .isInstanceOfSatisfying(BusinessException.class,
              ex -> assertThat(ex.getCode()).isEqualTo("DEMO_RESET_BLOCKED"));
    }

    verify(accountRepository, never()).save(any());
    verify(walletService, never()).resetBalance(any(), any(), any(), any(), any(), any());
    verify(ledgerService, never()).recordDemoReset(any(), any(), any());
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void openPerpetualPositionBlocksButSpotCostPositionDoesNotBlock() {
    ResetFixture blocked = resetFixture();
    PositionEntity open = new PositionEntity();
    open.setId(UUID.randomUUID());
    open.setAccountId(blocked.account().getId());
    open.setStatus(PositionStatus.OPEN);
    when(positionRepository.findOpenByAccountIdForUpdate(blocked.account().getId())).thenReturn(List.of(open));

    assertThatThrownBy(() -> service.reset(
        blocked.account().getUserId(), blocked.account().getId(), blocked.requestId()))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getCode()).isEqualTo("DEMO_RESET_BLOCKED"));

    ResetFixture clean = resetFixture();
    SpotPositionEntity spotPosition = spotPosition(clean.account().getId(), "BTC", "0.25");
    when(spotPositionRepository.findByAccountIdForUpdate(clean.account().getId()))
        .thenReturn(List.of(spotPosition));

    service.reset(clean.account().getUserId(), clean.account().getId(), clean.requestId());

    assertThat(spotPosition.getQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(spotPosition.getAverageCost()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(spotPosition.getUnrealizedPnl()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(spotPosition.getFeeCost()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(spotPosition.getRealizedPnl()).isEqualByComparingTo("123.00000000");
    verify(spotPositionRepository).save(spotPosition);
  }

  @Test
  void cleanResetRestoresBalancesSettingsAndGenerationWithDeltaLedgersWithoutDeletingHistoryRows() {
    ResetFixture fixture = resetFixture();
    TradingAccountEntity account = fixture.account();
    account.setBalance(new BigDecimal("41000.00000000"));
    account.setEquity(new BigDecimal("42000.00000000"));
    account.setUsedMargin(new BigDecimal("1000.00000000"));
    account.setFreeMargin(new BigDecimal("41000.00000000"));
    account.setLeverage(25);
    account.setPositionMode(PositionMode.HEDGE);
    WalletBalanceEntity usdt = wallet(account.getId(), WalletType.SPOT, "USDT", "12000", "10000", "2000");
    WalletBalanceEntity btc = wallet(account.getId(), WalletType.SPOT, "BTC", "0.3", "0.3", "0");
    when(walletService.lockAllBalances(account.getId())).thenReturn(List.of(btc, usdt));
    AccountSymbolSettingEntity setting = new AccountSymbolSettingEntity();
    setting.setAccountId(account.getId());
    setting.setSymbol("BTCUSDT-PERP");
    setting.setLeverage(50);
    setting.setMarginMode(MarginMode.ISOLATED);
    setting.setQuantityUnit(QuantityUnit.CONTRACTS);
    setting.setVersion(7L);
    when(settingRepository.findByAccountIdForUpdate(account.getId())).thenReturn(List.of(setting));
    SpotPositionEntity spotPosition = spotPosition(account.getId(), "BTC", "0.3");
    when(spotPositionRepository.findByAccountIdForUpdate(account.getId())).thenReturn(List.of(spotPosition));

    var response = service.reset(account.getUserId(), account.getId(), fixture.requestId());

    assertThat(response.accountId()).isEqualTo(account.getId());
    assertThat(response.demoGeneration()).isEqualTo(2L);
    assertThat(response.spotAvailable()).isEqualByComparingTo("50000.00000000");
    assertThat(response.perpBalance()).isEqualByComparingTo("50000.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(account.getLeverage()).isEqualTo(10);
    assertThat(account.getPositionMode()).isEqualTo(PositionMode.ONE_WAY);
    assertThat(setting.getLeverage()).isEqualTo(10);
    assertThat(setting.getMarginMode()).isEqualTo(MarginMode.CROSS);
    assertThat(setting.getQuantityUnit()).isEqualTo(QuantityUnit.BASE);
    assertThat(setting.getVersion()).isEqualTo(8L);
    verify(demoExecutionGuard).requireDemoAccount(account);
    verify(walletService).resetBalance(
        account.getId(), WalletType.SPOT, "USDT", new BigDecimal("50000.00000000"),
        fixture.requestId(), "Reset Demo Spot balance");
    verify(walletService).resetBalance(
        account.getId(), WalletType.SPOT, "BTC", BigDecimal.ZERO.setScale(8),
        fixture.requestId(), "Reset Demo Spot asset");
    verify(ledgerService).recordDemoReset(
        account, new BigDecimal("9000.00000000"), fixture.requestId());
    verify(settingRepository).resetToDemoDefaults(account.getId(), "BTCUSDT-PERP");
    verify(settingRepository, never()).save(setting);
    verify(auditLogService).recordWithRequestId(
        account.getUserId(), "DEMO_RESET", "TRADING_ACCOUNT", account.getId().toString(),
        fixture.requestId(), "{\"generation\":2}");
    verify(orderRepository, never()).delete(any());
    verify(spotPositionRepository, never()).delete(any());
  }

  @Test
  void completedResetPublishesDemoResetAndBalanceRefreshEventsForTheAccountOwner() {
    ResetFixture fixture = resetFixture();

    var response = service.reset(
        fixture.account().getUserId(), fixture.account().getId(), fixture.requestId());

    ArgumentCaptor<TradingAccountMutationEvent> events =
        ArgumentCaptor.forClass(TradingAccountMutationEvent.class);
    verify(eventPublisher, times(2)).publishEvent(events.capture());
    assertThat(events.getAllValues()).containsExactly(
        new TradingAccountMutationEvent(
            fixture.account().getUserId(),
            fixture.account().getId(),
            "DEMO_RESET",
            "ACCOUNT",
            fixture.account().getId(),
            fixture.requestId(),
            response.demoGeneration(),
            response.resetAt()),
        new TradingAccountMutationEvent(
            fixture.account().getUserId(),
            fixture.account().getId(),
            "BALANCE_UPDATED",
            "ACCOUNT",
            fixture.account().getId(),
            fixture.requestId(),
            response.demoGeneration(),
            response.resetAt()));
  }

  @Test
  void resetReplayDoesNotIncrementGenerationOrWriteASecondLedger() {
    ResetFixture fixture = resetFixture();
    LedgerEntryEntity existing = new LedgerEntryEntity();
    existing.setEntryType(LedgerEntryType.DEMO_RESET);
    existing.setReferenceId(fixture.requestId());
    existing.setCreatedAt(Instant.parse("2026-07-12T01:02:03Z"));
    when(ledgerService.findDemoReset(fixture.account().getId(), fixture.requestId()))
        .thenReturn(Optional.of(existing));

    var response = service.reset(
        fixture.account().getUserId(), fixture.account().getId(), fixture.requestId());

    assertThat(response.replayed()).isTrue();
    assertThat(fixture.account().getDemoGeneration()).isEqualTo(1L);
    verifyNoInteractions(eventPublisher);
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordDemoReset(any(), any(), any());
  }

  @Test
  void resetRejectsTheSameRequestIdWhenExpectedGenerationChanges() {
    ResetFixture fixture = resetFixture();
    fixture.account().setDemoGeneration(2L);
    LedgerEntryEntity existing = new LedgerEntryEntity();
    existing.setEntryType(LedgerEntryType.DEMO_RESET);
    existing.setReferenceId(fixture.requestId());
    when(ledgerService.findDemoReset(fixture.account().getId(), fixture.requestId()))
        .thenReturn(Optional.of(existing));
    when(auditLogService.findDemoResetGeneration(
        fixture.account().getId(), fixture.requestId()))
        .thenReturn(Optional.of(2L));

    var replay = service.reset(
        fixture.account().getUserId(),
        fixture.account().getId(),
        fixture.requestId(),
        1L);
    assertThat(replay.replayed()).isTrue();

    assertThatThrownBy(() -> service.reset(
        fixture.account().getUserId(),
        fixture.account().getId(),
        fixture.requestId(),
        2L))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));

    assertThat(fixture.account().getDemoGeneration()).isEqualTo(2L);
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordDemoReset(any(), any(), any());
    verifyNoInteractions(eventPublisher);
  }

  @Test
  void resetRepairsMissingCanonicalSymbolSettingsWithoutInsertingExistingRows() {
    ResetFixture fixture = resetFixture();
    List<String> existingSymbols = List.of(
        "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
        "BTCUSDT-PERP", "ETHUSDT-PERP", "BNBUSDT-PERP", "SOLUSDT-PERP");
    List<AccountSymbolSettingEntity> existing = existingSymbols.stream()
        .map(symbol -> {
          AccountSymbolSettingEntity setting = new AccountSymbolSettingEntity();
          setting.setAccountId(fixture.account().getId());
          setting.setSymbol(symbol);
          setting.setLeverage(25);
          setting.setMarginMode(MarginMode.ISOLATED);
          setting.setQuantityUnit(QuantityUnit.CONTRACTS);
          setting.setVersion(2L);
          return setting;
        })
        .toList();
    when(settingRepository.findByAccountIdForUpdate(fixture.account().getId()))
        .thenReturn(existing);

    service.reset(fixture.account().getUserId(), fixture.account().getId(), fixture.requestId());

    verify(settingRepository, times(9)).resetToDemoDefaults(eq(fixture.account().getId()), any());
    verify(settingRepository).save(argThat(setting ->
        fixture.account().getId().equals(setting.getAccountId())
            && "XRPUSDT-PERP".equals(setting.getSymbol())
            && setting.getLeverage() == 10
            && setting.getMarginMode() == MarginMode.CROSS
            && setting.getQuantityUnit() == QuantityUnit.BASE
            && setting.getVersion() == 0L));
  }

  @Test
  void resetAcquiresGlobalLocksBeforeLedgerReplayCheck() {
    ResetFixture fixture = resetFixture();

    service.reset(fixture.account().getUserId(), fixture.account().getId(), fixture.requestId());

    InOrder locks = inOrder(
        accountRepository,
        settingRepository,
        walletService,
        spotPositionRepository,
        positionRepository,
        orderRepository,
        ledgerService);
    locks.verify(accountRepository).findByIdAndUserIdForUpdate(
        fixture.account().getId(), fixture.account().getUserId());
    locks.verify(settingRepository).findByAccountIdForUpdate(fixture.account().getId());
    locks.verify(walletService).lockAllBalances(fixture.account().getId());
    locks.verify(spotPositionRepository).findByAccountIdForUpdate(fixture.account().getId());
    locks.verify(positionRepository).findOpenByAccountIdForUpdate(fixture.account().getId());
    locks.verify(orderRepository).findActiveByAccountIdForUpdate(fixture.account().getId());
    locks.verify(ledgerService).findDemoReset(fixture.account().getId(), fixture.requestId());
  }

  private ResetFixture resetFixture() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    TradingAccountEntity account = demoAccount(accountId, userId, 1L);
    WalletBalanceEntity usdt = wallet(accountId, WalletType.SPOT, "USDT", "50000", "50000", "0");
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(account));
    when(settingRepository.findByAccountIdForUpdate(accountId)).thenReturn(List.of());
    when(walletService.lockAllBalances(accountId)).thenReturn(List.of(usdt));
    when(spotPositionRepository.findByAccountIdForUpdate(accountId)).thenReturn(List.of());
    when(positionRepository.findOpenByAccountIdForUpdate(accountId)).thenReturn(List.of());
    when(orderRepository.findActiveByAccountIdForUpdate(accountId)).thenReturn(List.of());
    when(ledgerService.findDemoReset(accountId, requestId)).thenReturn(Optional.empty());
    org.mockito.Mockito.lenient().when(walletService.resetBalance(any(), any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> {
          usdt.setTotal(invocation.getArgument(3));
          usdt.setAvailable(invocation.getArgument(3));
          usdt.setLocked(BigDecimal.ZERO.setScale(8));
          return usdt;
        });
    return new ResetFixture(account, requestId);
  }

  private static TradingAccountEntity demoAccount(UUID id, UUID userId, long generation) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(id);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    account.setBaseCurrency("USDT");
    account.setBalance(new BigDecimal("50000.00000000"));
    account.setEquity(new BigDecimal("50000.00000000"));
    account.setUsedMargin(BigDecimal.ZERO.setScale(8));
    account.setFreeMargin(new BigDecimal("50000.00000000"));
    account.setLeverage(10);
    account.setPositionMode(PositionMode.ONE_WAY);
    account.setDemoGeneration(generation);
    return account;
  }

  private static WalletBalanceEntity wallet(
      UUID accountId,
      WalletType type,
      String asset,
      String total,
      String available,
      String locked
  ) {
    WalletBalanceEntity wallet = new WalletBalanceEntity();
    wallet.setId(UUID.randomUUID());
    wallet.setAccountId(accountId);
    wallet.setWalletType(type.code());
    wallet.setAsset(asset);
    wallet.setTotal(new BigDecimal(total));
    wallet.setAvailable(new BigDecimal(available));
    wallet.setLocked(new BigDecimal(locked));
    return wallet;
  }

  private static SpotPositionEntity spotPosition(UUID accountId, String asset, String quantity) {
    SpotPositionEntity position = new SpotPositionEntity();
    position.setId(UUID.randomUUID());
    position.setAccountId(accountId);
    position.setAsset(asset);
    position.setCostAsset("USDT");
    position.setQuantity(new BigDecimal(quantity));
    position.setAverageCost(new BigDecimal("40000.00000000"));
    position.setFeeCost(new BigDecimal("10.00000000"));
    position.setRealizedPnl(new BigDecimal("123.00000000"));
    return position;
  }

  private record ResetFixture(TradingAccountEntity account, UUID requestId) {
  }
}
