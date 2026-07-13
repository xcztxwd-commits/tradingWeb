package com.fxplatform.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.admin.dto.request.AdminBalanceAdjustmentRequest;
import com.fxplatform.admin.dto.request.AdminFundOperationRequest;
import com.fxplatform.admin.dto.request.AdminPaymentMethodRequest;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.finance.entity.AdminFundOperationEntity;
import com.fxplatform.finance.entity.AdminPaymentMethodEntity;
import com.fxplatform.finance.repository.AdminFundOperationRepository;
import com.fxplatform.finance.repository.AdminPaymentMethodRepository;
import com.fxplatform.ledger.entity.LedgerEntryEntity;
import com.fxplatform.ledger.service.LedgerService;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminFinanceCommandServiceTest {

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private LedgerService ledgerService;

  @Mock
  private AdminFundOperationRepository fundOperationRepository;

  @Mock
  private AdminPaymentMethodRepository paymentMethodRepository;

  @Mock
  private AuditLogService auditLogService;

  @Test
  void adjustmentRequiresConfirmationBeforeAccountLookup() {
    AdminFinanceCommandService service = service();

    assertThatThrownBy(() -> service.adjustBalance(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new AdminBalanceAdjustmentRequest(
                new BigDecimal("-10.00"),
                "manual correction",
                "ticket",
                "adjust-confirm")))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("ADMIN_CONFIRMATION_REQUIRED");

    verifyNoInteractions(accountRepository, ledgerService, fundOperationRepository, auditLogService);
  }

  @Test
  void depositIncreasesAccountBalanceAndWritesLedgerAndAudit() {
    UUID actorUserId = UUID.randomUUID();
    TradingAccountEntity account = account(new BigDecimal("1000.00"), BigDecimal.ZERO);
    AdminFundOperationRequest request = new AdminFundOperationRequest(
        new BigDecimal("250.00"),
        "manual deposit approved",
        null,
        "bank slip ok",
        "deposit-1");
    when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
    when(fundOperationRepository.findByAccountIdAndOperationTypeAndIdempotencyKey(
        account.getId(), "DEPOSIT", "deposit-1")).thenReturn(Optional.empty());
    when(fundOperationRepository.insertIfAbsent(any(AdminFundOperationEntity.class)))
        .thenAnswer(invocation -> {
          AdminFundOperationEntity operation = invocation.getArgument(0);
          operation.setId(UUID.randomUUID());
          return 1;
        });
    when(ledgerService.recordAdminAdjustment(
        eq(account),
        eq(new BigDecimal("250.00")),
        org.mockito.ArgumentMatchers.any(UUID.class),
        contains("manual deposit approved"))).thenReturn(new LedgerEntryEntity());

    AdminFinanceCommandService service = service();

    var response = service.deposit(actorUserId, account.getId(), request);

    assertThat(response.operationType()).isEqualTo("DEPOSIT");
    assertThat(response.afterBalance()).isEqualByComparingTo("1250.00");
    assertThat(account.getBalance()).isEqualByComparingTo("1250.00");
    assertThat(account.getEquity()).isEqualByComparingTo("1250.00");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("1250.00");
    verify(accountRepository).save(account);
    verify(ledgerService).recordAdminAdjustment(
        eq(account),
        eq(new BigDecimal("250.00")),
        org.mockito.ArgumentMatchers.any(UUID.class),
        contains("manual deposit approved"));
    verify(auditLogService).record(
        eq(actorUserId),
        eq("ADMIN_FINANCE_DEPOSIT"),
        eq("ACCOUNT"),
        eq(account.getId().toString()),
        contains("manual deposit approved"));
  }

  @Test
  void depositComputesFromTheLatestLockedBalance() {
    UUID actorUserId = UUID.randomUUID();
    TradingAccountEntity stale = account(new BigDecimal("100.00"), BigDecimal.ZERO);
    TradingAccountEntity locked = account(new BigDecimal("120.00"), BigDecimal.ZERO);
    locked.setId(stale.getId());
    locked.setUserId(stale.getUserId());
    AdminFundOperationRequest request = new AdminFundOperationRequest(
        new BigDecimal("10.00"),
        "concurrent deposit",
        null,
        null,
        "deposit-locked-balance");
    when(fundOperationRepository.findByAccountIdAndOperationTypeAndIdempotencyKey(
        stale.getId(), "DEPOSIT", "deposit-locked-balance")).thenReturn(Optional.empty());
    when(accountRepository.findByIdForUpdate(stale.getId())).thenReturn(Optional.of(locked));
    when(fundOperationRepository.insertIfAbsent(any(AdminFundOperationEntity.class)))
        .thenAnswer(invocation -> {
          AdminFundOperationEntity operation = invocation.getArgument(0);
          operation.setId(UUID.randomUUID());
          return 1;
        });

    var response = service().deposit(actorUserId, stale.getId(), request);

    assertThat(response.beforeBalance()).isEqualByComparingTo("120.00");
    assertThat(response.afterBalance()).isEqualByComparingTo("130.00");
    assertThat(locked.getBalance()).isEqualByComparingTo("130.00");
    assertThat(stale.getBalance()).isEqualByComparingTo("100.00");
    verify(accountRepository).findByIdForUpdate(stale.getId());
    verify(accountRepository, never()).findById(stale.getId());
    verify(ledgerService).recordAdminAdjustment(
        locked,
        new BigDecimal("10.00"),
        response.id(),
        "concurrent deposit");
  }

  @Test
  void depositAddsTheDeltaWithoutErasingUnrealizedPnlOrMarginHolds() {
    TradingAccountEntity account = account(new BigDecimal("100.00"), new BigDecimal("10.00"));
    account.setEquity(new BigDecimal("120.00"));
    account.setFreeMargin(new BigDecimal("110.00"));
    when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
    when(fundOperationRepository.save(any(AdminFundOperationEntity.class)))
        .thenAnswer(invocation -> {
          AdminFundOperationEntity operation = invocation.getArgument(0);
          operation.setId(UUID.randomUUID());
          return operation;
        });

    service().deposit(
        UUID.randomUUID(),
        account.getId(),
        new AdminFundOperationRequest(
            new BigDecimal("10.00"), "deposit with open position", null, null, null));

    assertThat(account.getBalance()).isEqualByComparingTo("110.00");
    assertThat(account.getEquity()).isEqualByComparingTo("130.00");
    assertThat(account.getUsedMargin()).isEqualByComparingTo("10.00");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("120.00");
  }

  @Test
  void withdrawalUsesTheLockedFreeMarginInsteadOfRebuildingItFromBalance() {
    TradingAccountEntity account = account(new BigDecimal("100.00"), new BigDecimal("10.00"));
    account.setEquity(new BigDecimal("80.00"));
    account.setFreeMargin(new BigDecimal("70.00"));
    when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));

    assertThatThrownBy(() -> service().withdraw(
            UUID.randomUUID(),
            account.getId(),
            new AdminFundOperationRequest(
                new BigDecimal("80.00"), "withdraw with open loss", null, null, null)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Insufficient free margin");

    assertThat(account.getBalance()).isEqualByComparingTo("100.00");
    assertThat(account.getEquity()).isEqualByComparingTo("80.00");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("70.00");
    verify(fundOperationRepository, never()).save(any());
    verify(accountRepository, never()).save(any());
    verifyNoInteractions(ledgerService);
  }

  @Test
  void depositReturnsExistingOperationForRepeatedIdempotencyKey() {
    UUID actorUserId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    AdminFundOperationEntity existing = new AdminFundOperationEntity();
    existing.setId(operationId);
    existing.setAccountId(accountId);
    existing.setUserId(UUID.randomUUID());
    existing.setOperationType("DEPOSIT");
    existing.setAmount(new BigDecimal("250.00"));
    existing.setCurrency("USD");
    existing.setBeforeBalance(new BigDecimal("1000.00"));
    existing.setAfterBalance(new BigDecimal("1250.00"));
    existing.setAdminUserId(actorUserId);
    existing.setReason("manual deposit approved");
    existing.setIdempotencyKey("deposit-1");
    AdminFundOperationRequest request = new AdminFundOperationRequest(
        new BigDecimal("250.00"),
        "manual deposit approved",
        null,
        "retry",
        "deposit-1");

    when(fundOperationRepository.findByAccountIdAndOperationTypeAndIdempotencyKey(
        accountId, "DEPOSIT", "deposit-1")).thenReturn(Optional.of(existing));

    AdminFinanceCommandService service = service();

    var response = service.deposit(actorUserId, accountId, request);

    assertThat(response.id()).isEqualTo(operationId);
    assertThat(response.afterBalance()).isEqualByComparingTo("1250.00");
    verify(accountRepository, never()).findByIdForUpdate(accountId);
    verify(accountRepository, never()).save(any());
    verify(fundOperationRepository, never()).insertIfAbsent(any());
    verify(fundOperationRepository, never()).save(any());
    verify(ledgerService, never()).recordAdminAdjustment(any(), any(), any(), any());
    verify(auditLogService, never()).record(any(), any(), any(), any(), any(String.class));
  }

  @Test
  void depositRetryWithSameIdempotencyKeyDoesNotApplyBalanceOrLedgerTwice() {
    UUID actorUserId = UUID.randomUUID();
    TradingAccountEntity account = account(new BigDecimal("1000.00"), BigDecimal.ZERO);
    AdminFundOperationRequest request = new AdminFundOperationRequest(
        new BigDecimal("250.00"),
        "manual deposit approved",
        null,
        "retry-safe deposit",
        "deposit-retry-1");
    AtomicReference<AdminFundOperationEntity> storedOperation = new AtomicReference<>();
    when(fundOperationRepository.findByAccountIdAndOperationTypeAndIdempotencyKey(
        account.getId(), "DEPOSIT", "deposit-retry-1"))
        .thenReturn(Optional.empty())
        .thenAnswer(invocation -> Optional.of(storedOperation.get()));
    when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
    when(fundOperationRepository.insertIfAbsent(any(AdminFundOperationEntity.class)))
        .thenAnswer(invocation -> {
          AdminFundOperationEntity operation = invocation.getArgument(0);
          operation.setId(UUID.randomUUID());
          storedOperation.set(operation);
          return 1;
        });
    when(ledgerService.recordAdminAdjustment(
        eq(account),
        eq(new BigDecimal("250.00")),
        org.mockito.ArgumentMatchers.any(UUID.class),
        contains("manual deposit approved"))).thenReturn(new LedgerEntryEntity());

    AdminFinanceCommandService service = service();

    var first = service.deposit(actorUserId, account.getId(), request);
    var retry = service.deposit(actorUserId, account.getId(), request);

    assertThat(retry.id()).isEqualTo(first.id());
    assertThat(retry.afterBalance()).isEqualByComparingTo("1250.00");
    assertThat(account.getBalance()).isEqualByComparingTo("1250.00");
    verify(accountRepository).save(account);
    verify(fundOperationRepository).insertIfAbsent(any(AdminFundOperationEntity.class));
    verify(ledgerService).recordAdminAdjustment(
        eq(account),
        eq(new BigDecimal("250.00")),
        org.mockito.ArgumentMatchers.any(UUID.class),
        contains("manual deposit approved"));
  }

  @Test
  void withdrawalRetryWithSameIdempotencyKeyDoesNotApplyBalanceOrLedgerTwice() {
    UUID actorUserId = UUID.randomUUID();
    TradingAccountEntity account = account(new BigDecimal("1000.00"), new BigDecimal("100.00"));
    AdminFundOperationRequest request = new AdminFundOperationRequest(
        new BigDecimal("200.00"),
        "manual withdrawal approved",
        null,
        "retry-safe withdrawal",
        "withdraw-retry-1");
    AtomicReference<AdminFundOperationEntity> storedOperation = new AtomicReference<>();
    when(fundOperationRepository.findByAccountIdAndOperationTypeAndIdempotencyKey(
        account.getId(), "WITHDRAWAL", "withdraw-retry-1"))
        .thenReturn(Optional.empty())
        .thenAnswer(invocation -> Optional.of(storedOperation.get()));
    when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
    when(fundOperationRepository.insertIfAbsent(any(AdminFundOperationEntity.class)))
        .thenAnswer(invocation -> {
          AdminFundOperationEntity operation = invocation.getArgument(0);
          operation.setId(UUID.randomUUID());
          storedOperation.set(operation);
          return 1;
        });
    when(ledgerService.recordAdminAdjustment(
        eq(account),
        eq(new BigDecimal("-200.00")),
        org.mockito.ArgumentMatchers.any(UUID.class),
        contains("manual withdrawal approved"))).thenReturn(new LedgerEntryEntity());

    AdminFinanceCommandService service = service();

    var first = service.withdraw(actorUserId, account.getId(), request);
    var retry = service.withdraw(actorUserId, account.getId(), request);

    assertThat(retry.id()).isEqualTo(first.id());
    assertThat(retry.afterBalance()).isEqualByComparingTo("800.00");
    assertThat(account.getBalance()).isEqualByComparingTo("800.00");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("700.00");
    verify(accountRepository).save(account);
    verify(fundOperationRepository).insertIfAbsent(any(AdminFundOperationEntity.class));
    verify(ledgerService).recordAdminAdjustment(
        eq(account),
        eq(new BigDecimal("-200.00")),
        org.mockito.ArgumentMatchers.any(UUID.class),
        contains("manual withdrawal approved"));
  }

  @Test
  void withdrawalRejectsWhenFreeMarginWouldBecomeNegative() {
    UUID actorUserId = UUID.randomUUID();
    TradingAccountEntity account = account(new BigDecimal("1000.00"), new BigDecimal("900.00"));
    AdminFundOperationRequest request = new AdminFundOperationRequest(
        new BigDecimal("200.00"),
        "withdrawal request",
        null,
        null,
        "withdraw-1");
    when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));

    AdminFinanceCommandService service = service();

    assertThatThrownBy(() -> service.withdraw(actorUserId, account.getId(), request))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Insufficient free margin");

    verify(accountRepository, never()).save(account);
    verify(fundOperationRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void adjustmentCanDecreaseBalanceWithinFreeMarginAndWritesSignedLedger() {
    UUID actorUserId = UUID.randomUUID();
    TradingAccountEntity account = account(new BigDecimal("1000.00"), new BigDecimal("100.00"));
    AdminBalanceAdjustmentRequest request = new AdminBalanceAdjustmentRequest(
        new BigDecimal("-150.00"),
        "chargeback correction",
        "chargeback proof",
        "adjust-1",
        "CONFIRM_ADJUSTMENT");
    when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
    when(fundOperationRepository.findByAccountIdAndOperationTypeAndIdempotencyKey(
        account.getId(), "ADJUSTMENT", "adjust-1")).thenReturn(Optional.empty());
    when(fundOperationRepository.insertIfAbsent(any(AdminFundOperationEntity.class)))
        .thenAnswer(invocation -> {
          AdminFundOperationEntity operation = invocation.getArgument(0);
          operation.setId(UUID.randomUUID());
          return 1;
        });
    when(ledgerService.recordAdminAdjustment(
        eq(account),
        eq(new BigDecimal("-150.00")),
        org.mockito.ArgumentMatchers.any(UUID.class),
        contains("chargeback correction"))).thenReturn(new LedgerEntryEntity());

    AdminFinanceCommandService service = service();

    var response = service.adjustBalance(actorUserId, account.getId(), request);

    assertThat(response.operationType()).isEqualTo("ADJUSTMENT");
    assertThat(response.afterBalance()).isEqualByComparingTo("850.00");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("750.00");
    verify(ledgerService).recordAdminAdjustment(
        eq(account),
        eq(new BigDecimal("-150.00")),
        org.mockito.ArgumentMatchers.any(UUID.class),
        contains("chargeback correction"));
  }

  @Test
  void adjustmentRetryWithSameIdempotencyKeyDoesNotApplyBalanceOrLedgerTwice() {
    UUID actorUserId = UUID.randomUUID();
    TradingAccountEntity account = account(new BigDecimal("1000.00"), new BigDecimal("50.00"));
    AdminBalanceAdjustmentRequest request = new AdminBalanceAdjustmentRequest(
        new BigDecimal("-125.00"),
        "chargeback correction",
        "retry-safe adjustment",
        "adjust-retry-1",
        "CONFIRM_ADJUSTMENT");
    AtomicReference<AdminFundOperationEntity> storedOperation = new AtomicReference<>();
    when(fundOperationRepository.findByAccountIdAndOperationTypeAndIdempotencyKey(
        account.getId(), "ADJUSTMENT", "adjust-retry-1"))
        .thenReturn(Optional.empty())
        .thenAnswer(invocation -> Optional.of(storedOperation.get()));
    when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
    when(fundOperationRepository.insertIfAbsent(any(AdminFundOperationEntity.class)))
        .thenAnswer(invocation -> {
          AdminFundOperationEntity operation = invocation.getArgument(0);
          operation.setId(UUID.randomUUID());
          storedOperation.set(operation);
          return 1;
        });
    when(ledgerService.recordAdminAdjustment(
        eq(account),
        eq(new BigDecimal("-125.00")),
        org.mockito.ArgumentMatchers.any(UUID.class),
        contains("chargeback correction"))).thenReturn(new LedgerEntryEntity());

    AdminFinanceCommandService service = service();

    var first = service.adjustBalance(actorUserId, account.getId(), request);
    var retry = service.adjustBalance(actorUserId, account.getId(), request);

    assertThat(retry.id()).isEqualTo(first.id());
    assertThat(retry.afterBalance()).isEqualByComparingTo("875.00");
    assertThat(account.getBalance()).isEqualByComparingTo("875.00");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("825.00");
    verify(accountRepository).save(account);
    verify(fundOperationRepository).insertIfAbsent(any(AdminFundOperationEntity.class));
    verify(ledgerService).recordAdminAdjustment(
        eq(account),
        eq(new BigDecimal("-125.00")),
        org.mockito.ArgumentMatchers.any(UUID.class),
        contains("chargeback correction"));
  }

  @Test
  void createPaymentMethodStoresOperationalSettingsAndAudits() {
    UUID actorUserId = UUID.randomUUID();
    AdminPaymentMethodRequest request = new AdminPaymentMethodRequest(
        "Bank Transfer",
        "BANK_TRANSFER",
        "USD",
        true,
        10,
        "Upload bank slip");
    when(paymentMethodRepository.save(org.mockito.ArgumentMatchers.any(AdminPaymentMethodEntity.class)))
        .thenAnswer(invocation -> {
          AdminPaymentMethodEntity method = invocation.getArgument(0);
          method.setId(UUID.randomUUID());
          return method;
        });

    AdminFinanceCommandService service = service();

    var response = service.createPaymentMethod(actorUserId, request);

    assertThat(response.name()).isEqualTo("Bank Transfer");
    assertThat(response.enabled()).isTrue();
    verify(auditLogService).record(
        eq(actorUserId),
        eq("ADMIN_PAYMENT_METHOD_CREATE"),
        eq("PAYMENT_METHOD"),
        eq(response.id().toString()),
        contains("Bank Transfer"));
  }

  @Test
  void updatePaymentMethodOnlyChangesExistingMethod() {
    UUID actorUserId = UUID.randomUUID();
    AdminPaymentMethodEntity method = new AdminPaymentMethodEntity();
    method.setId(UUID.randomUUID());
    method.setName("Old");
    method.setMethodType("BANK_TRANSFER");
    method.setCurrency("USD");
    method.setEnabled(true);
    method.setDisplayOrder(1);
    method.setInstructions("old");
    when(paymentMethodRepository.findById(method.getId())).thenReturn(Optional.of(method));
    when(paymentMethodRepository.save(method)).thenReturn(method);

    AdminFinanceCommandService service = service();

    var response = service.updatePaymentMethod(actorUserId, method.getId(), new AdminPaymentMethodRequest(
        "Crypto USDT",
        "CRYPTO",
        "USDT",
        false,
        2,
        "TRC20 only"));

    assertThat(response.name()).isEqualTo("Crypto USDT");
    assertThat(response.enabled()).isFalse();
    verify(paymentMethodRepository).save(method);
    verify(auditLogService).record(
        eq(actorUserId),
        eq("ADMIN_PAYMENT_METHOD_UPDATE"),
        eq("PAYMENT_METHOD"),
        eq(method.getId().toString()),
        contains("Crypto USDT"));
  }

  @Test
  void deletePaymentMethodRemovesExistingMethodAndAuditsReason() {
    UUID actorUserId = UUID.randomUUID();
    AdminPaymentMethodEntity method = new AdminPaymentMethodEntity();
    method.setId(UUID.randomUUID());
    method.setName("Old Wallet");
    method.setMethodType("CRYPTO");
    method.setCurrency("USDT");
    method.setEnabled(true);
    when(paymentMethodRepository.findById(method.getId())).thenReturn(Optional.of(method));

    AdminFinanceCommandService service = service();
    service.deletePaymentMethod(actorUserId, method.getId(), "后台删除收款方式");

    verify(paymentMethodRepository).deleteById(method.getId());
    verify(auditLogService).record(
        eq(actorUserId),
        eq("ADMIN_PAYMENT_METHOD_DELETE"),
        eq("PAYMENT_METHOD"),
        eq(method.getId().toString()),
        contains("后台删除收款方式"));
  }

  private AdminFinanceCommandService service() {
    return new AdminFinanceCommandService(
        accountRepository,
        ledgerService,
        fundOperationRepository,
        paymentMethodRepository,
        auditLogService);
  }

  private TradingAccountEntity account(BigDecimal balance, BigDecimal usedMargin) {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(UUID.randomUUID());
    account.setUserId(UUID.randomUUID());
    account.setBalance(balance);
    account.setEquity(balance);
    account.setUsedMargin(usedMargin);
    account.setFreeMargin(balance.subtract(usedMargin));
    return account;
  }
}
