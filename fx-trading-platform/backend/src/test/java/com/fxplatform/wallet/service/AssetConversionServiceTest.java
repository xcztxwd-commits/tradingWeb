package com.fxplatform.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.execution.DemoExecutionGuard;
import com.fxplatform.wallet.enums.WalletType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssetConversionServiceTest {

  @Mock TradingAccountRepository accountRepository;
  @Mock WalletService walletService;
  @Mock DemoExecutionGuard demoExecutionGuard;

  private UUID userId;
  private UUID accountId;
  private TradingAccountEntity account;
  private AssetConversionService service;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    accountId = UUID.randomUUID();
    account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    account.setAccountType(AccountType.DEMO);
    account.setStatus(AccountStatus.ACTIVE);
    service = new AssetConversionService(accountRepository, walletService, demoExecutionGuard);
  }

  @Test
  void conversionLocksOwnerThenCanonicalWalletKeysBeforeDebitAndCredit() {
    UUID conversionId = UUID.randomUUID();
    BigDecimal amount = new BigDecimal("250.00000000");
    List<WalletService.WalletKey> canonicalKeys = List.of(
        new WalletService.WalletKey(WalletType.FX_MARGIN, "USD"),
        new WalletService.WalletKey(WalletType.SPOT, "USDT"));
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, userId))
        .thenReturn(Optional.of(account));
    when(walletService.lockWalletsInOrder(accountId, canonicalKeys)).thenReturn(List.of());

    AssetConversionService.ConversionResult result = service.convert(
        userId,
        accountId,
        WalletType.SPOT,
        "USDT",
        WalletType.FX_MARGIN,
        "USD",
        amount,
        conversionId);

    assertThat(result.fromAmount()).isEqualByComparingTo(amount);
    assertThat(result.toAmount()).isEqualByComparingTo(amount);
    InOrder order = inOrder(accountRepository, demoExecutionGuard, walletService);
    order.verify(accountRepository).findByIdAndUserIdForUpdate(accountId, userId);
    order.verify(demoExecutionGuard).requireDemoAccount(account);
    order.verify(walletService).lockWalletsInOrder(accountId, canonicalKeys);
    order.verify(walletService).debitAvailableWithEntryType(
        accountId, WalletType.SPOT, "USDT", amount, "ASSET_CONVERSION", conversionId,
        "Demo asset conversion out", "CONVERT_OUT");
    order.verify(walletService).creditAvailableWithEntryType(
        accountId, WalletType.FX_MARGIN, "USD", amount, "ASSET_CONVERSION", conversionId,
        "Demo asset conversion in", "CONVERT_IN");
  }

  @Test
  void wrongOwnerFailsBeforeGuardWalletLocksOrLedgerMutations() {
    UUID attacker = UUID.randomUUID();
    when(accountRepository.findByIdAndUserIdForUpdate(accountId, attacker)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.convert(
        attacker,
        accountId,
        WalletType.SPOT,
        "USDT",
        WalletType.FX_MARGIN,
        "USD",
        BigDecimal.ONE,
        UUID.randomUUID()))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("ACCOUNT_NOT_FOUND"));

    verify(demoExecutionGuard, never()).requireDemoAccount(any());
    verify(walletService, never()).lockWalletsInOrder(any(), any());
    verify(walletService, never()).debitAvailableWithEntryType(
        any(), any(), any(), any(), any(), any(), any(), any());
    verify(walletService, never()).creditAvailableWithEntryType(
        any(), any(), any(), any(), any(), any(), any(), any());
  }
}
