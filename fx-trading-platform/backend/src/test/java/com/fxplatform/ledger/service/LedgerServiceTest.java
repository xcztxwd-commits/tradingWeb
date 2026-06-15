package com.fxplatform.ledger.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.ledger.repository.LedgerEntryRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

  @Mock
  private LedgerEntryRepository ledgerEntryRepository;

  @Mock
  private TradingAccountRepository accountRepository;

  @Test
  void entriesRejectsAccountThatDoesNotBelongToUser() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.empty());

    LedgerService service = new LedgerService(ledgerEntryRepository, accountRepository);

    assertThatThrownBy(() -> service.entries(userId, accountId))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("Account not found");

    verify(ledgerEntryRepository, never()).findByAccountIdOrderByCreatedAtDesc(accountId);
  }

  @Test
  void entriesReadsLedgerForOwnedAccount() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));

    LedgerService service = new LedgerService(ledgerEntryRepository, accountRepository);

    service.entries(userId, accountId);

    verify(ledgerEntryRepository).findByAccountIdOrderByCreatedAtDesc(accountId);
  }
}
