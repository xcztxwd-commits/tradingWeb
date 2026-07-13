package com.fxplatform.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fxplatform.account.dto.AccountTransferRequest.Direction;
import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.ledger.entity.LedgerEntryEntity;
import com.fxplatform.ledger.enums.LedgerEntryType;
import com.fxplatform.ledger.repository.LedgerEntryRepository;
import com.fxplatform.wallet.entity.AssetLedgerEntryEntity;
import com.fxplatform.wallet.repository.AssetLedgerEntryRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AccountTransferQueryServiceTest {

  private final TradingAccountRepository accountRepository =
      org.mockito.Mockito.mock(TradingAccountRepository.class);
  private final LedgerEntryRepository ledgerEntryRepository =
      org.mockito.Mockito.mock(LedgerEntryRepository.class);
  private final AssetLedgerEntryRepository assetLedgerEntryRepository =
      org.mockito.Mockito.mock(AssetLedgerEntryRepository.class);
  private final AccountTransferQueryService service = new AccountTransferQueryService(
      accountRepository, ledgerEntryRepository, assetLedgerEntryRepository);

  @Test
  void historyChecksOwnershipBeforeDirectionFilteredBoundedDatabasePage() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID transferId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    LedgerEntryEntity cash = cashTransfer(
        accountId, transferId, LedgerEntryType.TRANSFER_OUT, "-50.00000000");
    AssetLedgerEntryEntity spot = assetTransfer(
        accountId, transferId, "TRANSFER_IN", "50.00000000");
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(ledgerEntryRepository.findTransferPage(
        eq(accountId), eq(LedgerEntryType.TRANSFER_OUT.name()), any(Page.class)))
        .thenAnswer(invocation -> page(invocation.getArgument(2), cash, 201));
    when(assetLedgerEntryRepository.findByReferences(
        accountId, "TRANSFER", List.of(transferId)))
        .thenReturn(List.of(spot));

    var result = service.history(userId, accountId, Direction.PERP_TO_SPOT, -1, 999);

    assertThat(result.page()).isZero();
    assertThat(result.size()).isEqualTo(100);
    assertThat(result.total()).isEqualTo(201);
    assertThat(result.items()).singleElement().satisfies(item -> {
      assertThat(item.transferId()).isEqualTo(transferId);
      assertThat(item.direction()).isEqualTo(Direction.PERP_TO_SPOT);
      assertThat(item.amount()).isEqualByComparingTo("50.00000000");
      assertThat(item.spotAvailable()).isEqualByComparingTo("1050.00000000");
      assertThat(item.perpBalance()).isEqualByComparingTo("950.00000000");
    });
    var ordered = inOrder(accountRepository, ledgerEntryRepository);
    ordered.verify(accountRepository).findByIdAndUserId(accountId, userId);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Page<LedgerEntryEntity>> request = ArgumentCaptor.forClass(Page.class);
    ordered.verify(ledgerEntryRepository).findTransferPage(
        eq(accountId), eq(LedgerEntryType.TRANSFER_OUT.name()), request.capture());
    assertThat(request.getValue().getCurrent()).isEqualTo(1);
    assertThat(request.getValue().getSize()).isEqualTo(100);
    verify(assetLedgerEntryRepository).findByReferences(
        accountId, "TRANSFER", List.of(transferId));
  }

  @Test
  void historyRejectsUnownedAccountBeforeReadingTransferLedgers() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.history(userId, accountId, null, 0, 20))
        .isInstanceOfSatisfying(AuthorizationException.class,
            error -> assertThat(error.getCode()).isEqualTo("ACCOUNT_NOT_FOUND"));

    verify(ledgerEntryRepository, never()).findTransferPage(any(), any(), any());
  }

  private static LedgerEntryEntity cashTransfer(
      UUID accountId,
      UUID transferId,
      LedgerEntryType type,
      String amount
  ) {
    LedgerEntryEntity entry = new LedgerEntryEntity();
    entry.setId(UUID.randomUUID());
    entry.setAccountId(accountId);
    entry.setReferenceType("TRANSFER");
    entry.setReferenceId(transferId);
    entry.setEntryType(type);
    entry.setOperationType(type.name());
    entry.setAmount(new BigDecimal(amount));
    entry.setBalanceAfter(new BigDecimal("950.00000000"));
    entry.setCreatedAt(Instant.parse("2026-07-13T00:00:00Z"));
    return entry;
  }

  private static AssetLedgerEntryEntity assetTransfer(
      UUID accountId,
      UUID transferId,
      String operationType,
      String amount
  ) {
    AssetLedgerEntryEntity entry = new AssetLedgerEntryEntity();
    entry.setId(UUID.randomUUID());
    entry.setAccountId(accountId);
    entry.setReferenceType("TRANSFER");
    entry.setReferenceId(transferId);
    entry.setOperationType(operationType);
    entry.setAmount(new BigDecimal(amount));
    entry.setBalanceAfter(new BigDecimal("1050.00000000"));
    entry.setCreatedAt(Instant.parse("2026-07-13T00:00:00Z"));
    return entry;
  }

  private static <T> Page<T> page(Page<T> page, T record, long total) {
    page.setRecords(List.of(record));
    page.setTotal(total);
    return page;
  }
}
