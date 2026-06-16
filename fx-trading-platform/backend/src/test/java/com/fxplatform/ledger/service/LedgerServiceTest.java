package com.fxplatform.ledger.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.ledger.dto.LedgerEntryResponse;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

  @Mock
  private LedgerEntryRepository ledgerEntryRepository;

  @Mock
  private TradingAccountRepository accountRepository;

  @Mock
  private AssetLedgerEntryRepository assetLedgerEntryRepository;

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

  @Test
  void visibleEntriesIncludesSpotAssetLedgerEntriesForOwnedAccount() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    LedgerEntryEntity demoDeposit = new LedgerEntryEntity();
    demoDeposit.setId(UUID.randomUUID());
    demoDeposit.setAccountId(accountId);
    demoDeposit.setEntryType(LedgerEntryType.DEMO_DEPOSIT);
    demoDeposit.setAmount(new BigDecimal("10000.00000000"));
    demoDeposit.setBalanceAfter(new BigDecimal("10000.00000000"));
    demoDeposit.setCurrency("USD");
    demoDeposit.setCreatedAt(Instant.parse("2026-06-16T00:00:00Z"));
    AssetLedgerEntryEntity spotEntry = new AssetLedgerEntryEntity();
    spotEntry.setId(UUID.randomUUID());
    spotEntry.setAccountId(accountId);
    spotEntry.setAsset("USDT");
    spotEntry.setAmount(new BigDecimal("-5000.00000000"));
    spotEntry.setBalanceAfter(new BigDecimal("5000.00000000"));
    spotEntry.setEntryType("SPOT_BUY_QUOTE_OUT");
    spotEntry.setReferenceType("ORDER");
    spotEntry.setReferenceId(orderId);
    spotEntry.setDescription("Spot buy quote spent");
    spotEntry.setCreatedAt(Instant.parse("2026-06-16T00:01:00Z"));

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId)).thenReturn(List.of(demoDeposit));
    when(assetLedgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId)).thenReturn(List.of(spotEntry));

    LedgerService service = new LedgerService(ledgerEntryRepository, accountRepository, assetLedgerEntryRepository);

    List<LedgerEntryResponse> entries = service.visibleEntries(userId, accountId);

    assertThat(entries).extracting(LedgerEntryResponse::entryType)
        .containsExactly("SPOT_BUY_QUOTE_OUT", "DEMO_DEPOSIT");
    assertThat(entries.getFirst().currency()).isEqualTo("USDT");
    assertThat(entries.getFirst().referenceId()).isEqualTo(orderId);
  }

  @Test
  void recordFundingFeeWritesSignedFundingLedgerEntry() {
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setBaseCurrency("USD");
    account.setBalance(new BigDecimal("9995.00000000"));

    when(ledgerEntryRepository.save(any(LedgerEntryEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    LedgerService service = new LedgerService(ledgerEntryRepository, accountRepository);

    LedgerEntryEntity entry = service.recordFundingFee(
        account,
        new BigDecimal("-5.00000000"),
        positionId,
        "Perpetual funding fee");

    assertThat(entry.getAccountId()).isEqualTo(accountId);
    assertThat(entry.getEntryType()).isEqualTo(LedgerEntryType.FUNDING_FEE);
    assertThat(entry.getAmount()).isEqualByComparingTo("-5.00000000");
    assertThat(entry.getBalanceAfter()).isEqualByComparingTo("9995.00000000");
    assertThat(entry.getCurrency()).isEqualTo("USD");
    assertThat(entry.getReferenceType()).isEqualTo("POSITION");
    assertThat(entry.getReferenceId()).isEqualTo(positionId);
  }

  @Test
  void recordFinancingWritesSignedFxFinancingLedgerEntry() {
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setBaseCurrency("USD");
    account.setBalance(new BigDecimal("9990.00000000"));

    when(ledgerEntryRepository.save(any(LedgerEntryEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    LedgerService service = new LedgerService(ledgerEntryRepository, accountRepository);

    LedgerEntryEntity entry = service.recordFinancing(
        account,
        new BigDecimal("-10.00000000"),
        positionId,
        "FX rollover financing");

    assertThat(entry.getAccountId()).isEqualTo(accountId);
    assertThat(entry.getEntryType()).isEqualTo(LedgerEntryType.FINANCING);
    assertThat(entry.getAmount()).isEqualByComparingTo("-10.00000000");
    assertThat(entry.getBalanceAfter()).isEqualByComparingTo("9990.00000000");
    assertThat(entry.getCurrency()).isEqualTo("USD");
    assertThat(entry.getReferenceType()).isEqualTo("POSITION");
    assertThat(entry.getReferenceId()).isEqualTo(positionId);
    assertThat(entry.getDescription()).isEqualTo("FX rollover financing");
  }

  @Test
  void recordForcedCloseWritesZeroAmountForcedCloseEntry() {
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setBaseCurrency("USD");
    account.setBalance(new BigDecimal("950.00000000"));

    when(ledgerEntryRepository.save(any(LedgerEntryEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    LedgerService service = new LedgerService(ledgerEntryRepository, accountRepository);

    LedgerEntryEntity entry = service.recordForcedClose(
        account,
        positionId,
        "FX_MARGIN_STOP_OUT");

    assertThat(entry.getAccountId()).isEqualTo(accountId);
    assertThat(entry.getEntryType()).isEqualTo(LedgerEntryType.FORCED_CLOSE);
    assertThat(entry.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(entry.getBalanceAfter()).isEqualByComparingTo("950.00000000");
    assertThat(entry.getCurrency()).isEqualTo("USD");
    assertThat(entry.getReferenceType()).isEqualTo("POSITION");
    assertThat(entry.getReferenceId()).isEqualTo(positionId);
    assertThat(entry.getDescription()).isEqualTo("FX_MARGIN_STOP_OUT");
  }
}
