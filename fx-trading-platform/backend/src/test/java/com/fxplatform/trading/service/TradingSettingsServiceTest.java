package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.dto.UpdatePositionModeRequest;
import com.fxplatform.account.dto.UpdateSymbolSettingsRequest;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
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
  void marginModeAndLeverageChangesFailClosedOnTargetOpenPosition() {
    PatchFixture fixture = patchFixture(3L);
    when(positionRepository.findOpenLinearPerpBySymbolForUpdate(
        fixture.accountId(), "BTCUSDT-PERP")).thenReturn(List.of(new PositionEntity()));

    assertCode("MARGIN_MODE_SWITCH_BLOCKED", () -> service().updateSymbolSettings(
        fixture.userId(), fixture.accountId(), "BTCUSDT-PERP",
        new UpdateSymbolSettingsRequest(null, MarginMode.ISOLATED, null, 3L)));
    assertCode("LEVERAGE_CHANGE_BLOCKED", () -> service().updateSymbolSettings(
        fixture.userId(), fixture.accountId(), "BTCUSDT-PERP",
        new UpdateSymbolSettingsRequest(20, null, null, 3L)));
    verify(settingRepository, never()).updateIfVersion(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
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
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT-PERP")).thenReturn(Optional.of(symbol));
    when(settingRepository.findByAccountIdAndSymbolForUpdate(accountId, "BTCUSDT-PERP"))
        .thenReturn(Optional.empty());
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
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol("BTCUSDT-PERP")).thenReturn(Optional.of(symbol));
    when(symbolRepository.findByEnabledTrueOrderBySymbolAsc()).thenReturn(List.of(symbol));
    when(settingRepository.findByAccountIdAndSymbolForUpdate(accountId, "BTCUSDT-PERP"))
        .thenReturn(Optional.of(current));
    when(settingRepository.findByAccountId(accountId)).thenReturn(List.of(current));
    return new PatchFixture(userId, accountId);
  }

  private TradingSettingsService service() {
    return new TradingSettingsService(
        accountRepository,
        symbolRepository,
        settingRepository,
        positionRepository,
        orderRepository,
        demoExecutionGuard);
  }

  private static TradingAccountEntity account(UUID userId, UUID accountId, PositionMode mode) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    account.setPositionMode(mode);
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
    return symbol;
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
}
