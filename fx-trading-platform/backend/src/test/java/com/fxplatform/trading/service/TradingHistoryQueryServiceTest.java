package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.dto.response.PositionResponse;
import com.fxplatform.trading.entity.FundingSettlementEntity;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.entity.SpotPositionEntity;
import com.fxplatform.trading.entity.TradeEntity;
import com.fxplatform.trading.enums.OrderStatus;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.FundingSettlementRepository;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.PositionRepository.ClosedPositionPageKey;
import com.fxplatform.trading.repository.SpotPositionRepository;
import com.fxplatform.trading.repository.TradeRepository;
import java.time.Instant;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

class TradingHistoryQueryServiceTest {

  private final TradingAccountRepository accountRepository =
      org.mockito.Mockito.mock(TradingAccountRepository.class);
  private final TradeRepository tradeRepository = org.mockito.Mockito.mock(TradeRepository.class);
  private final FundingSettlementRepository fundingSettlementRepository =
      org.mockito.Mockito.mock(FundingSettlementRepository.class);
  private final OrderRepository orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
  private final PositionRepository positionRepository =
      org.mockito.Mockito.mock(PositionRepository.class);
  private final SpotPositionRepository spotPositionRepository =
      org.mockito.Mockito.mock(SpotPositionRepository.class);
  private final OrderResponseMapper orderResponseMapper =
      org.mockito.Mockito.mock(OrderResponseMapper.class);
  private final PositionService positionService = org.mockito.Mockito.mock(PositionService.class);
  private final TradingHistoryQueryService service = new TradingHistoryQueryService(
      accountRepository,
      orderRepository,
      tradeRepository,
      positionRepository,
      spotPositionRepository,
      fundingSettlementRepository,
      orderResponseMapper,
      positionService);

  @Test
  void positionQueryKeepsExternalQuoteResolutionOutsideASpringTransaction() throws Exception {
    Method positions = TradingHistoryQueryService.class.getMethod(
        "positions",
        UUID.class,
        UUID.class,
        PositionStatus.class,
        String.class,
        int.class,
        int.class);

    assertThat(positions.getAnnotation(Transactional.class)).isNull();
  }

  @Test
  void orderHistoryChecksOwnershipBeforeFilteredDatabasePageAndClampsPageRequest() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = ownedAccount(userId, accountId);
    OrderEntity order = new OrderEntity();
    order.setId(UUID.randomUUID());
    OrderResponse mapped = org.mockito.Mockito.mock(OrderResponse.class);
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(orderRepository.findPageByAccountId(
        eq(accountId), eq(OrderStatus.FILLED), eq("BTCUSDT-PERP"), any(Page.class)))
        .thenAnswer(invocation -> page(invocation.getArgument(3), order, 101));
    when(orderResponseMapper.toResponse(order)).thenReturn(mapped);

    var result = service.orders(
        userId, accountId, OrderStatus.FILLED, " btcusdt-perp ", -2, 500);

    assertThat(result.items()).containsExactly(mapped);
    assertThat(result.page()).isZero();
    assertThat(result.size()).isEqualTo(100);
    var ordered = inOrder(accountRepository, orderRepository);
    ordered.verify(accountRepository).findByIdAndUserId(accountId, userId);
    ordered.verify(orderRepository).findPageByAccountId(
        eq(accountId), eq(OrderStatus.FILLED), eq("BTCUSDT-PERP"), any(Page.class));
  }

  @Test
  void openPositionsReuseRealtimeCrossAndSpotReadModelBeforeStablePaging() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    PositionResponse olderPerp = positionResponse(
        UUID.randomUUID(), "BTCUSDT-PERP", Instant.parse("2026-07-13T00:00:00Z"));
    PositionResponse newerSpot = positionResponse(
        UUID.randomUUID(), "ETHUSDT", Instant.parse("2026-07-13T01:00:00Z"));
    when(positionService.openPositions(userId, accountId))
        .thenReturn(List.of(olderPerp, newerSpot));

    var result = service.positions(
        userId, accountId, PositionStatus.OPEN, null, 0, 20);

    assertThat(result.items()).containsExactly(newerSpot, olderPerp);
    assertThat(result.total()).isEqualTo(2);
    verify(positionService).openPositions(userId, accountId);
    verify(positionRepository, never()).findClosedPageKeys(any(), any(), anyLong(), anyInt());
  }

  @Test
  void closedPositionsUseCrossTablePageKeysAndRetainSpotHistory() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = ownedAccount(userId, accountId);
    PositionEntity perp = new PositionEntity();
    perp.setId(UUID.randomUUID());
    SpotPositionEntity spot = new SpotPositionEntity();
    spot.setId(UUID.randomUUID());
    PositionResponse perpResponse = positionResponse(
        perp.getId(), "BTCUSDT-PERP", Instant.parse("2026-07-13T00:00:00Z"));
    PositionResponse spotResponse = positionResponse(
        spot.getId(), "ETHUSDT", Instant.parse("2026-07-13T01:00:00Z"));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(positionRepository.findClosedPageKeys(accountId, null, 0L, 20))
        .thenReturn(List.of(
            new ClosedPositionPageKey(spot.getId(), "SPOT", Instant.parse("2026-07-13T01:00:00Z")),
            new ClosedPositionPageKey(perp.getId(), "PERP", Instant.parse("2026-07-13T00:00:00Z"))));
    when(positionRepository.countClosedPageItems(accountId, null)).thenReturn(2L);
    when(positionRepository.findAllByIds(List.of(perp.getId()))).thenReturn(List.of(perp));
    when(spotPositionRepository.findAllByIds(List.of(spot.getId()))).thenReturn(List.of(spot));
    when(positionService.toStoredResponses(List.of(perp), account))
        .thenReturn(List.of(perpResponse));
    when(positionService.toClosedSpotResponses(List.of(spot)))
        .thenReturn(List.of(spotResponse));

    var result = service.positions(
        userId, accountId, PositionStatus.CLOSED, null, 0, 20);

    assertThat(result.items()).containsExactly(spotResponse, perpResponse);
    assertThat(result.total()).isEqualTo(2);
    verify(positionRepository).findAllByIds(List.of(perp.getId()));
    verify(spotPositionRepository).findAllByIds(List.of(spot.getId()));
  }

  @Test
  void tradeHistoryRequiresOwnershipBeforeQueryAndUsesBoundedZeroBasedPaging() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = ownedAccount(userId, accountId);
    TradeEntity trade = new TradeEntity();
    trade.setId(UUID.randomUUID());
    trade.setAccountId(accountId);
    trade.setExecutedAt(Instant.parse("2026-07-13T00:00:00Z"));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(tradeRepository.findPageByAccountId(eq(accountId), eq("BTCUSDT"), any(Page.class)))
        .thenAnswer(invocation -> page(invocation.getArgument(2), trade, 101));

    var result = service.trades(userId, accountId, " btcusdt ", -3, 500);

    assertThat(result.page()).isZero();
    assertThat(result.size()).isEqualTo(100);
    assertThat(result.total()).isEqualTo(101);
    assertThat(result.totalPages()).isEqualTo(2);
    assertThat(result.items()).singleElement().satisfies(item -> {
      assertThat(item.id()).isEqualTo(trade.getId());
      assertThat(item.accountId()).isEqualTo(accountId);
    });
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Page<TradeEntity>> pageCaptor = ArgumentCaptor.forClass(Page.class);
    verify(tradeRepository).findPageByAccountId(
        eq(accountId), eq("BTCUSDT"), pageCaptor.capture());
    assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
    assertThat(pageCaptor.getValue().getSize()).isEqualTo(100);
  }

  @Test
  void fundingHistoryRejectsAnotherUsersAccountWithoutRepositoryAccess() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.fundingSettlements(userId, accountId, null, 0, 20))
        .isInstanceOfSatisfying(AuthorizationException.class,
            error -> assertThat(error.getCode()).isEqualTo("ACCOUNT_NOT_FOUND"));

    verify(fundingSettlementRepository, never()).findPageByAccountId(any(), any(), any());
  }

  @Test
  void fundingHistoryMapsAStablePageWithoutExposingPersistenceEntities() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = ownedAccount(userId, accountId);
    FundingSettlementEntity settlement = new FundingSettlementEntity();
    settlement.setId(UUID.randomUUID());
    settlement.setAccountId(accountId);
    settlement.setFundingTime(Instant.parse("2026-07-13T00:00:00Z"));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(fundingSettlementRepository.findPageByAccountId(
        eq(accountId), eq("BTCUSDT-PERP"), any(Page.class)))
        .thenAnswer(invocation -> page(invocation.getArgument(2), settlement, 1));

    var result = service.fundingSettlements(
        userId, accountId, "BTCUSDT-PERP", 1, 10);

    assertThat(result.page()).isEqualTo(1);
    assertThat(result.size()).isEqualTo(10);
    assertThat(result.items()).singleElement().satisfies(item -> {
      assertThat(item.id()).isEqualTo(settlement.getId());
      assertThat(item.accountId()).isEqualTo(accountId);
    });
  }

  private static TradingAccountEntity ownedAccount(UUID userId, UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    return account;
  }

  private static PositionResponse positionResponse(UUID id, String symbol, Instant timestamp) {
    PositionResponse response = org.mockito.Mockito.mock(PositionResponse.class);
    when(response.id()).thenReturn(id);
    when(response.symbol()).thenReturn(symbol);
    when(response.openedAt()).thenReturn(timestamp);
    when(response.closedAt()).thenReturn(timestamp);
    return response;
  }

  private static <T> Page<T> page(Page<T> page, T record, long total) {
    page.setRecords(List.of(record));
    page.setTotal(total);
    return page;
  }
}
