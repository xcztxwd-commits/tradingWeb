package com.fxplatform.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fxplatform.account.dto.AssetLedgerEntryResponse;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.wallet.entity.AssetLedgerEntryEntity;
import com.fxplatform.wallet.enums.WalletType;
import com.fxplatform.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountAssetLedgerTest {

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private LedgerService ledgerService;

  @Mock
  private AccountSnapshotService accountSnapshotService;

  @Mock
  private WalletService walletService;

  @Test
  void assetLedgerReturnsOwnedAccountEntriesWithFilters() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    Instant from = Instant.parse("2026-06-16T00:00:00Z");
    Instant to = Instant.parse("2026-06-16T23:59:59Z");
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    AssetLedgerEntryEntity entity = assetEntry(
        accountId,
        WalletType.USDT_PERP.code(),
        "USDT",
        "-5000.00000000",
        "5000.00000000",
        "SPOT_BUY_QUOTE_OUT",
        orderId,
        from.plusSeconds(10));

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(walletService.assetLedgerEntries(accountId, "USDT_PERP", "usdt", "SPOT_BUY_QUOTE_OUT", orderId, from, to))
        .thenReturn(List.of(entity));

    AccountService service = new AccountService(accountRepository, ledgerService, accountSnapshotService, walletService);

    List<AssetLedgerEntryResponse> entries = service.assetLedger(
        userId,
        accountId,
        "USDT_PERP",
        "usdt",
        "SPOT_BUY_QUOTE_OUT",
        orderId,
        from,
        to);

    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().walletType()).isEqualTo("USDT_PERP");
    assertThat(entries.getFirst().asset()).isEqualTo("USDT");
    assertThat(entries.getFirst().entryType()).isEqualTo("SPOT_BUY_QUOTE_OUT");
    assertThat(entries.getFirst().referenceId()).isEqualTo(orderId);
  }

  private static AssetLedgerEntryEntity assetEntry(
      UUID accountId,
      String walletType,
      String asset,
      String amount,
      String balanceAfter,
      String entryType,
      UUID referenceId,
      Instant createdAt
  ) {
    AssetLedgerEntryEntity entry = new AssetLedgerEntryEntity();
    entry.setId(UUID.randomUUID());
    entry.setAccountId(accountId);
    entry.setWalletType(walletType);
    entry.setAsset(asset);
    entry.setAmount(new BigDecimal(amount));
    entry.setBalanceAfter(new BigDecimal(balanceAfter));
    entry.setEntryType(entryType);
    entry.setReferenceType("ORDER");
    entry.setReferenceId(referenceId);
    entry.setDescription("Spot asset ledger entry");
    entry.setCreatedAt(createdAt);
    return entry;
  }
}
