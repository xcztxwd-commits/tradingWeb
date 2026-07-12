package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.trading.entity.CrossLiquidationChargeEntity;
import com.fxplatform.trading.entity.PositionEntity;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.repository.CrossLiquidationChargeRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LiquidationSettlementServiceTest {

  @Mock TradingAccountRepository accountRepository;
  @Mock PositionRepository positionRepository;
  @Mock CrossLiquidationChargeRepository chargeRepository;
  @Mock LedgerService ledgerService;
  @Mock AuditLogService auditLogService;
  @Mock DemoExecutionGuard demoExecutionGuard;
  @Mock TradingTransactionExecutor transactionExecutor;

  @BeforeEach
  void executeMutation() {
    when(transactionExecutor.execute(any())).thenAnswer(invocation ->
        ((Supplier<?>) invocation.getArgument(0)).get());
  }

  @Test
  void finalCrossNetLossAndAllContractualFeesSettleOnceAfterEveryPositionClosed() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "-50.00000000");
    List<CrossLiquidationChargeEntity> charges = List.of(
        charge(accountId, uuid(1), uuid(11), "10.00000000"),
        charge(accountId, uuid(2), uuid(12), "20.00000000"));
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of());
    when(chargeRepository.findPendingByAccountIdForUpdate(accountId)).thenReturn(charges);

    assertThat(service().settleIfReady(accountId)).isTrue();

    assertThat(account.getBalance()).isEqualByComparingTo("0.00000000");
    assertThat(account.getEquity()).isEqualByComparingTo("0.00000000");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("0.00000000");
    assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    assertThat(charges).allSatisfy(charge -> {
      assertThat(charge.getStatus()).isEqualTo("SETTLED");
      assertThat(charge.getFeeCharged()).isEqualByComparingTo("0.00000000");
    });
    verify(ledgerService).recordCrossLiquidationShortfall(
        eq(account), eq(new BigDecimal("80.00000000")), any(UUID.class), anyString());
    verify(ledgerService, never()).recordLiquidationFee(any(), any(), any(), anyString());
    verify(auditLogService).record(
        eq(null), eq("BANKRUPTCY_SHORTFALL"), eq("ACCOUNT"),
        eq(accountId.toString()), anyString());
  }

  @Test
  void availableFinalBalancePaysFeesInStableOrderAndRecordsOnlyUnpaidRemainder() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "25.00000000");
    CrossLiquidationChargeEntity first = charge(
        accountId, uuid(1), uuid(11), "10.00000000");
    CrossLiquidationChargeEntity second = charge(
        accountId, uuid(2), uuid(12), "20.00000000");
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of());
    when(chargeRepository.findPendingByAccountIdForUpdate(accountId))
        .thenReturn(List.of(first, second));

    assertThat(service().settleIfReady(accountId)).isTrue();

    assertThat(account.getBalance()).isEqualByComparingTo("0.00000000");
    assertThat(first.getFeeCharged()).isEqualByComparingTo("10.00000000");
    assertThat(second.getFeeCharged()).isEqualByComparingTo("15.00000000");
    verify(ledgerService).recordLiquidationFee(
        account, new BigDecimal("10.00000000"), uuid(11), "Liquidation fee charged");
    verify(ledgerService).recordLiquidationFee(
        account, new BigDecimal("15.00000000"), uuid(12), "Liquidation fee charged");
    verify(ledgerService).recordCrossLiquidationShortfall(
        eq(account), eq(new BigDecimal("5.00000000")), any(UUID.class), anyString());
  }

  @Test
  void failedCrossItemKeepsPendingChargesAndAccountStateUntouched() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "-10.00000000");
    PositionEntity remaining = new PositionEntity();
    remaining.setMarginMode(MarginMode.CROSS);
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of(remaining));

    assertThat(service().settleIfReady(accountId)).isFalse();

    assertThat(account.getBalance()).isEqualByComparingTo("-10.00000000");
    assertThat(account.getStatus()).isEqualTo(AccountStatus.LIQUIDATION_PENDING);
    verify(chargeRepository, never()).findPendingByAccountIdForUpdate(any());
    verify(accountRepository, never()).save(any());
    verify(ledgerService, never()).recordCrossLiquidationShortfall(
        any(), any(), any(), anyString());
  }

  @Test
  void crossSettlementProtectsEveryOpenIsolatedMarginPool() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "40.00000000");
    PositionEntity isolated = new PositionEntity();
    isolated.setMarginMode(MarginMode.ISOLATED);
    isolated.setMarginHeld(new BigDecimal("30.00000000"));
    CrossLiquidationChargeEntity charge = charge(
        accountId, uuid(1), uuid(11), "20.00000000");
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of(isolated));
    when(chargeRepository.findPendingByAccountIdForUpdate(accountId))
        .thenReturn(List.of(charge));

    assertThat(service().settleIfReady(accountId)).isTrue();

    assertThat(account.getBalance()).isEqualByComparingTo("30.00000000");
    assertThat(charge.getFeeCharged()).isEqualByComparingTo("10.00000000");
    verify(ledgerService).recordLiquidationFee(
        account, new BigDecimal("10.00000000"), uuid(11), "Liquidation fee charged");
    verify(ledgerService).recordCrossLiquidationShortfall(
        eq(account), eq(new BigDecimal("10.00000000")), any(UUID.class), anyString());
  }

  @Test
  void adminSettlementKeepsWriteGatePendingUntilCleanupChecksTheFinalScope() {
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = account(accountId, "40.00000000");
    account.setStatus(AccountStatus.RISK_REDUCTION_PENDING);
    when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
    when(positionRepository.findOpenLinearPerpByAccountIdForUpdate(accountId))
        .thenReturn(List.of());
    when(chargeRepository.findPendingByAccountIdForUpdate(accountId)).thenReturn(List.of());

    assertThat(service().settleIfReadyKeepingPending(accountId)).isTrue();

    assertThat(account.getStatus()).isEqualTo(AccountStatus.RISK_REDUCTION_PENDING);
    verify(accountRepository).save(account);
  }

  private LiquidationSettlementService service() {
    return new LiquidationSettlementService(
        accountRepository,
        positionRepository,
        chargeRepository,
        ledgerService,
        auditLogService,
        demoExecutionGuard,
        transactionExecutor);
  }

  private static TradingAccountEntity account(UUID id, String balance) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(id);
    account.setBalance(new BigDecimal(balance));
    account.setEquity(new BigDecimal(balance));
    account.setFreeMargin(new BigDecimal(balance));
    account.setStatus(AccountStatus.LIQUIDATION_PENDING);
    return account;
  }

  private static CrossLiquidationChargeEntity charge(
      UUID accountId,
      UUID orderId,
      UUID positionId,
      String amount
  ) {
    CrossLiquidationChargeEntity charge = new CrossLiquidationChargeEntity();
    charge.setOrderId(orderId);
    charge.setAccountId(accountId);
    charge.setPositionId(positionId);
    charge.setFeeDue(new BigDecimal(amount));
    charge.setFeeCharged(BigDecimal.ZERO);
    charge.setStatus("PENDING");
    return charge;
  }

  private static UUID uuid(int suffix) {
    return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", suffix));
  }
}
