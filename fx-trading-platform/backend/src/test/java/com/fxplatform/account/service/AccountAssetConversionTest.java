package com.fxplatform.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fxplatform.account.dto.AssetConversionRequest;
import com.fxplatform.account.dto.AssetConversionResponse;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.wallet.enums.WalletType;
import com.fxplatform.wallet.service.AssetConversionService;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountAssetConversionTest {

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private LedgerService ledgerService;

  @Mock
  private AccountSnapshotService accountSnapshotService;

  @Mock
  private WalletService walletService;

  @Mock
  private AssetConversionService assetConversionService;

  @Test
  void convertAssetChecksAccountOwnershipAndDelegatesWalletConversion() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID conversionId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    AssetConversionRequest request = new AssetConversionRequest(
        "SPOT",
        "USDT",
        "FX_MARGIN",
        "USD",
        new BigDecimal("250.00000000"),
        conversionId);
    AssetConversionService.ConversionResult result = new AssetConversionService.ConversionResult(
        accountId,
        "SPOT",
        "USDT",
        "FX_MARGIN",
        "USD",
        new BigDecimal("250.00000000"),
        new BigDecimal("250.00000000"),
        BigDecimal.ONE,
        conversionId);
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(assetConversionService.convert(
        userId,
        accountId,
        WalletType.SPOT,
        "USDT",
        WalletType.FX_MARGIN,
        "USD",
        new BigDecimal("250.00000000"),
        conversionId)).thenReturn(result);
    AccountService service = new AccountService(
        accountRepository,
        ledgerService,
        accountSnapshotService,
        walletService,
        assetConversionService);

    AssetConversionResponse response = service.convertAsset(userId, accountId, request);

    assertThat(response.accountId()).isEqualTo(accountId);
    assertThat(response.fromWalletType()).isEqualTo("SPOT");
    assertThat(response.toAsset()).isEqualTo("USD");
    verify(assetConversionService).convert(
        userId,
        accountId,
        WalletType.SPOT,
        "USDT",
        WalletType.FX_MARGIN,
        "USD",
        new BigDecimal("250.00000000"),
        conversionId);
  }

  @Test
  void wrongOwnerDoesNotReachTheConversionWriter() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    AssetConversionRequest request = new AssetConversionRequest(
        "SPOT", "USDT", "FX_MARGIN", "USD", BigDecimal.ONE, UUID.randomUUID());
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.empty());
    AccountService service = new AccountService(
        accountRepository,
        ledgerService,
        accountSnapshotService,
        walletService,
        assetConversionService);

    assertThatThrownBy(() -> service.convertAsset(userId, accountId, request))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("ACCOUNT_NOT_FOUND"));

    verify(assetConversionService, never()).convert(
        any(), any(), any(), any(), any(), any(), any(), any());
  }
}
