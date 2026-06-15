package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.market.dto.QuoteResponse;
import com.fxplatform.market.service.QuoteService;
import com.fxplatform.risk.service.PnLCalculator;
import com.fxplatform.trading.dto.request.UpdatePositionProtectionRequest;
import com.fxplatform.trading.dto.response.PositionResponse;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
    when(pnlCalculator.floatingPnl(OrderSide.BUY, new BigDecimal("0.10"), new BigDecimal("1.10020"), new BigDecimal("1.10120")))
        .thenReturn(floatingPnl);

    PositionService service = new PositionService(positionRepository, accountRepository, quoteService, pnlCalculator, ledgerService);

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
    position.setLots(new BigDecimal("0.01"));
    position.setOpenPrice(new BigDecimal("63874.8"));
    position.setCurrentPrice(new BigDecimal("63874.8"));
    position.setMarginHeld(new BigDecimal("0.07"));

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.OPEN))
        .thenReturn(List.of(position));
    when(quoteService.freshQuote("BTCUSDT")).thenReturn(new QuoteResponse(
        "quote",
        "BTCUSDT",
        new BigDecimal("63876.8"),
        new BigDecimal("63877.0"),
        new BigDecimal("63876.9"),
        new BigDecimal("0.2"),
        "test",
        1780660000000L));

    PositionService service = new PositionService(positionRepository, accountRepository, quoteService, pnlCalculator, ledgerService);

    List<PositionResponse> responses = service.openPositions(userId, accountId);

    assertThat(responses).hasSize(1);
    PositionResponse response = responses.get(0);
    assertThat(response.instrumentType()).isEqualTo("SWAP");
    assertThat(response.positionUnit()).isEqualTo("CONTRACT");
    assertThat(response.markPrice()).isEqualByComparingTo("63876.9");
    assertThat(response.floatingPnl()).isEqualByComparingTo("0.020");
    assertThat(response.floatingPnlRatio()).isEqualByComparingTo("0.28571429");
    verify(pnlCalculator, never()).floatingPnl(eq(OrderSide.BUY), eq(new BigDecimal("0.01")), any(), any());
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

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findByAccountIdAndStatusOrderByOpenedAtDesc(accountId, PositionStatus.CLOSED))
        .thenReturn(List.of(position));

    PositionService service = new PositionService(positionRepository, accountRepository, quoteService, pnlCalculator, ledgerService);

    List<PositionResponse> responses = service.positionHistory(userId, accountId);

    assertThat(responses).hasSize(1);
    assertThat(responses.get(0).status()).isEqualTo("CLOSED");
    assertThat(responses.get(0).realizedPnl()).isEqualByComparingTo("15.00000000");
    assertThat(responses.get(0).stopLoss()).isEqualByComparingTo("1.09000");
    assertThat(responses.get(0).takeProfit()).isEqualByComparingTo("1.12000");
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

    PositionService service = new PositionService(positionRepository, accountRepository, quoteService, pnlCalculator, ledgerService);

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

    PositionService service = new PositionService(positionRepository, accountRepository, quoteService, pnlCalculator, ledgerService);

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

    PositionService service = new PositionService(positionRepository, accountRepository, quoteService, pnlCalculator, ledgerService);

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

    PositionService service = new PositionService(positionRepository, accountRepository, quoteService, pnlCalculator, ledgerService);

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

    PositionService service = new PositionService(positionRepository, accountRepository, quoteService, pnlCalculator, ledgerService);

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

    PositionService service = new PositionService(positionRepository, accountRepository, quoteService, pnlCalculator, ledgerService);

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
    when(pnlCalculator.floatingPnl(OrderSide.BUY, new BigDecimal("0.10"), new BigDecimal("1.10020"), new BigDecimal("1.10120")))
        .thenReturn(realizedPnl);
    when(positionRepository.closeIfOpen(position)).thenReturn(1);

    PositionService service = new PositionService(positionRepository, accountRepository, quoteService, pnlCalculator, ledgerService);

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
    when(pnlCalculator.floatingPnl(OrderSide.BUY, new BigDecimal("0.10"), new BigDecimal("1.10020"), new BigDecimal("1.10120")))
        .thenReturn(realizedPnl);
    when(positionRepository.closeIfOpen(position)).thenReturn(1);

    PositionService service = new PositionService(positionRepository, accountRepository, quoteService, pnlCalculator, ledgerService);

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

    PositionService service = new PositionService(positionRepository, accountRepository, quoteService, pnlCalculator, ledgerService);

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
    when(pnlCalculator.floatingPnl(OrderSide.BUY, new BigDecimal("0.10"), new BigDecimal("1.10020"), new BigDecimal("1.10120")))
        .thenReturn(new BigDecimal("10.00000000"));
    when(positionRepository.closeIfOpen(position)).thenReturn(0);

    PositionService service = new PositionService(positionRepository, accountRepository, quoteService, pnlCalculator, ledgerService);

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

    PositionService service = new PositionService(positionRepository, accountRepository, quoteService, pnlCalculator, ledgerService);

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

    PositionService service = new PositionService(positionRepository, accountRepository, quoteService, pnlCalculator, ledgerService);

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

    PositionService service = new PositionService(positionRepository, accountRepository, quoteService, pnlCalculator, ledgerService);

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
    when(pnlCalculator.floatingPnl(OrderSide.BUY, new BigDecimal("0.10"), new BigDecimal("1.10020"), new BigDecimal("1.10120")))
        .thenReturn(realizedPnl);
    when(positionRepository.closeIfOpen(position)).thenReturn(1);

    PositionService service = new PositionService(positionRepository, accountRepository, quoteService, pnlCalculator, ledgerService);

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
  void closeSystemPositionRejectsPositionFromDifferentAccount() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    PositionEntity position = openPosition(UUID.randomUUID(), positionId);

    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(userId, accountId)));
    when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));

    PositionService service = new PositionService(positionRepository, accountRepository, quoteService, pnlCalculator, ledgerService);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.closeSystemPosition(accountId, positionId))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Position does not belong to account");

    verify(positionRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordMarginRelease(any(), any(), any(), any());
    verify(ledgerService, never()).recordTradePnl(any(), any(), any(), any());
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

  private static TradingAccountEntity account(UUID userId, UUID accountId) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setBalance(new BigDecimal("10000.00000000"));
    account.setEquity(new BigDecimal("10000.00000000"));
    account.setUsedMargin(new BigDecimal("300.00000000"));
    account.setFreeMargin(new BigDecimal("9700.00000000"));
    account.setLeverage(100);
    return account;
  }
}
