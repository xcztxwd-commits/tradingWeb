package com.fxplatform.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.ledger.entity.LedgerEntryEntity;
import com.fxplatform.ledger.enums.LedgerEntryType;
import com.fxplatform.ledger.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LiquidationFeeLedgerServiceTest {

  @Mock
  private LedgerEntryRepository ledgerEntryRepository;

  @Mock
  private TradingAccountRepository accountRepository;

  @Test
  void recordLiquidationFeeWritesDebitLedgerEntry() {
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setBaseCurrency("USD");
    account.setBalance(new BigDecimal("980.00000000"));

    when(ledgerEntryRepository.save(any(LedgerEntryEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    LedgerEntryEntity entry = new LedgerService(ledgerEntryRepository, accountRepository)
        .recordLiquidationFee(account, new BigDecimal("20.00000000"), positionId, "Liquidation fee charged");

    assertThat(entry.getAccountId()).isEqualTo(accountId);
    assertThat(entry.getEntryType()).isEqualTo(LedgerEntryType.LIQUIDATION_FEE);
    assertThat(entry.getAmount()).isEqualByComparingTo("-20.00000000");
    assertThat(entry.getBalanceAfter()).isEqualByComparingTo("980.00000000");
    assertThat(entry.getCurrency()).isEqualTo("USD");
    assertThat(entry.getReferenceType()).isEqualTo("POSITION");
    assertThat(entry.getReferenceId()).isEqualTo(positionId);
    assertThat(entry.getDescription()).isEqualTo("Liquidation fee charged");
  }
}
