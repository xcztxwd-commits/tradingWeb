package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.entity.SymbolEntity;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.market.repository.SymbolRepository;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.model.InstrumentKind;
import com.fxplatform.risk.service.PnLCalculator;
import com.fxplatform.trading.dto.request.ClosePositionRequest;
import com.fxplatform.trading.dto.request.UpdatePositionProtectionRequest;
import com.fxplatform.trading.dto.response.PositionResponse;
import com.fxplatform.trading.entity.OrderEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.entity.SpotPositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderOrigin;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.repository.PositionRepository;
import com.fxplatform.trading.repository.SpotPositionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PositionServiceTest {

  @Mock
  private PositionRepository positionRepository;

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private QuoteService quoteService;

  @Mock
  private PnLCalculator pnlCalculator;

  @Mock
  private LedgerService ledgerService;

  @Mock
  private SymbolRepository symbolRepository;

  @Mock
  private SpotPositionRepository spotPositionRepository;

  @Mock
  private DemoExecutionGuard demoExecutionGuard;

  @Mock
  private SystemCloseOrderService systemCloseOrderService;

  @BeforeEach
  void rowLockQueriesReturnTheSameFixtureRows() {
    lenient().when(accountRepository.findByIdAndUserIdForUpdate(any(UUID.class), any(UUID.class)))
        .thenAnswer(invocation -> accountRepository.findByIdAndUserId(
            invocation.getArgument(0), invocation.getArgument(1)));
    lenient().when(accountRepository.findByIdForUpdate(any(UUID.class)))
        .thenAnswer(invocation -> accountRepository.findById(invocation.getArgument(0)));
    lenient().when(positionRepository.findByIdForUpdate(any(UUID.class)))
        .thenAnswer(invocation -> positionRepository.findById(invocation.getArgument(0)));
  }

  @Test
  void openPositionsRefreshFloatingPnlFromLatestQuote() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId);
    BigDecimal floatingPnl = new BigDecimal("10.00000000");

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(quoteService.freshQuote("EURUSD")).thenReturn(new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.10120"),
        new BigDecimal("1.10124"),
        new BigDecimal("1.10122"),
        new BigDecimal("0.00004"),
        "test",
        1780660000000L));
    when(pnlCalculator.floatingPnl("EURUSD", "USD", OrderSide.BUY, new BigDecimal("0.10"), new BigDecimal("1.10020"), new BigDecimal("1.10120")))
        .thenReturn(floatingPnl);

    PositionService service = service();

    List<PositionResponse> responses = service.openPositions(userId, accountId);

    assertThat(responses).hasSize(1);
    assertThat(responses.get(0).currentPrice()).isEqualByComparingTo("1.10120");
    assertThat(responses.get(0).floatingPnl()).isEqualByComparingTo(floatingPnl);
    assertThat(responses.get(0).stopLoss()).isEqualByComparingTo("1.09000");
    assertThat(responses.get(0).takeProfit()).isEqualByComparingTo("1.12000");
    verify(positionRepository, never()).save(position);
  }

  @Test
  void openCryptoContractPositionUsesLinearDisplayPnl() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId);
    position.setSymbol("BTCUSDT");
    position.setLeverage(20);
    position.setLots(new BigDecimal("0.01"));
    position.setOpenPrice(new BigDecimal("63874.8"));
    position.setCurrentPrice(new BigDecimal("63874.8"));
    position.setMarginHeld(new BigDecimal("0.07"));
    position.setNotional(new BigDecimal("638.74800000"));
    position.setMaintenanceMargin(new BigDecimal("250.00000000"));

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol(
        "BTCUSDT",
        ProductType.LINEAR_PERP,
        "BTC",
        "USDT",
        BigDecimal.ONE,
        BigDecimal.ONE,
        20)));
    when(quoteService.freshQuote("BTCUSDT")).thenReturn(new QuoteResponse(
        "quote",
        "BTCUSDT",
        new BigDecimal("63876.8"),
        new BigDecimal("63877.0"),
        new BigDecimal("63876.9"),
        new BigDecimal("63876.9"),
        new BigDecimal("0.2"),
        "test",
        1780660000000L,
        null, null, null, null));

    PositionService service = service();

    List<PositionResponse> responses = service.openPositions(userId, accountId);

    assertThat(responses).hasSize(1);
    PositionResponse response = responses.get(0);
    assertThat(response.instrumentType()).isEqualTo("SWAP");
    assertThat(response.marginMode()).isEqualTo("CROSS");
    assertThat(response.leverage()).isEqualTo(20);
    assertThat(response.positionUnit()).isEqualTo("CONTRACT");
    assertThat(response.markPrice()).isEqualByComparingTo("63876.9");
    assertThat(response.notional()).isEqualByComparingTo("638.76900000");
    assertThat(response.liquidationPrice()).isEqualByComparingTo("0.00000000");
    assertThat(response.maintenanceMargin()).isEqualByComparingTo("3.19384500");
    assertThat(response.floatingPnl()).isEqualByComparingTo("0.02100000");
    assertThat(response.floatingPnlRatio()).isEqualByComparingTo("0.30000000");
    verify(pnlCalculator, never()).floatingPnl(eq(OrderSide.BUY), eq(new BigDecimal("0.01")), any(), any());
  }

  @Test
  void openSpotCryptoPositionUsesSymbolMetadataForSpotDisplayPnl() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId);
    position.setSymbol("BTCUSDT");
    position.setLeverage(20);
    position.setLots(new BigDecimal("0.01"));
    position.setOpenPrice(new BigDecimal("63874.8"));
    position.setCurrentPrice(new BigDecimal("63874.8"));
    position.setMarginHeld(new BigDecimal("638.74800000"));

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol(
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        "BTC",
        "USDT",
        BigDecimal.ONE,
        BigDecimal.ONE,
        1)));
    when(quoteService.freshQuote("BTCUSDT")).thenReturn(new QuoteResponse(
        "quote",
        "BTCUSDT",
        new BigDecimal("63876.8"),
        new BigDecimal("63877.0"),
        new BigDecimal("63876.9"),
        new BigDecimal("0.2"),
        "test",
        1780660000000L));
    when(pnlCalculator.floatingPnl(
        InstrumentKind.SPOT,
        OrderSide.BUY,
        new BigDecimal("0.01"),
        new BigDecimal("63874.8"),
        new BigDecimal("63876.8"),
        BigDecimal.ONE))
        .thenReturn(new BigDecimal("0.02000000"));

    PositionService service = service();

    List<PositionResponse> responses = service.openPositions(userId, accountId);

    assertThat(responses).hasSize(1);
    PositionResponse response = responses.get(0);
    assertThat(response.instrumentType()).isEqualTo("SPOT");
    assertThat(response.marginMode()).isEqualTo("CASH");
    assertThat(response.leverage()).isNull();
    assertThat(response.positionUnit()).isEqualTo("BTC");
    assertThat(response.liquidationPrice()).isNull();
    assertThat(response.floatingPnl()).isEqualByComparingTo("0.020");
    verify(pnlCalculator).floatingPnl(
        eq(InstrumentKind.SPOT),
        eq(OrderSide.BUY),
        eq(new BigDecimal("0.01")),
        eq(new BigDecimal("63874.8")),
        eq(new BigDecimal("63876.8")),
        eq(BigDecimal.ONE));
  }

  @Test
  void openCryptoSpotPositionHasNoLeverageOrLiquidationPrice() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId);
    position.setSymbol("BTCUSDT");
    position.setLeverage(50);
    position.setLots(new BigDecimal("0.1998"));
    position.setOpenPrice(new BigDecimal("50050.05005"));
    position.setCurrentPrice(new BigDecimal("50050.05005"));
    position.setMarginHeld(new BigDecimal("10000.00000000"));

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol(
        "BTCUSDT",
        ProductType.CRYPTO_SPOT,
        "BTC",
        "USDT",
        BigDecimal.ONE,
        BigDecimal.ONE,
        1)));
    when(quoteService.freshQuote("BTCUSDT")).thenReturn(new QuoteResponse(
        "quote",
        "BTCUSDT",
        new BigDecimal("54999.8"),
        new BigDecimal("55000.2"),
        new BigDecimal("55000.0"),
        new BigDecimal("0.4"),
        "test",
        1780660000000L));
    when(pnlCalculator.floatingPnl(
        InstrumentKind.SPOT,
        OrderSide.BUY,
        new BigDecimal("0.1998"),
        new BigDecimal("50050.05005"),
        new BigDecimal("54999.8"),
        BigDecimal.ONE))
        .thenReturn(new BigDecimal("989.06000000"));

    PositionService service = service();

    List<PositionResponse> responses = service.openPositions(userId, accountId);

    assertThat(responses).hasSize(1);
    PositionResponse response = responses.get(0);
    assertThat(response.instrumentType()).isEqualTo("SPOT");
    assertThat(response.marginMode()).isEqualTo("CASH");
    assertThat(response.leverage()).isNull();
    assertThat(response.positionUnit()).isEqualTo("BTC");
    assertThat(response.liquidationPrice()).isNull();
    assertThat(response.floatingPnl()).isEqualByComparingTo("989.06000000");
  }

  @Test
  void openInverseContractPositionUsesReciprocalDisplayPnl() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId);
    position.setSymbol("BTCUSD");
    position.setLeverage(10);
    position.setLots(new BigDecimal("100"));
    position.setOpenPrice(new BigDecimal("50000"));
    position.setCurrentPrice(new BigDecimal("50000"));
    position.setMarginHeld(new BigDecimal("0.02000000"));

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("BTCUSD")).thenReturn(Optional.of(symbol(
        "BTCUSD",
        ProductType.INVERSE_PERP,
        "BTC",
        "USD",
        BigDecimal.ONE,
        new BigDecimal("100"),
        10)));
    when(quoteService.freshQuote("BTCUSD")).thenReturn(new QuoteResponse(
        "quote",
        "BTCUSD",
        new BigDecimal("55000"),
        new BigDecimal("55010"),
        new BigDecimal("55005"),
        new BigDecimal("10"),
        "test",
        1780660000000L));
    when(pnlCalculator.floatingPnl(
        InstrumentKind.INVERSE_PERPETUAL,
        OrderSide.BUY,
        new BigDecimal("100"),
        new BigDecimal("50000"),
        new BigDecimal("55005"),
        new BigDecimal("100")))
        .thenReturn(new BigDecimal("0.01818182"));

    PositionService service = service();

    List<PositionResponse> responses = service.openPositions(userId, accountId);

    assertThat(responses).hasSize(1);
    PositionResponse response = responses.get(0);
    assertThat(response.instrumentType()).isEqualTo("SWAP");
    assertThat(response.positionUnit()).isEqualTo("CONTRACT");
    assertThat(response.currentPrice()).isEqualByComparingTo("55000");
    assertThat(response.markPrice()).isEqualByComparingTo("55005");
    assertThat(response.liquidationPrice()).isEqualByComparingTo("45454.54545455");
    assertThat(response.floatingPnl()).isEqualByComparingTo("0.01818182");
    assertThat(response.floatingPnlRatio()).isEqualByComparingTo("0.90909100");
  }

  @Test
  void openPositionsRejectsSymbolWithoutExplicitProductType() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId);
    position.setSymbol("BTCUSDT");
    position.setLeverage(1);

    SymbolEntity symbol = symbol(
        "BTCUSDT",
        null,
        "BTC",
        "USDT",
        BigDecimal.ONE,
        BigDecimal.ONE,
        20);

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.of(symbol));
    when(quoteService.freshQuote("BTCUSDT")).thenReturn(new QuoteResponse(
        "quote",
        "BTCUSDT",
        new BigDecimal("63876.8"),
        new BigDecimal("63877.0"),
        new BigDecimal("63876.9"),
        new BigDecimal("0.2"),
        "test",
        1780660000000L));

    PositionService service = service();

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.openPositions(userId, accountId))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("product type");
  }

  @Test
  void positionHistoryReturnsClosedPositionsWithoutRefreshingQuotes() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId);
    position.setStatus(PositionStatus.CLOSED);
    position.setFloatingPnl(BigDecimal.ZERO);
    position.setRealizedPnl(new BigDecimal("15.00000000"));
    position.setFundingPnl(new BigDecimal("-0.10000000"));

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.CLOSED))
        .thenReturn(List.of(position));

    PositionService service = service();

    List<PositionResponse> responses = service.positionHistory(userId, accountId);

    assertThat(responses).hasSize(1);
    assertThat(responses.get(0).status()).isEqualTo("CLOSED");
    assertThat(responses.get(0).realizedPnl()).isEqualByComparingTo("15.00000000");
    assertThat(responses.get(0).fundingPnl()).isEqualByComparingTo("-0.10000000");
    assertThat(responses.get(0).stopLoss()).isEqualByComparingTo("1.09000");
    assertThat(responses.get(0).takeProfit()).isEqualByComparingTo("1.12000");
    verify(quoteService, never()).freshQuote(any());
  }

  @Test
  void openPositionsIncludesSpotWalletHoldings() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    SpotPositionEntity spotPosition = spotPosition(accountId, "BTC", "USDT");
    spotPosition.setQuantity(new BigDecimal("0.19980000"));
    spotPosition.setAverageCost(new BigDecimal("50050.05005000"));

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of());
    when(spotPositionRepository.findOpenByAccountId(accountId)).thenReturn(List.of(spotPosition));
    when(quoteService.freshQuote("BTCUSDT")).thenReturn(new QuoteResponse(
        "quote",
        "BTCUSDT",
        new BigDecimal("55000.00000000"),
        new BigDecimal("55000.20000000"),
        new BigDecimal("55000.10000000"),
        new BigDecimal("0.20000000"),
        "test",
        1780660000000L));
    when(pnlCalculator.floatingPnl(
        InstrumentKind.SPOT,
        OrderSide.BUY,
        new BigDecimal("0.19980000"),
        new BigDecimal("50050.05005000"),
        new BigDecimal("55000.00000000"),
        BigDecimal.ONE))
        .thenReturn(new BigDecimal("989.00000000"));

    PositionService service = service();

    List<PositionResponse> responses = service.openPositions(userId, accountId);

    assertThat(responses).hasSize(1);
    PositionResponse response = responses.get(0);
    assertThat(response.id()).isEqualTo(spotPosition.getId());
    assertThat(response.symbol()).isEqualTo("BTCUSDT");
    assertThat(response.side()).isEqualTo("BUY");
    assertThat(response.instrumentType()).isEqualTo("SPOT");
    assertThat(response.marginMode()).isEqualTo("CASH");
    assertThat(response.leverage()).isNull();
    assertThat(response.positionUnit()).isEqualTo("BTC");
    assertThat(response.lots()).isEqualByComparingTo("0.19980000");
    assertThat(response.openPrice()).isEqualByComparingTo("50050.05005000");
    assertThat(response.markPrice()).isEqualByComparingTo("55000.10000000");
    assertThat(response.currentPrice()).isEqualByComparingTo("55000.00000000");
    assertThat(response.floatingPnl()).isEqualByComparingTo("989.00000000");
    assertThat(response.marginHeld()).isEqualByComparingTo("9999.99999999");
    assertThat(response.maintenanceMargin()).isNull();
    assertThat(response.status()).isEqualTo("OPEN");
  }

  @Test
  void positionHistoryIncludesClosedSpotWalletHoldingsWithoutRefreshingQuotes() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    SpotPositionEntity spotPosition = spotPosition(accountId, "ETH", "USDT");
    spotPosition.setQuantity(BigDecimal.ZERO);
    spotPosition.setAverageCost(new BigDecimal("3200.00000000"));
    spotPosition.setRealizedPnl(new BigDecimal("125.00000000"));
    spotPosition.setUpdatedAt(Instant.parse("2026-06-16T10:00:00Z"));

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.CLOSED))
        .thenReturn(List.of());
    when(spotPositionRepository.findClosedWithRealizedPnlByAccountId(accountId)).thenReturn(List.of(spotPosition));

    PositionService service = service();

    List<PositionResponse> responses = service.positionHistory(userId, accountId);

    assertThat(responses).hasSize(1);
    PositionResponse response = responses.get(0);
    assertThat(response.id()).isEqualTo(spotPosition.getId());
    assertThat(response.symbol()).isEqualTo("ETHUSDT");
    assertThat(response.instrumentType()).isEqualTo("SPOT");
    assertThat(response.marginMode()).isEqualTo("CASH");
    assertThat(response.lots()).isEqualByComparingTo("0");
    assertThat(response.openPrice()).isEqualByComparingTo("3200.00000000");
    assertThat(response.currentPrice()).isEqualByComparingTo("3200.00000000");
    assertThat(response.floatingPnl()).isEqualByComparingTo("0");
    assertThat(response.realizedPnl()).isEqualByComparingTo("125.00000000");
    assertThat(response.status()).isEqualTo("CLOSED");
    assertThat(response.closedAt()).isEqualTo(Instant.parse("2026-06-16T10:00:00Z"));
    verify(quoteService, never()).freshQuote(any());
  }

  @Test
  void updatePositionProtectionChangesStopLossAndTakeProfitForOwnedOpenPosition() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId);

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));
    when(quoteService.freshQuote("EURUSD")).thenReturn(new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.10120"),
        new BigDecimal("1.10124"),
        new BigDecimal("1.10122"),
        new BigDecimal("0.00004"),
        "test",
        1780660000000L));

    PositionService service = service();

    PositionResponse response = service.updateProtection(userId, accountId, positionId, new UpdatePositionProtectionRequest(
        new BigDecimal("1.09500"),
        new BigDecimal("1.12500")));

    assertThat(response.stopLoss()).isEqualByComparingTo("1.09500");
    assertThat(response.takeProfit()).isEqualByComparingTo("1.12500");
    assertThat(position.getStopLoss()).isEqualByComparingTo("1.09500");
    assertThat(position.getTakeProfit()).isEqualByComparingTo("1.12500");
    verify(positionRepository).save(position);
  }

  @Test
  void updatePositionProtectionRejectsBuyPricesInWrongDirection() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId);

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));

    PositionService service = service();

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.updateProtection(
            userId,
            accountId,
            positionId,
            new UpdatePositionProtectionRequest(new BigDecimal("1.12500"), new BigDecimal("1.09500"))))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Buy stop loss must be below take profit");

    verify(positionRepository, never()).save(any());
  }

  @Test
  void updatePositionProtectionRejectsSellPricesInWrongDirection() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId);
    position.setSide(OrderSide.SELL);
    position.setStopLoss(new BigDecimal("1.12000"));
    position.setTakeProfit(new BigDecimal("1.09000"));

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));

    PositionService service = service();

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.updateProtection(
            userId,
            accountId,
            positionId,
            new UpdatePositionProtectionRequest(new BigDecimal("1.09500"), new BigDecimal("1.12500"))))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Sell stop loss must be above take profit");

    verify(positionRepository, never()).save(any());
  }

  @Test
  void updatePositionProtectionRejectsImmediateBuyTriggerByDefault() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId);

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));
    when(quoteService.freshQuote("EURUSD")).thenReturn(new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.10120"),
        new BigDecimal("1.10124"),
        new BigDecimal("1.10122"),
        new BigDecimal("0.00004"),
        "test",
        1780660000000L));

    PositionService service = service();

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.updateProtection(
            userId,
            accountId,
            positionId,
            new UpdatePositionProtectionRequest(new BigDecimal("1.10120"), new BigDecimal("1.12500"))))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Stop loss would trigger immediately");

    verify(positionRepository, never()).save(any());
  }

  @Test
  void updatePositionProtectionAllowsImmediateTriggerWhenExplicitlyRequested() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId);

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));

    PositionService service = service();

    PositionResponse response = service.updateProtection(
        userId,
        accountId,
        positionId,
        new UpdatePositionProtectionRequest(new BigDecimal("1.10120"), new BigDecimal("1.12500"), true));

    assertThat(response.stopLoss()).isEqualByComparingTo("1.10120");
    verify(positionRepository).save(position);
  }

  @Test
  void updatePositionProtectionRejectsDifferentAccount() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(UUID.randomUUID(), positionId);

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));

    PositionService service = service();

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.updateProtection(
            userId,
            accountId,
            positionId,
            new UpdatePositionProtectionRequest(new BigDecimal("1.09500"), new BigDecimal("1.12500"))))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("Position does not belong to account");

    verify(positionRepository, never()).save(any());
  }

  @Test
  void closePositionReleasesOnlyThisPositionMarginAndWritesLedger() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId);
    TradingAccountEntity account = account(userId, accountId);
    BigDecimal realizedPnl = new BigDecimal("10.00000000");
    BigDecimal marginHeld = new BigDecimal("110.02000000");

    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(quoteService.freshQuote("EURUSD")).thenReturn(new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.10120"),
        new BigDecimal("1.10124"),
        new BigDecimal("1.10122"),
        new BigDecimal("0.00004"),
        "test",
        1780660000000L));
    when(pnlCalculator.floatingPnl("EURUSD", "USD", OrderSide.BUY, new BigDecimal("0.10"), new BigDecimal("1.10020"), new BigDecimal("1.10120")))
        .thenReturn(realizedPnl);
    when(positionRepository.closeIfOpen(position)).thenReturn(1);

    PositionService service = service();

    PositionResponse response = service.closePosition(userId, accountId, positionId);

    assertThat(response.status()).isEqualTo(PositionStatus.CLOSED.name());
    assertThat(response.realizedPnl()).isEqualByComparingTo(realizedPnl);
    assertThat(response.marginHeld()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(account.getBalance()).isEqualByComparingTo("10010.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("189.98000000");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("9820.02000000");
    assertThat(position.getMarginHeld()).isEqualByComparingTo(BigDecimal.ZERO);

    verify(positionRepository).closeIfOpen(position);
    verify(accountRepository).save(account);
    verify(ledgerService).recordMarginRelease(eq(account), eq(marginHeld), eq(positionId), eq("Position margin released"));
    verify(ledgerService).recordTradePnl(eq(account), eq(realizedPnl), eq(positionId), eq("Position closed"));
  }

  @Test
  void linearPerpetualPartialCloseDelegatesToCanonicalSystemClosePath() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    TradingAccountEntity account = account(userId, accountId);
    PositionEntity position = openPosition(accountId, positionId);
    position.setSymbol("BTCUSDT-PERP");
    position.setProductType(ProductType.LINEAR_PERP);
    position.setLots(new BigDecimal("1.0"));
    PositionEntity reduced = openPosition(accountId, positionId);
    reduced.setSymbol("BTCUSDT-PERP");
    reduced.setProductType(ProductType.LINEAR_PERP);
    reduced.setLots(new BigDecimal("0.4"));
    ClosePositionRequest request = new ClosePositionRequest(
        new BigDecimal("0.6"),
        QuantityUnit.BASE,
        "partial-close-1");
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));
    when(symbolRepository.findBySymbol("BTCUSDT-PERP")).thenReturn(Optional.of(symbol(
        "BTCUSDT-PERP",
        ProductType.LINEAR_PERP,
        "BTC",
        "USDT",
        BigDecimal.ONE,
        BigDecimal.ONE,
        100)));
    when(systemCloseOrderService.closeUser(userId, accountId, positionId, request))
        .thenReturn(new SystemCloseOrderService.CloseResult(
            new OrderEntity(),
            reduced,
            account,
            false));

    PositionResponse response = service().closePosition(
        userId,
        accountId,
        positionId,
        request);

    assertThat(response.lots()).isEqualByComparingTo("0.4");
    verify(systemCloseOrderService).closeUser(userId, accountId, positionId, request);
    verify(quoteService, never()).freshQuote(any());
    verify(positionRepository, never()).closeIfOpen(any());
    verify(ledgerService, never()).recordMarginRelease(any(), any(), any(), any());
    verify(ledgerService, never()).recordTradePnl(any(), any(), any(), any());
  }

  @Test
  void linearPerpetualNullCloseDelegatesWholeIntentWithoutSnapshottingQuantity() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    TradingAccountEntity account = account(userId, accountId);
    PositionEntity position = openPosition(accountId, positionId);
    position.setSymbol("BTCUSDT-PERP");
    position.setProductType(ProductType.LINEAR_PERP);
    position.setLots(new BigDecimal("1.0"));
    PositionEntity closed = openPosition(accountId, positionId);
    closed.setSymbol("BTCUSDT-PERP");
    closed.setProductType(ProductType.LINEAR_PERP);
    closed.setLots(BigDecimal.ZERO);
    closed.setStatus(PositionStatus.CLOSED);
    String idempotencyKey = "position-close-" + positionId;
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));
    when(symbolRepository.findBySymbol("BTCUSDT-PERP")).thenReturn(Optional.of(symbol(
        "BTCUSDT-PERP",
        ProductType.LINEAR_PERP,
        "BTC",
        "USDT",
        BigDecimal.ONE,
        BigDecimal.ONE,
        100)));
    when(systemCloseOrderService.closeUserWhole(
        userId, accountId, positionId, idempotencyKey))
        .thenReturn(new SystemCloseOrderService.CloseResult(
            new OrderEntity(), closed, account, false));

    PositionResponse response = service().closePosition(userId, accountId, positionId);

    assertThat(response.status()).isEqualTo(PositionStatus.CLOSED.name());
    verify(systemCloseOrderService).closeUserWhole(
        userId, accountId, positionId, idempotencyKey);
    verify(systemCloseOrderService, never()).closeUser(
        any(), any(), any(), any(ClosePositionRequest.class));
  }

  @Test
  void linearPerpetualPositionWithFxSymbolMetadataFailsClosedBeforeLegacySettlement() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    TradingAccountEntity account = account(userId, accountId);
    PositionEntity position = openPosition(accountId, positionId);
    position.setSymbol("BTCUSDT-PERP");
    position.setProductType(ProductType.LINEAR_PERP);
    SymbolEntity drifted = symbol(
        "BTCUSDT-PERP",
        ProductType.FX_MARGIN,
        "BTC",
        "USDT",
        BigDecimal.ONE,
        BigDecimal.ONE,
        100);
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));
    when(symbolRepository.findBySymbol("BTCUSDT-PERP")).thenReturn(Optional.of(drifted));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service().closePosition(userId, accountId, positionId))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("INVALID_INSTRUMENT_RULES"));

    verify(systemCloseOrderService, never()).closeUserWhole(any(), any(), any(), any());
    verify(quoteService, never()).freshQuote(any());
    verify(positionRepository, never()).closeIfOpen(any());
    verify(ledgerService, never()).recordTradePnl(any(), any(), any(), any());
  }

  @Test
  void p0PerpetualSymbolWithBothMetadataRowsMisclassifiedFailsClosed() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    TradingAccountEntity account = account(userId, accountId);
    PositionEntity position = openPosition(accountId, positionId);
    position.setSymbol("BTCUSDT-PERP");
    position.setProductType(ProductType.FX_MARGIN);
    SymbolEntity drifted = symbol(
        "BTCUSDT-PERP",
        ProductType.FX_MARGIN,
        "BTC",
        "USDT",
        BigDecimal.ONE,
        BigDecimal.ONE,
        100);
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));
    when(symbolRepository.findBySymbol("BTCUSDT-PERP")).thenReturn(Optional.of(drifted));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service().closePosition(userId, accountId, positionId))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo("INVALID_INSTRUMENT_RULES"));

    verify(systemCloseOrderService, never()).closeUserWhole(any(), any(), any(), any());
    verify(quoteService, never()).freshQuote(any());
    verify(accountRepository, never()).findByIdAndUserIdForUpdate(any(), any());
    verify(positionRepository, never()).closeIfOpen(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordMarginRelease(any(), any(), any(), any());
    verify(ledgerService, never()).recordTradePnl(any(), any(), any(), any());
  }

  @Test
  void linearPerpetualSystemFacadesMapAdminAndLiquidationMetadata() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID adminPositionId = UUID.randomUUID();
    UUID liquidationPositionId = UUID.randomUUID();
    TradingAccountEntity account = account(userId, accountId);
    PositionEntity adminPosition = openPosition(accountId, adminPositionId);
    adminPosition.setSymbol("BTCUSDT-PERP");
    adminPosition.setProductType(ProductType.LINEAR_PERP);
    PositionEntity liquidationPosition = openPosition(accountId, liquidationPositionId);
    liquidationPosition.setSymbol("BTCUSDT-PERP");
    liquidationPosition.setProductType(ProductType.LINEAR_PERP);
    PositionEntity adminClosed = openPosition(accountId, adminPositionId);
    adminClosed.setSymbol("BTCUSDT-PERP");
    adminClosed.setProductType(ProductType.LINEAR_PERP);
    adminClosed.setStatus(PositionStatus.CLOSED);
    PositionEntity liquidationClosed = openPosition(accountId, liquidationPositionId);
    liquidationClosed.setSymbol("BTCUSDT-PERP");
    liquidationClosed.setProductType(ProductType.LINEAR_PERP);
    liquidationClosed.setStatus(PositionStatus.CLOSED);
    SymbolEntity perpetual = symbol(
        "BTCUSDT-PERP",
        ProductType.LINEAR_PERP,
        "BTC",
        "USDT",
        BigDecimal.ONE,
        BigDecimal.ONE,
        100);
    when(positionRepository.findById(adminPositionId)).thenReturn(Optional.of(adminPosition));
    when(positionRepository.findById(liquidationPositionId))
        .thenReturn(Optional.of(liquidationPosition));
    when(symbolRepository.findBySymbol("BTCUSDT-PERP")).thenReturn(Optional.of(perpetual));
    when(systemCloseOrderService.closeWhole(
        accountId,
        adminPositionId,
        OrderOrigin.ADMIN_FORCE_CLOSE,
        "ADMIN_FORCE_CLOSE",
        "system-close-admin_force_close-" + adminPositionId))
        .thenReturn(new SystemCloseOrderService.CloseResult(
            new OrderEntity(), adminClosed, account, false));
    when(systemCloseOrderService.closeWhole(
        accountId,
        liquidationPositionId,
        OrderOrigin.LIQUIDATION,
        "maintenance breach",
        "system-close-liquidation-" + liquidationPositionId))
        .thenReturn(new SystemCloseOrderService.CloseResult(
            new OrderEntity(), liquidationClosed, account, false));

    PositionService facade = service();
    facade.closeSystemPosition(accountId, adminPositionId);
    facade.closeSystemPosition(accountId, liquidationPositionId, "  maintenance breach  ");

    verify(systemCloseOrderService).closeWhole(
        accountId,
        adminPositionId,
        OrderOrigin.ADMIN_FORCE_CLOSE,
        "ADMIN_FORCE_CLOSE",
        "system-close-admin_force_close-" + adminPositionId);
    verify(systemCloseOrderService).closeWhole(
        accountId,
        liquidationPositionId,
        OrderOrigin.LIQUIDATION,
        "maintenance breach",
        "system-close-liquidation-" + liquidationPositionId);
    verify(quoteService, never()).freshQuote(any());
    verify(positionRepository, never()).closeIfOpen(any());
  }

  @Test
  void closePositionFallsBackForNullAccountAmountsAndPositionMargin() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId);
    position.setMarginHeld(null);
    TradingAccountEntity account = account(userId, accountId);
    account.setEquity(null);
    account.setUsedMargin(null);
    account.setFreeMargin(null);
    BigDecimal realizedPnl = new BigDecimal("10.00000000");

    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(quoteService.freshQuote("EURUSD")).thenReturn(new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.10120"),
        new BigDecimal("1.10124"),
        new BigDecimal("1.10122"),
        new BigDecimal("0.00004"),
        "test",
        1780660000000L));
    when(pnlCalculator.floatingPnl("EURUSD", "USD", OrderSide.BUY, new BigDecimal("0.10"), new BigDecimal("1.10020"), new BigDecimal("1.10120")))
        .thenReturn(realizedPnl);
    when(positionRepository.closeIfOpen(position)).thenReturn(1);

    PositionService service = service();

    PositionResponse response = service.closePosition(userId, accountId, positionId);

    assertThat(response.status()).isEqualTo(PositionStatus.CLOSED.name());
    assertThat(response.marginHeld()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(account.getBalance()).isEqualByComparingTo("10010.00000000");
    assertThat(account.getEquity()).isEqualByComparingTo("10010.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(account.getFreeMargin()).isEqualByComparingTo("10010.00000000");
    verify(ledgerService).recordMarginRelease(eq(account), eq(BigDecimal.ZERO), eq(positionId), eq("Position margin released"));
    verify(ledgerService).recordTradePnl(eq(account), eq(realizedPnl), eq(positionId), eq("Position closed"));
  }

  @Test
  void closePositionRejectsAlreadyClosedPositionWithoutWritingLedger() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId);
    position.setStatus(PositionStatus.CLOSED);

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));

    PositionService service = service();

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.closePosition(userId, accountId, positionId))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Only open positions can be closed");

    verify(quoteService, never()).freshQuote(any());
    verify(positionRepository, never()).closeIfOpen(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordMarginRelease(any(), any(), any(), any());
    verify(ledgerService, never()).recordTradePnl(any(), any(), any(), any());
  }

  @Test
  void closePositionRejectsWhenOpenPositionWasAlreadyClaimed() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId);
    TradingAccountEntity account = account(userId, accountId);

    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(quoteService.freshQuote("EURUSD")).thenReturn(new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.10120"),
        new BigDecimal("1.10124"),
        new BigDecimal("1.10122"),
        new BigDecimal("0.00004"),
        "test",
        1780660000000L));
    when(pnlCalculator.floatingPnl("EURUSD", "USD", OrderSide.BUY, new BigDecimal("0.10"), new BigDecimal("1.10020"), new BigDecimal("1.10120")))
        .thenReturn(new BigDecimal("10.00000000"));
    when(positionRepository.closeIfOpen(position)).thenReturn(0);

    PositionService service = service();

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.closePosition(userId, accountId, positionId))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Position is no longer open");

    verify(positionRepository).closeIfOpen(position);
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordMarginRelease(any(), any(), any(), any());
    verify(ledgerService, never()).recordTradePnl(any(), any(), any(), any());
  }

  @Test
  void openPositionsRejectsAccountThatDoesNotBelongToUser() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.empty());

    PositionService service = service();

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.openPositions(userId, accountId))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("Account not found");

    verify(positionRepository, never()).findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN);
  }

  @Test
  void closePositionRejectsAccountThatDoesNotBelongToUser() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.empty());

    PositionService service = service();

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.closePosition(userId, accountId, positionId))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("Account not found");

    verify(positionRepository, never()).findById(positionId);
    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
  }

  @Test
  void closePositionRejectsPositionFromDifferentAccount() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(UUID.randomUUID(), positionId);

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));

    PositionService service = service();

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.closePosition(userId, accountId, positionId))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("Position does not belong to account");

    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordMarginRelease(any(), any(), any(), any());
    verify(ledgerService, never()).recordTradePnl(any(), any(), any(), any());
  }

  @Test
  void closeSystemPositionClosesUsingAccountIdOnly() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId);
    TradingAccountEntity account = account(userId, accountId);
    BigDecimal realizedPnl = new BigDecimal("10.00000000");
    BigDecimal marginHeld = new BigDecimal("110.02000000");

    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));
    when(quoteService.freshQuote("EURUSD")).thenReturn(new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.10120"),
        new BigDecimal("1.10124"),
        new BigDecimal("1.10122"),
        new BigDecimal("0.00004"),
        "test",
        1780660000000L));
    when(pnlCalculator.floatingPnl("EURUSD", "USD", OrderSide.BUY, new BigDecimal("0.10"), new BigDecimal("1.10020"), new BigDecimal("1.10120")))
        .thenReturn(realizedPnl);
    when(positionRepository.closeIfOpen(position)).thenReturn(1);

    PositionService service = service();

    PositionResponse response = service.closeSystemPosition(accountId, positionId);

    assertThat(response.status()).isEqualTo(PositionStatus.CLOSED.name());
    assertThat(response.realizedPnl()).isEqualByComparingTo(realizedPnl);
    assertThat(response.marginHeld()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(account.getBalance()).isEqualByComparingTo("10010.00000000");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("189.98000000");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("9820.02000000");
    assertThat(position.getMarginHeld()).isEqualByComparingTo(BigDecimal.ZERO);

    verify(accountRepository, never()).findByIdAndUserId(any(), any());
    verify(positionRepository).closeIfOpen(position);
    verify(accountRepository).save(account);
    verify(ledgerService).recordMarginRelease(eq(account), eq(marginHeld), eq(positionId), eq("Position margin released"));
    verify(ledgerService).recordTradePnl(eq(account), eq(realizedPnl), eq(positionId), eq("Position closed"));
  }

  @Test
  void closeSystemPositionWithReasonWritesForcedCloseLedger() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, positionId);
    TradingAccountEntity account = account(userId, accountId);
    BigDecimal realizedPnl = new BigDecimal("-25.00000000");

    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));
    when(quoteService.freshQuote("EURUSD")).thenReturn(new QuoteResponse(
        "quote",
        "EURUSD",
        new BigDecimal("1.09770"),
        new BigDecimal("1.09774"),
        new BigDecimal("1.09772"),
        new BigDecimal("0.00004"),
        "test",
        1780660000000L));
    when(pnlCalculator.floatingPnl("EURUSD", "USD", OrderSide.BUY, new BigDecimal("0.10"), new BigDecimal("1.10020"), new BigDecimal("1.09770")))
        .thenReturn(realizedPnl);
    when(positionRepository.closeIfOpen(position)).thenReturn(1);

    PositionService service = service();

    PositionResponse response = service.closeSystemPosition(accountId, positionId, "FX_MARGIN_STOP_OUT");

    assertThat(response.status()).isEqualTo(PositionStatus.CLOSED.name());
    verify(ledgerService).recordMarginRelease(eq(account), eq(new BigDecimal("110.02000000")), eq(positionId), eq("Position margin released"));
    verify(ledgerService).recordTradePnl(eq(account), eq(realizedPnl), eq(positionId), eq("Position closed"));
    verify(ledgerService).recordForcedClose(eq(account), eq(positionId), eq("FX_MARGIN_STOP_OUT"));
  }

  @Test
  void closeSystemPositionRejectsPositionFromDifferentAccount() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(UUID.randomUUID(), positionId);

    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));

    PositionService service = service();

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.closeSystemPosition(accountId, positionId))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Position does not belong to account");

    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordMarginRelease(any(), any(), any(), any());
    verify(ledgerService, never()).recordTradePnl(any(), any(), any(), any());
  }

  private PositionService service() {
    lenient().when(symbolRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(symbol(
        "EURUSD",
        ProductType.FX_MARGIN,
        "EUR",
        "USD",
        new BigDecimal("100000"),
        new BigDecimal("100000"),
        100)));
    PositionService service = new PositionService(
        positionRepository,
        accountRepository,
        quoteService,
        pnlCalculator,
        ledgerService,
        symbolRepository,
        spotPositionRepository,
        demoExecutionGuard);
    service.setSystemCloseOrderService(systemCloseOrderService);
    return service;
  }

  @Test
  void openPerpetualPositionReturnsPersistedModeSideAndMarginSnapshots() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, UUID.randomUUID());
    position.setSymbol("BTCUSDT-PERP");
    position.setProductType(ProductType.LINEAR_PERP);
    position.setPositionMode(PositionMode.HEDGE);
    position.setPositionSide(PositionSide.SHORT);
    position.setMarginMode(MarginMode.ISOLATED);
    position.setSide(OrderSide.SELL);
    position.setLots(new BigDecimal("0.2"));
    position.setLeverage(20);
    position.setMarkPrice(new BigDecimal("50000"));
    when(accountRepository.findByIdAndUserId(accountId, userId))
        .thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(
        accountId, PositionStatus.OPEN)).thenReturn(List.of(position));
    SymbolEntity symbol = symbol(
        "BTCUSDT-PERP", ProductType.LINEAR_PERP, "BTC", "USDT", BigDecimal.ONE,
        new BigDecimal("0.01"), 100);
    symbol.setContractMultiplier(new BigDecimal("10"));
    when(symbolRepository.findBySymbol("BTCUSDT-PERP")).thenReturn(Optional.of(symbol));
    when(quoteService.freshQuote("BTCUSDT-PERP")).thenReturn(new QuoteResponse(
        "quote", "BTCUSDT-PERP", new BigDecimal("49999"), new BigDecimal("50001"),
        new BigDecimal("50000"), new BigDecimal("50000"), new BigDecimal("2"),
        "test", 1780660000000L, null, null, null, null));

    PositionResponse response = service().openPositions(userId, accountId).get(0);

    assertThat(response.productType()).isEqualTo(ProductType.LINEAR_PERP);
    assertThat(response.positionMode()).isEqualTo(PositionMode.HEDGE);
    assertThat(response.positionSide()).isEqualTo(PositionSide.SHORT);
    assertThat(response.marginMode()).isEqualTo(MarginMode.ISOLATED.name());
    assertThat(response.leverage()).isEqualTo(20);
  }

  @Test
  void openLinearPerpetualPositionUsesAuthorityMarkForEveryRiskField() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, UUID.randomUUID());
    position.setSymbol("BTCUSDT-PERP");
    position.setProductType(ProductType.LINEAR_PERP);
    position.setPositionMode(PositionMode.ONE_WAY);
    position.setPositionSide(PositionSide.BOTH);
    position.setMarginMode(MarginMode.ISOLATED);
    position.setSide(OrderSide.BUY);
    position.setLots(new BigDecimal("0.2"));
    position.setOpenPrice(new BigDecimal("50000"));
    position.setMarginHeld(new BigDecimal("1000"));
    position.setFundingPnl(new BigDecimal("20"));
    position.setLeverage(10);
    position.setNotional(new BigDecimal("1"));
    position.setMaintenanceMargin(new BigDecimal("1"));
    when(accountRepository.findByIdAndUserId(accountId, userId))
        .thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(
        accountId, PositionStatus.OPEN)).thenReturn(List.of(position));
    SymbolEntity symbol = symbol(
        "BTCUSDT-PERP", ProductType.LINEAR_PERP, "BTC", "USDT", BigDecimal.ONE,
        new BigDecimal("0.01"), 100);
    symbol.setContractMultiplier(new BigDecimal("10"));
    symbol.setMaintenanceMarginRate(new BigDecimal("0.005"));
    when(symbolRepository.findBySymbol("BTCUSDT-PERP")).thenReturn(Optional.of(symbol));
    when(quoteService.freshQuote("BTCUSDT-PERP")).thenReturn(new QuoteResponse(
        "quote", "BTCUSDT-PERP",
        new BigDecimal("50490"), new BigDecimal("50510"), new BigDecimal("50500"),
        new BigDecimal("50600"), new BigDecimal("20"), "test", 1780660000000L,
        null, null, null, null));

    PositionResponse response = service().openPositions(userId, accountId).get(0);

    assertThat(response.markPrice()).isEqualByComparingTo("50600.00000000");
    assertThat(response.currentPrice()).isEqualByComparingTo("50490");
    assertThat(response.notional()).isEqualByComparingTo("10120.00000000");
    assertThat(response.floatingPnl()).isEqualByComparingTo("120.00000000");
    assertThat(response.floatingPnlRatio()).isEqualByComparingTo("0.12000000");
    assertThat(response.maintenanceMargin()).isEqualByComparingTo("50.60000000");
    assertThat(response.liquidationPrice()).isEqualByComparingTo("45148.31573655");
  }

  @Test
  void openDeltaNeutralHedgeCrossPositionsShareOneAccountLiquidationBoundary() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    PositionEntity longPosition = openPosition(accountId, UUID.randomUUID());
    longPosition.setSymbol("BTCUSDT-PERP");
    longPosition.setProductType(ProductType.LINEAR_PERP);
    longPosition.setPositionMode(PositionMode.HEDGE);
    longPosition.setPositionSide(PositionSide.LONG);
    longPosition.setMarginMode(MarginMode.CROSS);
    longPosition.setSide(OrderSide.BUY);
    longPosition.setLots(BigDecimal.ONE);
    longPosition.setOpenPrice(new BigDecimal("50000"));
    longPosition.setMarginHeld(new BigDecimal("5000"));
    longPosition.setLeverage(10);
    PositionEntity shortPosition = openPosition(accountId, UUID.randomUUID());
    shortPosition.setSymbol("BTCUSDT-PERP");
    shortPosition.setProductType(ProductType.LINEAR_PERP);
    shortPosition.setPositionMode(PositionMode.HEDGE);
    shortPosition.setPositionSide(PositionSide.SHORT);
    shortPosition.setMarginMode(MarginMode.CROSS);
    shortPosition.setSide(OrderSide.SELL);
    shortPosition.setLots(BigDecimal.ONE);
    shortPosition.setOpenPrice(new BigDecimal("50000"));
    shortPosition.setMarginHeld(new BigDecimal("5000"));
    shortPosition.setLeverage(10);
    when(accountRepository.findByIdAndUserId(accountId, userId))
        .thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(
        accountId, PositionStatus.OPEN)).thenReturn(List.of(longPosition, shortPosition));
    SymbolEntity symbol = symbol(
        "BTCUSDT-PERP", ProductType.LINEAR_PERP, "BTC", "USDT", BigDecimal.ONE,
        new BigDecimal("0.01"), 100);
    symbol.setMaintenanceMarginRate(new BigDecimal("0.005"));
    when(symbolRepository.findBySymbol("BTCUSDT-PERP")).thenReturn(Optional.of(symbol));
    QuoteResponse first = new QuoteResponse(
        "quote", "BTCUSDT-PERP",
        new BigDecimal("50590"), new BigDecimal("50610"), new BigDecimal("50600"),
        new BigDecimal("50600"), new BigDecimal("20"), "test", 1780660000000L,
        null, null, null, null);
    QuoteResponse inconsistentSecond = new QuoteResponse(
        "quote", "BTCUSDT-PERP",
        new BigDecimal("50990"), new BigDecimal("51010"), new BigDecimal("51000"),
        new BigDecimal("51000"), new BigDecimal("20"), "test", 1780660001000L,
        null, null, null, null);
    when(quoteService.freshQuote("BTCUSDT-PERP")).thenReturn(first, inconsistentSecond);

    List<PositionResponse> responses = service().openPositions(userId, accountId);

    assertThat(responses).extracting(PositionResponse::markPrice)
        .allSatisfy(mark -> assertThat(mark).isEqualByComparingTo("50600"));
    assertThat(responses).extracting(PositionResponse::liquidationPrice)
        .allSatisfy(price -> assertThat(price).isEqualByComparingTo("909090.90909091"));
    verify(quoteService, times(1)).freshQuote("BTCUSDT-PERP");
  }

  @Test
  void openSingleCrossPositionFloorsANegativeAccountBoundaryAtZero() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    PositionEntity position = linearPerpetualPosition(
        accountId,
        "BTCUSDT-PERP",
        OrderSide.BUY,
        MarginMode.CROSS,
        "0.1",
        "50000",
        "500");
    when(accountRepository.findByIdAndUserId(accountId, userId))
        .thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(
        accountId, PositionStatus.OPEN)).thenReturn(List.of(position));
    when(symbolRepository.findBySymbol("BTCUSDT-PERP"))
        .thenReturn(Optional.of(linearPerpetualSymbol("BTCUSDT-PERP", "BTC", "USDT")));
    when(quoteService.freshQuote("BTCUSDT-PERP")).thenReturn(new QuoteResponse(
        "quote", "BTCUSDT-PERP",
        new BigDecimal("50590"), new BigDecimal("50610"), new BigDecimal("50600"),
        new BigDecimal("50600"), new BigDecimal("20"), "test", 1780660000000L,
        null, null, null, null));

    PositionResponse response = service().openPositions(userId, accountId).getFirst();

    assertThat(response.liquidationPrice()).isEqualByComparingTo("0.00000000");
  }

  @Test
  void openCrossPositionsFixOtherSymbolsAtCachedMarksAndSubtractIsolatedPrincipal() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    PositionEntity btcCross = linearPerpetualPosition(
        accountId, "BTCUSDT-PERP", OrderSide.BUY, MarginMode.CROSS,
        "1", "50000", "5000");
    PositionEntity ethCross = linearPerpetualPosition(
        accountId, "ETHUSDT-PERP", OrderSide.BUY, MarginMode.CROSS,
        "1", "1000", "100");
    PositionEntity solIsolated = linearPerpetualPosition(
        accountId, "SOLUSDT-PERP", OrderSide.BUY, MarginMode.ISOLATED,
        "1", "100", "2000");
    when(accountRepository.findByIdAndUserId(accountId, userId))
        .thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(
        accountId, PositionStatus.OPEN))
        .thenReturn(List.of(btcCross, ethCross, solIsolated));
    when(symbolRepository.findBySymbol("BTCUSDT-PERP"))
        .thenReturn(Optional.of(linearPerpetualSymbol("BTCUSDT-PERP", "BTC", "USDT")));
    when(symbolRepository.findBySymbol("ETHUSDT-PERP"))
        .thenReturn(Optional.of(linearPerpetualSymbol("ETHUSDT-PERP", "ETH", "USDT")));
    when(symbolRepository.findBySymbol("SOLUSDT-PERP"))
        .thenReturn(Optional.of(linearPerpetualSymbol("SOLUSDT-PERP", "SOL", "USDT")));
    when(quoteService.freshQuote("BTCUSDT-PERP")).thenReturn(new QuoteResponse(
        "quote", "BTCUSDT-PERP", new BigDecimal("50590"), new BigDecimal("50610"),
        new BigDecimal("50600"), new BigDecimal("50600"), new BigDecimal("20"),
        "test", 1780660000000L, null, null, null, null));
    when(quoteService.freshQuote("ETHUSDT-PERP")).thenReturn(new QuoteResponse(
        "quote", "ETHUSDT-PERP", new BigDecimal("1999"), new BigDecimal("2001"),
        new BigDecimal("2000"), new BigDecimal("2000"), new BigDecimal("2"),
        "test", 1780660000000L, null, null, null, null));
    when(quoteService.freshQuote("SOLUSDT-PERP")).thenReturn(new QuoteResponse(
        "quote", "SOLUSDT-PERP", new BigDecimal("99"), new BigDecimal("101"),
        new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("2"),
        "test", 1780660000000L, null, null, null, null));

    Map<String, PositionResponse> bySymbol = service().openPositions(userId, accountId).stream()
        .collect(java.util.stream.Collectors.toMap(PositionResponse::symbol, response -> response));

    assertThat(bySymbol.get("BTCUSDT-PERP").liquidationPrice())
        .isEqualByComparingTo("41237.80794369");
    assertThat(bySymbol.get("ETHUSDT-PERP").liquidationPrice())
        .isEqualByComparingTo("0.00000000");
    assertThat(bySymbol.get("SOLUSDT-PERP").liquidationPrice())
        .isNotNull();
    verify(quoteService, times(1)).freshQuote("BTCUSDT-PERP");
    verify(quoteService, times(1)).freshQuote("ETHUSDT-PERP");
    verify(quoteService, times(1)).freshQuote("SOLUSDT-PERP");
  }

  @Test
  void openLinearPerpetualPositionRejectsQuoteWithoutAuthorityMark() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    PositionEntity position = openPosition(accountId, UUID.randomUUID());
    position.setSymbol("BTCUSDT-PERP");
    position.setProductType(ProductType.LINEAR_PERP);
    position.setLots(new BigDecimal("0.2"));
    position.setLeverage(10);
    when(accountRepository.findByIdAndUserId(accountId, userId))
        .thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(
        accountId, PositionStatus.OPEN)).thenReturn(List.of(position));
    SymbolEntity symbol = symbol(
        "BTCUSDT-PERP", ProductType.LINEAR_PERP, "BTC", "USDT", BigDecimal.ONE,
        BigDecimal.ONE, 10);
    symbol.setMaintenanceMarginRate(new BigDecimal("0.005"));
    when(symbolRepository.findBySymbol("BTCUSDT-PERP")).thenReturn(Optional.of(symbol));
    when(quoteService.freshQuote("BTCUSDT-PERP")).thenReturn(new QuoteResponse(
        "quote", "BTCUSDT-PERP", new BigDecimal("50490"), new BigDecimal("50510"),
        new BigDecimal("50500"), new BigDecimal("20"), "test", 1780660000000L));

    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> service().openPositions(userId, accountId))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo("MARKET_DATA_UNAVAILABLE"));
  }

  private static SpotPositionEntity spotPosition(UUID accountId, String asset, String costAsset) {
    SpotPositionEntity position = new SpotPositionEntity();
    position.setId(UUID.randomUUID());
    position.setAccountId(accountId);
    position.setWalletType("SPOT");
    position.setAsset(asset);
    position.setCostAsset(costAsset);
    position.setQuantity(BigDecimal.ZERO);
    position.setAverageCost(BigDecimal.ZERO);
    position.setRealizedPnl(BigDecimal.ZERO);
    position.setUnrealizedPnl(BigDecimal.ZERO);
    position.setFeeCost(BigDecimal.ZERO);
    position.setUpdatedAt(Instant.parse("2026-06-16T00:00:00Z"));
    return position;
  }

  private static SymbolEntity symbol(
      String code,
      ProductType productType,
      String baseCurrency,
      String quoteCurrency,
      BigDecimal lotSize,
      BigDecimal contractSize,
      int leverage
  ) {
    SymbolEntity symbol = new SymbolEntity();
    symbol.setSymbol(code);
    symbol.setProductType(productType);
    symbol.setAssetClass(assetClass(productType));
    symbol.setBaseCurrency(baseCurrency);
    symbol.setQuoteCurrency(quoteCurrency);
    symbol.setLotSize(lotSize);
    symbol.setContractSize(contractSize);
    symbol.setLeverage(leverage);
    if (productType == ProductType.LINEAR_PERP) {
      symbol.setMaintenanceMarginRate(new BigDecimal("0.005"));
    }
    return symbol;
  }

  private static String assetClass(ProductType productType) {
    if (productType == ProductType.CRYPTO_SPOT) {
      return "CRYPTO";
    }
    if (productType == ProductType.LINEAR_PERP) {
      return "LINEAR_PERPETUAL";
    }
    if (productType == ProductType.INVERSE_PERP) {
      return "INVERSE_PERPETUAL";
    }
    return "FOREX";
  }

  private static PositionEntity openPosition(UUID accountId, UUID positionId) {
    PositionEntity position = new PositionEntity();
    position.setId(positionId);
    position.setAccountId(accountId);
    position.setSymbol("EURUSD");
    position.setSide(OrderSide.BUY);
    position.setLots(new BigDecimal("0.10"));
    position.setOpenPrice(new BigDecimal("1.10020"));
    position.setCurrentPrice(new BigDecimal("1.10020"));
    position.setStopLoss(new BigDecimal("1.09000"));
    position.setTakeProfit(new BigDecimal("1.12000"));
    position.setMarginHeld(new BigDecimal("110.02000000"));
    position.setStatus(PositionStatus.OPEN);
    return position;
  }

  private static PositionEntity linearPerpetualPosition(
      UUID accountId,
      String symbol,
      OrderSide side,
      MarginMode marginMode,
      String quantity,
      String entryPrice,
      String marginHeld
  ) {
    PositionEntity position = openPosition(accountId, UUID.randomUUID());
    position.setSymbol(symbol);
    position.setProductType(ProductType.LINEAR_PERP);
    position.setPositionMode(PositionMode.ONE_WAY);
    position.setPositionSide(PositionSide.BOTH);
    position.setMarginMode(marginMode);
    position.setSide(side);
    position.setLots(new BigDecimal(quantity));
    position.setOpenPrice(new BigDecimal(entryPrice));
    position.setMarginHeld(new BigDecimal(marginHeld));
    position.setFundingPnl(BigDecimal.ZERO);
    position.setLeverage(10);
    return position;
  }

  private static SymbolEntity linearPerpetualSymbol(
      String symbolName,
      String baseAsset,
      String quoteAsset
  ) {
    SymbolEntity symbol = symbol(
        symbolName,
        ProductType.LINEAR_PERP,
        baseAsset,
        quoteAsset,
        BigDecimal.ONE,
        new BigDecimal("0.01"),
        100);
    symbol.setMaintenanceMarginRate(new BigDecimal("0.005"));
    return symbol;
  }

  private static TradingAccountEntity account(UUID userId, UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setBaseCurrency("USD");
    account.setBalance(new BigDecimal("10000.00000000"));
    account.setEquity(new BigDecimal("10000.00000000"));
    account.setUsedMargin(new BigDecimal("300.00000000"));
    account.setFreeMargin(new BigDecimal("9700.00000000"));
    account.setLeverage(100);
    return account;
  }

}
