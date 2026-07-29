package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.ExecutableMarketSnapshot;
import com.fxplatform.execution.ExecutionAdapter;
import com.fxplatform.execution.FullFillCoordinator;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.CandleRequest;
import com.fxplatform.market.model.MarketSourceMode;
import com.fxplatform.market.model.PerpetualMarketBundle;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.risk.service.PerpMarginCalculator;
import com.fxplatform.risk.service.PerpetualRiskService;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PerpetualAccountRiskSnapshotServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-12T10:00:00Z");
  private static final String BTC = "BTCUSDT-PERP";
  private static final String ETH = "ETHUSDT-PERP";

  @Mock PositionRepository positionRepository;
  @Mock SymbolRepository symbolRepository;
  @Mock MarketBundleResolver marketBundleResolver;

  private PerpetualAccountRiskSnapshotService service;

  @BeforeEach
  void setUp() {
    FullFillCoordinator freshness = new FullFillCoordinator(
        mock(ExecutionAdapter.class),
        Clock.fixed(NOW, ZoneOffset.UTC));
    service = new PerpetualAccountRiskSnapshotService(
        positionRepository,
        symbolRepository,
        marketBundleResolver,
        freshness,
        new PerpetualRiskService(new PerpMarginCalculator()));
  }

  @Test
  void prepareReusesProvidedSnapshotAndResolvesEachRemainingHedgeSymbolOnce() {
    UUID accountId = UUID.randomUUID();
    PositionEntity btcLong = position(
        accountId, UUID.randomUUID(), BTC, PositionSide.LONG, MarginMode.CROSS,
        OrderSide.BUY, "1", "100", "10", "0", 7L);
    PositionEntity btcShort = position(
        accountId, UUID.randomUUID(), BTC, PositionSide.SHORT, MarginMode.CROSS,
        OrderSide.SELL, "2", "100", "20", "0", 8L);
    PositionEntity eth = position(
        accountId, UUID.randomUUID(), ETH, PositionSide.BOTH, MarginMode.CROSS,
        OrderSide.BUY, "1", "200", "20", "0", 9L);
    when(positionRepository.findOpenLinearPerpByAccountId(accountId))
        .thenReturn(List.of(btcShort, eth, btcLong));
    stubSymbol(BTC);
    stubSymbol(ETH);
    when(marketBundleResolver.resolvePerp(eq(ETH), any(CandleRequest.class)))
        .thenReturn(bundle(ETH, "210"));

    var prepared = service.prepare(accountId, Map.of(BTC, snapshot(BTC, "110")));

    assertThat(prepared.accountId()).isEqualTo(accountId);
    assertThat(prepared.snapshots()).containsOnlyKeys(BTC, ETH);
    assertThat(prepared.positionFingerprints())
        .extracting(PerpetualAccountRiskSnapshotService.PositionFingerprint::positionId)
        .containsExactly(btcLong.getId(), btcShort.getId(), eth.getId());
    verify(marketBundleResolver, never()).resolvePerp(eq(BTC), any(CandleRequest.class));
    verify(marketBundleResolver, times(1)).resolvePerp(eq(ETH), any(CandleRequest.class));
  }

  @Test
  void projectUsesFreshCrossAndIsolatedPoolsAndExcludesOnlyInternalCloseHoldsFromAvailable() {
    UUID accountId = UUID.randomUUID();
    PositionEntity cross = position(
        accountId, UUID.randomUUID(), BTC, PositionSide.BOTH, MarginMode.CROSS,
        OrderSide.BUY, "1", "100", "10", "0", 2L);
    PositionEntity isolated = position(
        accountId, UUID.randomUUID(), ETH, PositionSide.BOTH, MarginMode.ISOLATED,
        OrderSide.BUY, "1", "200", "30", "7", 3L);
    when(positionRepository.findOpenLinearPerpByAccountId(accountId))
        .thenReturn(List.of(cross, isolated));
    stubSymbol(BTC);
    stubSymbol(ETH);
    var prepared = service.prepare(accountId, Map.of(
        BTC, snapshot(BTC, "110"),
        ETH, snapshot(ETH, "190")));
    TradingAccountEntity account = account(accountId, "1000", "9999", "8888", "7777");
    OrderEntity crossHold = activeOrder(accountId, BTC, MarginMode.CROSS, null, "5");
    OrderEntity isolatedOpeningHold = activeOrder(accountId, ETH, MarginMode.ISOLATED, null, "7");
    OrderEntity isolatedInternalHold = activeOrder(
        accountId, ETH, MarginMode.ISOLATED, isolated.getId(), "3");

    var projection = service.project(
        account,
        List.of(cross, isolated),
        List.of(isolatedInternalHold, crossHold, isolatedOpeningHold),
        prepared);

    assertThat(projection.displayEquity()).isEqualByComparingTo("1007.00000000");
    assertThat(projection.usedMargin()).isEqualByComparingTo("55.00000000");
    assertThat(projection.totalOrderHolds()).isEqualByComparingTo("15.00000000");
    assertThat(projection.externalOrderHolds()).isEqualByComparingTo("12.00000000");
    assertThat(projection.crossEquity()).isEqualByComparingTo("980.00000000");
    assertThat(projection.crossAvailable()).isEqualByComparingTo("958.00000000");
    assertThat(projection.crossMaintenance()).isEqualByComparingTo("0.55000000");
    assertThat(projection.crossEstimatedCloseFees()).isEqualByComparingTo("0.05500000");
    assertThat(projection.positionProjections()).hasSize(2);
  }

  @Test
  void projectIgnoresPersistedFreeMarginAndCanExposeAFreshCrossLoss() {
    UUID accountId = UUID.randomUUID();
    PositionEntity cross = position(
        accountId, UUID.randomUUID(), BTC, PositionSide.BOTH, MarginMode.CROSS,
        OrderSide.BUY, "1", "100", "10", "0", 1L);
    when(positionRepository.findOpenLinearPerpByAccountId(accountId)).thenReturn(List.of(cross));
    stubSymbol(BTC);
    var prepared = service.prepare(accountId, Map.of(BTC, snapshot(BTC, "50")));
    TradingAccountEntity staleAccount = account(accountId, "20", "20", "10", "999");

    var projection = service.project(staleAccount, List.of(cross), List.of(), prepared);

    assertThat(projection.displayEquity()).isEqualByComparingTo("-30.00000000");
    assertThat(projection.crossAvailable()).isEqualByComparingTo("-40.00000000");
  }

  @Test
  void projectRejectsPositionFingerprintDriftAsRetryableStaleState() {
    UUID accountId = UUID.randomUUID();
    PositionEntity preparedPosition = position(
        accountId, UUID.randomUUID(), BTC, PositionSide.BOTH, MarginMode.CROSS,
        OrderSide.BUY, "1", "100", "10", "0", 4L);
    when(positionRepository.findOpenLinearPerpByAccountId(accountId))
        .thenReturn(List.of(preparedPosition));
    stubSymbol(BTC);
    var prepared = service.prepare(accountId, Map.of(BTC, snapshot(BTC, "110")));
    PositionEntity changed = copy(preparedPosition);
    changed.setVersion(5L);

    assertThatThrownBy(() -> service.project(
        account(accountId, "1000", "1000", "10", "990"),
        List.of(changed),
        List.of(),
        prepared))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.MARKET_DATA_STALE));
  }

  @Test
  void projectRejectsMissingOrNonPositiveMarkAsUnavailable() {
    UUID accountId = UUID.randomUUID();
    PositionEntity position = position(
        accountId, UUID.randomUUID(), BTC, PositionSide.BOTH, MarginMode.CROSS,
        OrderSide.BUY, "1", "100", "10", "0", 1L);
    when(positionRepository.findOpenLinearPerpByAccountId(accountId)).thenReturn(List.of(position));
    stubSymbol(BTC);
    var prepared = service.prepare(accountId, Map.of(BTC, snapshot(BTC, "0")));

    assertThatThrownBy(() -> service.project(
        account(accountId, "1000", "1000", "10", "990"),
        List.of(position),
        List.of(),
        prepared))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.MARKET_DATA_UNAVAILABLE));
  }

  @Test
  void projectRejectsAnInternalIsolatedHoldWhoseParentSlotIsNoLongerOpen() {
    UUID accountId = UUID.randomUUID();
    when(positionRepository.findOpenLinearPerpByAccountId(accountId)).thenReturn(List.of());
    var prepared = service.prepare(accountId, Map.of());
    OrderEntity orphan = activeOrder(
        accountId, BTC, MarginMode.ISOLATED, UUID.randomUUID(), "10");

    assertThatThrownBy(() -> service.project(
        account(accountId, "1000", "1000", "10", "1000"),
        List.of(),
        List.of(orphan),
        prepared))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.ORDER_HOLD_INVALID));
  }

  @Test
  void projectRejectsExpiredPreparedSnapshotWithoutResolvingUnderLocks() {
    UUID accountId = UUID.randomUUID();
    PositionEntity position = position(
        accountId, UUID.randomUUID(), BTC, PositionSide.BOTH, MarginMode.CROSS,
        OrderSide.BUY, "1", "100", "10", "0", 1L);
    when(positionRepository.findOpenLinearPerpByAccountId(accountId)).thenReturn(List.of(position));
    stubSymbol(BTC);
    var prepared = service.prepare(accountId, Map.of(BTC, snapshot(
        BTC,
        "110",
        NOW.minusSeconds(10),
        NOW)));
    clearInvocations(marketBundleResolver);

    assertThatThrownBy(() -> service.project(
        account(accountId, "1000", "1000", "10", "990"),
        List.of(position),
        List.of(),
        prepared))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.MARKET_DATA_STALE));
    verify(marketBundleResolver, never()).resolvePerp(any(), any());
  }

  @Test
  void applyRevaluationMutatesOnlyLockedObjectsAndIncrementsChangedPositionVersions() {
    UUID accountId = UUID.randomUUID();
    PositionEntity cross = position(
        accountId, UUID.randomUUID(), BTC, PositionSide.BOTH, MarginMode.CROSS,
        OrderSide.BUY, "1", "100", "10", "0", 11L);
    PositionEntity isolated = position(
        accountId, UUID.randomUUID(), ETH, PositionSide.BOTH, MarginMode.ISOLATED,
        OrderSide.BUY, "1", "200", "30", "4", 12L);
    when(positionRepository.findOpenLinearPerpByAccountId(accountId))
        .thenReturn(List.of(cross, isolated));
    stubSymbol(BTC);
    stubSymbol(ETH);
    var prepared = service.prepare(accountId, Map.of(
        BTC, snapshot(BTC, "110"),
        ETH, snapshot(ETH, "190")));
    TradingAccountEntity account = account(accountId, "1000", "1", "2", "3");
    var projection = service.project(account, List.of(cross, isolated), List.of(), prepared);

    service.applyRevaluation(account, List.of(cross, isolated), projection);

    assertThat(account.getEquity()).isEqualByComparingTo("1004.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("40.00000000");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("970.00000000");
    assertThat(cross.getMarkPrice()).isEqualByComparingTo("110.00000000");
    assertThat(cross.getFloatingPnl()).isEqualByComparingTo("10.00000000");
    assertThat(cross.getMaintenanceMargin()).isEqualByComparingTo("0.55000000");
    assertThat(cross.getVersion()).isEqualTo(12L);
    assertThat(isolated.getMarkPrice()).isEqualByComparingTo("190.00000000");
    assertThat(isolated.getFloatingPnl()).isEqualByComparingTo("-10.00000000");
    assertThat(isolated.getVersion()).isEqualTo(13L);
    verify(positionRepository, never()).insert(any(PositionEntity.class));
    verify(positionRepository, never()).updateById(any(PositionEntity.class));
  }

  private void stubSymbol(String symbol) {
    when(symbolRepository.findBySymbol(symbol)).thenReturn(Optional.of(symbol(symbol)));
  }

  private static TradingAccountEntity account(
      UUID accountId,
      String balance,
      String equity,
      String used,
      String free
  ) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setBalance(decimal(balance));
    account.setEquity(decimal(equity));
    account.setUsedMargin(decimal(used));
    account.setFreeMargin(decimal(free));
    account.setPositionMode(PositionMode.ONE_WAY);
    return account;
  }

  private static PositionEntity position(
      UUID accountId,
      UUID positionId,
      String symbol,
      PositionSide positionSide,
      MarginMode marginMode,
      OrderSide side,
      String quantity,
      String entry,
      String marginHeld,
      String fundingPnl,
      long version
  ) {
    PositionEntity position = new PositionEntity();
    position.setId(positionId);
    position.setAccountId(accountId);
    position.setSymbol(symbol);
    position.setProductType(ProductType.LINEAR_PERP);
    position.setPositionMode(positionSide == PositionSide.BOTH
        ? PositionMode.ONE_WAY
        : PositionMode.HEDGE);
    position.setPositionSide(positionSide);
    position.setMarginMode(marginMode);
    position.setSide(side);
    position.setLots(decimal(quantity));
    position.setOpenPrice(decimal(entry));
    position.setCurrentPrice(decimal(entry));
    position.setMarkPrice(decimal(entry));
    position.setMarginHeld(decimal(marginHeld));
    position.setInitialMargin(decimal(marginHeld));
    position.setMaintenanceMargin(decimal("0.5"));
    position.setNotional(decimal("100"));
    position.setFloatingPnl(BigDecimal.ZERO);
    position.setFundingPnl(decimal(fundingPnl));
    position.setLeverage(10);
    position.setStatus(PositionStatus.OPEN);
    position.setVersion(version);
    return position;
  }

  private static OrderEntity activeOrder(
      UUID accountId,
      String symbol,
      MarginMode marginMode,
      UUID parentPositionId,
      String hold
  ) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setAccountId(accountId);
    order.setSymbol(symbol);
    order.setProductType(ProductType.LINEAR_PERP);
    order.setMarginMode(marginMode);
    order.setParentPositionId(parentPositionId);
    order.setPositionMode(PositionMode.ONE_WAY);
    order.setPositionSide(PositionSide.BOTH);
    order.setStatus(OrderStatus.PENDING);
    order.setHoldAmount(decimal(hold));
    return order;
  }

  private static PositionEntity copy(PositionEntity source) {
    return position(
        source.getAccountId(),
        source.getId(),
        source.getSymbol(),
        source.getPositionSide(),
        source.getMarginMode(),
        source.getSide(),
        source.getLots().toPlainString(),
        source.getOpenPrice().toPlainString(),
        source.getMarginHeld().toPlainString(),
        source.getFundingPnl().toPlainString(),
        source.getVersion());
  }

  private static SymbolEntity symbol(String code) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(UUID.randomUUID());
    symbol.setSymbol(code);
    symbol.setProductType(ProductType.LINEAR_PERP);
    symbol.setAssetClass("CRYPTO_PERPETUAL");
    symbol.setMaintenanceMarginRate(decimal("0.005"));
    symbol.setSettlementAsset("USDT");
    symbol.setMarginAsset("USDT");
    return symbol;
  }

  private static ExecutableMarketSnapshot snapshot(String symbol, String mark) {
    return snapshot(symbol, mark, NOW.minusSeconds(1), NOW.plusSeconds(30));
  }

  private static ExecutableMarketSnapshot snapshot(
      String symbol,
      String mark,
      Instant asOf,
      Instant expiresAt
  ) {
    return new ExecutableMarketSnapshot(
        symbol,
        ProductType.LINEAR_PERP,
        "local-perp",
        symbol,
        MarketSourceMode.LOCAL_SIMULATED,
        decimal(mark).subtract(BigDecimal.ONE),
        decimal(mark).add(BigDecimal.ONE),
        decimal(mark),
        decimal(mark),
        decimal(mark),
        asOf,
        expiresAt);
  }

  private static PerpetualMarketBundle bundle(String symbol, String mark) {
    ExecutableMarketSnapshot snapshot = snapshot(symbol, mark);
    return new PerpetualMarketBundle(
        symbol,
        symbol,
        snapshot.providerCode(),
        snapshot.sourceMode(),
        snapshot.bid(),
        snapshot.ask(),
        snapshot.last(),
        snapshot.mark(),
        snapshot.index(),
        null,
        List.of(),
        List.of(),
        snapshot.asOf(),
        snapshot.expiresAt());
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }
}
