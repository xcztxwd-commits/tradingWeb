package com.fxplatform.account.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.fxplatform.ledger.service.LedgerService;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AccountServiceRegressionProtectionTest {

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private LedgerService ledgerService;

  @Mock
  private AccountSnapshotService accountSnapshotService;

  @Test
  void createDemoAccountInitializesBalanceEquityAndFreeMarginFromConfiguredDefaults() {
    UUID userId = UUID.randomUUID();
    BigDecimal defaultBalance = new BigDecimal("10000.00000000");
    AccountService service = new AccountService(accountRepository, ledgerService, accountSnapshotService);
    ReflectionTestUtils.setField(service, "defaultDemoBalance", defaultBalance);
    ReflectionTestUtils.setField(service, "defaultCurrency", "USD");
    ReflectionTestUtils.setField(service, "defaultLeverage", 100);
    when(accountRepository.save(org.mockito.ArgumentMatchers.any(TradingAccountEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    TradingAccountEntity account = service.createDemoAccount(userId);

    assertThat(account.getUserId()).isEqualTo(userId);
    assertThat(account.getAccountType()).isEqualTo(AccountType.DEMO);
    assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    assertThat(account.getBaseCurrency()).isEqualTo("USD");
    assertThat(account.getLeverage()).isEqualTo(100);
    assertThat(account.getBalance()).isEqualByComparingTo(defaultBalance);
    assertThat(account.getEquity()).isEqualByComparingTo(defaultBalance);
    assertThat(account.getFreeMargin()).isEqualByComparingTo(defaultBalance);
    assertThat(account.getUsedMargin()).isEqualByComparingTo(BigDecimal.ZERO);
    verify(accountRepository).save(account);
    verify(ledgerService).recordDemoDeposit(eq(account), eq(defaultBalance), eq("Initial DEMO balance"));
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
}
