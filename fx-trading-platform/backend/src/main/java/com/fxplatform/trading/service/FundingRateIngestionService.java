package com.fxplatform.trading.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.ExecutableMarketSnapshots;
import com.fxplatform.market.entity.DataProviderEntity;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.entity.SymbolProviderBindingEntity;
import com.fxplatform.market.funding.FundingRateProvider;
import com.fxplatform.market.funding.FundingRateSnapshot;
import com.fxplatform.market.funding.FundingSource;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.DataProviderRepository;
import com.fxplatform.market.repository.SymbolProviderBindingRepository;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.trading.entity.FundingRateEntity;
import com.fxplatform.trading.repository.FundingRateRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Prepares funding observations without database locks and persists each symbol's
 * monotonic canonical stream in one short transaction.
 */
@Service
@Slf4j
public class FundingRateIngestionService {

  private static final List<FundingSource> DEFAULT_PRIORITY = List.of(
      FundingSource.BINANCE, FundingSource.OKX, FundingSource.FIXED);

  private final Map<FundingSource, FundingRateProvider> providers;
  private final FundingRateRepository fundingRateRepository;
  private final PositionRepository positionRepository;
  private final SymbolRepository symbolRepository;
  private final SymbolProviderBindingRepository symbolProviderBindingRepository;
  private final DataProviderRepository dataProviderRepository;
  private final MarketBundleResolver marketBundleResolver;
  private final TradingTransactionExecutor transactionExecutor;
  private final Clock clock;

  @Autowired
  public FundingRateIngestionService(
      List<FundingRateProvider> providers,
      FundingRateRepository fundingRateRepository,
      PositionRepository positionRepository,
      SymbolRepository symbolRepository,
      SymbolProviderBindingRepository symbolProviderBindingRepository,
      DataProviderRepository dataProviderRepository,
      MarketBundleResolver marketBundleResolver,
      TradingTransactionExecutor transactionExecutor
  ) {
    this(
        providers,
        fundingRateRepository,
        positionRepository,
        symbolRepository,
        symbolProviderBindingRepository,
        dataProviderRepository,
        marketBundleResolver,
        transactionExecutor,
        Clock.systemUTC());
  }

  FundingRateIngestionService(
      List<FundingRateProvider> providers,
      FundingRateRepository fundingRateRepository,
      PositionRepository positionRepository,
      SymbolRepository symbolRepository,
      SymbolProviderBindingRepository symbolProviderBindingRepository,
      DataProviderRepository dataProviderRepository,
      MarketBundleResolver marketBundleResolver,
      TradingTransactionExecutor transactionExecutor,
      Clock clock
  ) {
    this.providers = providerMap(providers);
    this.fundingRateRepository = fundingRateRepository;
    this.positionRepository = positionRepository;
    this.symbolRepository = symbolRepository;
    this.symbolProviderBindingRepository = symbolProviderBindingRepository;
    this.dataProviderRepository = dataProviderRepository;
    this.marketBundleResolver = marketBundleResolver;
    this.transactionExecutor = transactionExecutor;
    this.clock = clock;
  }

  public int ingestDueRates(Instant now) {
    if (now == null) {
      throw new BusinessException("FUNDING_INGESTION_TIME_REQUIRED", "Funding ingestion time is required");
    }
    int inserted = 0;
    for (SymbolEntity symbol : symbolRepository.findByEnabledTrueOrderBySymbolAsc()) {
      if (!isEligible(symbol)) {
        continue;
      }
      try {
        Optional<PreparedRates> prepared = prepare(symbol, now);
        if (prepared.isPresent()) {
          inserted += transactionExecutor.execute(() -> persist(prepared.get()));
        }
      } catch (RuntimeException exception) {
        log.warn("Funding rate ingestion failed for symbol={}", symbol.getSymbol(), exception);
      }
    }
    return inserted;
  }

  private Optional<PreparedRates> prepare(SymbolEntity symbol, Instant now) {
    List<FundingSource> priority = priority(symbol.getFundingSourcePriority());
    String platformSymbol = normalize(symbol.getSymbol());
    Map<FundingSource, String> providerSymbols = providerSymbols(symbol, false);
    int fixedIntervalMinutes = positive(symbol.getFixedFundingIntervalMinutes(), 480);
    Instant latestTime = fundingRateRepository.findLatestBySymbol(platformSymbol)
        .map(FundingRateEntity::getFundingTime)
        .orElse(null);

    Instant historyCursor = latestTime == null
        ? bootstrapCursor(platformSymbol, now, fixedIntervalMinutes)
        : latestTime;
    TreeMap<Instant, FundingRateSnapshot> canonical = new TreeMap<>();
    for (FundingSource source : priority) {
      FundingRateProvider provider = providers.get(source);
      if (provider == null || !providerSymbols.containsKey(source)) {
        continue;
      }
      try {
        FundingRateProvider.Query query = query(symbol, providerSymbols, source);
        for (FundingRateSnapshot snapshot : safe(
            provider.history(query, historyCursor, now))) {
          if (validSnapshot(snapshot, platformSymbol, source)
              && snapshot.fundingTime().isAfter(historyCursor)
              && !snapshot.fundingTime().isAfter(now)) {
            canonical.putIfAbsent(snapshot.fundingTime(), snapshot);
          }
        }
      } catch (RuntimeException exception) {
        log.debug(
            "Funding history source unavailable: symbol={}, source={}",
            platformSymbol,
            source,
            exception);
      }
    }

    SelectedProvider selected = selectCurrent(
        priority, providerSymbols, symbol, now).orElse(null);
    if (selected == null && canonical.isEmpty()) {
      return Optional.empty();
    }
    Instant currentFreshThrough = null;
    if (selected != null) {
      FundingRateSnapshot current = selected.current();
      if (validSnapshot(current, platformSymbol, selected.provider().source())
          && (latestTime == null || current.fundingTime().isAfter(latestTime))
          && canonical.putIfAbsent(current.fundingTime(), current) == null) {
        currentFreshThrough = safePlus(
            current.asOf(), positive(symbol.getFundingStaleSeconds(), 900));
      }
    }
    if (canonical.isEmpty()) {
      return Optional.empty();
    }

    List<FundingRateSnapshot> snapshots = new ArrayList<>(canonical.values());
    Instant authorityExpiresAt = null;
    if (snapshots.stream().anyMatch(snapshot -> !positive(snapshot.markPrice()))) {
      ExecutableMarketSnapshot authority = ExecutableMarketSnapshot.from(
          marketBundleResolver.resolvePerp(
              platformSymbol,
              new CandleRequest("1m", now.minus(1, ChronoUnit.MINUTES), now)));
      ExecutableMarketSnapshots.requireComplete(
          platformSymbol, ProductType.LINEAR_PERP, authority);
      if (!numericFits(authority.mark(), 24, 10)
          || !now.isBefore(authority.expiresAt())) {
        throw new BusinessException("FUNDING_MARK_STALE", "Funding mark snapshot is stale");
      }
      authorityExpiresAt = authority.expiresAt();
      snapshots = snapshots.stream()
          .map(snapshot -> positive(snapshot.markPrice())
              ? snapshot
              : withAuthorityMark(snapshot, authority))
          .toList();
    }
    return Optional.of(new PreparedRates(
        symbol.getId(),
        platformSymbol,
        configuration(symbol, providerSymbols),
        List.copyOf(snapshots),
        currentFreshThrough,
        authorityExpiresAt));
  }

  private Optional<SelectedProvider> selectCurrent(
      List<FundingSource> priority,
      Map<FundingSource, String> providerSymbols,
      SymbolEntity symbol,
      Instant now
  ) {
    Instant freshAtOrAfter = safeMinus(now, positive(symbol.getFundingStaleSeconds(), 900), 1L);
    for (FundingSource source : priority) {
      FundingRateProvider provider = providers.get(source);
      if (provider == null || !providerSymbols.containsKey(source)) {
        continue;
      }
      try {
        FundingRateProvider.Query query = query(symbol, providerSymbols, source);
        Optional<FundingRateSnapshot> snapshot = provider.current(query);
        if (snapshot.isPresent()
            && validCurrentSnapshot(
                snapshot.get(), normalize(symbol.getSymbol()), source, now)
            && snapshot.get().asOf() != null
            && !snapshot.get().asOf().isBefore(freshAtOrAfter)) {
          return Optional.of(new SelectedProvider(provider, snapshot.get()));
        }
      } catch (RuntimeException exception) {
        log.debug(
            "Current funding source unavailable: symbol={}, source={}",
            symbol.getSymbol(),
            source,
            exception);
      }
    }
    return Optional.empty();
  }

  private int persist(PreparedRates prepared) {
    SymbolEntity locked = symbolRepository.findByIdForUpdate(prepared.symbolId())
        .orElseThrow(() -> new BusinessException("SYMBOL_NOT_FOUND", "Symbol not found"));
    Map<FundingSource, String> lockedProviderSymbols = providerSymbols(locked, true);
    if (!isEligible(locked)
        || !normalize(locked.getSymbol()).equals(prepared.symbol())
        || !configuration(locked, lockedProviderSymbols).equals(prepared.configuration())) {
      return 0;
    }

    Instant cursor = fundingRateRepository.findLatestBySymbol(prepared.symbol())
        .map(FundingRateEntity::getFundingTime)
        .orElse(null);
    Instant mutationTime = clock.instant();
    if (prepared.currentFreshThrough() != null
        && mutationTime.isAfter(prepared.currentFreshThrough())) {
      return 0;
    }
    if (prepared.authorityExpiresAt() != null
        && !mutationTime.isBefore(prepared.authorityExpiresAt())) {
      return 0;
    }
    int inserted = 0;
    for (FundingRateSnapshot snapshot : prepared.snapshots()) {
      if (cursor != null && !snapshot.fundingTime().isAfter(cursor)) {
        continue;
      }
      FundingRateEntity entity = entity(snapshot);
      if (fundingRateRepository.insertIfAbsent(entity)) {
        inserted++;
        cursor = snapshot.fundingTime();
      }
    }
    return inserted;
  }

  private FundingRateEntity entity(FundingRateSnapshot snapshot) {
    FundingRateEntity entity = new FundingRateEntity();
    entity.setId(UUID.randomUUID());
    entity.setSymbol(normalize(snapshot.symbol()));
    entity.setFundingRate(snapshot.fundingRate());
    entity.setFundingTime(snapshot.fundingTime());
    entity.setNextFundingTime(snapshot.nextFundingTime());
    entity.setMarkPrice(snapshot.markPrice());
    entity.setProviderCode(snapshot.providerCode());
    entity.setSourceMode(snapshot.sourceMode().name());
    entity.setAsOf(snapshot.asOf());
    entity.setIntervalMinutes(snapshot.intervalMinutes());
    entity.setRawPayloadHash(snapshot.rawPayloadHash());
    return entity;
  }

  private FundingRateSnapshot withAuthorityMark(
      FundingRateSnapshot snapshot,
      ExecutableMarketSnapshot authority
  ) {
    return new FundingRateSnapshot(
        snapshot.symbol(),
        snapshot.fundingRate(),
        snapshot.fundingTime(),
        snapshot.nextFundingTime(),
        authority.mark(),
        snapshot.asOf(),
        snapshot.providerCode(),
        snapshot.sourceMode(),
        snapshot.intervalMinutes(),
        sha256(snapshot.rawPayloadHash()
            + '|' + authority.providerCode()
            + '|' + authority.mark().toPlainString()
            + '|' + authority.asOf()));
  }

  private boolean validSnapshot(
      FundingRateSnapshot snapshot,
      String symbol,
      FundingSource expectedSource
  ) {
    return snapshot != null
        && normalize(snapshot.symbol()).equals(symbol)
        && snapshot.fundingRate() != null
        && snapshot.fundingTime() != null
        && snapshot.nextFundingTime() != null
        && snapshot.nextFundingTime().isAfter(snapshot.fundingTime())
        && snapshot.asOf() != null
        && snapshot.sourceMode() != null
        && snapshot.intervalMinutes() > 0
        && intervalMatches(snapshot)
        && numericFits(snapshot.fundingRate(), 18, 10)
        && snapshot.rawPayloadHash() != null
        && !snapshot.rawPayloadHash().isBlank()
        && snapshot.sourceMode() == expectedSourceMode(expectedSource)
        && (expectedSource == FundingSource.FIXED
            ? snapshot.markPrice() == null || numericFits(snapshot.markPrice(), 24, 10)
            : positive(snapshot.markPrice()) && numericFits(snapshot.markPrice(), 24, 10))
        && expectedSource.providerCode().equals(normalizeCode(snapshot.providerCode()));
  }

  private boolean validCurrentSnapshot(
      FundingRateSnapshot snapshot,
      String symbol,
      FundingSource expectedSource,
      Instant now
  ) {
    return validSnapshot(snapshot, symbol, expectedSource)
        && !snapshot.fundingTime().isBefore(now)
        && !snapshot.fundingTime().isAfter(
            safePlusMinutes(now, snapshot.intervalMinutes()));
  }

  private boolean intervalMatches(FundingRateSnapshot snapshot) {
    try {
      long seconds = Duration.between(
          snapshot.fundingTime(), snapshot.nextFundingTime()).getSeconds();
      return seconds == Math.multiplyExact((long) snapshot.intervalMinutes(), 60L);
    } catch (ArithmeticException | java.time.DateTimeException ignored) {
      return false;
    }
  }

  private boolean numericFits(BigDecimal value, int precision, int scale) {
    if (value == null) {
      return false;
    }
    try {
      return value.setScale(scale, RoundingMode.UNNECESSARY).precision() <= precision;
    } catch (ArithmeticException ignored) {
      return false;
    }
  }

  private MarketSourceMode expectedSourceMode(FundingSource source) {
    return source == FundingSource.FIXED
        ? MarketSourceMode.LOCAL_SIMULATED
        : MarketSourceMode.PUBLIC_EXTERNAL;
  }

  private List<FundingSource> priority(List<String> configured) {
    List<String> values = configured == null ? List.of() : configured;
    List<FundingSource> parsed;
    try {
      parsed = values.stream()
          .map(value -> FundingSource.valueOf(normalizeCode(value).toUpperCase(Locale.ROOT)))
          .toList();
    } catch (IllegalArgumentException exception) {
      throw new BusinessException("INVALID_FUNDING_SOURCE_PRIORITY", "Funding source priority is invalid");
    }
    if (parsed.size() != DEFAULT_PRIORITY.size()
        || !FundingSource.FIXED.equals(parsed.getLast())
        || EnumSet.copyOf(parsed).size() != DEFAULT_PRIORITY.size()
        || !EnumSet.copyOf(parsed).equals(EnumSet.allOf(FundingSource.class))) {
      throw new BusinessException(
          "INVALID_FUNDING_SOURCE_PRIORITY",
          "Funding source priority must contain BINANCE, OKX and FIXED exactly once");
    }
    return parsed;
  }

  private Configuration configuration(
      SymbolEntity symbol,
      Map<FundingSource, String> providerSymbols
  ) {
    return new Configuration(
        List.copyOf(symbol.getFundingSourcePriority() == null
            ? DEFAULT_PRIORITY.stream().map(Enum::name).toList()
            : symbol.getFundingSourcePriority()),
        symbol.getFixedFundingRate(),
        positive(symbol.getFixedFundingIntervalMinutes(), 480),
        positive(symbol.getFundingStaleSeconds(), 900),
        Map.copyOf(providerSymbols));
  }

  private FundingRateProvider.Query query(
      SymbolEntity symbol,
      Map<FundingSource, String> providerSymbols,
      FundingSource source
  ) {
    return new FundingRateProvider.Query(
        normalize(symbol.getSymbol()),
        providerSymbols.get(source),
        symbol.getFixedFundingRate(),
        positive(symbol.getFixedFundingIntervalMinutes(), 480));
  }

  private Map<FundingSource, String> providerSymbols(
      SymbolEntity symbol,
      boolean forUpdate
  ) {
    EnumMap<FundingSource, String> result = new EnumMap<>(FundingSource.class);
    result.put(FundingSource.FIXED, normalize(symbol.getSymbol()));
    List<SymbolProviderBindingEntity> bindings = safe(forUpdate
        ? symbolProviderBindingRepository.findEnabledBySymbolIdForUpdate(symbol.getId())
        : symbolProviderBindingRepository.findEnabledBySymbolIdOrderByPriority(symbol.getId()));
    List<UUID> providerIds = bindings.stream()
        .map(SymbolProviderBindingEntity::getProviderId)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
    Map<UUID, DataProviderEntity> providersById = new java.util.LinkedHashMap<>();
    List<DataProviderEntity> providerRows = forUpdate
        ? dataProviderRepository.findByIdsForUpdate(providerIds)
        : dataProviderRepository.findByIds(providerIds);
    for (DataProviderEntity provider : safe(providerRows)) {
      if (provider != null && provider.getId() != null && Boolean.TRUE.equals(provider.getEnabled())) {
        providersById.put(provider.getId(), provider);
      }
    }
    Set<FundingSource> bound = EnumSet.noneOf(FundingSource.class);
    for (SymbolProviderBindingEntity binding : bindings) {
      DataProviderEntity provider = providersById.get(binding.getProviderId());
      FundingSource source = provider == null ? null : fundingSource(provider.getCode());
      if (source != null && bound.add(source)
          && binding.getProviderSymbol() != null
          && !binding.getProviderSymbol().isBlank()) {
        result.put(source, normalize(binding.getProviderSymbol()));
      }
    }
    return Map.copyOf(result);
  }

  private FundingSource fundingSource(String providerCode) {
    return switch (normalizeCode(providerCode)) {
      case "binance", "binance-usdm" -> FundingSource.BINANCE;
      case "okx", "okx-swap" -> FundingSource.OKX;
      case "fixed" -> FundingSource.FIXED;
      default -> null;
    };
  }

  private boolean isEligible(SymbolEntity symbol) {
    return symbol != null
        && Boolean.TRUE.equals(symbol.getEnabled())
        && Boolean.TRUE.equals(symbol.getTradable())
        && symbol.getProductType() == ProductType.LINEAR_PERP;
  }

  private static Map<FundingSource, FundingRateProvider> providerMap(
      List<FundingRateProvider> providerList
  ) {
    EnumMap<FundingSource, FundingRateProvider> result = new EnumMap<>(FundingSource.class);
    for (FundingRateProvider provider : safe(providerList)) {
      if (provider == null || provider.source() == null
          || result.putIfAbsent(provider.source(), provider) != null) {
        throw new IllegalArgumentException("Funding providers must have unique sources");
      }
    }
    return Map.copyOf(result);
  }

  private static <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
  }

  private static Instant safeMinus(Instant instant, int intervalMinutes) {
    return safeMinus(instant, intervalMinutes, 60L);
  }

  private static Instant safePlus(Instant instant, int seconds) {
    try {
      return instant.plusSeconds(seconds);
    } catch (ArithmeticException | java.time.DateTimeException ignored) {
      return Instant.MAX;
    }
  }

  private static Instant safePlusMinutes(Instant instant, int minutes) {
    try {
      return instant.plusSeconds(Math.multiplyExact((long) minutes, 60L));
    } catch (ArithmeticException | java.time.DateTimeException ignored) {
      return Instant.MAX;
    }
  }

  private static Instant safeMinus(Instant instant, int units, long secondsPerUnit) {
    try {
      return instant.minusSeconds(Math.multiplyExact((long) units, secondsPerUnit));
    } catch (ArithmeticException | java.time.DateTimeException ignored) {
      return Instant.EPOCH;
    }
  }

  private Instant bootstrapCursor(
      String symbol,
      Instant now,
      int fixedIntervalMinutes
  ) {
    return positionRepository.findEarliestOpenLinearPerpTimeBySymbol(symbol)
        .filter(openedAt -> !openedAt.isAfter(now))
        .map(FundingRateIngestionService::exclusiveBefore)
        .orElseGet(() -> safeMinus(now, fixedIntervalMinutes));
  }

  private static Instant exclusiveBefore(Instant instant) {
    try {
      return instant.minusMillis(1L);
    } catch (ArithmeticException | java.time.DateTimeException ignored) {
      return Instant.MIN;
    }
  }

  private static int positive(Integer value, int fallback) {
    return value == null || value <= 0 ? fallback : value;
  }

  private static boolean positive(BigDecimal value) {
    return value != null && value.compareTo(BigDecimal.ZERO) > 0;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  private static String normalizeCode(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private record SelectedProvider(FundingRateProvider provider, FundingRateSnapshot current) {
  }

  private record Configuration(
      List<String> priority,
      BigDecimal fixedRate,
      int fixedIntervalMinutes,
      int staleSeconds,
      Map<FundingSource, String> providerSymbols
  ) {
  }

  private record PreparedRates(
      UUID symbolId,
      String symbol,
      Configuration configuration,
      List<FundingRateSnapshot> snapshots,
      Instant currentFreshThrough,
      Instant authorityExpiresAt
  ) {
  }
}
