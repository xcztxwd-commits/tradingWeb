package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doThrow;
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
import com.fxplatform.risk.service.PerpMarginCalculator;
import com.fxplatform.risk.service.PerpetualRiskService;
import com.fxplatform.trading.dto.request.AdjustPositionMarginRequest;
import com.fxplatform.trading.dto.request.AdjustPositionMarginRequest.Action;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.event.TradingAccountMutationEvent;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class PositionMarginServiceTest {

  @Mock TradingAccountRepository accountRepository;
  @Mock PositionRepository positionRepository;
  @Mock OrderRepository orderRepository;
  @Mock SymbolRepository symbolRepository;
  @Mock MarketBundleResolver marketBundleResolver;
  @Mock FullFillCoordinator fullFillCoordinator;
  @Mock DemoExecutionGuard demoExecutionGuard;
  @Mock LedgerService ledgerService;
  @Mock ApplicationEventPublisher eventPublisher;

  @Test
  void addMovesAvailablePrincipalIntoTheIsolatedSlotAndEmitsOneLedgerAndEvent() {
    Fixture fixture = fixture("50500", "1000", "1000", "9000", 3L);

    var response = service().adjust(
        fixture.userId(), fixture.positionId(), request(Action.ADD, "200", 3L));

    assertThat(response).isNotNull();
    assertThat(response.positionMargin()).isEqualByComparingTo("1200.00000000");
    assertThat(response.accountUsedMargin()).isEqualByComparingTo("1200.00000000");
    assertThat(response.accountFreeMargin()).isEqualByComparingTo("8800.00000000");
    assertThat(fixture.account().getEquity()).isEqualByComparingTo("10100.00000000");
    assertThat(response.version()).isEqualTo(4L);
    verify(ledgerService).recordMarginHold(
        fixture.account(), new BigDecimal("200.00000000"), fixture.positionId(),
        "Isolated position margin added");
    verify(eventPublisher).publishEvent(any(PositionMarginService.PositionMarginAdjustedEvent.class));
    ArgumentCaptor<TradingAccountMutationEvent> eventCaptor =
        ArgumentCaptor.forClass(TradingAccountMutationEvent.class);
    verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getAllValues()).containsExactly(
        new TradingAccountMutationEvent(
            fixture.userId(),
            fixture.accountId(),
            "MARGIN_ADJUSTED",
            "POSITION",
            fixture.positionId(),
            null,
            4L,
            eventCaptor.getAllValues().get(0).occurredAt()),
        new TradingAccountMutationEvent(
            fixture.userId(),
            fixture.accountId(),
            "POSITION_UPDATED",
            "POSITION",
            fixture.positionId(),
            null,
            4L,
            eventCaptor.getAllValues().get(1).occurredAt()));
    verify(demoExecutionGuard, times(2)).requireDemo(
        fixture.account(), ProductType.LINEAR_PERP, "BTCUSDT-PERP");
  }

  @Test
  void safeReduceMayMovePositionMarginBelowTheoreticalInitialMargin() {
    Fixture fixture = fixture("50000", "1200", "1200", "8800", 3L);
    fixture.position().setInitialMargin(new BigDecimal("1000"));

    var response = service().adjust(
        fixture.userId(), fixture.positionId(), request(Action.REDUCE, "300", 3L));

    assertThat(response).isNotNull();
    assertThat(response.initialMargin()).isEqualByComparingTo("1000.00000000");
    assertThat(response.positionMargin()).isEqualByComparingTo("900.00000000");
    assertThat(response.accountUsedMargin()).isEqualByComparingTo("900.00000000");
    assertThat(response.accountFreeMargin()).isEqualByComparingTo("9100.00000000");
    verify(ledgerService).recordMarginRelease(
        fixture.account(), new BigDecimal("300.00000000"), fixture.positionId(),
        "Isolated position margin reduced");
    verify(eventPublisher).publishEvent(any(PositionMarginService.PositionMarginAdjustedEvent.class));
  }

  @Test
  void reduceRejectsTheInclusiveMaintenanceAndCloseFeeBoundaryWithZeroMutation() {
    Fixture fixture = fixture("45000", "1500", "1500", "8500", 3L);

    assertCode("MARGIN_REDUCTION_UNSAFE", () -> service().adjust(
        fixture.userId(), fixture.positionId(), request(Action.REDUCE, "451", 3L)));

    assertThat(fixture.position().getMarginHeld()).isEqualByComparingTo("1500");
    assertThat(fixture.account().getUsedMargin()).isEqualByComparingTo("1500");
    assertThat(fixture.account().getFreeMargin()).isEqualByComparingTo("8500");
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verifyNoInteractions(ledgerService, eventPublisher);
  }

  @Test
  void stalePositionVersionHasZeroMutation() {
    Fixture fixture = fixture("50000", "1000", "1000", "9000", 3L);

    assertCode("POSITION_VERSION_CONFLICT", () -> service().adjust(
        fixture.userId(), fixture.positionId(), request(Action.ADD, "100", 2L)));

    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verifyNoInteractions(ledgerService, eventPublisher);
  }

  @Test
  void insufficientAvailableMarginRejectsAddWithZeroMutation() {
    Fixture fixture = fixture("50000", "1000", "1000", "100", 3L);
    fixture.account().setBalance(new BigDecimal("1100"));
    fixture.account().setEquity(new BigDecimal("1100"));

    assertCode("INSUFFICIENT_MARGIN", () -> service().adjust(
        fixture.userId(), fixture.positionId(), request(Action.ADD, "200", 3L)));

    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verifyNoInteractions(ledgerService, eventPublisher);
  }

  @Test
  void addRejectsFreshLossFromAnotherCrossSymbolDespitePersistedFreeMargin() {
    Fixture fixture = fixture("50000", "1000", "1000", "9000", 3L);
    PositionEntity eth = crossPosition(
        fixture.accountId(), "ETHUSDT-PERP", "10000", "1000", "1", 8L);
    when(positionRepository.findOpenLinearPerpByAccountId(fixture.accountId()))
        .thenReturn(List.of(fixture.position(), eth));
    when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(fixture.accountId()))
        .thenReturn(List.of(fixture.position(), eth));
    when(symbolRepository.findBySymbol("ETHUSDT-PERP"))
        .thenReturn(Optional.of(symbol("ETHUSDT-PERP")));
    when(marketBundleResolver.resolvePerp(eq("ETHUSDT-PERP"), any()))
        .thenReturn(bundle("ETHUSDT-PERP", "1000"));

    assertCode(ErrorCode.INSUFFICIENT_MARGIN, () -> service().adjust(
        fixture.userId(), fixture.positionId(), request(Action.ADD, "200", 3L)));

    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verifyNoInteractions(ledgerService, eventPublisher);
  }

  @Test
  void addUsesFreshProfitAcrossSymbolsAndResolvesEachProviderBeforeTheAccountLock() {
    Fixture fixture = fixture("50500", "1000", "1100", "0", 3L);
    PositionEntity eth = crossPosition(
        fixture.accountId(), "ETHUSDT-PERP", "1000", "100", "1", 8L);
    when(positionRepository.findOpenLinearPerpByAccountId(fixture.accountId()))
        .thenReturn(List.of(fixture.position(), eth));
    when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(fixture.accountId()))
        .thenReturn(List.of(fixture.position(), eth));
    when(symbolRepository.findBySymbol("ETHUSDT-PERP"))
        .thenReturn(Optional.of(symbol("ETHUSDT-PERP")));
    when(marketBundleResolver.resolvePerp(eq("ETHUSDT-PERP"), any()))
        .thenReturn(bundle("ETHUSDT-PERP", "10000"));

    var response = service().adjust(
        fixture.userId(), fixture.positionId(), request(Action.ADD, "200", 3L));

    assertThat(response.positionMargin()).isEqualByComparingTo("1200.00000000");
    assertThat(response.accountUsedMargin()).isEqualByComparingTo("1300.00000000");
    assertThat(response.accountFreeMargin()).isEqualByComparingTo("17700.00000000");
    assertThat(fixture.account().getEquity()).isEqualByComparingTo("19100.00000000");
    assertThat(fixture.position().getVersion()).isEqualTo(4L);
    assertThat(eth.getVersion()).isEqualTo(9L);
    verify(positionRepository).save(fixture.position());
    verify(positionRepository).save(eth);
    verify(marketBundleResolver, times(1)).resolvePerp(eq("BTCUSDT-PERP"), any());
    verify(marketBundleResolver, times(1)).resolvePerp(eq("ETHUSDT-PERP"), any());
    InOrder providerBeforeLock = org.mockito.Mockito.inOrder(
        marketBundleResolver, accountRepository);
    providerBeforeLock.verify(marketBundleResolver).resolvePerp(eq("BTCUSDT-PERP"), any());
    providerBeforeLock.verify(marketBundleResolver).resolvePerp(eq("ETHUSDT-PERP"), any());
    providerBeforeLock.verify(accountRepository).findByIdAndUserIdForUpdate(
        fixture.accountId(), fixture.userId());
  }

  @Test
  void reduceMaySucceedWhileFreshCrossAvailableRemainsNegative() {
    Fixture fixture = fixture("50000", "1200", "1200", "9000", 3L);
    fixture.account().setBalance(new BigDecimal("500"));
    fixture.account().setEquity(new BigDecimal("500"));

    var response = service().adjust(
        fixture.userId(), fixture.positionId(), request(Action.REDUCE, "300", 3L));

    assertThat(response.positionMargin()).isEqualByComparingTo("900.00000000");
    assertThat(response.accountUsedMargin()).isEqualByComparingTo("900.00000000");
    assertThat(response.accountFreeMargin()).isEqualByComparingTo("-400.00000000");
    assertThat(fixture.account().getEquity()).isEqualByComparingTo("500.00000000");
    verify(ledgerService).recordMarginRelease(
        fixture.account(), new BigDecimal("300.00000000"), fixture.positionId(),
        "Isolated position margin reduced");
  }

  @Test
  void reduceRejectsWhenInternalIsolatedCloseHoldsReachTheNewSlotCapacity() {
    Fixture fixture = fixture("50000", "1200", "2045", "8800", 3L);
    OrderEntity internalClose = activeInternalOrder(
        fixture.accountId(), fixture.positionId(), "845");
    when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(fixture.accountId()))
        .thenReturn(List.of(internalClose));

    assertCode(ErrorCode.MARGIN_REDUCTION_UNSAFE, () -> service().adjust(
        fixture.userId(), fixture.positionId(), request(Action.REDUCE, "300", 3L)));

    assertThat(fixture.position().getMarginHeld()).isEqualByComparingTo("1200");
    assertThat(fixture.position().getVersion()).isEqualTo(3L);
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verifyNoInteractions(ledgerService, eventPublisher);
  }

  @Test
  void positionFingerprintDriftRetriesWithFreshPreparationAndCommitsOnce() {
    Fixture fixture = fixture("50000", "1000", "1000", "9000", 3L);
    PositionEntity stale = position(
        fixture.positionId(), fixture.accountId(), "50000", "1000", 2L);
    when(positionRepository.findOpenLinearPerpByAccountId(fixture.accountId()))
        .thenReturn(List.of(stale), List.of(fixture.position()));

    var response = service().adjust(
        fixture.userId(), fixture.positionId(), request(Action.ADD, "100", 3L));

    assertThat(response.version()).isEqualTo(4L);
    assertThat(response.positionMargin()).isEqualByComparingTo("1100.00000000");
    verify(marketBundleResolver, times(2)).resolvePerp(eq("BTCUSDT-PERP"), any());
    verify(accountRepository, times(2)).findByIdAndUserIdForUpdate(
        fixture.accountId(), fixture.userId());
    verify(positionRepository, times(1)).save(fixture.position());
    verify(ledgerService, times(1)).recordMarginHold(
        fixture.account(), new BigDecimal("100.00000000"), fixture.positionId(),
        "Isolated position margin added");
    verify(eventPublisher, times(1)).publishEvent(
        any(PositionMarginService.PositionMarginAdjustedEvent.class));
  }

  @Test
  void reduceGreaterThanAllocatedPrincipalRejectsWithZeroMutation() {
    Fixture fixture = fixture("50000", "1000", "1000", "9000", 3L);

    assertCode("MARGIN_REDUCTION_UNSAFE", () -> service().adjust(
        fixture.userId(), fixture.positionId(), request(Action.REDUCE, "1000.00000001", 3L)));

    assertThat(fixture.position().getMarginHeld()).isEqualByComparingTo("1000");
    assertThat(fixture.account().getUsedMargin()).isEqualByComparingTo("1000");
    assertThat(fixture.account().getFreeMargin()).isEqualByComparingTo("9000");
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verifyNoInteractions(ledgerService, eventPublisher);
  }

  @Test
  void rejectsCrossClosedAndNonLinearPositionsBeforeAnyMutation() {
    Fixture cross = preflightFixture("1000", "1000", "9000", 3L);
    cross.position().setMarginMode(MarginMode.CROSS);
    assertCode("INVALID_MARGIN_MODE", () -> service().adjust(
        cross.userId(), cross.positionId(), request(Action.ADD, "1", 3L)));

    Fixture closed = preflightFixture("1000", "1000", "9000", 3L);
    closed.position().setStatus(PositionStatus.CLOSED);
    assertCode("POSITION_NOT_OPEN", () -> service().adjust(
        closed.userId(), closed.positionId(), request(Action.ADD, "1", 3L)));

    Fixture spot = preflightFixture("1000", "1000", "9000", 3L);
    spot.position().setProductType(ProductType.CRYPTO_SPOT);
    assertCode("PRODUCT_NOT_ALLOWED", () -> service().adjust(
        spot.userId(), spot.positionId(), request(Action.ADD, "1", 3L)));

    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verifyNoInteractions(ledgerService, eventPublisher);
  }

  @Test
  void rejectsNonPositiveOrOverScaleAmountBeforeResolvingMarketData() {
    UUID userId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();

    assertCode("MARGIN_ADJUSTMENT_INVALID", () -> service().adjust(
        userId, positionId, request(Action.ADD, "0", 0L)));
    assertCode("MARGIN_ADJUSTMENT_INVALID", () -> service().adjust(
        userId, positionId, request(Action.ADD, "0.123456789", 0L)));

    verifyNoInteractions(marketBundleResolver, accountRepository, positionRepository, ledgerService, eventPublisher);
  }

  @Test
  void ownershipIsCheckedBeforeResolvingAuthorityBundle() {
    UUID userId = UUID.randomUUID();
    PositionEntity position = position(UUID.randomUUID(), UUID.randomUUID(), "50000", "1000", 3L);
    when(positionRepository.findById(position.getId())).thenReturn(Optional.of(position));
    when(accountRepository.findByIdAndUserId(position.getAccountId(), userId)).thenReturn(Optional.empty());

    assertCode("ACCOUNT_NOT_FOUND", () -> service().adjust(
        userId, position.getId(), request(Action.ADD, "1", 3L)));

    verifyNoInteractions(marketBundleResolver, ledgerService, eventPublisher);
  }

  @Test
  void staleAuthorityBundleRetriesOutsideTheMutationAndPublishesOnlyTheSuccessfulAttempt() {
    Fixture fixture = fixture("50000", "1000", "1000", "9000", 3L);
    doThrow(new BusinessException(ErrorCode.MARKET_DATA_STALE, "stale"))
        .doNothing()
        .when(fullFillCoordinator).requireFresh(any(ExecutableMarketSnapshot.class));

    var response = service().adjust(
        fixture.userId(), fixture.positionId(), request(Action.ADD, "100", 3L));

    assertThat(response.positionMargin()).isEqualByComparingTo("1100.00000000");
    verify(marketBundleResolver, times(2)).resolvePerp(eq("BTCUSDT-PERP"), any());
    verify(accountRepository, times(2)).findByIdAndUserIdForUpdate(
        fixture.accountId(), fixture.userId());
    verify(positionRepository, times(1)).save(fixture.position());
    verify(ledgerService, times(1)).recordMarginHold(
        fixture.account(), new BigDecimal("100.00000000"), fixture.positionId(),
        "Isolated position margin added");
    verify(eventPublisher, times(1)).publishEvent(
        any(PositionMarginService.PositionMarginAdjustedEvent.class));
    InOrder order = org.mockito.Mockito.inOrder(marketBundleResolver, accountRepository);
    order.verify(marketBundleResolver).resolvePerp(eq("BTCUSDT-PERP"), any());
    order.verify(accountRepository).findByIdAndUserIdForUpdate(
        fixture.accountId(), fixture.userId());
    order.verify(marketBundleResolver).resolvePerp(eq("BTCUSDT-PERP"), any());
    order.verify(accountRepository).findByIdAndUserIdForUpdate(
        fixture.accountId(), fixture.userId());
  }

  @Test
  void staleResolverFailureAlsoRetriesBeforeEnteringTheMutation() {
    Fixture fixture = fixture("50000", "1000", "1000", "9000", 3L);
    when(marketBundleResolver.resolvePerp(eq("BTCUSDT-PERP"), any()))
        .thenThrow(new BusinessException(ErrorCode.MARKET_DATA_STALE, "resolver stale"))
        .thenReturn(bundle("50000"));

    var response = service().adjust(
        fixture.userId(), fixture.positionId(), request(Action.ADD, "100", 3L));

    assertThat(response.positionMargin()).isEqualByComparingTo("1100.00000000");
    verify(marketBundleResolver, times(2)).resolvePerp(eq("BTCUSDT-PERP"), any());
    verify(accountRepository, times(1)).findByIdAndUserIdForUpdate(
        fixture.accountId(), fixture.userId());
    verify(positionRepository, times(1)).save(fixture.position());
  }

  private Fixture fixture(
      String mark,
      String marginHeld,
      String usedMargin,
      String freeMargin,
      long version
  ) {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    TradingAccountEntity account = account(userId, accountId, usedMargin, freeMargin);
    PositionEntity position = position(positionId, accountId, "50000", marginHeld, version);
    SymbolEntity symbol = symbol();
    PerpetualMarketBundle bundle = bundle(mark);
    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId)).thenReturn(Optional.of(account));
    when(positionRepository.findOpenLinearPerpByAccountId(accountId)).thenReturn(List.of(position));
    when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of(position));
    when(orderRepository.findActiveLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of());
    when(symbolRepository.findBySymbol("BTCUSDT-PERP")).thenReturn(Optional.of(symbol));
    org.mockito.Mockito.lenient()
        .when(marketBundleResolver.resolvePerp(eq("BTCUSDT-PERP"), any()))
        .thenReturn(bundle);
    return new Fixture(userId, accountId, positionId, account, position);
  }

  private Fixture preflightFixture(
      String marginHeld,
      String usedMargin,
      String freeMargin,
      long version
  ) {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    TradingAccountEntity account = account(userId, accountId, usedMargin, freeMargin);
    PositionEntity position = position(positionId, accountId, "50000", marginHeld, version);
    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    return new Fixture(userId, accountId, positionId, account, position);
  }

  private PositionMarginService service() {
    return new PositionMarginService(
        accountRepository,
        positionRepository,
        orderRepository,
        symbolRepository,
        marketBundleResolver,
        demoExecutionGuard,
        new PerpetualRiskService(new PerpMarginCalculator()),
        accountRiskSnapshotService(),
        ledgerService,
        new TradingTransactionExecutor(),
        eventPublisher);
  }

  private PerpetualAccountRiskSnapshotService accountRiskSnapshotService() {
    return new PerpetualAccountRiskSnapshotService(
        positionRepository,
        symbolRepository,
        marketBundleResolver,
        fullFillCoordinator,
        new PerpetualRiskService(new PerpMarginCalculator()));
  }

  private static AdjustPositionMarginRequest request(Action action, String amount, long version) {
    return new AdjustPositionMarginRequest(action, new BigDecimal(amount), version);
  }

  private static TradingAccountEntity account(
      UUID userId,
      UUID accountId,
      String usedMargin,
      String freeMargin
  ) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    account.setBaseCurrency("USDT");
    account.setBalance(new BigDecimal("10000"));
    account.setEquity(new BigDecimal("10000"));
    account.setUsedMargin(new BigDecimal(usedMargin));
    account.setFreeMargin(new BigDecimal(freeMargin));
    return account;
  }

  private static PositionEntity position(
      UUID positionId,
      UUID accountId,
      String entry,
      String marginHeld,
      long version
  ) {
    PositionEntity position = new PositionEntity();
    position.setId(positionId);
    position.setAccountId(accountId);
    position.setSymbol("BTCUSDT-PERP");
    position.setProductType(ProductType.LINEAR_PERP);
    position.setPositionMode(PositionMode.ONE_WAY);
    position.setPositionSide(PositionSide.BOTH);
    position.setMarginMode(MarginMode.ISOLATED);
    position.setSide(OrderSide.BUY);
    position.setLots(new BigDecimal("0.2"));
    position.setOpenPrice(new BigDecimal(entry));
    position.setMarginHeld(new BigDecimal(marginHeld));
    position.setInitialMargin(new BigDecimal("1000"));
    position.setFundingPnl(BigDecimal.ZERO);
    position.setLeverage(10);
    position.setStatus(PositionStatus.OPEN);
    position.setVersion(version);
    return position;
  }

  private static PositionEntity crossPosition(
      UUID accountId,
      String symbol,
      String entry,
      String marginHeld,
      String quantity,
      long version
  ) {
    PositionEntity position = position(
        UUID.randomUUID(), accountId, entry, marginHeld, version);
    position.setSymbol(symbol);
    position.setMarginMode(MarginMode.CROSS);
    position.setLots(new BigDecimal(quantity));
    position.setInitialMargin(new BigDecimal(marginHeld));
    return position;
  }

  private static OrderEntity activeInternalOrder(
      UUID accountId,
      UUID parentPositionId,
      String hold
  ) {
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    order.setAccountId(accountId);
    order.setSymbol("BTCUSDT-PERP");
    order.setProductType(ProductType.LINEAR_PERP);
    order.setMarginMode(MarginMode.ISOLATED);
    order.setParentPositionId(parentPositionId);
    order.setHoldAmount(new BigDecimal(hold));
    order.setStatus(OrderStatus.PENDING);
    return order;
  }

  private static SymbolEntity symbol() {
    return symbol("BTCUSDT-PERP");
  }

  private static SymbolEntity symbol(String symbolName) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol(symbolName);
    symbol.setProductType(ProductType.LINEAR_PERP);
    symbol.setMaintenanceMarginRate(new BigDecimal("0.005"));
    symbol.setTradable(true);
    return symbol;
  }

  private static PerpetualMarketBundle bundle(String mark) {
    return bundle("BTCUSDT-PERP", mark);
  }

  private static PerpetualMarketBundle bundle(String symbol, String mark) {
    Instant asOf = Instant.parse("2026-07-12T08:00:00Z");
    return new PerpetualMarketBundle(
        symbol,
        symbol,
        "local-perp",
        MarketSourceMode.LOCAL_SIMULATED,
        new BigDecimal("49999"),
        new BigDecimal("50001"),
        new BigDecimal("50000"),
        new BigDecimal(mark),
        new BigDecimal(mark),
        null,
        List.of(),
        List.of(),
        asOf,
        asOf.plusSeconds(60));
  }

  private static void assertCode(String code, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(code));
  }

  private record Fixture(
      UUID userId,
      UUID accountId,
      UUID positionId,
      TradingAccountEntity account,
      PositionEntity position
  ) {
  }
}
