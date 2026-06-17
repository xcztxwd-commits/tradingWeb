package com.fxplatform.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.wallet.enums.WalletType;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AccountWalletInitializationTest {

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private LedgerService ledgerService;

  @Mock
  private AccountSnapshotService accountSnapshotService;

  @Mock
  private WalletService walletService;

  @Test
  void createDemoAccountCreditsInitialBaseCurrencyWalletWithoutChangingLegacyAccountBalances() {
    UUID userId = UUID.randomUUID();
    BigDecimal defaultBalance = new BigDecimal("10000.00000000");
    when(accountRepository.save(org.mockito.ArgumentMatchers.any(TradingAccountEntity.class)))
        .thenAnswer(invocation -> {
          TradingAccountEntity account = invocation.getArgument(0);
          account.setId(UUID.randomUUID());
          return account;
        });
    AccountService service = new AccountService(accountRepository, ledgerService, accountSnapshotService, walletService);
    ReflectionTestUtils.setField(service, "defaultDemoBalance", defaultBalance);
    ReflectionTestUtils.setField(service, "defaultCurrency", "USD");
    ReflectionTestUtils.setField(service, "defaultLeverage", 100);

    TradingAccountEntity account = service.createDemoAccount(userId);

    assertThat(account.getBalance()).isEqualByComparingTo(defaultBalance);
    assertThat(account.getEquity()).isEqualByComparingTo(defaultBalance);
    assertThat(account.getFreeMargin()).isEqualByComparingTo(defaultBalance);
    verify(walletService).creditAvailableWithEntryType(
        eq(account.getId()),
        eq(WalletType.FX_MARGIN),
        eq("USD"),
        eq(defaultBalance),
        eq("DEMO_ACCOUNT"),
        eq(account.getId()),
        eq("Initial DEMO wallet balance"),
        eq("CREDIT_AVAILABLE"));
    verify(walletService).creditAvailableWithEntryType(
        eq(account.getId()),
        eq(WalletType.SPOT),
        eq("USDT"),
        eq(defaultBalance),
        eq("DEMO_ACCOUNT"),
        eq(account.getId()),
        eq("Initial DEMO spot wallet balance"),
        eq("CREDIT_AVAILABLE"));
  }
}
