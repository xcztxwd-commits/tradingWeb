package com.fxplatform.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.dto.AccountResponse;
import com.fxplatform.account.dto.AccountSnapshot;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.ledger.service.LedgerService;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountServiceRegressionProtectionTest {

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private LedgerService ledgerService;

  @Mock
  private AccountSnapshotService accountSnapshotService;

  @Mock
  private DemoAccountLifecycleService demoAccountLifecycleService;

  @Mock
  private AccountTransferService accountTransferService;

  @Test
  void createDemoAccountUsesTheIdempotentLifecycleResult() {
    UUID userId = UUID.randomUUID();
    TradingAccountEntity expected = new TradingAccountEntity();
    expected.setId(UUID.randomUUID());
    expected.setUserId(userId);
    when(demoAccountLifecycleService.getOrCreateDemoAccount(userId)).thenReturn(expected);
    AccountService service = new AccountService(
        accountRepository,
        ledgerService,
        accountSnapshotService,
        null,
        null,
        demoAccountLifecycleService,
        accountTransferService);

    TradingAccountEntity account = service.createDemoAccount(userId);

    assertThat(account).isSameAs(expected);
    verify(demoAccountLifecycleService).getOrCreateDemoAccount(userId);
  }

  @Test
  void summaryUsesSnapshotValuesWithoutPersistingDynamicEquity() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setBaseCurrency("USD");
    account.setBalance(new BigDecimal("10000.00000000"));
    account.setEquity(new BigDecimal("10000.00000000"));
    account.setUsedMargin(new BigDecimal("110.02000000"));
    account.setFreeMargin(new BigDecimal("9889.98000000"));
    account.setLeverage(100);
    AccountSnapshot snapshot = new AccountSnapshot(
        accountId,
        new BigDecimal("10000.00000000"),
        new BigDecimal("10.00000000"),
        new BigDecimal("10010.00000000"),
        new BigDecimal("110.02000000"),
        BigDecimal.ZERO,
        new BigDecimal("9899.98000000"),
        new BigDecimal("9098.34575532"),
        "USD");
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(accountSnapshotService.snapshot(account)).thenReturn(snapshot);
    AccountService service = new AccountService(accountRepository, ledgerService, accountSnapshotService);

    AccountResponse response = service.summary(userId, accountId);

    assertThat(response.balance()).isEqualByComparingTo("10000.00000000");
    assertThat(response.equity()).isEqualByComparingTo("10010.00000000");
    assertThat(response.usedMargin()).isEqualByComparingTo("110.02000000");
    assertThat(response.freeMargin()).isEqualByComparingTo("9899.98000000");
    assertThat(response.marginLevel()).isEqualByComparingTo("9098.34575532");
    assertThat(account.getEquity()).isEqualByComparingTo("10000.00000000");
    assertThat(account.getFreeMargin()).isEqualByComparingTo("9889.98000000");
    verify(accountSnapshotService).snapshot(account);
    verify(accountRepository, never()).save(any());
  }

  @Test
  void summaryRejectsForeignAccountAsAuthorizationFailure() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.empty());
    AccountService service = new AccountService(accountRepository, ledgerService, accountSnapshotService);

    assertThatThrownBy(() -> service.summary(userId, accountId))
        .isInstanceOfSatisfying(AuthorizationException.class,
            error -> assertThat(error.getCode()).isEqualTo("ACCOUNT_NOT_FOUND"));
  }
}
