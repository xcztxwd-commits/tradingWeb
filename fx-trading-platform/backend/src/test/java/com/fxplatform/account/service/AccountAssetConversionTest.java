package com.fxplatform.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.dto.AssetConversionRequest;
import com.fxplatform.account.dto.AssetConversionResponse;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
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
        "USDT_PERP",
        "USDT",
        "FX_MARGIN",
        "USD",
        new BigDecimal("250.00000000"),
        conversionId);
    AssetConversionService.ConversionResult result = new AssetConversionService.ConversionResult(
        accountId,
        "USDT_PERP",
        "USDT",
        "FX_MARGIN",
        "USD",
        new BigDecimal("250.00000000"),
        new BigDecimal("250.00000000"),
        BigDecimal.ONE,
        conversionId);
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(assetConversionService.convert(
        accountId,
        WalletType.USDT_PERP,
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
    assertThat(response.fromWalletType()).isEqualTo("USDT_PERP");
    assertThat(response.toAsset()).isEqualTo("USD");
    verify(assetConversionService).convert(
        accountId,
        WalletType.USDT_PERP,
        "USDT",
        WalletType.FX_MARGIN,
        "USD",
        new BigDecimal("250.00000000"),
        conversionId);
  }
}
