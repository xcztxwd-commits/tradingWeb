package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.entity.DataProviderEntity;
import com.fxplatform.market.entity.SymbolProviderBindingEntity;
import com.fxplatform.market.funding.FundingRateProvider;
import com.fxplatform.market.funding.FundingRateSnapshot;
import com.fxplatform.market.funding.FundingSource;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.repository.DataProviderRepository;
import com.fxplatform.market.repository.SymbolProviderBindingRepository;
import com.fxplatform.trading.entity.FundingRateEntity;
import com.fxplatform.trading.repository.FundingRateRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FundingRateIngestionServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-12T10:00:00Z");
  private static final Instant NEXT = Instant.parse("2026-07-12T16:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Mock private FundingRateProvider binance;
  @Mock private FundingRateProvider okx;
  @Mock private FundingRateProvider fixed;
  @Mock private FundingRateRepository fundingRateRepository;
  @Mock private PositionRepository positionRepository;
  @Mock private SymbolRepository symbolRepository;
  @Mock private SymbolProviderBindingRepository symbolProviderBindingRepository;
  @Mock private DataProviderRepository dataProviderRepository;
  @Mock private MarketBundleResolver marketBundleResolver;
  @Mock private TradingTransactionExecutor transactionExecutor;

  private SymbolEntity symbol;
  private AtomicBoolean insideTransaction;
  private DataProviderEntity binanceProviderEntity;
  private DataProviderEntity okxProviderEntity;
  private SymbolProviderBindingEntity binanceBinding;
  private SymbolProviderBindingEntity okxBinding;

  @BeforeEach
  void setUp() {
    symbol = symbol();
    insideTransaction = new AtomicBoolean();
    binanceProviderEntity = provider("binance-usdm");
    okxProviderEntity = provider("okx-swap");
    binanceBinding = binding(binanceProviderEntity.getId(), "BTCUSDT", 10);
    okxBinding = binding(okxProviderEntity.getId(), "BTC-USDT-SWAP", 20);
    org.mockito.Mockito.lenient().when(binance.source()).thenReturn(FundingSource.BINANCE);
    org.mockito.Mockito.lenient().when(okx.source()).thenReturn(FundingSource.OKX);
    org.mockito.Mockito.lenient().when(fixed.source()).thenReturn(FundingSource.FIXED);
    org.mockito.Mockito.lenient().when(symbolRepository.findByEnabledTrueOrderBySymbolAsc())
        .thenReturn(List.of(symbol));
    org.mockito.Mockito.lenient().when(symbolRepository.findByIdForUpdate(symbol.getId()))
        .thenReturn(Optional.of(symbol));
    org.mockito.Mockito.lenient().when(
        symbolProviderBindingRepository.findEnabledBySymbolIdOrderByPriority(symbol.getId()))
        .thenReturn(List.of(binanceBinding, okxBinding));
    org.mockito.Mockito.lenient().when(
        symbolProviderBindingRepository.findEnabledBySymbolIdForUpdate(symbol.getId()))
        .thenReturn(List.of(binanceBinding, okxBinding));
    org.mockito.Mockito.lenient().when(dataProviderRepository.findByIds(any()))
        .thenReturn(List.of(binanceProviderEntity, okxProviderEntity));
    org.mockito.Mockito.lenient().when(dataProviderRepository.findByIdsForUpdate(any()))
        .thenReturn(List.of(binanceProviderEntity, okxProviderEntity));
    org.mockito.Mockito.lenient().when(
        positionRepository.findEarliestOpenLinearPerpTimeBySymbol(symbol.getSymbol()))
        .thenReturn(Optional.empty());
    org.mockito.Mockito.lenient().when(fundingRateRepository.findLatestBySymbol(symbol.getSymbol()))
        .thenReturn(Optional.empty());
    org.mockito.Mockito.lenient().when(fundingRateRepository.insertIfAbsent(any()))
        .thenReturn(true);
    org.mockito.Mockito.lenient().when(transactionExecutor.execute(any())).thenAnswer(invocation -> {
      insideTransaction.set(true);
      try {
        return ((Supplier<?>) invocation.getArgument(0)).get();
      } finally {
        insideTransaction.set(false);
      }
    });
  }

  @Test
  void freshBinanceWinsAndPersistsItsActualSourceOutsideTheMutationTransaction() {
    FundingRateSnapshot selected = snapshot(
        "0.0001", NEXT, "65000", NOW.minusSeconds(30), "binance-usdm",
        MarketSourceMode.PUBLIC_EXTERNAL, "binance-hash");
    when(binance.current(any())).thenAnswer(invocation -> {
      assertThat(insideTransaction).isFalse();
      return Optional.of(selected);
    });
    when(binance.history(any(), any(), eq(NOW))).thenReturn(List.of());

    int inserted = service().ingestDueRates(NOW);

    assertThat(inserted).isEqualTo(1);
    ArgumentCaptor<FundingRateEntity> persisted = ArgumentCaptor.forClass(FundingRateEntity.class);
    verify(fundingRateRepository).insertIfAbsent(persisted.capture());
    assertThat(persisted.getValue()).satisfies(rate -> {
      assertThat(rate.getSymbol()).isEqualTo("BTCUSDT-PERP");
      assertThat(rate.getFundingRate()).isEqualByComparingTo("0.0001");
      assertThat(rate.getFundingTime()).isEqualTo(NEXT);
      assertThat(rate.getMarkPrice()).isEqualByComparingTo("65000");
      assertThat(rate.getProviderCode()).isEqualTo("binance-usdm");
      assertThat(rate.getSourceMode()).isEqualTo("PUBLIC_EXTERNAL");
      assertThat(rate.getAsOf()).isEqualTo(NOW.minusSeconds(30));
      assertThat(rate.getIntervalMinutes()).isEqualTo(480);
      assertThat(rate.getRawPayloadHash()).isEqualTo("binance-hash");
    });
    verify(okx, never()).current(any());
    verify(fixed, never()).current(any());
    verify(marketBundleResolver, never()).resolvePerp(any(), any());
  }

  @Test
  void staleBinanceFallsBackToFreshOkxAtTheConfiguredThreshold() {
    when(binance.current(any())).thenReturn(Optional.of(snapshot(
        "0.0002", NEXT, "65000", NOW.minusSeconds(901), "binance-usdm",
        MarketSourceMode.PUBLIC_EXTERNAL, "stale")));
    when(okx.current(any())).thenReturn(Optional.of(snapshot(
        "0.0003", NEXT, "65001", NOW.minusSeconds(900), "okx-swap",
        MarketSourceMode.PUBLIC_EXTERNAL, "okx-hash")));
    when(okx.history(any(), any(), eq(NOW))).thenReturn(List.of());

    int inserted = service().ingestDueRates(NOW);

    assertThat(inserted).isEqualTo(1);
    ArgumentCaptor<FundingRateEntity> persisted = ArgumentCaptor.forClass(FundingRateEntity.class);
    verify(fundingRateRepository).insertIfAbsent(persisted.capture());
    assertThat(persisted.getValue().getProviderCode()).isEqualTo("okx-swap");
    assertThat(persisted.getValue().getFundingRate()).isEqualByComparingTo("0.0003");
    verify(fixed, never()).current(any());
  }

  @Test
  void bothExternalSourcesUnavailableUseFixedRateAndOneWholeAuthorityMark() {
    when(binance.current(any())).thenReturn(Optional.empty());
    when(okx.current(any())).thenThrow(new IllegalStateException("OKX unavailable"));
    when(fixed.current(any())).thenReturn(Optional.of(snapshot(
        "0.0001", NEXT, null, NOW, "fixed",
        MarketSourceMode.LOCAL_SIMULATED, "fixed-hash")));
    when(fixed.history(any(), any(), eq(NOW))).thenReturn(List.of());
    when(marketBundleResolver.resolvePerp(eq("BTCUSDT-PERP"), any()))
        .thenReturn(bundle("65002"));

    int inserted = service().ingestDueRates(NOW);

    assertThat(inserted).isEqualTo(1);
    ArgumentCaptor<FundingRateEntity> persisted = ArgumentCaptor.forClass(FundingRateEntity.class);
    verify(fundingRateRepository).insertIfAbsent(persisted.capture());
    assertThat(persisted.getValue().getProviderCode()).isEqualTo("fixed");
    assertThat(persisted.getValue().getSourceMode()).isEqualTo("LOCAL_SIMULATED");
    assertThat(persisted.getValue().getMarkPrice()).isEqualByComparingTo("65002");
    verify(marketBundleResolver).resolvePerp(eq("BTCUSDT-PERP"), any());
  }

  @Test
  void providerRecoveryCannotReplaceTheSameCycleButCanCreateAStrictlyLaterCycle() {
    FundingRateEntity canonical = entity(NEXT, "okx-swap");
    when(fundingRateRepository.findLatestBySymbol("BTCUSDT-PERP"))
        .thenReturn(Optional.of(canonical));
    when(binance.current(any())).thenReturn(Optional.of(snapshot(
        "0.0009", NEXT, "65000", NOW, "binance-usdm",
        MarketSourceMode.PUBLIC_EXTERNAL, "same-cycle")));
    when(binance.history(any(), eq(NEXT), eq(NOW))).thenReturn(List.of());

    assertThat(service().ingestDueRates(NOW)).isZero();

    verify(fundingRateRepository, never()).insertIfAbsent(any());

    Instant later = Instant.parse("2026-07-13T00:00:00Z");
    Instant laterObservedAt = Instant.parse("2026-07-12T18:00:00Z");
    when(binance.current(any())).thenReturn(Optional.of(snapshot(
        "0.0004", later, "65100", laterObservedAt, "binance-usdm",
        MarketSourceMode.PUBLIC_EXTERNAL, "later-cycle")));

    assertThat(service(Clock.fixed(laterObservedAt, ZoneOffset.UTC))
        .ingestDueRates(laterObservedAt)).isEqualTo(1);
    ArgumentCaptor<FundingRateEntity> persisted = ArgumentCaptor.forClass(FundingRateEntity.class);
    verify(fundingRateRepository).insertIfAbsent(persisted.capture());
    assertThat(persisted.getValue().getFundingTime()).isEqualTo(later);
    assertThat(persisted.getValue().getProviderCode()).isEqualTo("binance-usdm");
  }

  @Test
  void restartPersistsEveryPreparedGapInAscendingOrderAndDuplicateCallbacksAreNoOps() {
    Instant start = Instant.parse("2026-07-11T16:00:00Z");
    Instant firstGap = Instant.parse("2026-07-12T00:00:00Z");
    Instant secondGap = Instant.parse("2026-07-12T08:00:00Z");
    FundingRateEntity latest = entity(start, "fixed");
    when(fundingRateRepository.findLatestBySymbol("BTCUSDT-PERP"))
        .thenReturn(Optional.of(latest));
    when(binance.current(any())).thenReturn(Optional.empty());
    when(okx.current(any())).thenReturn(Optional.empty());
    when(fixed.current(any())).thenReturn(Optional.of(snapshot(
        "0.0001", NEXT, null, NOW, "fixed", MarketSourceMode.LOCAL_SIMULATED, "next")));
    when(fixed.history(any(), eq(start), eq(NOW))).thenReturn(List.of(
        snapshot("0.0001", secondGap, null, secondGap, "fixed",
            MarketSourceMode.LOCAL_SIMULATED, "gap-2"),
        snapshot("0.0001", firstGap, null, firstGap, "fixed",
            MarketSourceMode.LOCAL_SIMULATED, "gap-1")));
    when(marketBundleResolver.resolvePerp(eq("BTCUSDT-PERP"), any()))
        .thenReturn(bundle("65000"));
    List<Instant> insertedTimes = new ArrayList<>();
    when(fundingRateRepository.insertIfAbsent(any())).thenAnswer(invocation -> {
      insertedTimes.add(((FundingRateEntity) invocation.getArgument(0)).getFundingTime());
      return true;
    });

    assertThat(service().ingestDueRates(NOW)).isEqualTo(3);
    assertThat(insertedTimes).containsExactly(firstGap, secondGap, NEXT);

    org.mockito.Mockito.doReturn(false).when(fundingRateRepository).insertIfAbsent(any());
    assertThat(service().ingestDueRates(NOW)).isZero();
  }

  @Test
  void fixedGapEnrichmentNeverOverwritesExternalProviderMarks() {
    Instant start = Instant.parse("2026-07-12T00:00:00Z");
    Instant externalGap = Instant.parse("2026-07-12T04:00:00Z");
    Instant fixedGap = Instant.parse("2026-07-12T08:00:00Z");
    when(fundingRateRepository.findLatestBySymbol("BTCUSDT-PERP"))
        .thenReturn(Optional.of(entity(start, "binance-usdm")));
    when(binance.history(any(), eq(start), eq(NOW))).thenReturn(List.of(snapshot(
        "0.0002", externalGap, "64000", externalGap, "binance-usdm",
        MarketSourceMode.PUBLIC_EXTERNAL, "external-gap")));
    when(fixed.history(any(), eq(start), eq(NOW))).thenReturn(List.of(snapshot(
        "0.0001", fixedGap, null, fixedGap, "fixed",
        MarketSourceMode.LOCAL_SIMULATED, "fixed-gap")));
    when(binance.current(any())).thenReturn(Optional.of(snapshot(
        "0.0003", NEXT, "65000", NOW, "binance-usdm",
        MarketSourceMode.PUBLIC_EXTERNAL, "current")));
    when(marketBundleResolver.resolvePerp(eq("BTCUSDT-PERP"), any()))
        .thenReturn(bundle("66000"));
    List<FundingRateEntity> inserted = new ArrayList<>();
    when(fundingRateRepository.insertIfAbsent(any())).thenAnswer(invocation -> {
      inserted.add(invocation.getArgument(0));
      return true;
    });

    assertThat(service().ingestDueRates(NOW)).isEqualTo(3);

    assertThat(inserted).extracting(FundingRateEntity::getFundingTime)
        .containsExactly(externalGap, fixedGap, NEXT);
    assertThat(inserted).extracting(FundingRateEntity::getMarkPrice)
        .containsExactly(
            new BigDecimal("64000"), new BigDecimal("66000"), new BigDecimal("65000"));
    assertThat(inserted).extracting(FundingRateEntity::getProviderCode)
        .containsExactly("binance-usdm", "fixed", "binance-usdm");
    assertThat(inserted.get(1).getRawPayloadHash()).hasSize(64);
  }

  @Test
  void providerSpecificBindingWinsOverAnotherPrimaryProviderSymbol() {
    symbol.setProvider("okx-swap");
    symbol.setProviderSymbol("BTC-USDT-SWAP");
    UUID providerId = UUID.randomUUID();
    SymbolProviderBindingEntity binding = new SymbolProviderBindingEntity();
    binding.setSymbolId(symbol.getId());
    binding.setProviderId(providerId);
    binding.setProviderSymbol("BTCUSDT");
    binding.setEnabled(true);
    DataProviderEntity provider = new DataProviderEntity();
    provider.setId(providerId);
    provider.setCode("binance-usdm");
    provider.setEnabled(true);
    when(symbolProviderBindingRepository.findEnabledBySymbolIdOrderByPriority(symbol.getId()))
        .thenReturn(List.of(binding));
    when(symbolProviderBindingRepository.findEnabledBySymbolIdForUpdate(symbol.getId()))
        .thenReturn(List.of(binding));
    when(dataProviderRepository.findByIds(List.of(providerId))).thenReturn(List.of(provider));
    when(dataProviderRepository.findByIdsForUpdate(List.of(providerId))).thenReturn(List.of(provider));
    when(binance.current(any())).thenAnswer(invocation -> {
      FundingRateProvider.Query query = invocation.getArgument(0);
      assertThat(query.providerSymbol()).isEqualTo("BTCUSDT");
      return Optional.of(snapshot(
          "0.0001", NEXT, "65000", NOW, "binance-usdm",
          MarketSourceMode.PUBLIC_EXTERNAL, "bound-binance"));
    });

    assertThat(service().ingestDueRates(NOW)).isEqualTo(1);
  }

  @Test
  void currentSnapshotThatExpiresWhileWaitingForTheSymbolLockMakesNoMutation() {
    when(binance.current(any())).thenReturn(Optional.of(snapshot(
        "0.0001", NEXT, "65000", NOW, "binance-usdm",
        MarketSourceMode.PUBLIC_EXTERNAL, "expires-under-lock")));
    Clock afterFreshnessDeadline = Clock.fixed(NOW.plusSeconds(901), ZoneOffset.UTC);

    assertThat(service(afterFreshnessDeadline).ingestDueRates(NOW)).isZero();

    verify(fundingRateRepository, never()).insertIfAbsent(any());
  }

  @Test
  void firstCanonicalRateBootstrapsHistoryFromTheEarliestEligibleOpenPosition() {
    Instant openedAt = Instant.parse("2026-07-10T16:00:00Z");
    when(positionRepository.findEarliestOpenLinearPerpTimeBySymbol("BTCUSDT-PERP"))
        .thenReturn(Optional.of(openedAt));
    when(binance.current(any())).thenReturn(Optional.of(snapshot(
        "0.0001", NEXT, "65000", NOW, "binance-usdm",
        MarketSourceMode.PUBLIC_EXTERNAL, "bootstrap")));

    assertThat(service().ingestDueRates(NOW)).isEqualTo(1);

    verify(binance).history(any(), eq(openedAt.minusMillis(1)), eq(NOW));
  }

  @Test
  void farFutureExternalCurrentIsRejectedBeforeItCanFreezeTheCanonicalCursor() {
    Instant farFuture = NOW.plusSeconds(24 * 60 * 60);
    when(binance.current(any())).thenReturn(Optional.of(snapshot(
        "0.0001", farFuture, "65000", NOW, "binance-usdm",
        MarketSourceMode.PUBLIC_EXTERNAL, "far-future")));
    when(okx.current(any())).thenReturn(Optional.of(snapshot(
        "0.0002", NEXT, "65001", NOW, "okx-swap",
        MarketSourceMode.PUBLIC_EXTERNAL, "valid-okx")));

    assertThat(service().ingestDueRates(NOW)).isEqualTo(1);

    ArgumentCaptor<FundingRateEntity> persisted = ArgumentCaptor.forClass(FundingRateEntity.class);
    verify(fundingRateRepository).insertIfAbsent(persisted.capture());
    assertThat(persisted.getValue().getProviderCode()).isEqualTo("okx-swap");
    assertThat(persisted.getValue().getFundingTime()).isEqualTo(NEXT);
  }

  @Test
  void disabledExternalBindingIsNotPolledAndFallsThroughToNextEnabledBinding() {
    binanceProviderEntity.setEnabled(false);
    when(okx.current(any())).thenReturn(Optional.of(snapshot(
        "0.0002", NEXT, "65001", NOW, "okx-swap",
        MarketSourceMode.PUBLIC_EXTERNAL, "enabled-okx")));

    assertThat(service().ingestDueRates(NOW)).isEqualTo(1);

    verify(binance, never()).current(any());
  }

  @Test
  void malformedExternalNumericAndSourceMetadataFallsBackBeforeInsert() {
    when(binance.current(any())).thenReturn(Optional.of(new FundingRateSnapshot(
        "BTCUSDT-PERP",
        new BigDecimal("123456789.1234567890"),
        NEXT,
        NEXT.plusSeconds(480L * 60L),
        new BigDecimal("65000"),
        NOW,
        "binance-usdm",
        MarketSourceMode.LOCAL_SIMULATED,
        480,
        "")));
    when(okx.current(any())).thenReturn(Optional.of(snapshot(
        "0.0002", NEXT, "65001", NOW, "okx-swap",
        MarketSourceMode.PUBLIC_EXTERNAL, "valid-okx")));

    assertThat(service().ingestDueRates(NOW)).isEqualTo(1);

    ArgumentCaptor<FundingRateEntity> persisted = ArgumentCaptor.forClass(FundingRateEntity.class);
    verify(fundingRateRepository).insertIfAbsent(persisted.capture());
    assertThat(persisted.getValue().getProviderCode()).isEqualTo("okx-swap");
  }

  @Test
  void fixedAuthorityMarkAtItsExpiryMakesNoMutationAfterAllConfigLocks() {
    when(binance.current(any())).thenReturn(Optional.empty());
    when(okx.current(any())).thenReturn(Optional.empty());
    when(fixed.current(any())).thenReturn(Optional.of(snapshot(
        "0.0001", NEXT, null, NOW, "fixed",
        MarketSourceMode.LOCAL_SIMULATED, "fixed-expiry")));
    when(marketBundleResolver.resolvePerp(eq("BTCUSDT-PERP"), any()))
        .thenReturn(bundle("65000"));
    Clock atExpiry = Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC);

    assertThat(service(atExpiry).ingestDueRates(NOW)).isZero();

    verify(symbolProviderBindingRepository).findEnabledBySymbolIdForUpdate(symbol.getId());
    verify(dataProviderRepository).findByIdsForUpdate(any());
    verify(fundingRateRepository, never()).insertIfAbsent(any());
  }

  private FundingRateIngestionService service() {
    return service(CLOCK);
  }

  private FundingRateIngestionService service(Clock clock) {
    return new FundingRateIngestionService(
        List.of(binance, okx, fixed),
        fundingRateRepository,
        positionRepository,
        symbolRepository,
        symbolProviderBindingRepository,
        dataProviderRepository,
        marketBundleResolver,
        transactionExecutor,
        clock);
  }

  private FundingRateSnapshot snapshot(
      String rate,
      Instant fundingTime,
      String mark,
      Instant asOf,
      String providerCode,
      MarketSourceMode sourceMode,
      String hash
  ) {
    return new FundingRateSnapshot(
        "BTCUSDT-PERP",
        new BigDecimal(rate),
        fundingTime,
        fundingTime.plusSeconds(480L * 60L),
        mark == null ? null : new BigDecimal(mark),
        asOf,
        providerCode,
        sourceMode,
        480,
        hash);
  }

  private FundingRateEntity entity(Instant fundingTime, String providerCode) {
    FundingRateEntity entity = new FundingRateEntity();
    entity.setId(UUID.randomUUID());
    entity.setSymbol("BTCUSDT-PERP");
    entity.setFundingRate(new BigDecimal("0.0001"));
    entity.setFundingTime(fundingTime);
    entity.setNextFundingTime(fundingTime.plusSeconds(480L * 60L));
    entity.setMarkPrice(new BigDecimal("65000"));
    entity.setProviderCode(providerCode);
    entity.setSourceMode(providerCode.equals("fixed") ? "LOCAL_SIMULATED" : "PUBLIC_EXTERNAL");
    entity.setAsOf(fundingTime);
    entity.setIntervalMinutes(480);
    entity.setRawPayloadHash(providerCode + "-hash");
    return entity;
  }

  private SymbolEntity symbol() {
    SymbolEntity entity = new SymbolEntity();
    entity.setId(UUID.randomUUID());
    entity.setSymbol("BTCUSDT-PERP");
    entity.setProviderSymbol("BTCUSDT");
    entity.setProductType(ProductType.LINEAR_PERP);
    entity.setEnabled(true);
    entity.setTradable(true);
    entity.setFixedFundingRate(new BigDecimal("0.0001"));
    entity.setFixedFundingIntervalMinutes(480);
    entity.setFundingSourcePriority(List.of("BINANCE", "OKX", "FIXED"));
    entity.setFundingStaleSeconds(900);
    return entity;
  }

  private PerpetualMarketBundle bundle(String mark) {
    return new PerpetualMarketBundle(
        "BTCUSDT-PERP",
        "BTCUSDT-PERP",
        "local-perp",
        MarketSourceMode.LOCAL_SIMULATED,
        new BigDecimal(mark).subtract(BigDecimal.ONE),
        new BigDecimal(mark).add(BigDecimal.ONE),
        new BigDecimal(mark),
        new BigDecimal(mark),
        new BigDecimal(mark),
        null,
        List.of(),
        List.of(),
        NOW.minusSeconds(1),
        NOW.plusSeconds(30));
  }

  private DataProviderEntity provider(String code) {
    DataProviderEntity provider = new DataProviderEntity();
    provider.setId(UUID.randomUUID());
    provider.setCode(code);
    provider.setEnabled(true);
    return provider;
  }

  private SymbolProviderBindingEntity binding(UUID providerId, String providerSymbol, int priority) {
    SymbolProviderBindingEntity binding = new SymbolProviderBindingEntity();
    binding.setSymbolId(symbol.getId());
    binding.setProviderId(providerId);
    binding.setProviderSymbol(providerSymbol);
    binding.setPriority(priority);
    binding.setEnabled(true);
    return binding;
  }
}
