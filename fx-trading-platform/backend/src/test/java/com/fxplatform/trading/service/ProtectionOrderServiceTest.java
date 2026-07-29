package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.fxplatform.risk.model.InstrumentRules;
import com.fxplatform.risk.service.InstrumentRulesEngine;
import com.fxplatform.trading.dto.request.CreateOrderRequest.AttachedProtectionRequest;
import com.fxplatform.trading.dto.request.CreateProtectionRequest;
import com.fxplatform.trading.dto.request.UpdateProtectionRequest;
import com.fxplatform.trading.entity.AccountSymbolSettingEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TimeInForce;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.repository.AccountSymbolSettingRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProtectionOrderServiceTest {

  private static final BigDecimal AUTHORITY_MARK = new BigDecimal("50000");

  @Mock TradingAccountRepository accountRepository;
  @Mock PositionRepository positionRepository;
  @Mock OrderRepository orderRepository;
  @Mock AccountSymbolSettingRepository settingRepository;
  @Mock SymbolRepository symbolRepository;
  @Mock MarketBundleResolver marketBundleResolver;
  @Mock FullFillCoordinator fullFillCoordinator;
  @Mock InstrumentRulesEngine instrumentRulesEngine;
  @Mock DemoExecutionGuard demoExecutionGuard;
  @Mock LedgerService ledgerService;
  @Mock OrderEventService orderEventService;

  @Test
  void createRejectsReservedSystemNamespaceBeforeReadsOrProviderCalls() {
    assertCode(ErrorCode.DUPLICATE_CLIENT_ORDER_ID, () -> service().create(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new CreateProtectionRequest(
            ProtectionType.TAKE_PROFIT,
            new BigDecimal("0.1"),
            QuantityUnit.BASE,
            new BigDecimal("51000"),
            TriggerExecutionType.MARKET,
            null,
            " __system__:forged-protection ")));

    verifyNoInteractions(
        orderRepository,
        positionRepository,
        accountRepository,
        marketBundleResolver);
  }

  @Test
  void createPersistsBoundPendingMarkCarrierFromOneWholeBundleBeforeLocks() {
    Fixture fixture = fixture(OrderSide.BUY, "1");

    var response = service().create(fixture.userId(), fixture.positionId(), new CreateProtectionRequest(
        ProtectionType.TAKE_PROFIT,
        new BigDecimal("25000"),
        QuantityUnit.QUOTE,
        new BigDecimal("51000"),
        TriggerExecutionType.MARKET,
        null,
        "tp-1"));

    ArgumentCaptor<OrderEntity> saved = ArgumentCaptor.forClass(OrderEntity.class);
    verify(orderRepository).insert(saved.capture());
    OrderEntity order = saved.getValue();
    assertThat(order.getParentPositionId()).isEqualTo(fixture.positionId());
    assertThat(order.getOrderType()).isEqualTo(OrderType.STOP_MARKET);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_ACTIVATION);
    assertThat(order.getSide()).isEqualTo(OrderSide.SELL);
    assertThat(order.getReduceOnly()).isTrue();
    assertThat(order.getOrderOrigin()).isEqualTo(OrderOrigin.PROTECTIVE);
    assertThat(order.getProtectionType()).isEqualTo(ProtectionType.TAKE_PROFIT);
    assertThat(order.getTriggerPriceType()).isEqualTo(TriggerPriceType.MARK_PRICE);
    assertThat(order.getTriggerExecutionType()).isEqualTo(TriggerExecutionType.MARKET);
    assertThat(order.getQuantityUnit()).isEqualTo(QuantityUnit.QUOTE);
    assertThat(order.getOriginalQuantity()).isEqualByComparingTo("25000");
    assertThat(order.getBaseQuantity()).isEqualByComparingTo("0.5");
    assertThat(order.getLots()).isEqualByComparingTo("0.5");
    assertThat(order.getRemainingQuantity()).isEqualByComparingTo("0.5");
    assertThat(order.getHoldAmount()).isEqualByComparingTo("0");
    assertThat(order.getHoldCurrency()).isEqualTo("USDT");
    assertThat(order.getTimeInForce()).isEqualTo(TimeInForce.GTC);
    assertThat(response.id()).isEqualTo(order.getId());
    assertThat(response.triggerPriceType()).isEqualTo(TriggerPriceType.MARK_PRICE);

    InOrder providerBeforeLock = inOrder(marketBundleResolver, accountRepository);
    providerBeforeLock.verify(marketBundleResolver).resolvePerp(eq(fixture.symbol()), any());
    providerBeforeLock.verify(accountRepository).findByIdAndUserIdForUpdate(
        fixture.accountId(), fixture.userId());
    InOrder freshBeforeWrite = inOrder(fullFillCoordinator, orderRepository);
    freshBeforeWrite.verify(fullFillCoordinator).requireFresh(any(ExecutableMarketSnapshot.class));
    freshBeforeWrite.verify(orderRepository).insert(order);
    verify(orderEventService).record(
        order.getId(), "PROTECTION_CREATED", null, OrderStatus.PENDING_ACTIVATION, null, null);
  }

  @Test
  void createRejectsLongAndShortTriggerDirectionsAtInclusiveMarkBoundary() {
    assertDirectionRejected(OrderSide.BUY, ProtectionType.TAKE_PROFIT, "50000");
    assertDirectionRejected(OrderSide.BUY, ProtectionType.STOP_LOSS, "50000");
    assertDirectionRejected(OrderSide.SELL, ProtectionType.TAKE_PROFIT, "50000");
    assertDirectionRejected(OrderSide.SELL, ProtectionType.STOP_LOSS, "50000");
  }

  @Test
  void marketForbidsPriceAndLimitRequiresPositivePrice() {
    Fixture fixture = fixture(OrderSide.BUY, "1");

    assertCode("PROTECTION_PRICE_INVALID", () -> service().create(
        fixture.userId(), fixture.positionId(), createRequest(
            ProtectionType.TAKE_PROFIT, "0.1", "51000", TriggerExecutionType.MARKET, "50900")));
    assertCode(ErrorCode.ORDER_PRICE_REQUIRED, () -> service().create(
        fixture.userId(), fixture.positionId(), createRequest(
            ProtectionType.TAKE_PROFIT, "0.1", "51000", TriggerExecutionType.LIMIT, null)));
    assertCode(ErrorCode.ORDER_PRICE_REQUIRED, () -> service().create(
        fixture.userId(), fixture.positionId(), createRequest(
            ProtectionType.TAKE_PROFIT, "0.1", "51000", TriggerExecutionType.LIMIT, "0")));

    verify(orderRepository, never()).insert(any(OrderEntity.class));
  }

  @Test
  void triggerAndLimitPriceMustRespectInstrumentTick() {
    Fixture fixture = fixture(OrderSide.BUY, "1");

    assertCode("PRICE_TICK_MISMATCH", () -> service().create(
        fixture.userId(), fixture.positionId(), createRequest(
            ProtectionType.TAKE_PROFIT, "0.1", "51000.05", TriggerExecutionType.MARKET, null)));
    assertCode("PRICE_TICK_MISMATCH", () -> service().create(
        fixture.userId(), fixture.positionId(), createRequest(
            ProtectionType.TAKE_PROFIT, "0.1", "51000", TriggerExecutionType.LIMIT, "50900.05")));

    verify(orderRepository, never()).insert(any(OrderEntity.class));
  }

  @Test
  void task9InternalParentRowDoesNotCountTowardTenProtectionLimit() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    List<OrderEntity> active = new ArrayList<>();
    for (int index = 0; index < 9; index++) {
      active.add(existingProtection(
          fixture, index % 2 == 0 ? ProtectionType.TAKE_PROFIT : ProtectionType.STOP_LOSS, "0.05"));
    }
    active.add(task9InternalClose(fixture, "99"));
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), fixture.symbol())).thenReturn(active);

    service().create(fixture.userId(), fixture.positionId(), createRequest(
        ProtectionType.TAKE_PROFIT, "0.1", "51000", TriggerExecutionType.MARKET, null));

    verify(orderRepository).insert(any(OrderEntity.class));
  }

  @Test
  void eleventhBoundProtectionIsRejectedWithoutWriting() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    List<OrderEntity> active = new ArrayList<>();
    for (int index = 0; index < 10; index++) {
      active.add(existingProtection(
          fixture, index % 2 == 0 ? ProtectionType.TAKE_PROFIT : ProtectionType.STOP_LOSS, "0.05"));
    }
    active.add(task9InternalClose(fixture, "99"));
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), fixture.symbol())).thenReturn(active);

    assertCode(ErrorCode.PROTECTION_LIMIT_EXCEEDED, () -> service().create(
        fixture.userId(), fixture.positionId(), createRequest(
            ProtectionType.TAKE_PROFIT, "0.1", "51000", TriggerExecutionType.MARKET, null)));

    verify(orderRepository, never()).insert(any(OrderEntity.class));
    verifyNoInteractions(orderEventService);
  }

  @Test
  void takeProfitAndStopLossQuantityBudgetsAreIndependent() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), fixture.symbol())).thenReturn(List.of(
            existingProtection(fixture, ProtectionType.TAKE_PROFIT, "1"),
            task9InternalClose(fixture, "99")));

    service().create(fixture.userId(), fixture.positionId(), createRequest(
        ProtectionType.STOP_LOSS, "1", "49000", TriggerExecutionType.MARKET, null));

    verify(orderRepository).insert(any(OrderEntity.class));
  }

  @Test
  void sameProtectionTypeCanonicalTotalCannotExceedPositionQuantity() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), fixture.symbol())).thenReturn(List.of(
            existingProtection(fixture, ProtectionType.TAKE_PROFIT, "0.8"),
            task9InternalClose(fixture, "99")));

    assertCode(ErrorCode.PROTECTION_QUANTITY_EXCEEDED, () -> service().create(
        fixture.userId(), fixture.positionId(), createRequest(
            ProtectionType.TAKE_PROFIT, "0.3", "51000", TriggerExecutionType.MARKET, null)));

    verify(orderRepository, never()).insert(any(OrderEntity.class));
  }

  @Test
  void updateUsesExpectedVersionAndExcludesItselfFromSameTypeQuantityTotal() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    OrderEntity preflight = existingProtection(fixture, ProtectionType.TAKE_PROFIT, "0.8");
    preflight.setVersion(3L);
    OrderEntity locked = copyProtection(preflight);
    OrderEntity other = existingProtection(fixture, ProtectionType.TAKE_PROFIT, "0.3");
    when(orderRepository.findByUserIdAndId(fixture.userId(), preflight.getId()))
        .thenReturn(Optional.of(preflight));
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), fixture.symbol())).thenReturn(List.of(locked, other));

    var response = service().update(fixture.userId(), preflight.getId(), new UpdateProtectionRequest(
        new BigDecimal("0.7"),
        QuantityUnit.BASE,
        new BigDecimal("52000"),
        TriggerExecutionType.LIMIT,
        new BigDecimal("51900"),
        3L));

    assertThat(locked.getBaseQuantity()).isEqualByComparingTo("0.7");
    assertThat(locked.getTriggerPrice()).isEqualByComparingTo("52000");
    assertThat(locked.getTriggerExecutionType()).isEqualTo(TriggerExecutionType.LIMIT);
    assertThat(locked.getPrice()).isEqualByComparingTo("51900");
    assertThat(locked.getVersion()).isEqualTo(4L);
    assertThat(response.baseQuantity()).isEqualByComparingTo("0.7");
    verify(orderRepository).updateById(locked);
    verify(orderEventService).record(
        locked.getId(), "PROTECTION_UPDATED", OrderStatus.PENDING_ACTIVATION,
        OrderStatus.PENDING_ACTIVATION, null, null);
  }

  @Test
  void updateRejectsStaleVersionWithZeroMutation() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    OrderEntity preflight = existingProtection(fixture, ProtectionType.TAKE_PROFIT, "0.5");
    preflight.setVersion(3L);
    OrderEntity locked = copyProtection(preflight);
    locked.setVersion(4L);
    when(orderRepository.findByUserIdAndId(fixture.userId(), preflight.getId()))
        .thenReturn(Optional.of(preflight));
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), fixture.symbol())).thenReturn(List.of(locked));

    assertCode(ErrorCode.PROTECTION_VERSION_CONFLICT, () -> service().update(
        fixture.userId(), preflight.getId(), new UpdateProtectionRequest(
            null, null, new BigDecimal("52000"), null, null, 3L)));

    assertThat(locked.getTriggerPrice()).isEqualByComparingTo("51000");
    verify(orderRepository, never()).updateById(any(OrderEntity.class));
    verifyNoInteractions(orderEventService);
  }

  @Test
  void triggerOnlyUpdatePreservesQuoteCanonicalQuantityAcrossMarkChanges() {
    Fixture fixture = fixture(OrderSide.BUY, "2");
    OrderEntity preflight = existingProtection(
        fixture, ProtectionType.TAKE_PROFIT, "0.5");
    preflight.setOriginalQuantity(new BigDecimal("25000"));
    preflight.setQuantity(new BigDecimal("25000"));
    preflight.setQuantityUnit(QuantityUnit.QUOTE);
    preflight.setVersion(3L);
    OrderEntity locked = copyProtection(preflight);
    when(orderRepository.findByUserIdAndId(fixture.userId(), preflight.getId()))
        .thenReturn(Optional.of(preflight));
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), fixture.symbol())).thenReturn(List.of(locked));
    when(marketBundleResolver.resolvePerp(eq(fixture.symbol()), any()))
        .thenReturn(bundle(fixture.symbol(), "40000"));

    service().update(fixture.userId(), preflight.getId(), new UpdateProtectionRequest(
        null, null, new BigDecimal("52000"), null, null, 3L));

    assertThat(locked.getOriginalQuantity()).isEqualByComparingTo("25000");
    assertThat(locked.getQuantityUnit()).isEqualTo(QuantityUnit.QUOTE);
    assertThat(locked.getBaseQuantity()).isEqualByComparingTo("0.5");
    assertThat(locked.getRemainingQuantity()).isEqualByComparingTo("0.5");
    assertThat(locked.getTriggerPrice()).isEqualByComparingTo("52000");
  }

  @Test
  void triggerOnlyUpdatePreservesResizedFractionalContractsWithoutReconversion() {
    Fixture fixture = fixture(OrderSide.BUY, "2");
    OrderEntity preflight = existingProtection(
        fixture, ProtectionType.STOP_LOSS, "0.5");
    preflight.setOriginalQuantity(new BigDecimal("0.5"));
    preflight.setQuantity(new BigDecimal("0.5"));
    preflight.setQuantityUnit(QuantityUnit.CONTRACTS);
    preflight.setVersion(2L);
    OrderEntity locked = copyProtection(preflight);
    when(orderRepository.findByUserIdAndId(fixture.userId(), preflight.getId()))
        .thenReturn(Optional.of(preflight));
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), fixture.symbol())).thenReturn(List.of(locked));

    service().update(fixture.userId(), preflight.getId(), new UpdateProtectionRequest(
        null, null, new BigDecimal("48000"), null, null, 2L));

    assertThat(locked.getOriginalQuantity()).isEqualByComparingTo("0.5");
    assertThat(locked.getQuantityUnit()).isEqualTo(QuantityUnit.CONTRACTS);
    assertThat(locked.getBaseQuantity()).isEqualByComparingTo("0.5");
    assertThat(locked.getTriggerPrice()).isEqualByComparingTo("48000");
  }

  @Test
  void updateRejectsQuantityUnitWithoutAnExplicitQuantity() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    OrderEntity preflight = existingProtection(
        fixture, ProtectionType.TAKE_PROFIT, "0.5");
    when(orderRepository.findByUserIdAndId(fixture.userId(), preflight.getId()))
        .thenReturn(Optional.of(preflight));

    assertCode("PROTECTION_REQUEST_INVALID", () -> service().update(
        fixture.userId(), preflight.getId(), new UpdateProtectionRequest(
            null, QuantityUnit.QUOTE, null, null, null, 0L)));

    verifyNoInteractions(marketBundleResolver, fullFillCoordinator);
    verify(orderRepository, never()).updateById(any(OrderEntity.class));
  }

  @Test
  void dedicatedProtectionUpdateRejectsTrailingCarrierWithoutConvertingIt() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    OrderEntity trailing = existingProtection(fixture, ProtectionType.STOP_LOSS, "0.5");
    trailing.setOrderType(OrderType.TRAILING_STOP_MARKET);
    trailing.setTrailingDelta(new BigDecimal("100"));
    trailing.setTrailingExtreme(new BigDecimal("50000"));
    when(orderRepository.findByUserIdAndId(fixture.userId(), trailing.getId()))
        .thenReturn(Optional.of(trailing));

    assertCode("PROTECTION_NOT_MODIFIABLE", () -> service().update(
        fixture.userId(), trailing.getId(), new UpdateProtectionRequest(
            null, null, new BigDecimal("48000"), null, null, 0L)));

    assertThat(trailing.getOrderType()).isEqualTo(OrderType.TRAILING_STOP_MARKET);
    assertThat(trailing.getTriggerPrice()).isEqualByComparingTo("49000");
    verify(orderRepository, never()).updateById(any(OrderEntity.class));
    verifyNoInteractions(marketBundleResolver, fullFillCoordinator);
  }

  @Test
  void cancelStillAllowsAnUntriggeredTrailingCarrier() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    OrderEntity preflight = existingProtection(fixture, ProtectionType.STOP_LOSS, "0.5");
    preflight.setOrderType(OrderType.TRAILING_STOP_MARKET);
    preflight.setTrailingDelta(new BigDecimal("100"));
    OrderEntity locked = copyProtection(preflight);
    when(orderRepository.findByUserIdAndId(fixture.userId(), preflight.getId()))
        .thenReturn(Optional.of(preflight));
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), fixture.symbol())).thenReturn(List.of(locked));

    var response = service().cancel(fixture.userId(), preflight.getId());

    assertThat(response.status()).isEqualTo(OrderStatus.CANCELED.name());
    assertThat(locked.getStatus()).isEqualTo(OrderStatus.CANCELED);
    verify(orderRepository).updateById(locked);
  }

  @Test
  void cancelUsesPreflightVersionOptimisticallyAndNeverResolvesMarketOrReleasesHold() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    OrderEntity preflight = existingProtection(fixture, ProtectionType.TAKE_PROFIT, "0.5");
    preflight.setVersion(3L);
    OrderEntity locked = copyProtection(preflight);
    when(orderRepository.findByUserIdAndId(fixture.userId(), preflight.getId()))
        .thenReturn(Optional.of(preflight));
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), fixture.symbol())).thenReturn(List.of(locked));

    var response = service().cancel(fixture.userId(), preflight.getId());

    assertThat(locked.getStatus()).isEqualTo(OrderStatus.CANCELED);
    assertThat(locked.getVersion()).isEqualTo(4L);
    assertThat(locked.getRemainingQuantity()).isEqualByComparingTo("0");
    assertThat(locked.getHoldAmount()).isEqualByComparingTo("0");
    assertThat(locked.getCanceledAt()).isNotNull();
    assertThat(response.status()).isEqualTo(OrderStatus.CANCELED.name());
    verify(orderRepository).updateById(locked);
    verify(orderEventService).record(
        locked.getId(), "PROTECTION_CANCELED", OrderStatus.PENDING_ACTIVATION,
        OrderStatus.CANCELED, null, null);
    verifyNoInteractions(marketBundleResolver, fullFillCoordinator);
  }

  @Test
  void cancelRejectsConcurrentVersionWinnerWithZeroMutation() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    OrderEntity preflight = existingProtection(fixture, ProtectionType.TAKE_PROFIT, "0.5");
    preflight.setVersion(3L);
    OrderEntity locked = copyProtection(preflight);
    locked.setVersion(4L);
    when(orderRepository.findByUserIdAndId(fixture.userId(), preflight.getId()))
        .thenReturn(Optional.of(preflight));
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), fixture.symbol())).thenReturn(List.of(locked));

    assertCode(ErrorCode.PROTECTION_VERSION_CONFLICT,
        () -> service().cancel(fixture.userId(), preflight.getId()));

    assertThat(locked.getStatus()).isEqualTo(OrderStatus.PENDING_ACTIVATION);
    verify(orderRepository, never()).updateById(any(OrderEntity.class));
    verifyNoInteractions(orderEventService, marketBundleResolver, fullFillCoordinator);
  }

  @Test
  void staleBundleRetriesOnceWithFreshWholePreparationAndOneWrite() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    when(marketBundleResolver.resolvePerp(eq(fixture.symbol()), any()))
        .thenReturn(bundle(fixture.symbol(), "50000"), bundle(fixture.symbol(), "50010"));
    org.mockito.Mockito.doThrow(
            new BusinessException(ErrorCode.MARKET_DATA_STALE, "expired"))
        .doNothing()
        .when(fullFillCoordinator).requireFresh(any(ExecutableMarketSnapshot.class));

    service().create(fixture.userId(), fixture.positionId(), createRequest(
        ProtectionType.TAKE_PROFIT, "0.1", "51000", TriggerExecutionType.MARKET, null));

    verify(marketBundleResolver, times(2)).resolvePerp(eq(fixture.symbol()), any());
    verify(accountRepository, times(2)).findByIdAndUserIdForUpdate(
        fixture.accountId(), fixture.userId());
    verify(orderRepository, times(1)).insert(any(OrderEntity.class));
  }

  @Test
  void createFailsClosedWhenLockedSettingIsMissingOrDriftsFromPosition() {
    Fixture missing = fixture(OrderSide.BUY, "1");
    when(settingRepository.findByAccountIdAndSymbolForUpdate(
        missing.accountId(), missing.symbol())).thenReturn(Optional.empty());

    assertCode("INVALID_INSTRUMENT_RULES", () -> service().create(
        missing.userId(), missing.positionId(), createRequest(
            ProtectionType.TAKE_PROFIT, "0.1", "51000", TriggerExecutionType.MARKET, null)));
    verify(orderRepository, never()).insert(any(OrderEntity.class));

    org.mockito.Mockito.reset(
        accountRepository,
        positionRepository,
        orderRepository,
        settingRepository,
        symbolRepository,
        marketBundleResolver,
        fullFillCoordinator,
        instrumentRulesEngine,
        demoExecutionGuard,
        ledgerService,
        orderEventService);
    Fixture drift = fixture(OrderSide.BUY, "1");
    drift.setting().setMarginMode(MarginMode.ISOLATED);

    assertCode("INVALID_INSTRUMENT_RULES", () -> service().create(
        drift.userId(), drift.positionId(), createRequest(
            ProtectionType.TAKE_PROFIT, "0.1", "51000", TriggerExecutionType.MARKET, null)));
    verify(orderRepository, never()).insert(any(OrderEntity.class));
  }

  @Test
  void createFailsClosedWhenLockedLeverageNoLongerMatchesPosition() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    fixture.setting().setLeverage(20);

    assertCode("INVALID_INSTRUMENT_RULES", () -> service().create(
        fixture.userId(), fixture.positionId(), createRequest(
            ProtectionType.TAKE_PROFIT, "0.1", "51000", TriggerExecutionType.MARKET, null)));

    verify(orderRepository, never()).insert(any(OrderEntity.class));
  }

  @Test
  void createFailsClosedWhenLockedPositionSlotAuthorityIsInvalid() {
    Fixture oneWay = fixture(OrderSide.BUY, "1");
    oneWay.position().setPositionSide(PositionSide.LONG);

    assertCode(ErrorCode.INVALID_POSITION_SIDE, () -> service().create(
        oneWay.userId(), oneWay.positionId(), createRequest(
            ProtectionType.TAKE_PROFIT, "0.1", "51000", TriggerExecutionType.MARKET, null)));

    Fixture hedgeLong = fixture(OrderSide.SELL, "1");
    hedgeLong.account().setPositionMode(PositionMode.HEDGE);
    hedgeLong.position().setPositionMode(PositionMode.HEDGE);
    hedgeLong.position().setPositionSide(PositionSide.LONG);

    assertCode(ErrorCode.INVALID_POSITION_SIDE, () -> service().create(
        hedgeLong.userId(), hedgeLong.positionId(), createRequest(
            ProtectionType.TAKE_PROFIT, "0.1", "49000", TriggerExecutionType.MARKET, null)));

    Fixture hedgeShort = fixture(OrderSide.BUY, "1");
    hedgeShort.account().setPositionMode(PositionMode.HEDGE);
    hedgeShort.position().setPositionMode(PositionMode.HEDGE);
    hedgeShort.position().setPositionSide(PositionSide.SHORT);

    assertCode(ErrorCode.INVALID_POSITION_SIDE, () -> service().create(
        hedgeShort.userId(), hedgeShort.positionId(), createRequest(
            ProtectionType.TAKE_PROFIT, "0.1", "51000", TriggerExecutionType.MARKET, null)));

    verify(orderRepository, never()).insert(any(OrderEntity.class));
  }

  @Test
  void createRejectsLinearPerpetualSymbolOutsideStrictP0Universe() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    String nonP0 = "DOGEUSDT-PERP";
    fixture.position().setSymbol(nonP0);
    fixture.symbolEntity().setSymbol(nonP0);
    fixture.setting().setSymbol(nonP0);
    when(symbolRepository.findBySymbol(nonP0)).thenReturn(Optional.of(fixture.symbolEntity()));

    assertCode(ErrorCode.PRODUCT_NOT_ALLOWED, () -> service().create(
        fixture.userId(), fixture.positionId(), createRequest(
            ProtectionType.TAKE_PROFIT, "0.1", "51000", TriggerExecutionType.MARKET, null)));

    verifyNoInteractions(marketBundleResolver);
    verify(orderRepository, never()).insert(any(OrderEntity.class));
  }

  @Test
  void contractsCreateFailsClosedForMissingOrZeroConfiguredMultiplier() {
    Fixture missing = fixture(OrderSide.BUY, "1");
    missing.symbolEntity().setContractMultiplier(null);

    assertCode("INVALID_INSTRUMENT_RULES", () -> service().create(
        missing.userId(), missing.positionId(), new CreateProtectionRequest(
            ProtectionType.TAKE_PROFIT,
            BigDecimal.ONE,
            QuantityUnit.CONTRACTS,
            new BigDecimal("51000"),
            TriggerExecutionType.MARKET,
            null,
            UUID.randomUUID().toString())));

    Fixture zero = fixture(OrderSide.BUY, "1");
    zero.symbolEntity().setContractMultiplier(BigDecimal.ZERO);

    assertCode("INVALID_INSTRUMENT_RULES", () -> service().create(
        zero.userId(), zero.positionId(), new CreateProtectionRequest(
            ProtectionType.TAKE_PROFIT,
            BigDecimal.ONE,
            QuantityUnit.CONTRACTS,
            new BigDecimal("51000"),
            TriggerExecutionType.MARKET,
            null,
            UUID.randomUUID().toString())));

    verify(orderRepository, never()).insert(any(OrderEntity.class));
  }

  @Test
  void createRejectsMismatchedOrIncompletePerpetualAuthorityBundleBeforeLocksAndWrites() {
    Fixture mismatch = fixture(OrderSide.BUY, "1");
    when(marketBundleResolver.resolvePerp(eq(mismatch.symbol()), any()))
        .thenReturn(bundle("ETHUSDT-PERP", "50000"));

    assertCode("MARKET_BUNDLE_INCOMPLETE", () -> service().create(
        mismatch.userId(), mismatch.positionId(), createRequest(
            ProtectionType.TAKE_PROFIT, "0.1", "51000", TriggerExecutionType.MARKET, null)));

    Fixture incomplete = fixture(OrderSide.BUY, "1");
    Instant now = Instant.now();
    when(marketBundleResolver.resolvePerp(eq(incomplete.symbol()), any()))
        .thenReturn(new PerpetualMarketBundle(
            incomplete.symbol(),
            "BTCUSDT",
            "local-perp",
            MarketSourceMode.LOCAL_SIMULATED,
            BigDecimal.ZERO,
            null,
            new BigDecimal("50000"),
            new BigDecimal("50000"),
            null,
            null,
            List.of(),
            List.of(),
            now,
            now.plusSeconds(30)));

    assertCode("MARKET_BUNDLE_INCOMPLETE", () -> service().create(
        incomplete.userId(), incomplete.positionId(), createRequest(
            ProtectionType.TAKE_PROFIT, "0.1", "51000", TriggerExecutionType.MARKET, null)));

    verify(accountRepository, never()).findByIdAndUserIdForUpdate(any(), any());
    verify(orderRepository, never()).insert(any(OrderEntity.class));
  }

  @Test
  void standaloneCreateReplaysMatchingClientIdAndRejectsConflictingPayload() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    OrderEntity existing = existingProtection(fixture, ProtectionType.TAKE_PROFIT, "0.1");
    existing.setClientOrderId("same-key");
    existing.setIdempotencyKey("same-key");
    when(orderRepository.findByUserIdAndIdempotencyKey(fixture.userId(), "same-key"))
        .thenReturn(Optional.of(existing));

    var replay = service().create(fixture.userId(), fixture.positionId(), new CreateProtectionRequest(
        ProtectionType.TAKE_PROFIT,
        new BigDecimal("0.1"),
        QuantityUnit.BASE,
        new BigDecimal("51000"),
        TriggerExecutionType.MARKET,
        null,
        "same-key"));

    assertThat(replay.id()).isEqualTo(existing.getId());
    verifyNoInteractions(marketBundleResolver, fullFillCoordinator);
    verify(orderRepository, never()).insert(any(OrderEntity.class));

    assertCode(ErrorCode.DUPLICATE_CLIENT_ORDER_ID, () -> service().create(
        fixture.userId(), fixture.positionId(), new CreateProtectionRequest(
            ProtectionType.STOP_LOSS,
            new BigDecimal("0.1"),
            QuantityUnit.BASE,
            new BigDecimal("49000"),
            TriggerExecutionType.MARKET,
            null,
            "same-key")));
    verify(orderRepository, never()).insert(any(OrderEntity.class));
  }

  @Test
  void standaloneCreateReplaysOriginalPayloadAfterResizeAndTerminalStateBeforePositionChecks() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    CreateProtectionRequest original = new CreateProtectionRequest(
        ProtectionType.TAKE_PROFIT,
        new BigDecimal("0.4"),
        QuantityUnit.BASE,
        new BigDecimal("51000"),
        TriggerExecutionType.LIMIT,
        new BigDecimal("50900"),
        "stable-key");

    var initialResponse = service().create(fixture.userId(), fixture.positionId(), original);

    ArgumentCaptor<OrderEntity> saved = ArgumentCaptor.forClass(OrderEntity.class);
    verify(orderRepository).insert(saved.capture());
    OrderEntity committed = saved.getValue();
    assertThat(initialResponse.systemReason()).isNull();
    assertThat(committed.getSystemReason()).startsWith("IDEMP:").hasSize(49);
    committed.setOriginalQuantity(new BigDecimal("0.1"));
    committed.setQuantity(new BigDecimal("0.1"));
    committed.setBaseQuantity(new BigDecimal("0.1"));
    committed.setLots(new BigDecimal("0.1"));
    committed.setTriggerPrice(new BigDecimal("52000"));
    committed.setPrice(new BigDecimal("51900"));
    fixture.position().setStatus(PositionStatus.CLOSED);
    fixture.position().setLots(BigDecimal.ZERO);
    when(orderRepository.findByUserIdAndIdempotencyKey(fixture.userId(), "stable-key"))
        .thenReturn(Optional.of(committed));

    committed.setStatus(OrderStatus.FILLED);
    var filledReplay = service().create(fixture.userId(), fixture.positionId(), original);
    assertThat(filledReplay.id()).isEqualTo(committed.getId());
    assertThat(filledReplay.systemReason()).isNull();
    committed.setStatus(OrderStatus.EXPIRED);
    var expiredReplay = service().create(fixture.userId(), fixture.positionId(), original);
    assertThat(expiredReplay.id()).isEqualTo(committed.getId());
    assertThat(expiredReplay.systemReason()).isNull();

    verify(orderRepository, times(1)).insert(any(OrderEntity.class));
    verify(marketBundleResolver, times(1)).resolvePerp(eq(fixture.symbol()), any());
  }

  @Test
  void standaloneCreateUsesGlobalUserKeyAndRejectsCrossAccountReuse() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    OrderEntity existing = existingProtection(fixture, ProtectionType.TAKE_PROFIT, "0.1");
    existing.setAccountId(UUID.randomUUID());
    existing.setParentPositionId(UUID.randomUUID());
    existing.setClientOrderId("global-key");
    existing.setIdempotencyKey("global-key");
    when(orderRepository.findByUserIdAndIdempotencyKey(fixture.userId(), "global-key"))
        .thenReturn(Optional.of(existing));

    assertCode(ErrorCode.DUPLICATE_CLIENT_ORDER_ID, () -> service().create(
        fixture.userId(), fixture.positionId(), new CreateProtectionRequest(
            ProtectionType.TAKE_PROFIT,
            new BigDecimal("0.1"),
            QuantityUnit.BASE,
            new BigDecimal("51000"),
            TriggerExecutionType.MARKET,
            null,
            "global-key")));

    verifyNoInteractions(marketBundleResolver, fullFillCoordinator);
    verify(orderRepository, never()).insert(any(OrderEntity.class));
  }

  @Test
  void standaloneCreateRejectsAnExistingAccountClientIdWithAnotherIdempotencyKey() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    OrderEntity ordinaryOrder = new OrderEntity();
    ordinaryOrder.setId(UUID.randomUUID());
    ordinaryOrder.setUserId(fixture.userId());
    ordinaryOrder.setAccountId(fixture.accountId());
    ordinaryOrder.setClientOrderId("shared-client-id");
    ordinaryOrder.setIdempotencyKey("ordinary-idempotency-key");
    ordinaryOrder.setProductType(ProductType.LINEAR_PERP);
    ordinaryOrder.setOrderOrigin(OrderOrigin.USER);
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        fixture.userId(), fixture.accountId(), "shared-client-id"))
        .thenReturn(Optional.of(ordinaryOrder));

    assertCode(ErrorCode.DUPLICATE_CLIENT_ORDER_ID, () -> service().create(
        fixture.userId(), fixture.positionId(), new CreateProtectionRequest(
            ProtectionType.TAKE_PROFIT,
            new BigDecimal("0.1"),
            QuantityUnit.BASE,
            new BigDecimal("51000"),
            TriggerExecutionType.MARKET,
            null,
            "shared-client-id")));

    verifyNoInteractions(marketBundleResolver, fullFillCoordinator);
    verify(orderRepository, never()).insert(any(OrderEntity.class));
  }

  @Test
  void standaloneCreateMapsAccountClientKeyCollisionToStandardDuplicate() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    OrderEntity ordinaryOrder = existingProtection(
        fixture, ProtectionType.TAKE_PROFIT, "0.1");
    ordinaryOrder.setProtectionType(null);
    ordinaryOrder.setOrderOrigin(OrderOrigin.USER);
    ordinaryOrder.setParentPositionId(null);
    ordinaryOrder.setClientOrderId("shared-client-key");
    ordinaryOrder.setIdempotencyKey("different-idempotency-key");
    when(orderRepository.findByUserIdAndAccountIdAndClientOrderId(
        fixture.userId(), fixture.accountId(), "shared-client-key"))
        .thenReturn(Optional.of(ordinaryOrder));

    assertCode(ErrorCode.DUPLICATE_CLIENT_ORDER_ID, () -> service().create(
        fixture.userId(), fixture.positionId(), new CreateProtectionRequest(
            ProtectionType.TAKE_PROFIT,
            new BigDecimal("0.1"),
            QuantityUnit.BASE,
            new BigDecimal("51000"),
            TriggerExecutionType.MARKET,
            null,
            "shared-client-key")));

    verifyNoInteractions(marketBundleResolver, fullFillCoordinator);
    verify(orderRepository, never()).insert(any(OrderEntity.class));
  }

  @Test
  void standaloneCreateMapsGlobalUniqueRaceToCommittedReplay() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    CreateProtectionRequest request = new CreateProtectionRequest(
        ProtectionType.TAKE_PROFIT,
        new BigDecimal("0.1"),
        QuantityUnit.BASE,
        new BigDecimal("51000"),
        TriggerExecutionType.MARKET,
        null,
        "race-key");
    OrderEntity committed = existingProtection(fixture, ProtectionType.TAKE_PROFIT, "0.1");
    committed.setClientOrderId("race-key");
    committed.setIdempotencyKey("race-key");
    when(orderRepository.findByUserIdAndIdempotencyKey(fixture.userId(), "race-key"))
        .thenReturn(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(committed));
    when(orderRepository.insert(any(OrderEntity.class)))
        .thenThrow(new DataIntegrityViolationException("global user key won concurrently"));

    assertThat(service().create(fixture.userId(), fixture.positionId(), request).id())
        .isEqualTo(committed.getId());

    verify(orderRepository, times(5))
        .findByUserIdAndIdempotencyKey(fixture.userId(), "race-key");
  }

  @Test
  void standaloneCreateHidesWhetherPositionIsMissingOrOwnedByAnotherUser() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    UUID otherUserId = UUID.randomUUID();

    assertCode("POSITION_NOT_FOUND", () -> service().create(
        fixture.userId(), UUID.randomUUID(), createRequest(
            ProtectionType.TAKE_PROFIT, "0.1", "51000", TriggerExecutionType.MARKET, null)));
    assertCode("POSITION_NOT_FOUND", () -> service().create(
        otherUserId, fixture.positionId(), createRequest(
            ProtectionType.TAKE_PROFIT, "0.1", "51000", TriggerExecutionType.MARKET, null)));

    verifyNoInteractions(marketBundleResolver, fullFillCoordinator);
    verify(orderRepository, never()).insert(any(OrderEntity.class));
  }

  @Test
  void cancelTriggeredPendingLimitReleasesExactCrossHoldOnce() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    fixture.account().setUsedMargin(new BigDecimal("100"));
    fixture.account().setFreeMargin(new BigDecimal("900"));
    OrderEntity preflight = existingProtection(fixture, ProtectionType.TAKE_PROFIT, "0.5");
    preflight.setStatus(OrderStatus.PENDING);
    preflight.setOrderType(OrderType.LIMIT);
    preflight.setTriggerExecutionType(TriggerExecutionType.LIMIT);
    preflight.setPrice(new BigDecimal("51000"));
    preflight.setHoldAmount(new BigDecimal("25"));
    preflight.setMarginMode(MarginMode.CROSS);
    preflight.setVersion(3L);
    OrderEntity locked = copyProtection(preflight);
    when(orderRepository.findByUserIdAndId(fixture.userId(), preflight.getId()))
        .thenReturn(Optional.of(preflight));
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), fixture.symbol())).thenReturn(List.of(locked));

    serviceWithLedger().cancel(fixture.userId(), preflight.getId());

    assertThat(fixture.account().getUsedMargin()).isEqualByComparingTo("75");
    assertThat(fixture.account().getFreeMargin()).isEqualByComparingTo("925");
    assertThat(locked.getHoldAmount()).isZero();
    assertThat(locked.getStatus()).isEqualTo(OrderStatus.CANCELED);
    verify(accountRepository).save(fixture.account());
    verify(ledgerService).recordOrderRelease(
        fixture.account(),
        new BigDecimal("25.00000000"),
        locked.getId(),
        "Triggered protection LIMIT canceled");
  }

  @Test
  void attachedCreatePersistsUnboundMarkCarriersWithNoHold() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    OrderEntity parent = parentOrder(fixture, "1");
    List<AttachedProtectionRequest> requests = List.of(
        new AttachedProtectionRequest(
            ProtectionType.TAKE_PROFIT,
            new BigDecimal("51000"),
            TriggerPriceType.MARK_PRICE,
            TriggerExecutionType.MARKET,
            null),
        new AttachedProtectionRequest(
            ProtectionType.STOP_LOSS,
            new BigDecimal("49000"),
            null,
            TriggerExecutionType.LIMIT,
            new BigDecimal("48900")));

    serviceWithLedger().createAttachedLocked(parent, requests, AUTHORITY_MARK);

    ArgumentCaptor<OrderEntity> carriers = ArgumentCaptor.forClass(OrderEntity.class);
    verify(orderRepository, times(2)).insert(carriers.capture());
    assertThat(carriers.getAllValues()).allSatisfy(carrier -> {
      assertThat(carrier.getParentOrderId()).isEqualTo(parent.getId());
      assertThat(carrier.getParentPositionId()).isNull();
      assertThat(carrier.getStatus()).isEqualTo(OrderStatus.PENDING_ACTIVATION);
      assertThat(carrier.getTriggerPriceType()).isEqualTo(TriggerPriceType.MARK_PRICE);
      assertThat(carrier.getBaseQuantity()).isEqualByComparingTo("1");
      assertThat(carrier.getHoldAmount()).isZero();
      assertThat(carrier.getOrderOrigin()).isEqualTo(OrderOrigin.PROTECTIVE);
    });
    verify(orderEventService, times(2)).record(
        any(UUID.class),
        eq("PROTECTION_CREATED"),
        eq(null),
        eq(OrderStatus.PENDING_ACTIVATION),
        eq(null),
        eq(null));
  }

  @Test
  void attachedChildKeysAreBoundedAndDerivedOnlyFromSystemParentId() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    OrderEntity parent = parentOrder(fixture, "1");
    parent.setClientOrderId("X".repeat(128));

    serviceWithLedger().createAttachedLocked(parent, List.of(new AttachedProtectionRequest(
        ProtectionType.TAKE_PROFIT,
        new BigDecimal("51000"),
        null,
        TriggerExecutionType.MARKET,
        null)), AUTHORITY_MARK);

    ArgumentCaptor<OrderEntity> carrier = ArgumentCaptor.forClass(OrderEntity.class);
    verify(orderRepository).insert(carrier.capture());
    String expected = parent.getId() + ":PROTECTION:0";
    assertThat(carrier.getValue().getClientOrderId()).isEqualTo(expected);
    assertThat(carrier.getValue().getClientOrderId().length()).isLessThanOrEqualTo(128);
    assertThat(carrier.getValue().getIdempotencyKey()).isEqualTo(expected);
  }

  @Test
  void attachedCreateRejectsLastPriceTypeAndInvalidMarketLimitPriceContracts() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    OrderEntity parent = parentOrder(fixture, "1");

    assertCode("PROTECTION_PRICE_TYPE_INVALID", () -> serviceWithLedger().createAttachedLocked(
        parent,
        List.of(new AttachedProtectionRequest(
            ProtectionType.TAKE_PROFIT,
            new BigDecimal("51000"),
            TriggerPriceType.LAST_PRICE,
            TriggerExecutionType.MARKET,
            null)), AUTHORITY_MARK));
    assertCode("PROTECTION_PRICE_INVALID", () -> serviceWithLedger().createAttachedLocked(
        parent,
        List.of(new AttachedProtectionRequest(
            ProtectionType.TAKE_PROFIT,
            new BigDecimal("51000"),
            TriggerPriceType.MARK_PRICE,
            TriggerExecutionType.MARKET,
            new BigDecimal("50900"))), AUTHORITY_MARK));
    assertCode(ErrorCode.ORDER_PRICE_REQUIRED, () -> serviceWithLedger().createAttachedLocked(
        parent,
        List.of(new AttachedProtectionRequest(
            ProtectionType.TAKE_PROFIT,
            new BigDecimal("51000"),
            null,
            TriggerExecutionType.LIMIT,
            null)), AUTHORITY_MARK));

    verify(orderRepository, never()).insert(any(OrderEntity.class));
  }

  @Test
  void attachedCreateValidatesEveryLimitNotionalBeforeWritingAnyChild() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    OrderEntity tooSmall = parentOrder(fixture, "0.00001");

    assertCode("ORDER_NOTIONAL_TOO_SMALL", () -> serviceWithLedger().createAttachedLocked(
        tooSmall,
        List.of(
            new AttachedProtectionRequest(
                ProtectionType.TAKE_PROFIT,
                new BigDecimal("51000"),
                null,
                TriggerExecutionType.MARKET,
                null),
            new AttachedProtectionRequest(
                ProtectionType.STOP_LOSS,
                new BigDecimal("49000"),
                null,
                TriggerExecutionType.LIMIT,
                new BigDecimal("100"))), AUTHORITY_MARK));

    OrderEntity tooLarge = parentOrder(fixture, "1000");
    assertCode("ORDER_NOTIONAL_TOO_LARGE", () -> serviceWithLedger().createAttachedLocked(
        tooLarge,
        List.of(new AttachedProtectionRequest(
            ProtectionType.TAKE_PROFIT,
            new BigDecimal("51000"),
            null,
            TriggerExecutionType.LIMIT,
            new BigDecimal("50000"))), AUTHORITY_MARK));

    verify(orderRepository, never()).insert(any(OrderEntity.class));
  }

  @Test
  void attachedCreateDoesNotApplyLimitNotionalChecksToMarketChild() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    OrderEntity parent = parentOrder(fixture, "0.00001");

    serviceWithLedger().createAttachedLocked(parent, List.of(new AttachedProtectionRequest(
        ProtectionType.TAKE_PROFIT,
        new BigDecimal("51000"),
        null,
        TriggerExecutionType.MARKET,
        null)), AUTHORITY_MARK);

    verify(orderRepository, times(1)).insert(any(OrderEntity.class));
  }

  @Test
  void attachedMarketUsesCanonicalInstrumentRulesTickInsteadOfSymbolSnapshotTick() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    fixture.symbolEntity().setTickSize(new BigDecimal("0.01"));
    OrderEntity parent = parentOrder(fixture, "1");

    assertCode("PRICE_TICK_MISMATCH", () -> serviceWithLedger().createAttachedLocked(
        parent,
        List.of(new AttachedProtectionRequest(
            ProtectionType.TAKE_PROFIT,
            new BigDecimal("51000.05"),
            null,
            TriggerExecutionType.MARKET,
            null)), AUTHORITY_MARK));

    verify(orderRepository, never()).insert(any(OrderEntity.class));
  }

  @Test
  void attachedCreateEnforcesTenRowsAndIndependentSameTypeQuantityBudgets() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    OrderEntity parent = parentOrder(fixture, "1");
    List<AttachedProtectionRequest> eleven = new ArrayList<>();
    for (int index = 0; index < 11; index++) {
      eleven.add(new AttachedProtectionRequest(
          index % 2 == 0 ? ProtectionType.TAKE_PROFIT : ProtectionType.STOP_LOSS,
          index % 2 == 0 ? new BigDecimal("51000") : new BigDecimal("49000"),
          null,
          TriggerExecutionType.MARKET,
          null));
    }

    assertCode(ErrorCode.PROTECTION_LIMIT_EXCEEDED,
        () -> serviceWithLedger().createAttachedLocked(parent, eleven, AUTHORITY_MARK));
    assertCode(ErrorCode.PROTECTION_QUANTITY_EXCEEDED,
        () -> serviceWithLedger().createAttachedLocked(parent, List.of(
            new AttachedProtectionRequest(
                ProtectionType.TAKE_PROFIT,
                new BigDecimal("51000"),
                null,
                TriggerExecutionType.MARKET,
                null),
            new AttachedProtectionRequest(
                ProtectionType.TAKE_PROFIT,
                new BigDecimal("52000"),
                null,
                TriggerExecutionType.MARKET,
                null)), AUTHORITY_MARK));

    verify(orderRepository, never()).insert(any(OrderEntity.class));
  }

  @Test
  void attachedCreateAllowsSameTypeLevelsThatExactlyFillParentBudget() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    OrderEntity parent = parentOrder(fixture, "1");
    List<AttachedProtectionRequest> requests = List.of(
        attached(ProtectionType.TAKE_PROFIT, "51000", "0.4", QuantityUnit.BASE),
        attached(ProtectionType.TAKE_PROFIT, "52000", "0.6", null));

    serviceWithLedger().createAttachedLocked(parent, requests, AUTHORITY_MARK);

    ArgumentCaptor<OrderEntity> carriers = ArgumentCaptor.forClass(OrderEntity.class);
    verify(orderRepository, times(2)).insert(carriers.capture());
    assertThat(carriers.getAllValues())
        .extracting(OrderEntity::getOriginalQuantity)
        .containsExactly(new BigDecimal("0.4"), new BigDecimal("0.6"));
    assertThat(carriers.getAllValues())
        .extracting(OrderEntity::getQuantityUnit)
        .containsExactly(QuantityUnit.BASE, QuantityUnit.BASE);
    assertThat(carriers.getAllValues())
        .extracting(OrderEntity::getBaseQuantity)
        .containsExactly(new BigDecimal("0.4"), new BigDecimal("0.6"));
    assertThat(carriers.getAllValues())
        .allSatisfy(carrier -> {
          assertThat(carrier.getLots()).isEqualByComparingTo(carrier.getBaseQuantity());
          assertThat(carrier.getRemainingQuantity()).isEqualByComparingTo(carrier.getBaseQuantity());
        });
  }

  @Test
  void attachedCreateRejectsSameTypeOverBudgetBeforeAnyInsert() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    OrderEntity parent = parentOrder(fixture, "1");

    assertCode(ErrorCode.PROTECTION_QUANTITY_EXCEEDED,
        () -> serviceWithLedger().createAttachedLocked(
            parent,
            List.of(
                attached(ProtectionType.TAKE_PROFIT, "51000", "0.6", QuantityUnit.BASE),
                attached(ProtectionType.TAKE_PROFIT, "52000", "0.5", QuantityUnit.BASE)),
            AUTHORITY_MARK));

    verify(orderRepository, never()).insert(any(OrderEntity.class));
  }

  @Test
  void attachedCreateBudgetsTakeProfitAndStopLossIndependently() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    OrderEntity parent = parentOrder(fixture, "1");

    serviceWithLedger().createAttachedLocked(
        parent,
        List.of(
            attached(ProtectionType.TAKE_PROFIT, "51000", "1", QuantityUnit.BASE),
            attached(ProtectionType.STOP_LOSS, "49000", "1", QuantityUnit.BASE)),
        AUTHORITY_MARK);

    verify(orderRepository, times(2)).insert(any(OrderEntity.class));
  }

  @Test
  void attachedCreateConvertsBaseQuoteAndContractsWithOneAuthorityMark() {
    Fixture fixture = fixture(OrderSide.BUY, "2");
    OrderEntity parent = parentOrder(fixture, "2");
    List<AttachedProtectionRequest> requests = List.of(
        attached(ProtectionType.TAKE_PROFIT, "51000", "0.1", QuantityUnit.BASE),
        attached(ProtectionType.TAKE_PROFIT, "52000", "10000", QuantityUnit.QUOTE),
        attached(ProtectionType.TAKE_PROFIT, "53000", "1", QuantityUnit.CONTRACTS));

    serviceWithLedger().createAttachedLocked(parent, requests, AUTHORITY_MARK);

    ArgumentCaptor<OrderEntity> carriers = ArgumentCaptor.forClass(OrderEntity.class);
    verify(orderRepository, times(3)).insert(carriers.capture());
    assertThat(carriers.getAllValues())
        .extracting(OrderEntity::getOriginalQuantity)
        .containsExactly(
            new BigDecimal("0.1"),
            new BigDecimal("10000"),
            new BigDecimal("1"));
    assertThat(carriers.getAllValues())
        .extracting(OrderEntity::getQuantityUnit)
        .containsExactly(QuantityUnit.BASE, QuantityUnit.QUOTE, QuantityUnit.CONTRACTS);
    assertThat(carriers.getAllValues())
        .extracting(OrderEntity::getBaseQuantity)
        .satisfiesExactly(
            quantity -> assertThat(quantity).isEqualByComparingTo("0.1"),
            quantity -> assertThat(quantity).isEqualByComparingTo("0.2"),
            quantity -> assertThat(quantity).isEqualByComparingTo("1"));
    verify(marketBundleResolver, never()).resolvePerp(any(), any());
  }

  @Test
  void attachedCreateAppliesPerLevelCanonicalRulesAndLimitNotionalBeforeWrites() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    OrderEntity parent = parentOrder(fixture, "1");
    AttachedProtectionRequest tinyLimit = new AttachedProtectionRequest(
        ProtectionType.STOP_LOSS,
        new BigDecimal("49000"),
        TriggerPriceType.MARK_PRICE,
        TriggerExecutionType.LIMIT,
        new BigDecimal("100"),
        new BigDecimal("0.001"),
        QuantityUnit.BASE);

    assertCode("ORDER_NOTIONAL_TOO_SMALL", () -> serviceWithLedger().createAttachedLocked(
        parent, List.of(tinyLimit), AUTHORITY_MARK));

    OrderEntity oversizedParent = parentOrder(fixture, "200");
    assertCode("QUANTITY_TOO_LARGE", () -> serviceWithLedger().createAttachedLocked(
        oversizedParent,
        List.of(attached(ProtectionType.TAKE_PROFIT, "51000", "101", QuantityUnit.BASE)),
        AUTHORITY_MARK));

    verify(orderRepository, never()).insert(any(OrderEntity.class));
  }

  @Test
  void parentFillBindsAttachedCarriersToActualSlotAfterValidatingMarkDirection() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    OrderEntity parent = parentOrder(fixture, "1");
    OrderEntity takeProfit = unboundAttached(
        parent, ProtectionType.TAKE_PROFIT, "51000", TriggerExecutionType.MARKET, null);
    OrderEntity stopLoss = unboundAttached(
        parent, ProtectionType.STOP_LOSS, "49000", TriggerExecutionType.LIMIT, "48900");
    when(orderRepository.findProtectionsByParentOrderIdForUpdate(parent.getId()))
        .thenReturn(List.of(takeProfit, stopLoss));
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), fixture.symbol())).thenReturn(List.of(takeProfit, stopLoss));

    serviceWithLedger().afterPerpetualFillLocked(
        parent,
        new PositionEngine.PositionUpdateResult(fixture.position()),
        new BigDecimal("50000"));

    assertThat(takeProfit.getParentPositionId()).isEqualTo(fixture.positionId());
    assertThat(stopLoss.getParentPositionId()).isEqualTo(fixture.positionId());
    assertThat(takeProfit.getSide()).isEqualTo(OrderSide.SELL);
    assertThat(stopLoss.getSide()).isEqualTo(OrderSide.SELL);
    verify(orderRepository).updateById(takeProfit);
    verify(orderRepository).updateById(stopLoss);
    verify(orderEventService, times(2)).record(
        any(UUID.class),
        eq("PROTECTION_ACTIVATED"),
        eq(OrderStatus.PENDING_ACTIVATION),
        eq(OrderStatus.PENDING_ACTIVATION),
        eq(null),
        eq(null));
  }

  @Test
  void canceledParentExpiresOnlyUnboundAttachedProtectionsAndReleasesTheirHold() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    fixture.account().setUsedMargin(new BigDecimal("125"));
    fixture.account().setFreeMargin(new BigDecimal("875"));
    OrderEntity parent = parentOrder(fixture, "1");
    parent.setStatus(OrderStatus.CANCELED);
    OrderEntity attached = unboundAttached(
        parent, ProtectionType.TAKE_PROFIT, "51000", TriggerExecutionType.MARKET, null);
    attached.setHoldAmount(new BigDecimal("25"));
    OrderEntity bound = unboundAttached(
        parent, ProtectionType.STOP_LOSS, "49000", TriggerExecutionType.MARKET, null);
    bound.setParentPositionId(fixture.positionId());
    OrderEntity task9Internal = task9InternalClose(fixture, "99");
    task9Internal.setParentOrderId(parent.getId());
    when(orderRepository.findProtectionsByParentOrderIdForUpdate(parent.getId()))
        .thenReturn(List.of(attached, bound, task9Internal));

    serviceWithLedger().expireAttachedForCanceledParentLocked(parent, fixture.account());

    assertThat(attached.getStatus()).isEqualTo(OrderStatus.EXPIRED);
    assertThat(attached.getRemainingQuantity()).isZero();
    assertThat(attached.getHoldAmount()).isZero();
    assertThat(attached.getVersion()).isEqualTo(1L);
    assertThat(fixture.account().getUsedMargin()).isEqualByComparingTo("100.00000000");
    assertThat(fixture.account().getFreeMargin()).isEqualByComparingTo("900.00000000");
    assertThat(bound.getStatus()).isEqualTo(OrderStatus.PENDING_ACTIVATION);
    assertThat(task9Internal.getStatus()).isEqualTo(OrderStatus.PENDING_ACTIVATION);
    assertThat(task9Internal.getBaseQuantity()).isEqualByComparingTo("99");
    verify(orderRepository).updateById(attached);
    verify(orderRepository, never()).updateById(bound);
    verify(orderRepository, never()).updateById(task9Internal);
    verify(ledgerService).recordOrderRelease(
        fixture.account(),
        new BigDecimal("25.00000000"),
        attached.getId(),
        "Parent cancellation released attached protection hold");
    verify(orderEventService).record(
        attached.getId(),
        "PROTECTION_EXPIRED",
        OrderStatus.PENDING_ACTIVATION,
        OrderStatus.EXPIRED,
        null,
        null);
  }

  @Test
  void afterReductionKeepsOldestPerTypeAndShrinksOrExpiresNewestFirst() {
    Fixture fixture = fixture(OrderSide.BUY, "0.6");
    Instant start = Instant.parse("2026-07-12T00:00:00Z");
    OrderEntity tpOld = existingProtection(fixture, ProtectionType.TAKE_PROFIT, "0.4");
    OrderEntity tpNew = existingProtection(fixture, ProtectionType.TAKE_PROFIT, "0.4");
    OrderEntity slOld = existingProtection(fixture, ProtectionType.STOP_LOSS, "0.6");
    OrderEntity slNew = existingProtection(fixture, ProtectionType.STOP_LOSS, "0.4");
    tpOld.setCreatedAt(start);
    tpNew.setCreatedAt(start.plusSeconds(1));
    slOld.setCreatedAt(start);
    slNew.setCreatedAt(start.plusSeconds(1));
    OrderEntity internal = task9InternalClose(fixture, "99");
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), fixture.symbol()))
        .thenReturn(List.of(tpOld, tpNew, slOld, slNew, internal));
    OrderEntity close = parentOrder(fixture, "0.4");
    close.setReduceOnly(true);

    serviceWithLedger().afterPerpetualFillLocked(
        close,
        new PositionEngine.PositionUpdateResult(fixture.position())
            .withReduction(fixture.positionId(), new BigDecimal("1"), new BigDecimal("0.6")),
        new BigDecimal("50000"));

    assertThat(tpOld.getBaseQuantity()).isEqualByComparingTo("0.4");
    assertThat(tpNew.getBaseQuantity()).isEqualByComparingTo("0.2");
    assertThat(tpNew.getStatus()).isEqualTo(OrderStatus.PENDING_ACTIVATION);
    assertThat(slOld.getBaseQuantity()).isEqualByComparingTo("0.6");
    assertThat(slNew.getBaseQuantity()).isZero();
    assertThat(slNew.getStatus()).isEqualTo(OrderStatus.EXPIRED);
    assertThat(internal.getBaseQuantity()).isEqualByComparingTo("99");
    verify(orderRepository).updateById(tpNew);
    verify(orderRepository).updateById(slNew);
    verify(orderRepository, never()).updateById(tpOld);
    verify(orderRepository, never()).updateById(slOld);
    verify(orderRepository, never()).updateById(internal);
    verify(orderEventService).record(
        tpNew.getId(), "PROTECTION_RESIZED", OrderStatus.PENDING_ACTIVATION,
        OrderStatus.PENDING_ACTIVATION, null, null);
    verify(orderEventService).record(
        slNew.getId(), "PROTECTION_EXPIRED", OrderStatus.PENDING_ACTIVATION,
        OrderStatus.EXPIRED, null, null);
  }

  @Test
  void partialReductionReconcilesExistingProtectionsBeforeBindingAttachedOnes() {
    Fixture fixture = fixture(OrderSide.BUY, "1.5");
    OrderEntity parent = parentOrder(fixture, "0.5");
    parent.setSide(OrderSide.SELL);
    OrderEntity existingTakeProfit = existingProtection(
        fixture, ProtectionType.TAKE_PROFIT, "2");
    OrderEntity attachedStopLoss = unboundAttached(
        parent, ProtectionType.STOP_LOSS, "49000", TriggerExecutionType.MARKET, null);
    when(orderRepository.findProtectionsByParentOrderIdForUpdate(parent.getId()))
        .thenReturn(List.of(attachedStopLoss));
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), fixture.symbol()))
        .thenReturn(
            List.of(existingTakeProfit, attachedStopLoss),
            List.of(existingTakeProfit, attachedStopLoss));

    serviceWithLedger().afterPerpetualFillLocked(
        parent,
        new PositionEngine.PositionUpdateResult(fixture.position())
            .withReduction(
                fixture.positionId(),
                new BigDecimal("2"),
                new BigDecimal("1.5")),
        new BigDecimal("50000"));

    assertThat(existingTakeProfit.getBaseQuantity()).isEqualByComparingTo("1.5");
    assertThat(attachedStopLoss.getParentPositionId()).isEqualTo(fixture.positionId());
    assertThat(attachedStopLoss.getSide()).isEqualTo(OrderSide.SELL);
    verify(orderEventService).record(
        existingTakeProfit.getId(),
        "PROTECTION_RESIZED",
        OrderStatus.PENDING_ACTIVATION,
        OrderStatus.PENDING_ACTIVATION,
        null,
        null);
    verify(orderEventService).record(
        attachedStopLoss.getId(),
        "PROTECTION_ACTIVATED",
        OrderStatus.PENDING_ACTIVATION,
        OrderStatus.PENDING_ACTIVATION,
        null,
        null);
  }

  @Test
  void nonTerminalParentFillReconcilesReductionWithoutBindingAttachedProtections() {
    Fixture fixture = fixture(OrderSide.BUY, "1.5");
    OrderEntity parent = parentOrder(fixture, "0.5");
    parent.setSide(OrderSide.SELL);
    OrderEntity existingTakeProfit = existingProtection(
        fixture, ProtectionType.TAKE_PROFIT, "2");
    OrderEntity attachedStopLoss = unboundAttached(
        parent, ProtectionType.STOP_LOSS, "49000", TriggerExecutionType.MARKET, null);
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), fixture.symbol()))
        .thenReturn(
            List.of(existingTakeProfit, attachedStopLoss),
            List.of(existingTakeProfit, attachedStopLoss));

    serviceWithLedger().afterPerpetualFillLocked(
        parent,
        new PositionEngine.PositionUpdateResult(fixture.position())
            .withReduction(
                fixture.positionId(),
                new BigDecimal("2"),
                new BigDecimal("1.5")),
        new BigDecimal("50000"),
        false);

    assertThat(existingTakeProfit.getBaseQuantity()).isEqualByComparingTo("1.5");
    assertThat(attachedStopLoss.getParentPositionId()).isNull();
    verify(orderEventService).record(
        existingTakeProfit.getId(),
        "PROTECTION_RESIZED",
        OrderStatus.PENDING_ACTIVATION,
        OrderStatus.PENDING_ACTIVATION,
        null,
        null);
    verify(orderRepository, never()).findProtectionsByParentOrderIdForUpdate(parent.getId());
    verify(orderRepository, never()).updateById(attachedStopLoss);
  }

  @Test
  void terminalIocThatOnlyReducesOppositeSlotExpiresUnboundAttachedInsteadOfBindingReducedSlot() {
    Fixture fixture = fixture(OrderSide.BUY, "1.5");
    OrderEntity parent = parentOrder(fixture, "0.5");
    parent.setSide(OrderSide.SELL);
    parent.setTimeInForce(TimeInForce.IOC);
    OrderEntity attachedStopLoss = unboundAttached(
        parent, ProtectionType.STOP_LOSS, "49000", TriggerExecutionType.MARKET, null);
    when(orderRepository.findProtectionsByParentOrderIdForUpdate(parent.getId()))
        .thenReturn(List.of(attachedStopLoss));
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), fixture.symbol()))
        .thenReturn(List.of(attachedStopLoss), List.of(attachedStopLoss));

    serviceWithLedger().afterPerpetualFillLocked(
        parent,
        new PositionEngine.PositionUpdateResult(fixture.position())
            .withReduction(
                fixture.positionId(),
                new BigDecimal("2"),
                new BigDecimal("1.5")),
        new BigDecimal("50000"),
        true);

    assertThat(attachedStopLoss.getParentPositionId()).isNull();
    assertThat(attachedStopLoss.getStatus()).isEqualTo(OrderStatus.EXPIRED);
    assertThat(attachedStopLoss.getBaseQuantity()).isZero();
    assertThat(attachedStopLoss.getRemainingQuantity()).isZero();
    verify(orderRepository).updateById(attachedStopLoss);
    verify(orderEventService).record(
        attachedStopLoss.getId(),
        "PROTECTION_EXPIRED",
        OrderStatus.PENDING_ACTIVATION,
        OrderStatus.EXPIRED,
        null,
        null);
    verify(orderEventService, never()).record(
        eq(attachedStopLoss.getId()),
        eq("PROTECTION_ACTIVATED"),
        any(),
        any(),
        any(),
        any());
  }

  @Test
  void terminalParentFillStillBindsAttachedProtections() {
    Fixture fixture = fixture(OrderSide.BUY, "1");
    OrderEntity parent = parentOrder(fixture, "1");
    OrderEntity attachedTakeProfit = unboundAttached(
        parent, ProtectionType.TAKE_PROFIT, "51000", TriggerExecutionType.MARKET, null);
    when(orderRepository.findProtectionsByParentOrderIdForUpdate(parent.getId()))
        .thenReturn(List.of(attachedTakeProfit));
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), fixture.symbol())).thenReturn(List.of(attachedTakeProfit));

    serviceWithLedger().afterPerpetualFillLocked(
        parent,
        new PositionEngine.PositionUpdateResult(fixture.position()),
        new BigDecimal("50000"),
        true);

    assertThat(attachedTakeProfit.getParentPositionId()).isEqualTo(fixture.positionId());
    assertThat(attachedTakeProfit.getSide()).isEqualTo(OrderSide.SELL);
    verify(orderRepository).updateById(attachedTakeProfit);
    verify(orderEventService).record(
        attachedTakeProfit.getId(),
        "PROTECTION_ACTIVATED",
        OrderStatus.PENDING_ACTIVATION,
        OrderStatus.PENDING_ACTIVATION,
        null,
        null);
  }

  @Test
  void triggeredPendingLimitResizeReleasesExactProportionalHold() {
    Fixture fixture = fixture(OrderSide.BUY, "0.4");
    fixture.account().setUsedMargin(new BigDecimal("200"));
    fixture.account().setFreeMargin(new BigDecimal("800"));
    OrderEntity pendingLimit = existingProtection(
        fixture, ProtectionType.TAKE_PROFIT, "1");
    pendingLimit.setStatus(OrderStatus.PENDING);
    pendingLimit.setOrderType(OrderType.LIMIT);
    pendingLimit.setTriggerExecutionType(TriggerExecutionType.LIMIT);
    pendingLimit.setHoldAmount(new BigDecimal("100"));
    pendingLimit.setMarginMode(MarginMode.CROSS);
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), fixture.symbol())).thenReturn(List.of(pendingLimit));
    when(accountRepository.findByIdForUpdate(fixture.accountId()))
        .thenReturn(Optional.of(fixture.account()));
    OrderEntity close = parentOrder(fixture, "0.6");
    close.setReduceOnly(true);

    serviceWithLedger().afterPerpetualFillLocked(
        close,
        new PositionEngine.PositionUpdateResult(fixture.position())
            .withReduction(fixture.positionId(), BigDecimal.ONE, new BigDecimal("0.4")),
        new BigDecimal("50000"));

    assertThat(pendingLimit.getBaseQuantity()).isEqualByComparingTo("0.4");
    assertThat(pendingLimit.getHoldAmount()).isEqualByComparingTo("40.00000000");
    assertThat(fixture.account().getUsedMargin()).isEqualByComparingTo("140.00000000");
    assertThat(fixture.account().getFreeMargin()).isEqualByComparingTo("860.00000000");
    verify(ledgerService).recordOrderRelease(
        fixture.account(),
        new BigDecimal("60.00000000"),
        pendingLimit.getId(),
        "Protection resize released order hold");
  }

  @Test
  void reversalExpiresOldSlotProtectionsBeforeBindingAttachedToNewSlot() {
    Fixture fixture = fixture(OrderSide.SELL, "0.2");
    UUID oldPositionId = UUID.randomUUID();
    OrderEntity parent = parentOrder(fixture, "1.2");
    parent.setSide(OrderSide.SELL);
    OrderEntity attached = unboundAttached(
        parent, ProtectionType.TAKE_PROFIT, "49000", TriggerExecutionType.MARKET, null);
    OrderEntity activeCopy = copyProtection(attached);
    OrderEntity oldProtection = existingProtection(
        fixture, ProtectionType.STOP_LOSS, "1");
    oldProtection.setParentPositionId(oldPositionId);
    when(orderRepository.findProtectionsByParentOrderIdForUpdate(parent.getId()))
        .thenReturn(List.of(attached));
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(
        fixture.accountId(), fixture.symbol()))
        .thenReturn(
            List.of(activeCopy, oldProtection),
            List.of(attached, oldProtection));

    serviceWithLedger().afterPerpetualFillLocked(
        parent,
        new PositionEngine.PositionUpdateResult(fixture.position())
            .withReduction(oldPositionId, BigDecimal.ONE, BigDecimal.ZERO),
        new BigDecimal("50000"));

    assertThat(attached.getParentPositionId()).isEqualTo(fixture.positionId());
    assertThat(attached.getStatus()).isEqualTo(OrderStatus.PENDING_ACTIVATION);
    assertThat(oldProtection.getStatus()).isEqualTo(OrderStatus.EXPIRED);
    verify(orderEventService).record(
        attached.getId(), "PROTECTION_ACTIVATED", OrderStatus.PENDING_ACTIVATION,
        OrderStatus.PENDING_ACTIVATION, null, null);
    verify(orderEventService).record(
        oldProtection.getId(), "PROTECTION_EXPIRED", OrderStatus.PENDING_ACTIVATION,
        OrderStatus.EXPIRED, null, null);
  }

  @Test
  void markTriggerUsesAllFourLongShortTakeProfitStopLossDirections() {
    ProtectionOrderService service = service();
    assertThat(service.isTriggered(protection(OrderSide.SELL, ProtectionType.TAKE_PROFIT, "51000"),
        new BigDecimal("51000"))).isTrue();
    assertThat(service.isTriggered(protection(OrderSide.SELL, ProtectionType.STOP_LOSS, "49000"),
        new BigDecimal("49000"))).isTrue();
    assertThat(service.isTriggered(protection(OrderSide.BUY, ProtectionType.TAKE_PROFIT, "49000"),
        new BigDecimal("49000"))).isTrue();
    assertThat(service.isTriggered(protection(OrderSide.BUY, ProtectionType.STOP_LOSS, "51000"),
        new BigDecimal("51000"))).isTrue();

    assertThat(service.isTriggered(protection(OrderSide.SELL, ProtectionType.TAKE_PROFIT, "51000"),
        new BigDecimal("50999.9"))).isFalse();
    assertThat(service.isTriggered(protection(OrderSide.SELL, ProtectionType.STOP_LOSS, "49000"),
        new BigDecimal("49000.1"))).isFalse();
    assertThat(service.isTriggered(protection(OrderSide.BUY, ProtectionType.TAKE_PROFIT, "49000"),
        new BigDecimal("49000.1"))).isFalse();
    assertThat(service.isTriggered(protection(OrderSide.BUY, ProtectionType.STOP_LOSS, "51000"),
        new BigDecimal("50999.9"))).isFalse();
  }

  @Test
  void triggerPredicateRejectsTask9InternalParentCarrierWithoutProtectionType() {
    OrderEntity internalClose = protection(OrderSide.SELL, null, "49000");

    assertThat(service().isTriggered(internalClose, new BigDecimal("48000"))).isFalse();
  }

  private ProtectionOrderService service() {
    return new ProtectionOrderService(
        accountRepository,
        positionRepository,
        orderRepository,
        settingRepository,
        symbolRepository,
        marketBundleResolver,
        fullFillCoordinator,
        new QuantityConversionService(),
        instrumentRulesEngine,
        demoExecutionGuard,
        orderEventService,
        new OrderResponseMapper(),
        new TradingTransactionExecutor());
  }

  private ProtectionOrderService serviceWithLedger() {
    return new ProtectionOrderService(
        accountRepository,
        positionRepository,
        orderRepository,
        settingRepository,
        symbolRepository,
        marketBundleResolver,
        fullFillCoordinator,
        new QuantityConversionService(),
        instrumentRulesEngine,
        demoExecutionGuard,
        ledgerService,
        orderEventService,
        new OrderResponseMapper(),
        new TradingTransactionExecutor());
  }

  private Fixture fixture(OrderSide positionSide, String lots) {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    String symbolCode = "BTCUSDT-PERP";
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    account.setBaseCurrency("USDT");
    account.setPositionMode(PositionMode.ONE_WAY);
    PositionEntity position = new PositionEntity();
    position.setId(positionId);
    position.setAccountId(accountId);
    position.setSymbol(symbolCode);
    position.setProductType(ProductType.LINEAR_PERP);
    position.setPositionMode(PositionMode.ONE_WAY);
    position.setPositionSide(PositionSide.BOTH);
    position.setMarginMode(MarginMode.CROSS);
    position.setSide(positionSide);
    position.setLots(new BigDecimal(lots));
    position.setLeverage(10);
    position.setStatus(PositionStatus.OPEN);
    position.setVersion(2L);
    SymbolEntity symbol = symbol(symbolCode);
    AccountSymbolSettingEntity setting = new AccountSymbolSettingEntity();
    setting.setAccountId(accountId);
    setting.setSymbol(symbolCode);
    setting.setLeverage(10);
    setting.setMarginMode(MarginMode.CROSS);
    setting.setQuantityUnit(QuantityUnit.BASE);

    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(symbolRepository.findBySymbol(symbolCode)).thenReturn(Optional.of(symbol));
    when(instrumentRulesEngine.rules(symbol)).thenReturn(rules(symbolCode));
    when(marketBundleResolver.resolvePerp(eq(symbolCode), any()))
        .thenReturn(bundle(symbolCode, "50000"));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId))
        .thenReturn(Optional.of(account));
    when(settingRepository.findByAccountIdAndSymbolForUpdate(accountId, symbolCode))
        .thenReturn(Optional.of(setting));
    when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of(position));
    when(orderRepository.findActiveLinearPerpBySymbolForUpdate(accountId, symbolCode))
        .thenReturn(List.of());
    when(orderRepository.insert(any(OrderEntity.class))).thenReturn(1);
    when(orderRepository.updateById(any(OrderEntity.class))).thenReturn(1);
    return new Fixture(
        userId,
        accountId,
        positionId,
        symbolCode,
        account,
        position,
        symbol,
        setting);
  }

  private void assertDirectionRejected(
      OrderSide positionSide,
      ProtectionType protectionType,
      String triggerPrice
  ) {
    org.mockito.Mockito.reset(
        accountRepository,
        positionRepository,
        orderRepository,
        settingRepository,
        symbolRepository,
        marketBundleResolver,
        fullFillCoordinator,
        instrumentRulesEngine,
        demoExecutionGuard,
        orderEventService);
    Fixture fixture = fixture(positionSide, "1");
    assertCode("PROTECTION_DIRECTION_INVALID", () -> service().create(
        fixture.userId(), fixture.positionId(), createRequest(
            protectionType, "0.1", triggerPrice, TriggerExecutionType.MARKET, null)));
    verify(orderRepository, never()).insert(any(OrderEntity.class));
  }

  private CreateProtectionRequest createRequest(
      ProtectionType type,
      String quantity,
      String triggerPrice,
      TriggerExecutionType executionType,
      String price
  ) {
    return new CreateProtectionRequest(
        type,
        new BigDecimal(quantity),
        QuantityUnit.BASE,
        new BigDecimal(triggerPrice),
        executionType,
        price == null ? null : new BigDecimal(price),
        UUID.randomUUID().toString());
  }

  private OrderEntity existingProtection(Fixture fixture, ProtectionType type, String quantity) {
    OrderEntity order = protection(
        fixture.position().getSide() == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY,
        type,
        type == ProtectionType.TAKE_PROFIT
            ? fixture.position().getSide() == OrderSide.BUY ? "51000" : "49000"
            : fixture.position().getSide() == OrderSide.BUY ? "49000" : "51000");
    order.setId(UUID.randomUUID());
    order.setUserId(fixture.userId());
    order.setAccountId(fixture.accountId());
    order.setSymbol(fixture.symbol());
    order.setProductType(ProductType.LINEAR_PERP);
    order.setParentPositionId(fixture.positionId());
    order.setBaseQuantity(new BigDecimal(quantity));
    order.setLots(new BigDecimal(quantity));
    order.setQuantity(new BigDecimal(quantity));
    order.setOriginalQuantity(new BigDecimal(quantity));
    order.setRemainingQuantity(new BigDecimal(quantity));
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setTriggerExecutionType(TriggerExecutionType.MARKET);
    order.setTriggerPriceType(TriggerPriceType.MARK_PRICE);
    order.setOrderType(OrderType.STOP_MARKET);
    order.setOrderOrigin(OrderOrigin.PROTECTIVE);
    order.setReduceOnly(true);
    order.setHoldAmount(BigDecimal.ZERO);
    order.setVersion(0L);
    return order;
  }

  private OrderEntity task9InternalClose(Fixture fixture, String quantity) {
    OrderEntity order = existingProtection(fixture, ProtectionType.STOP_LOSS, quantity);
    order.setProtectionType(null);
    order.setOrderOrigin(OrderOrigin.USER);
    return order;
  }

  private OrderEntity copyProtection(OrderEntity source) {
    OrderEntity copy = new OrderEntity();
    copy.setId(source.getId());
    copy.setUserId(source.getUserId());
    copy.setAccountId(source.getAccountId());
    copy.setSymbol(source.getSymbol());
    copy.setProductType(source.getProductType());
    copy.setPositionMode(source.getPositionMode());
    copy.setPositionSide(source.getPositionSide());
    copy.setMarginMode(source.getMarginMode());
    copy.setSide(source.getSide());
    copy.setOrderType(source.getOrderType());
    copy.setStatus(source.getStatus());
    copy.setLots(source.getLots());
    copy.setQuantity(source.getQuantity());
    copy.setQuantityUnit(source.getQuantityUnit());
    copy.setOriginalQuantity(source.getOriginalQuantity());
    copy.setBaseQuantity(source.getBaseQuantity());
    copy.setPrice(source.getPrice());
    copy.setTimeInForce(source.getTimeInForce());
    copy.setReduceOnly(source.getReduceOnly());
    copy.setOrderOrigin(source.getOrderOrigin());
    copy.setTriggerPrice(source.getTriggerPrice());
    copy.setTriggerPriceType(source.getTriggerPriceType());
    copy.setTriggerExecutionType(source.getTriggerExecutionType());
    copy.setProtectionType(source.getProtectionType());
    copy.setParentPositionId(source.getParentPositionId());
    copy.setRemainingQuantity(source.getRemainingQuantity());
    copy.setHoldAmount(source.getHoldAmount());
    copy.setHoldCurrency(source.getHoldCurrency());
    copy.setLeverage(source.getLeverage());
    copy.setVersion(source.getVersion());
    return copy;
  }

  private OrderEntity protection(OrderSide side, ProtectionType type, String triggerPrice) {
    OrderEntity order = new OrderEntity();
    order.setStatus(OrderStatus.PENDING_ACTIVATION);
    order.setSide(side);
    order.setProtectionType(type);
    order.setTriggerPrice(new BigDecimal(triggerPrice));
    return order;
  }

  private OrderEntity parentOrder(Fixture fixture, String quantity) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setUserId(fixture.userId());
    order.setAccountId(fixture.accountId());
    order.setSymbol(fixture.symbol());
    order.setProductType(ProductType.LINEAR_PERP);
    order.setPositionMode(PositionMode.ONE_WAY);
    order.setPositionSide(PositionSide.BOTH);
    order.setMarginMode(MarginMode.CROSS);
    order.setSide(OrderSide.BUY);
    order.setOrderType(OrderType.MARKET);
    order.setStatus(OrderStatus.ACCEPTED);
    order.setBaseQuantity(new BigDecimal(quantity));
    order.setLots(new BigDecimal(quantity));
    order.setQuantity(new BigDecimal(quantity));
    order.setOriginalQuantity(new BigDecimal(quantity));
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setClientOrderId(UUID.randomUUID().toString());
    order.setLeverage(10);
    order.setReduceOnly(false);
    return order;
  }

  private AttachedProtectionRequest attached(
      ProtectionType type,
      String trigger,
      String quantity,
      QuantityUnit quantityUnit
  ) {
    return new AttachedProtectionRequest(
        type,
        new BigDecimal(trigger),
        TriggerPriceType.MARK_PRICE,
        TriggerExecutionType.MARKET,
        null,
        new BigDecimal(quantity),
        quantityUnit);
  }

  private OrderEntity unboundAttached(
      OrderEntity parent,
      ProtectionType type,
      String trigger,
      TriggerExecutionType executionType,
      String price
  ) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setUserId(parent.getUserId());
    order.setAccountId(parent.getAccountId());
    order.setSymbol(parent.getSymbol());
    order.setProductType(ProductType.LINEAR_PERP);
    order.setPositionMode(parent.getPositionMode());
    order.setPositionSide(parent.getPositionSide());
    order.setMarginMode(parent.getMarginMode());
    order.setSide(parent.getSide() == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY);
    order.setOrderType(OrderType.STOP_MARKET);
    order.setStatus(OrderStatus.PENDING_ACTIVATION);
    order.setBaseQuantity(parent.getBaseQuantity());
    order.setLots(parent.getBaseQuantity());
    order.setQuantity(parent.getBaseQuantity());
    order.setOriginalQuantity(parent.getBaseQuantity());
    order.setRemainingQuantity(parent.getBaseQuantity());
    order.setQuantityUnit(QuantityUnit.BASE);
    order.setTriggerPrice(new BigDecimal(trigger));
    order.setTriggerPriceType(TriggerPriceType.MARK_PRICE);
    order.setTriggerExecutionType(executionType);
    order.setProtectionType(type);
    order.setPrice(price == null ? null : new BigDecimal(price));
    order.setParentOrderId(parent.getId());
    order.setOrderOrigin(OrderOrigin.PROTECTIVE);
    order.setReduceOnly(true);
    order.setHoldAmount(BigDecimal.ZERO);
    order.setVersion(0L);
    return order;
  }

  private SymbolEntity symbol(String symbolCode) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setId(UUID.randomUUID());
    symbol.setSymbol(symbolCode);
    symbol.setProductType(ProductType.LINEAR_PERP);
    symbol.setEnabled(true);
    symbol.setTradable(true);
    symbol.setQuoteEnabled(true);
    symbol.setTickSize(new BigDecimal("0.1"));
    symbol.setMinLot(new BigDecimal("0.001"));
    symbol.setMaxLot(new BigDecimal("100"));
    symbol.setContractSize(BigDecimal.ONE);
    symbol.setContractMultiplier(BigDecimal.ONE);
    symbol.setMarginAsset("USDT");
    symbol.setSettlementAsset("USDT");
    return symbol;
  }

  private InstrumentRules rules(String symbol) {
    return new InstrumentRules(
        symbol,
        true,
        true,
        true,
        true,
        true,
        true,
        true,
        ProductType.LINEAR_PERP,
        new BigDecimal("0.1"),
        new BigDecimal("0.001"),
        new BigDecimal("0.001"),
        new BigDecimal("100"),
        new BigDecimal("5"),
        new BigDecimal("10000000"),
        new BigDecimal("0.001"),
        new BigDecimal("100"),
        125,
        10,
        "USDT",
        "USDT",
        BigDecimal.ONE,
        "DEFAULT",
        "ALWAYS",
        "NONE",
        "NORMAL");
  }

  private PerpetualMarketBundle bundle(String symbol, String mark) {
    Instant now = Instant.now();
    return new PerpetualMarketBundle(
        symbol,
        "BTCUSDT",
        "local-perp",
        MarketSourceMode.LOCAL_SIMULATED,
        new BigDecimal("49999"),
        new BigDecimal("50001"),
        new BigDecimal("60000"),
        new BigDecimal(mark),
        new BigDecimal(mark),
        null,
        List.of(),
        List.of(),
        now,
        now.plusSeconds(30));
  }

  private void assertCode(String code, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).getCode())
        .isEqualTo(code);
  }

  private record Fixture(
      UUID userId,
      UUID accountId,
      UUID positionId,
      String symbol,
      TradingAccountEntity account,
      PositionEntity position,
      SymbolEntity symbolEntity,
      AccountSymbolSettingEntity setting
  ) {
  }
}
