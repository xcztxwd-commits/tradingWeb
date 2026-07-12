package com.fxplatform.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.wallet.service.WalletService;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountWalletInitializationTest {

  @Test
  void applicationDefaultsMatchTheV47DemoTruth() throws Exception {
    String yaml = Files.readString(Path.of("src/main/resources/application.yml"));

    assertThat(yaml).contains(
        "default-demo-balance: ${DEFAULT_DEMO_BALANCE:50000}",
        "default-account-currency: ${DEFAULT_ACCOUNT_CURRENCY:USDT}",
        "default-leverage: ${DEFAULT_LEVERAGE:10}");
  }

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private LedgerService ledgerService;

  @Mock
  private AccountSnapshotService accountSnapshotService;

  @Mock
  private WalletService walletService;

  @Mock
  private DemoAccountLifecycleService demoAccountLifecycleService;

  @Mock
  private AccountTransferService accountTransferService;

  @Test
  void createDemoAccountDelegatesToTheIdempotentV47Lifecycle() {
    UUID userId = UUID.randomUUID();
    TradingAccountEntity expected = new TradingAccountEntity();
    expected.setId(UUID.randomUUID());
    expected.setUserId(userId);
    when(demoAccountLifecycleService.getOrCreateDemoAccount(userId)).thenReturn(expected);
    AccountService service = new AccountService(
        accountRepository,
        ledgerService,
        accountSnapshotService,
        walletService,
        null,
        demoAccountLifecycleService,
        accountTransferService);

    TradingAccountEntity account = service.createDemoAccount(userId);

    assertThat(account).isSameAs(expected);
    verify(demoAccountLifecycleService).getOrCreateDemoAccount(userId);
  }
}
