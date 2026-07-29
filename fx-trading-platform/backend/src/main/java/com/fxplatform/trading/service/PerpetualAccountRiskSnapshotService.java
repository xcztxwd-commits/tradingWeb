package com.fxplatform.trading.service;

import static com.fxplatform.common.money.MoneyAmount.orZero;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.common.market.SymbolNormalizer;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.service.PerpetualRiskService;
import com.fxplatform.risk.service.PerpetualRiskService.CrossRisk;
import com.fxplatform.risk.service.PerpetualRiskService.PoolPosition;
import com.fxplatform.risk.service.PerpetualRiskService.PositionRisk;
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Prepares and projects fresh account-wide Linear Perpetual risk without owning persistence. */
@Service
public class PerpetualAccountRiskSnapshotService {

  private static final int MONEY_SCALE = 8;
  private static final List<OrderStatus> ACTIVE_ORDER_STATUSES = List.of(
      OrderStatus.RECEIVED,
      OrderStatus.VALIDATING,
      OrderStatus.ACCEPTED,
      OrderStatus.PENDING_ACTIVATION,
      OrderStatus.PENDING,
      OrderStatus.WORKING,
      OrderStatus.PARTIALLY_FILLED,
      OrderStatus.CANCEL_PENDING);
  private static final Comparator<PositionEntity> POSITION_ORDER = Comparator
      .comparing((PositionEntity position) -> normalize(position.getSymbol()))
      .thenComparing(position -> position.getPositionSide() == null
          ? ""
          : position.getPositionSide().name())
      .thenComparing(position -> position.getId() == null
          ? new UUID(0L, 0L)
          : position.getId());

  private final PositionRepository positionRepository;
  private final SymbolRepository symbolRepository;
  private final MarketBundleResolver marketBundleResolver;
  private final FullFillCoordinator fullFillCoordinator;
  private final PerpetualRiskService perpetualRiskService;
  private final InstrumentRulesEngine instrumentRulesEngine;

  @Autowired
  public PerpetualAccountRiskSnapshotService(
      PositionRepository positionRepository,
      SymbolRepository symbolRepository,
      MarketBundleResolver marketBundleResolver,
      FullFillCoordinator fullFillCoordinator,
      PerpetualRiskService perpetualRiskService,
      InstrumentRulesEngine instrumentRulesEngine
  ) {
    this.positionRepository = positionRepository;
    this.symbolRepository = symbolRepository;
    this.marketBundleResolver = marketBundleResolver;
    this.fullFillCoordinator = fullFillCoordinator;
    this.perpetualRiskService = perpetualRiskService;
    this.instrumentRulesEngine = instrumentRulesEngine;
  }

  /** Compatibility constructor for focused fixtures without the complete rules engine graph. */
  public PerpetualAccountRiskSnapshotService(
      PositionRepository positionRepository,
      SymbolRepository symbolRepository,
      MarketBundleResolver marketBundleResolver,
      FullFillCoordinator fullFillCoordinator,
      PerpetualRiskService perpetualRiskService
  ) {
    this(
        positionRepository,
        symbolRepository,
        marketBundleResolver,
        fullFillCoordinator,
        perpetualRiskService,
        null);
  }

  /** Resolves every unique open-position symbol outside the consumer's mutation transaction. */
  public PreparedAccountRisk prepare(
      UUID accountId,
      Map<String, ExecutableMarketSnapshot> providedSnapshots
  ) {
    if (accountId == null) {
      throw unavailable("Perpetual account risk requires an account id");
    }
    List<PositionEntity> positions = sortedPositions(
        positionRepository.findOpenLinearPerpByAccountId(accountId));
    List<PositionFingerprint> fingerprints = positions.stream()
        .map(PositionFingerprint::from)
        .toList();
    Map<String, ExecutableMarketSnapshot> supplied = normalizeProvided(providedSnapshots);
    TreeSet<String> symbols = fingerprints.stream()
        .map(PositionFingerprint::symbol)
        .collect(Collectors.toCollection(TreeSet::new));
    symbols.addAll(supplied.keySet());

    Map<String, PreparedSymbolRisk> preparedSymbols = new LinkedHashMap<>();
    for (String symbol : symbols) {
      SymbolEntity configuration = symbolRepository.findBySymbol(symbol)
          .orElseThrow(() -> unavailable("Linear Perpetual symbol configuration is unavailable"));
      BigDecimal maintenanceMarginRate = requireLinearConfiguration(configuration, symbol);
      int maxLeverage = currentMaxLeverage(configuration);
      ExecutableMarketSnapshot snapshot = supplied.get(symbol);
      if (snapshot == null) {
        Instant to = Instant.now();
        CandleRequest candles = new CandleRequest("1m", to.minus(Duration.ofMinutes(30)), to);
        snapshot = ExecutableMarketSnapshot.from(marketBundleResolver.resolvePerp(symbol, candles));
      }
      if (snapshot == null) {
        throw unavailable("Linear Perpetual market snapshot is unavailable");
      }
      preparedSymbols.put(
          symbol,
          new PreparedSymbolRisk(symbol, snapshot, maintenanceMarginRate, maxLeverage));
    }
    return new PreparedAccountRisk(accountId, preparedSymbols, fingerprints);
  }

  /** Pure locked-state projection. It performs no provider calls and no persistence writes. */
  public AccountRiskProjection project(
      TradingAccountEntity account,
      List<PositionEntity> lockedPositions,
      List<OrderEntity> lockedActiveOrders,
      PreparedAccountRisk prepared
  ) {
    requirePreparedAccount(account, prepared);
    List<PositionEntity> positions = sortedPositions(lockedPositions);
    requireMatchingFingerprints(positions, prepared.positionFingerprints());
    for (PreparedSymbolRisk symbolRisk : prepared.symbols().values()) {
      requireAuthoritySnapshot(symbolRisk);
    }

    List<PositionProjection> positionProjections = new ArrayList<>();
    List<PoolPosition> poolPositions = new ArrayList<>();
    BigDecimal allUnrealizedPnl = moneyZero();
    BigDecimal isolatedFundingPnl = moneyZero();
    BigDecimal positionMargin = moneyZero();
    for (PositionEntity position : positions) {
      requireLockedPosition(account.getId(), position);
      PreparedSymbolRisk symbolRisk = prepared.symbols().get(normalize(position.getSymbol()));
      if (symbolRisk == null) {
        throw stale("Locked position symbol was not prepared");
      }
      PositionRisk risk = positionRisk(position, symbolRisk);
      positionProjections.add(new PositionProjection(
          position.getId(),
          position.getVersion(),
          symbolRisk.snapshot().mark().setScale(MONEY_SCALE, RoundingMode.HALF_UP),
          risk));
      poolPositions.add(new PoolPosition(position.getMarginMode(), risk));
      allUnrealizedPnl = allUnrealizedPnl.add(risk.unrealizedPnl());
      if (position.getMarginMode() == MarginMode.ISOLATED) {
        isolatedFundingPnl = isolatedFundingPnl.add(orZero(position.getFundingPnl()));
      }
      positionMargin = positionMargin.add(risk.marginHeld());
    }

    OrderHolds holds = orderHolds(account.getId(), positions, lockedActiveOrders);
    BigDecimal displayEquity = orZero(account.getBalance())
        .add(allUnrealizedPnl)
        .add(isolatedFundingPnl)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal usedMargin = positionMargin
        .add(holds.total())
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    CrossRisk cross = perpetualRiskService.crossRisk(
        account.getBalance(),
        poolPositions,
        holds.external());
    return new AccountRiskProjection(
        displayEquity,
        usedMargin,
        holds.total(),
        holds.external(),
        cross.crossEquity(),
        cross.crossAvailable(),
        cross.crossMaintenance(),
        cross.estimatedCloseTakerFees(),
        cross.liquidatable(),
        positionProjections);
  }

  /** Applies a completed projection to locked in-memory entities; callers remain responsible for saves. */
  public void applyRevaluation(
      TradingAccountEntity account,
      List<PositionEntity> lockedPositions,
      AccountRiskProjection projection
  ) {
    if (account == null || projection == null) {
      throw stale("Account risk projection is incomplete");
    }
    Map<UUID, PositionEntity> positionsById = sortedPositions(lockedPositions).stream()
        .collect(Collectors.toMap(PositionEntity::getId, Function.identity()));
    if (positionsById.size() != projection.positionProjections().size()) {
      throw stale("Locked position set changed before risk revaluation");
    }
    for (PositionProjection positionProjection : projection.positionProjections()) {
      PositionEntity position = positionsById.get(positionProjection.positionId());
      if (position == null
          || !Objects.equals(position.getVersion(), positionProjection.expectedVersion())) {
        throw stale("Locked position changed before risk revaluation");
      }
      applyPositionProjection(position, positionProjection);
    }
    account.setEquity(projection.displayEquity());
    account.setUsedMargin(projection.usedMargin());
    account.setFreeMargin(projection.crossAvailable());
  }

  private Map<String, ExecutableMarketSnapshot> normalizeProvided(
      Map<String, ExecutableMarketSnapshot> providedSnapshots
  ) {
    if (providedSnapshots == null || providedSnapshots.isEmpty()) {
      return Map.of();
    }
    Map<String, ExecutableMarketSnapshot> normalized = new LinkedHashMap<>();
    for (Map.Entry<String, ExecutableMarketSnapshot> entry : providedSnapshots.entrySet()) {
      String symbol = normalize(entry.getKey());
      if (symbol.isBlank() || entry.getValue() == null || normalized.putIfAbsent(symbol, entry.getValue()) != null) {
        throw unavailable("Provided Perpetual market snapshots are invalid");
      }
    }
    return Collections.unmodifiableMap(normalized);
  }

  private BigDecimal requireLinearConfiguration(SymbolEntity configuration, String symbol) {
    if (configuration == null
        || configuration.getProductType() != ProductType.LINEAR_PERP
        || !symbol.equals(normalize(configuration.getSymbol()))
        || configuration.getMaintenanceMarginRate() == null
        || configuration.getMaintenanceMarginRate().compareTo(BigDecimal.ZERO) <= 0
        || configuration.getMaintenanceMarginRate().compareTo(BigDecimal.ONE) >= 0) {
      throw unavailable("Linear Perpetual symbol risk configuration is invalid");
    }
    return configuration.getMaintenanceMarginRate();
  }

  private int currentMaxLeverage(SymbolEntity configuration) {
    int maximum = 100;
    if (configuration.getLeverage() != null && configuration.getLeverage() > 0) {
      maximum = Math.min(maximum, configuration.getLeverage());
    }
    InstrumentRules rules = instrumentRulesEngine == null
        ? null
        : instrumentRulesEngine.rules(configuration);
    if (rules != null && rules.maxLeverage() != null && rules.maxLeverage() > 0) {
      maximum = Math.min(maximum, rules.maxLeverage());
    }
    return maximum;
  }

  /**
   * Re-reads the current local instrument authority inside the caller-owned mutation transaction.
   * Market preparation is intentionally outside locks, but an Admin max-leverage reduction must
   * not be hidden by that earlier snapshot.
   */
  public void requireCurrentLeverageWithinLimit(AccountSymbolSettingEntity setting) {
    if (setting == null
        || setting.getSymbol() == null
        || setting.getSymbol().isBlank()
        || setting.getLeverage() == null
        || setting.getLeverage() < 1) {
      throw new BusinessException(
          ErrorCode.LEVERAGE_OUT_OF_RANGE,
          "Locked Perpetual leverage is invalid");
    }
    String symbol = normalize(setting.getSymbol());
    SymbolEntity configuration = symbolRepository.findBySymbol(symbol)
        .orElseThrow(() -> unavailable("Linear Perpetual symbol configuration is unavailable"));
    requireLinearConfiguration(configuration, symbol);
    if (setting.getLeverage() > currentMaxLeverage(configuration)) {
      throw new BusinessException(
          ErrorCode.LEVERAGE_OUT_OF_RANGE,
          "Locked Perpetual leverage exceeds the current instrument maximum");
    }
  }

  private void requirePreparedAccount(
      TradingAccountEntity account,
      PreparedAccountRisk prepared
  ) {
    if (account == null
        || account.getId() == null
        || prepared == null
        || !account.getId().equals(prepared.accountId())) {
      throw stale("Prepared Perpetual account risk no longer matches the locked account");
    }
  }

  private void requireMatchingFingerprints(
      List<PositionEntity> positions,
      List<PositionFingerprint> preparedFingerprints
  ) {
    if (preparedFingerprints == null || positions.size() != preparedFingerprints.size()) {
      throw stale("Open Perpetual position set changed while market risk was prepared");
    }
    for (int index = 0; index < positions.size(); index++) {
      if (!preparedFingerprints.get(index).matches(positions.get(index))) {
        throw stale("Open Perpetual position changed while market risk was prepared");
      }
    }
  }

  private void requireAuthoritySnapshot(PreparedSymbolRisk symbolRisk) {
    ExecutableMarketSnapshot snapshot = symbolRisk.snapshot();
    if (snapshot == null
        || snapshot.productType() != ProductType.LINEAR_PERP
        || !symbolRisk.symbol().equals(normalize(snapshot.platformSymbol()))
        || snapshot.mark() == null
        || snapshot.mark().compareTo(BigDecimal.ZERO) <= 0
        || snapshot.asOf() == null
        || snapshot.expiresAt() == null) {
      throw unavailable("Positive fresh authority mark is required for every Perpetual symbol");
    }
    fullFillCoordinator.requireFresh(snapshot);
  }

  private void requireLockedPosition(UUID accountId, PositionEntity position) {
    if (position == null
        || position.getId() == null
        || !accountId.equals(position.getAccountId())
        || position.getProductType() != ProductType.LINEAR_PERP
        || position.getStatus() != PositionStatus.OPEN
        || position.getPositionMode() == null
        || position.getPositionSide() == null
        || (position.getMarginMode() != MarginMode.CROSS
            && position.getMarginMode() != MarginMode.ISOLATED)
        || position.getSide() == null
        || position.getLots() == null
        || position.getLots().compareTo(BigDecimal.ZERO) <= 0
        || position.getOpenPrice() == null
        || position.getOpenPrice().compareTo(BigDecimal.ZERO) <= 0
        || position.getMarginHeld() == null
        || position.getMarginHeld().compareTo(BigDecimal.ZERO) < 0
        || position.getLeverage() == null
        || position.getLeverage() <= 0) {
      throw stale("Locked Perpetual position risk state is invalid");
    }
  }

  private PositionRisk positionRisk(
      PositionEntity position,
      PreparedSymbolRisk symbolRisk
  ) {
    try {
      return perpetualRiskService.positionRisk(
          position.getSide(),
          position.getLots(),
          position.getOpenPrice(),
          symbolRisk.snapshot().mark(),
          position.getLeverage(),
          position.getMarginHeld(),
          position.getFundingPnl(),
          symbolRisk.maintenanceMarginRate());
    } catch (IllegalArgumentException exception) {
      throw stale("Locked Perpetual position risk state is invalid");
    }
  }

  private OrderHolds orderHolds(
      UUID accountId,
      List<PositionEntity> lockedPositions,
      List<OrderEntity> lockedActiveOrders
  ) {
    BigDecimal total = moneyZero();
    BigDecimal external = moneyZero();
    if (lockedActiveOrders == null) {
      return new OrderHolds(total, external);
    }
    Map<UUID, PositionEntity> positionsById = lockedPositions.stream()
        .collect(Collectors.toMap(PositionEntity::getId, Function.identity()));
    for (OrderEntity order : lockedActiveOrders) {
      if (order == null || order.getProductType() != ProductType.LINEAR_PERP) {
        continue;
      }
      if (!accountId.equals(order.getAccountId())
          || order.getStatus() == null
          || !ACTIVE_ORDER_STATUSES.contains(order.getStatus())) {
        throw stale("Locked active Perpetual order set is invalid");
      }
      BigDecimal hold = orZero(order.getHoldAmount());
      if (hold.compareTo(BigDecimal.ZERO) < 0) {
        throw stale("Locked Perpetual order hold is invalid");
      }
      total = total.add(hold);
      boolean internalIsolatedClose = order.getMarginMode() == MarginMode.ISOLATED
          && order.getParentPositionId() != null;
      if (internalIsolatedClose
          && !matchesOpenIsolatedParent(order, positionsById.get(order.getParentPositionId()))) {
        throw new BusinessException(
            ErrorCode.ORDER_HOLD_INVALID,
            "Internal Isolated order hold no longer matches an open position slot");
      }
      if (!internalIsolatedClose) {
        external = external.add(hold);
      }
    }
    return new OrderHolds(
        total.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
        external.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
  }

  private boolean matchesOpenIsolatedParent(OrderEntity order, PositionEntity parent) {
    return parent != null
        && parent.getMarginMode() == MarginMode.ISOLATED
        && parent.getProductType() == ProductType.LINEAR_PERP
        && parent.getStatus() == PositionStatus.OPEN
        && Objects.equals(order.getAccountId(), parent.getAccountId())
        && normalize(order.getSymbol()).equals(normalize(parent.getSymbol()))
        && order.getPositionMode() == parent.getPositionMode()
        && order.getPositionSide() == parent.getPositionSide();
  }

  private void applyPositionProjection(
      PositionEntity position,
      PositionProjection projection
  ) {
    PositionRisk risk = projection.risk();
    boolean changed = differs(position.getCurrentPrice(), projection.mark())
        || differs(position.getMarkPrice(), projection.mark())
        || differs(position.getNotional(), risk.markNotional())
        || differs(position.getInitialMargin(), risk.initialMargin())
        || differs(position.getMaintenanceMargin(), risk.maintenanceMargin())
        || differs(position.getFloatingPnl(), risk.unrealizedPnl());
    position.setCurrentPrice(projection.mark());
    position.setMarkPrice(projection.mark());
    position.setNotional(risk.markNotional());
    position.setInitialMargin(risk.initialMargin());
    position.setMaintenanceMargin(risk.maintenanceMargin());
    position.setFloatingPnl(risk.unrealizedPnl());
    if (changed) {
      long version = position.getVersion() == null ? 0L : position.getVersion();
      position.setVersion(version + 1L);
    }
  }

  private static List<PositionEntity> sortedPositions(List<PositionEntity> positions) {
    if (positions == null || positions.isEmpty()) {
      return List.of();
    }
    List<PositionEntity> sorted = new ArrayList<>(positions);
    sorted.sort(POSITION_ORDER);
    return sorted;
  }

  private static boolean differs(BigDecimal left, BigDecimal right) {
    return left == null || right == null || left.compareTo(right) != 0;
  }

  private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
    return left == null ? right == null : right != null && left.compareTo(right) == 0;
  }

  private static String normalize(String symbol) {
    return symbol == null ? "" : SymbolNormalizer.normalize(symbol);
  }

  private static BigDecimal moneyZero() {
    return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BusinessException unavailable(String message) {
    return new BusinessException(ErrorCode.MARKET_DATA_UNAVAILABLE, message);
  }

  private static BusinessException stale(String message) {
    return new BusinessException(ErrorCode.MARKET_DATA_STALE, message);
  }

  private record OrderHolds(BigDecimal total, BigDecimal external) {
  }

  public record PositionFingerprint(
      UUID positionId,
      UUID accountId,
      Long version,
      String symbol,
      ProductType productType,
      PositionMode positionMode,
      PositionSide positionSide,
      MarginMode marginMode,
      PositionStatus status,
      OrderSide side,
      BigDecimal quantity,
      BigDecimal entryPrice,
      BigDecimal marginHeld,
      BigDecimal fundingPnl,
      Integer leverage
  ) {

    private static PositionFingerprint from(PositionEntity position) {
      if (position == null) {
        throw stale("Prepared Perpetual position is missing");
      }
      return new PositionFingerprint(
          position.getId(),
          position.getAccountId(),
          position.getVersion(),
          normalize(position.getSymbol()),
          position.getProductType(),
          position.getPositionMode(),
          position.getPositionSide(),
          position.getMarginMode(),
          position.getStatus(),
          position.getSide(),
          position.getLots(),
          position.getOpenPrice(),
          position.getMarginHeld(),
          position.getFundingPnl(),
          position.getLeverage());
    }

    private boolean matches(PositionEntity position) {
      return position != null
          && Objects.equals(positionId, position.getId())
          && Objects.equals(accountId, position.getAccountId())
          && Objects.equals(version, position.getVersion())
          && symbol.equals(normalize(position.getSymbol()))
          && productType == position.getProductType()
          && positionMode == position.getPositionMode()
          && positionSide == position.getPositionSide()
          && marginMode == position.getMarginMode()
          && status == position.getStatus()
          && side == position.getSide()
          && sameDecimal(quantity, position.getLots())
          && sameDecimal(entryPrice, position.getOpenPrice())
          && sameDecimal(marginHeld, position.getMarginHeld())
          && sameDecimal(fundingPnl, position.getFundingPnl())
          && Objects.equals(leverage, position.getLeverage());
    }
  }

  public record PreparedSymbolRisk(
      String symbol,
      ExecutableMarketSnapshot snapshot,
      BigDecimal maintenanceMarginRate,
      int maxLeverage
  ) {
  }

  public record PreparedAccountRisk(
      UUID accountId,
      Map<String, PreparedSymbolRisk> symbols,
      List<PositionFingerprint> positionFingerprints
  ) {

    public PreparedAccountRisk {
      symbols = Collections.unmodifiableMap(new LinkedHashMap<>(symbols));
      positionFingerprints = List.copyOf(positionFingerprints);
    }

    public Map<String, ExecutableMarketSnapshot> snapshots() {
      Map<String, ExecutableMarketSnapshot> snapshots = new LinkedHashMap<>();
      symbols.forEach((symbol, risk) -> snapshots.put(symbol, risk.snapshot()));
      return Collections.unmodifiableMap(snapshots);
    }
  }

  public record PositionProjection(
      UUID positionId,
      Long expectedVersion,
      BigDecimal mark,
      PositionRisk risk
  ) {
  }

  public record AccountRiskProjection(
      BigDecimal displayEquity,
      BigDecimal usedMargin,
      BigDecimal totalOrderHolds,
      BigDecimal externalOrderHolds,
      BigDecimal crossEquity,
      BigDecimal crossAvailable,
      BigDecimal crossMaintenance,
      BigDecimal crossEstimatedCloseFees,
      boolean crossLiquidatable,
      List<PositionProjection> positionProjections
  ) {

    public AccountRiskProjection {
      positionProjections = List.copyOf(positionProjections);
    }
  }
}
