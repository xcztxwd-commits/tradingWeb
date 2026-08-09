package com.fxplatform.trading.service;

import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.dto.TradingSettingsResponse;
import com.fxplatform.account.dto.UpdatePositionModeRequest;
import com.fxplatform.account.dto.UpdateSymbolSettingsRequest;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketBundleResolver;
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
import com.fxplatform.risk.service.PerpetualRiskService;
import com.fxplatform.risk.service.PerpetualRiskService.PositionRisk;
import com.fxplatform.trading.service.PerpetualAccountRiskSnapshotService.AccountRiskProjection;
import com.fxplatform.trading.service.PerpetualAccountRiskSnapshotService.PreparedAccountRisk;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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

  private static final int MAX_STALE_MARKET_ATTEMPTS = 3;

  private final TradingAccountRepository accountRepository;
  private final SymbolRepository symbolRepository;
  private final AccountSymbolSettingRepository settingRepository;
  private final PositionRepository positionRepository;
  private final OrderRepository orderRepository;
  private final DemoExecutionGuard demoExecutionGuard;
  private final MarketBundleResolver marketBundleResolver;
  private final PerpetualRiskService perpetualRiskService;
  private final PerpetualAccountRiskSnapshotService accountRiskSnapshotService;
  private final LedgerService ledgerService;
  private final TradingTransactionExecutor transactionExecutor;

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

  public TradingSettingsResponse updateSymbolSettings(
      UUID userId,
      UUID accountId,
      String symbol,
      UpdateSymbolSettingsRequest request
  ) {
    String canonicalSymbol = canonicalSymbol(symbol);
    SymbolEntity configuredSymbol = requireTradablePerpetual(canonicalSymbol);
    if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0) {
      throw versionConflict();
    }
    if (request.leverage() != null) {
      validateLeverage(request.leverage(), effectiveMaxLeverage(configuredSymbol));
    }
    if (request.marginMode() != null
        && request.marginMode() != MarginMode.CROSS
        && request.marginMode() != MarginMode.ISOLATED) {
      throw new BusinessException(
          ErrorCode.INVALID_MARGIN_MODE,
          "Perpetual margin mode must be CROSS or ISOLATED");
    }
    TradingAccountEntity accountSnapshot = accountRepository.findByIdAndUserId(accountId, userId)
        .orElseThrow(TradingSettingsService::accountNotFound);
    demoExecutionGuard.requireDemoAccount(accountSnapshot);
    Optional<AccountSymbolSettingEntity> preflightSetting =
        settingRepository.findByAccountIdAndSymbol(accountId, canonicalSymbol);
    int preflightLeverage = preflightSetting
        .map(AccountSymbolSettingEntity::getLeverage)
        .filter(value -> value != null && value > 0)
        .orElse(10);
    long preflightVersion = preflightSetting
        .map(AccountSymbolSettingEntity::getVersion)
        .orElse(0L);
    boolean resolveRequired = request.leverage() != null
        && request.leverage() != preflightLeverage
        && request.expectedVersion() == preflightVersion;

    BusinessException staleFailure = null;
    for (int attempt = 0; attempt < MAX_STALE_MARKET_ATTEMPTS; attempt++) {
      try {
        ExecutableMarketSnapshot market = resolveRequired
            ? resolvePerpetualMarket(canonicalSymbol)
            : null;
        PreparedAccountRisk prepared = resolveRequired
            ? accountRiskSnapshotService.prepare(
                accountId,
                Map.of(canonicalSymbol, market))
            : null;
        return transactionExecutor.execute(() -> updateSymbolSettingsLocked(
            userId,
            accountId,
            canonicalSymbol,
            request,
            configuredSymbol,
            market,
            prepared));
      } catch (BusinessException exception) {
        if (!ErrorCode.MARKET_DATA_STALE.equals(exception.getCode())
            || attempt >= MAX_STALE_MARKET_ATTEMPTS - 1) {
          throw exception;
        }
        staleFailure = exception;
        resolveRequired = true;
      }
    }
    throw staleFailure == null
        ? new BusinessException(ErrorCode.MARKET_DATA_STALE, "Executable market snapshot expired")
        : staleFailure;
  }

  private TradingSettingsResponse updateSymbolSettingsLocked(
      UUID userId,
      UUID accountId,
      String canonicalSymbol,
      UpdateSymbolSettingsRequest request,
      SymbolEntity configuredSymbol,
      ExecutableMarketSnapshot market,
      PreparedAccountRisk prepared
  ) {
    TradingAccountEntity account = requireOwnedAccountForUpdate(userId, accountId);
    demoExecutionGuard.requireDemoAccount(account);

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

    List<PositionEntity> lockedPositions = List.of();
    List<OrderEntity> activeOrders = List.of();
    if (leverageChanged) {
      lockedPositions = new ArrayList<>(
          positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId));
      activeOrders = new ArrayList<>(
          orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId));
    } else if (marginModeChanged) {
      lockedPositions = new ArrayList<>(positionRepository.findOpenLinearPerpBySymbolForUpdate(
          accountId, canonicalSymbol));
      activeOrders = new ArrayList<>(orderRepository.findActiveLinearPerpBySymbolForUpdate(
          accountId, canonicalSymbol));
    }
    List<PositionEntity> targetPositions = leverageChanged
        ? lockedPositions.stream()
            .filter(position -> canonicalSymbol.equals(position.getSymbol()))
            .toList()
        : lockedPositions;
    List<OrderEntity> targetActiveOrders = leverageChanged
        ? activeOrders.stream()
            .filter(order -> canonicalSymbol.equals(order.getSymbol()))
            .toList()
        : activeOrders;
    if (marginModeChanged && !targetPositions.isEmpty()) {
      throw new BusinessException(
          "MARGIN_MODE_SWITCH_BLOCKED",
          "Margin mode cannot change while this Perpetual position is open");
    }
    if (marginModeChanged && !targetActiveOrders.isEmpty()) {
      throw new BusinessException(
          "MARGIN_MODE_SWITCH_BLOCKED",
          "Margin mode cannot change while this Perpetual order is active");
    }

    AccountRiskProjection accountRisk = null;
    List<LeverageProjection> projections = List.of();
    Map<UUID, Long> originalPositionVersions = Map.of();
    BigDecimal aggregateDelta = BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
    BigDecimal nextEquity = orZero(account.getEquity()).setScale(8, RoundingMode.HALF_UP);
    BigDecimal nextUsedMargin = orZero(account.getUsedMargin()).setScale(8, RoundingMode.HALF_UP);
    BigDecimal nextFreeMargin = orZero(account.getFreeMargin()).setScale(8, RoundingMode.HALF_UP);
    if (leverageChanged) {
      if (market == null || prepared == null) {
        throw new BusinessException(ErrorCode.MARKET_DATA_STALE, "Authority mark is required");
      }
      accountRisk = accountRiskSnapshotService.project(
          account,
          lockedPositions,
          activeOrders,
          prepared);
      projections = leverageProjections(
          targetPositions,
          currentLeverage,
          nextLeverage,
          configuredSymbol,
          market.mark(),
          activeOrders);
      aggregateDelta = projections.stream()
          .map(LeverageProjection::marginDelta)
          .reduce(BigDecimal.ZERO, BigDecimal::add)
          .setScale(8, RoundingMode.HALF_UP);
      if (aggregateDelta.compareTo(BigDecimal.ZERO) > 0
          && accountRisk.crossAvailable().compareTo(aggregateDelta) < 0) {
        throw new BusinessException(ErrorCode.INSUFFICIENT_MARGIN, "Free margin is not enough");
      }
      nextEquity = accountRisk.displayEquity();
      nextUsedMargin = accountRisk.usedMargin().add(aggregateDelta)
          .setScale(8, RoundingMode.HALF_UP);
      nextFreeMargin = accountRisk.crossAvailable().subtract(aggregateDelta)
          .setScale(8, RoundingMode.HALF_UP);
      if (nextUsedMargin.compareTo(BigDecimal.ZERO) < 0) {
        throw new BusinessException(
            ErrorCode.MARGIN_ADJUSTMENT_INVALID,
            "Leverage change would make account used margin negative");
      }
      Map<UUID, Long> versions = new HashMap<>();
      for (PositionEntity position : lockedPositions) {
        versions.put(position.getId(), position.getVersion());
      }
      originalPositionVersions = versions;
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

    if (accountRisk != null) {
      accountRiskSnapshotService.applyRevaluation(account, lockedPositions, accountRisk);
      for (LeverageProjection projection : projections) {
        PositionEntity position = projection.position();
        PositionRisk risk = projection.risk();
        position.setLeverage(nextLeverage);
        position.setMarginHeld(projection.nextMarginHeld());
        position.setNotional(risk.markNotional());
        position.setInitialMargin(risk.initialMargin());
        position.setMaintenanceMargin(risk.maintenanceMargin());
        position.setFloatingPnl(risk.unrealizedPnl());
        position.setCurrentPrice(market.mark());
        position.setMarkPrice(market.mark());
        position.setVersion(valueOrDefault(
            originalPositionVersions.get(position.getId()), 0L) + 1L);
      }
      account.setEquity(nextEquity);
      account.setUsedMargin(nextUsedMargin);
      account.setFreeMargin(nextFreeMargin);
      accountRepository.save(account);
      for (PositionEntity position : lockedPositions) {
        if (!Objects.equals(
            originalPositionVersions.get(position.getId()),
            position.getVersion())) {
          positionRepository.save(position);
        }
      }
      UUID referencePositionId = projections.isEmpty()
          ? null
          : projections.getFirst().position().getId();
      if (aggregateDelta.compareTo(BigDecimal.ZERO) > 0) {
        ledgerService.recordMarginHold(
            account,
            aggregateDelta,
            referencePositionId,
            "Perpetual leverage margin recalculated");
      } else if (aggregateDelta.compareTo(BigDecimal.ZERO) < 0) {
        ledgerService.recordMarginRelease(
            account,
            aggregateDelta.abs(),
            referencePositionId,
            "Perpetual leverage margin recalculated");
      }
    }
    return response(account);
  }

  private List<LeverageProjection> leverageProjections(
      List<PositionEntity> positions,
      int currentLeverage,
      int nextLeverage,
      SymbolEntity symbol,
      BigDecimal markPrice,
      List<OrderEntity> activeOrders
  ) {
    List<LeverageProjection> projections = new ArrayList<>();
    for (PositionEntity position : positions) {
      BigDecimal oldMarginHeld = orZero(position.getMarginHeld()).setScale(
          8, RoundingMode.HALF_UP);
      PositionRisk currentRisk = risk(
          position,
          currentLeverage,
          oldMarginHeld,
          symbol,
          markPrice);
      PositionRisk theoretical = risk(
          position,
          nextLeverage,
          oldMarginHeld,
          symbol,
          markPrice);
      BigDecimal nextMarginHeld = theoretical.initialMargin();
      if (position.getMarginMode() == MarginMode.ISOLATED) {
        BigDecimal signedManualAdjustment = oldMarginHeld.subtract(currentRisk.initialMargin());
        nextMarginHeld = theoretical.initialMargin()
            .add(signedManualAdjustment)
            .setScale(8, RoundingMode.HALF_UP);
      }
      if (nextMarginHeld.compareTo(BigDecimal.ZERO) < 0) {
        throw unsafeLeverageChange();
      }
      PositionRisk finalRisk = risk(
          position,
          nextLeverage,
          nextMarginHeld,
          symbol,
          markPrice);
      if (position.getMarginMode() == MarginMode.ISOLATED && finalRisk.liquidatable()) {
        throw unsafeLeverageChange();
      }
      if (position.getMarginMode() == MarginMode.ISOLATED
          && nextMarginHeld.compareTo(oldMarginHeld) < 0
          && internalIsolatedHolds(position.getId(), activeOrders)
              .compareTo(isolatedCapacity(finalRisk)) >= 0) {
        throw unsafeLeverageChange();
      }
      projections.add(new LeverageProjection(
          position,
          finalRisk,
          nextMarginHeld,
          nextMarginHeld.subtract(oldMarginHeld).setScale(8, RoundingMode.HALF_UP)));
    }
    return List.copyOf(projections);
  }

  private BigDecimal internalIsolatedHolds(UUID positionId, List<OrderEntity> activeOrders) {
    return activeOrders.stream()
        .filter(order -> order.getMarginMode() == MarginMode.ISOLATED)
        .filter(order -> positionId.equals(order.getParentPositionId()))
        .map(order -> orZero(order.getHoldAmount()))
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(8, RoundingMode.HALF_UP);
  }

  private BigDecimal isolatedCapacity(PositionRisk risk) {
    return risk.isolatedEquity()
        .subtract(risk.maintenanceMargin())
        .subtract(risk.estimatedCloseTakerFee())
        .setScale(8, RoundingMode.HALF_UP);
  }

  private PositionRisk risk(
      PositionEntity position,
      int leverage,
      BigDecimal marginHeld,
      SymbolEntity symbol,
      BigDecimal markPrice
  ) {
    try {
      return perpetualRiskService.positionRisk(
          position.getSide(),
          position.getLots(),
          position.getOpenPrice(),
          markPrice,
          leverage,
          marginHeld,
          position.getFundingPnl(),
          symbol.getMaintenanceMarginRate());
    } catch (IllegalArgumentException exception) {
      throw new BusinessException(
          ErrorCode.MARGIN_ADJUSTMENT_INVALID,
          "Position risk state is incomplete");
    }
  }

  private ExecutableMarketSnapshot resolvePerpetualMarket(String symbol) {
    Instant to = Instant.now();
    CandleRequest candles = new CandleRequest("1m", to.minus(Duration.ofMinutes(30)), to);
    return ExecutableMarketSnapshot.from(marketBundleResolver.resolvePerp(symbol, candles));
  }

  private BusinessException unsafeLeverageChange() {
    return new BusinessException(
        ErrorCode.MARGIN_REDUCTION_UNSAFE,
        "Leverage change would make an Isolated slot unsafe");
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

  private record LeverageProjection(
      PositionEntity position,
      PositionRisk risk,
      BigDecimal nextMarginHeld,
      BigDecimal marginDelta
  ) {
  }
}
