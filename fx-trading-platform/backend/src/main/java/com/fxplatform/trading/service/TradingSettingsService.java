package com.fxplatform.trading.service;

import com.fxplatform.account.dto.TradingSettingsResponse;
import com.fxplatform.account.dto.UpdatePositionModeRequest;
import com.fxplatform.account.dto.UpdateSymbolSettingsRequest;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account-level Perpetual trading settings contract. */
@Service
@RequiredArgsConstructor
public class TradingSettingsService {

  private final TradingAccountRepository accountRepository;
  private final SymbolRepository symbolRepository;
  private final AccountSymbolSettingRepository settingRepository;
  private final PositionRepository positionRepository;
  private final OrderRepository orderRepository;
  private final DemoExecutionGuard demoExecutionGuard;

  public TradingSettingsResponse get(UUID userId, UUID accountId) {
    TradingAccountEntity account = accountRepository.findByIdAndUserId(accountId, userId)
        .orElseThrow(TradingSettingsService::accountNotFound);
    return response(account);
  }

  @Transactional
  public TradingSettingsResponse updatePositionMode(
      UUID userId,
      UUID accountId,
      UpdatePositionModeRequest request
  ) {
    TradingAccountEntity account = requireOwnedAccountForUpdate(userId, accountId);
    demoExecutionGuard.requireDemoAccount(account);
    PositionMode requestedMode = request == null ? null : request.positionMode();
    if (requestedMode == null) {
      throw new BusinessException("INVALID_POSITION_MODE", "Position mode is required");
    }
    PositionMode currentMode = account.getPositionMode() == null
        ? PositionMode.ONE_WAY
        : account.getPositionMode();
    if (requestedMode == currentMode) {
      return response(account);
    }

    if (!positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId).isEmpty()) {
      throw new BusinessException(
          "POSITION_MODE_SWITCH_BLOCKED",
          "Position mode cannot change while Perpetual positions are open");
    }
    if (!orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId).isEmpty()) {
      throw new BusinessException(
          "POSITION_MODE_SWITCH_BLOCKED",
          "Position mode cannot change while Perpetual orders are active");
    }

    account.setPositionMode(requestedMode);
    accountRepository.save(account);
    return response(account);
  }

  @Transactional
  public TradingSettingsResponse updateSymbolSettings(
      UUID userId,
      UUID accountId,
      String symbol,
      UpdateSymbolSettingsRequest request
  ) {
    TradingAccountEntity account = requireOwnedAccountForUpdate(userId, accountId);
    demoExecutionGuard.requireDemoAccount(account);
    String canonicalSymbol = canonicalSymbol(symbol);
    SymbolEntity configuredSymbol = requireTradablePerpetual(canonicalSymbol);
    if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0) {
      throw versionConflict();
    }

    Optional<AccountSymbolSettingEntity> maybeCurrent =
        settingRepository.findByAccountIdAndSymbolForUpdate(accountId, canonicalSymbol);
    AccountSymbolSettingEntity current = maybeCurrent.orElseGet(
        () -> defaultSetting(accountId, canonicalSymbol));
    long currentVersion = valueOrDefault(current.getVersion(), 0L);
    if (request.expectedVersion() != currentVersion) {
      throw versionConflict();
    }

    int currentLeverage = valueOrDefault(current.getLeverage(), 10);
    MarginMode currentMarginMode = current.getMarginMode() == null
        ? MarginMode.CROSS
        : current.getMarginMode();
    QuantityUnit currentQuantityUnit = current.getQuantityUnit() == null
        ? QuantityUnit.BASE
        : current.getQuantityUnit();
    int nextLeverage = request.leverage() == null ? currentLeverage : request.leverage();
    MarginMode nextMarginMode = request.marginMode() == null
        ? currentMarginMode
        : request.marginMode();
    QuantityUnit nextQuantityUnit = request.quantityUnit() == null
        ? currentQuantityUnit
        : request.quantityUnit();
    validateLeverage(nextLeverage, effectiveMaxLeverage(configuredSymbol));
    if (nextMarginMode != MarginMode.CROSS && nextMarginMode != MarginMode.ISOLATED) {
      throw new BusinessException(
          "INVALID_MARGIN_MODE",
          "Perpetual margin mode must be CROSS or ISOLATED");
    }

    boolean leverageChanged = nextLeverage != currentLeverage;
    boolean marginModeChanged = nextMarginMode != currentMarginMode;
    boolean quantityUnitChanged = nextQuantityUnit != currentQuantityUnit;
    if (!leverageChanged && !marginModeChanged && !quantityUnitChanged) {
      return response(account);
    }

    if (leverageChanged || marginModeChanged) {
      boolean hasOpenPosition = !positionRepository.findOpenLinearPerpBySymbolForUpdate(
          accountId, canonicalSymbol).isEmpty();
      if (hasOpenPosition && marginModeChanged) {
        throw new BusinessException(
            "MARGIN_MODE_SWITCH_BLOCKED",
            "Margin mode cannot change while this Perpetual position is open");
      }
      if (hasOpenPosition) {
        throw new BusinessException(
            "LEVERAGE_CHANGE_BLOCKED",
            "Leverage cannot change until Task 9 can recalculate margin atomically");
      }
      if (marginModeChanged && !orderRepository.findActiveLinearPerpBySymbolForUpdate(
          accountId, canonicalSymbol).isEmpty()) {
        throw new BusinessException(
            "MARGIN_MODE_SWITCH_BLOCKED",
            "Margin mode cannot change while this Perpetual order is active");
      }
    }

    int changed = maybeCurrent.isPresent()
        ? settingRepository.updateIfVersion(
            accountId,
            canonicalSymbol,
            currentVersion,
            nextLeverage,
            nextMarginMode,
            nextQuantityUnit)
        : settingRepository.insertIfAbsent(
            accountId,
            canonicalSymbol,
            nextLeverage,
            nextMarginMode,
            nextQuantityUnit);
    if (changed != 1) {
      throw versionConflict();
    }
    return response(account);
  }

  private TradingAccountEntity requireOwnedAccountForUpdate(UUID userId, UUID accountId) {
    return accountRepository.findByIdAndUserIdForUpdate(accountId, userId)
        .orElseThrow(TradingSettingsService::accountNotFound);
  }

  private TradingSettingsResponse response(TradingAccountEntity account) {
    List<SymbolEntity> configured = symbolRepository.findByEnabledTrueOrderBySymbolAsc();
    List<AccountSymbolSettingEntity> stored = settingRepository.findByAccountId(account.getId());
    Map<String, AccountSymbolSettingEntity> bySymbol = new HashMap<>();
    if (stored != null) {
      for (AccountSymbolSettingEntity setting : stored) {
        if (setting != null && setting.getSymbol() != null) {
          bySymbol.put(setting.getSymbol(), setting);
        }
      }
    }

    List<TradingSettingsResponse.SymbolSettings> symbols = configured == null
        ? List.of()
        : configured.stream()
            .filter(Objects::nonNull)
            .filter(value -> Boolean.TRUE.equals(value.getEnabled()))
            .filter(value -> Boolean.TRUE.equals(value.getTradable()))
            .filter(value -> value.getProductType() == ProductType.LINEAR_PERP)
            .sorted(Comparator.comparing(SymbolEntity::getSymbol))
            .map(value -> toResponse(value, bySymbol.get(value.getSymbol())))
            .toList();
    return new TradingSettingsResponse(
        account.getId(),
        account.getPositionMode() == null ? PositionMode.ONE_WAY : account.getPositionMode(),
        symbols);
  }

  private TradingSettingsResponse.SymbolSettings toResponse(
      SymbolEntity symbol,
      AccountSymbolSettingEntity stored
  ) {
    AccountSymbolSettingEntity effective = stored == null
        ? defaultSetting(null, symbol.getSymbol())
        : stored;
    return new TradingSettingsResponse.SymbolSettings(
        symbol.getSymbol(),
        valueOrDefault(effective.getLeverage(), 10),
        effective.getMarginMode() == null ? MarginMode.CROSS : effective.getMarginMode(),
        effective.getQuantityUnit() == null ? QuantityUnit.BASE : effective.getQuantityUnit(),
        valueOrDefault(effective.getVersion(), 0L),
        effectiveMaxLeverage(symbol));
  }

  private SymbolEntity requireTradablePerpetual(String symbol) {
    SymbolEntity configured = symbolRepository.findBySymbol(symbol)
        .orElseThrow(() -> new BusinessException("SYMBOL_NOT_TRADABLE", "Symbol is not tradable"));
    if (configured.getProductType() != ProductType.LINEAR_PERP
        || !Boolean.TRUE.equals(configured.getEnabled())
        || !Boolean.TRUE.equals(configured.getTradable())) {
      throw new BusinessException("SYMBOL_NOT_TRADABLE", "Symbol is not a tradable Linear Perpetual");
    }
    return configured;
  }

  private void validateLeverage(int leverage, int maxLeverage) {
    if (leverage < 1 || leverage > maxLeverage) {
      throw new BusinessException(
          "LEVERAGE_OUT_OF_RANGE",
          "Leverage must be between 1 and " + maxLeverage);
    }
  }

  private int effectiveMaxLeverage(SymbolEntity symbol) {
    Integer configured = symbol.getLeverage();
    return Math.min(configured == null || configured < 1 ? 100 : configured, 100);
  }

  private AccountSymbolSettingEntity defaultSetting(UUID accountId, String symbol) {
    AccountSymbolSettingEntity setting = new AccountSymbolSettingEntity();
    setting.setAccountId(accountId);
    setting.setSymbol(symbol);
    setting.setLeverage(10);
    setting.setMarginMode(MarginMode.CROSS);
    setting.setQuantityUnit(QuantityUnit.BASE);
    setting.setVersion(0L);
    return setting;
  }

  private String canonicalSymbol(String symbol) {
    if (symbol == null || symbol.isBlank()) {
      throw new BusinessException("SYMBOL_NOT_TRADABLE", "Symbol is required");
    }
    return SymbolNormalizer.normalize(symbol.trim());
  }

  private static AuthorizationException accountNotFound() {
    return new AuthorizationException("ACCOUNT_NOT_FOUND", "Account not found");
  }

  private static BusinessException versionConflict() {
    return new BusinessException(
        "SETTINGS_VERSION_CONFLICT",
        "Trading settings version is stale");
  }

  private static int valueOrDefault(Integer value, int fallback) {
    return value == null ? fallback : value;
  }

  private static long valueOrDefault(Long value, long fallback) {
    return value == null ? fallback : value;
  }
}
