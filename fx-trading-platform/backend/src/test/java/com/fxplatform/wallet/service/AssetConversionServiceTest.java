package com.fxplatform.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fxplatform.wallet.entity.AssetLedgerEntryEntity;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.enums.WalletType;
import com.fxplatform.wallet.repository.AssetLedgerEntryRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssetConversionServiceTest {

  @Mock
  private WalletBalanceRepository walletBalanceRepository;

  @Mock
  private AssetLedgerEntryRepository assetLedgerEntryRepository;

  private final Map<String, WalletBalanceEntity> balances = new HashMap<>();
  private final List<AssetLedgerEntryEntity> ledgerEntries = new ArrayList<>();

  @BeforeEach
  void setUpRepositories() {
    when(walletBalanceRepository.findByAccountIdAndWalletTypeAndAssetForUpdate(
        any(UUID.class), any(String.class), any(String.class)))
        .thenAnswer(invocation -> Optional.ofNullable(balance(
            invocation.getArgument(0),
            invocation.getArgument(1),
            invocation.getArgument(2))));
    when(walletBalanceRepository.save(any(WalletBalanceEntity.class))).thenAnswer(invocation -> {
      WalletBalanceEntity balance = invocation.getArgument(0);
      if (balance.getId() == null) {
        balance.setId(UUID.randomUUID());
      }
      balances.put(key(balance.getAccountId(), balance.getWalletType(), balance.getAsset()), balance);
      return balance;
    });
    when(assetLedgerEntryRepository.save(any(AssetLedgerEntryEntity.class))).thenAnswer(invocation -> {
      AssetLedgerEntryEntity entry = invocation.getArgument(0);
      ledgerEntries.add(entry);
      return entry;
    });
  }

  @Test
  void convertsUsdtPerpUsdtToFxMarginUsdAtDemoParity() {
    UUID accountId = UUID.randomUUID();
    UUID conversionId = UUID.randomUUID();
    putBalance(accountId, WalletType.USDT_PERP, "USDT", "1000.00000000", "1000.00000000", "0");

    AssetConversionService.ConversionResult result = service().convert(
        accountId,
        WalletType.USDT_PERP,
        "USDT",
        WalletType.FX_MARGIN,
        "USD",
        new BigDecimal("250.00000000"),
        conversionId);

    assertThat(result.fromAmount()).isEqualByComparingTo("250.00000000");
    assertThat(result.toAmount()).isEqualByComparingTo("250.00000000");
    assertThat(balance(accountId, WalletType.USDT_PERP.code(), "USDT").getAvailable())
        .isEqualByComparingTo("750.00000000");
    assertThat(balance(accountId, WalletType.FX_MARGIN.code(), "USD").getAvailable())
        .isEqualByComparingTo("250.00000000");
    assertThat(ledgerEntries)
        .extracting(AssetLedgerEntryEntity::getEntryType)
        .containsExactly("CONVERT_OUT", "CONVERT_IN");
    assertThat(ledgerEntries)
        .extracting(AssetLedgerEntryEntity::getWalletType)
        .containsExactly(WalletType.USDT_PERP.code(), WalletType.FX_MARGIN.code());
  }

  private AssetConversionService service() {
    WalletService walletService = new WalletService(walletBalanceRepository, assetLedgerEntryRepository);
    return new AssetConversionService(walletService);
  }

  private WalletBalanceEntity balance(UUID accountId, String walletType, String asset) {
    return balances.get(key(accountId, walletType, asset));
  }

  private void putBalance(
      UUID accountId,
      WalletType walletType,
      String asset,
      String total,
      String available,
      String locked
  ) {
    WalletBalanceEntity balance = new WalletBalanceEntity();
    balance.setId(UUID.randomUUID());
    balance.setAccountId(accountId);
    balance.setWalletType(walletType.code());
    balance.setAsset(asset);
    balance.setTotal(new BigDecimal(total));
    balance.setAvailable(new BigDecimal(available));
    balance.setLocked(new BigDecimal(locked));
    balances.put(key(accountId, walletType.code(), asset), balance);
  }

  private static String key(UUID accountId, String walletType, String asset) {
    return accountId + ":" + walletType + ":" + asset;
  }
}
