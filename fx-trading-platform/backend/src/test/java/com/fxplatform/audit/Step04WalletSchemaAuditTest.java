package com.fxplatform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.wallet.entity.AssetLedgerEntryEntity;
import com.fxplatform.wallet.entity.WalletBalanceEntity;
import com.fxplatform.wallet.enums.WalletType;
import com.fxplatform.wallet.repository.AssetLedgerEntryRepository;
import com.fxplatform.wallet.repository.WalletBalanceRepository;
import com.fxplatform.wallet.service.WalletService;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Step04WalletSchemaAuditTest {

  @Mock
  private WalletBalanceRepository walletBalanceRepository;

  @Mock
  private AssetLedgerEntryRepository assetLedgerEntryRepository;

  @Test
  void walletMigrationCreatesBalancesAndAssetLedgerWithConstraints() throws IOException {
    String migration = Files.readString(Path.of(
        "src",
        "main",
        "resources",
        "db",
        "migration",
        "V33__wallet_balances_and_asset_ledger.sql"));

    assertThat(migration)
        .contains("core.wallet_balances")
        .contains("ledger.asset_ledger_entries")
        .contains("account_id")
        .contains("asset")
        .contains("available")
        .contains("locked")
        .contains("total")
        .contains("ux_wallet_balances_account_asset")
        .contains("UNIQUE(account_id, asset)")
        .contains("total = available + locked");
  }

  @Test
  void walletTypeMigrationPartitionsBalancesByWalletType() throws IOException {
    String migration = Files.readString(Path.of(
        "src",
        "main",
        "resources",
        "db",
        "migration",
        "V40__wallet_type_asset_conversion.sql"));

    assertThat(migration)
        .contains("ADD COLUMN wallet_type")
        .contains("DEFAULT 'SPOT'")
        .contains("DROP CONSTRAINT ux_wallet_balances_account_asset")
        .contains("ux_wallet_balances_account_wallet_asset")
        .contains("UNIQUE(account_id, wallet_type, asset)")
        .contains("idx_asset_ledger_account_wallet_time");
  }

  @Test
  void walletServiceCreatesBalanceAndWritesAssetLedgerEntry() {
    UUID accountId = UUID.randomUUID();
    when(walletBalanceRepository.findByAccountIdAndWalletTypeAndAssetForUpdate(
        accountId, WalletType.SPOT.code(), "USDT"))
        .thenReturn(Optional.empty());
    when(walletBalanceRepository.save(any(WalletBalanceEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(assetLedgerEntryRepository.save(any(AssetLedgerEntryEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

    WalletBalanceEntity balance = new WalletService(walletBalanceRepository, assetLedgerEntryRepository)
        .creditAvailable(accountId, "usdt", new BigDecimal("100.00000000"), "AUDIT", UUID.randomUUID(), "Audit credit");

    assertThat(balance.getAsset()).isEqualTo("USDT");
    assertThat(balance.getWalletType()).isEqualTo(WalletType.SPOT.code());
    AuditAssertions.assertAmountClose(balance.getTotal(), "100.00000000");
    AuditAssertions.assertAmountClose(balance.getAvailable(), "100.00000000");
    AuditAssertions.assertAmountClose(balance.getLocked(), "0.00000000");

    ArgumentCaptor<AssetLedgerEntryEntity> captor = ArgumentCaptor.forClass(AssetLedgerEntryEntity.class);
    verify(assetLedgerEntryRepository).save(captor.capture());
    assertThat(captor.getValue().getEntryType()).isEqualTo("CREDIT_AVAILABLE");
    assertThat(captor.getValue().getWalletType()).isEqualTo(WalletType.SPOT.code());
    AuditAssertions.assertAmountClose(captor.getValue().getAmount(), "100.00000000");
    AuditAssertions.assertAmountClose(captor.getValue().getBalanceAfter(), "100.00000000");
  }
}
