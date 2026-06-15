package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProtectiveOrderExecutionServiceTest {

  @Mock
  private PositionRepository positionRepository;

  @Mock
  private QuoteService quoteService;

  @Mock
  private PositionService positionService;

  @Test
  void closesBuyPositionWhenBidTouchesTakeProfit() {
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId, OrderSide.BUY);
    position.setTakeProfit(new BigDecimal("1.10100"));

    when(positionRepository.findByStatus(PositionStatus.OPEN)).thenReturn(List.of(position));
    when(quoteService.freshQuote("EURUSD")).thenReturn(quote(new BigDecimal("1.10100"), new BigDecimal("1.10104")));

    ProtectiveOrderExecutionService service = new ProtectiveOrderExecutionService(positionRepository, quoteService, positionService);

    int closed = service.executeProtectiveOrders();

    assertThat(closed).isEqualTo(1);
    verify(positionService).closeSystemPosition(accountId, positionId);
  }

  @Test
  void keepsSellPositionOpenWhenAskIsBetweenStopLossAndTakeProfit() {
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId, OrderSide.SELL);
    position.setStopLoss(new BigDecimal("1.10200"));
    position.setTakeProfit(new BigDecimal("1.09900"));

    when(positionRepository.findByStatus(PositionStatus.OPEN)).thenReturn(List.of(position));
    when(quoteService.freshQuote("EURUSD")).thenReturn(quote(new BigDecimal("1.10096"), new BigDecimal("1.10100")));

    ProtectiveOrderExecutionService service = new ProtectiveOrderExecutionService(positionRepository, quoteService, positionService);

    int closed = service.executeProtectiveOrders();

    assertThat(closed).isZero();
    verify(positionService, never()).closeSystemPosition(accountId, positionId);
  }

  @Test
  void skipsPositionWhenQuoteIsStale() {
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId, OrderSide.BUY);
    position.setTakeProfit(new BigDecimal("1.10100"));

    when(positionRepository.findByStatus(PositionStatus.OPEN)).thenReturn(List.of(position));
    when(quoteService.freshQuote("EURUSD")).thenThrow(new BusinessException("QUOTE_STALE", "Quote is stale"));

    ProtectiveOrderExecutionService service = new ProtectiveOrderExecutionService(positionRepository, quoteService, positionService);

    int closed = service.executeProtectiveOrders();

    assertThat(closed).isZero();
    verify(positionService, never()).closeSystemPosition(accountId, positionId);
  }

  @Test
  void continuesWhenTriggeredPositionWasAlreadyClosedByAnotherWorker() {
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId, OrderSide.BUY);
    position.setTakeProfit(new BigDecimal("1.10100"));

    when(positionRepository.findByStatus(PositionStatus.OPEN)).thenReturn(List.of(position));
    when(quoteService.freshQuote("EURUSD")).thenReturn(quote(new BigDecimal("1.10100"), new BigDecimal("1.10104")));
    when(positionService.closeSystemPosition(accountId, positionId))
        .thenThrow(new BusinessException("POSITION_NOT_OPEN", "Position is no longer open"));

    ProtectiveOrderExecutionService service = new ProtectiveOrderExecutionService(positionRepository, quoteService, positionService);

    int closed = service.executeProtectiveOrders();

    assertThat(closed).isZero();
    verify(positionService).closeSystemPosition(accountId, positionId);
  }

  private static PositionEntity openPosition(UUID accountId, UUID positionId, OrderSide side) {
    PositionEntity position = new PositionEntity();
    position.setId(positionId);
    position.setAccountId(accountId);
    position.setSymbol("EURUSD");
    position.setSide(side);
    position.setLots(new BigDecimal("0.10"));
    position.setOpenPrice(new BigDecimal("1.10020"));
    position.setStatus(PositionStatus.OPEN);
    return position;
  }

  private static QuoteResponse quote(BigDecimal bid, BigDecimal ask) {
    return new QuoteResponse("quote", "EURUSD", bid, ask, bid.add(ask).divide(new BigDecimal("2")), ask.subtract(bid), "test", 1780660000000L);
  }
}
