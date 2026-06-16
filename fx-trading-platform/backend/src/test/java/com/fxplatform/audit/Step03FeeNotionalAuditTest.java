package com.fxplatform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.ledger.entity.LedgerEntryEntity;
import com.fxplatform.ledger.enums.LedgerEntryType;
import com.fxplatform.ledger.repository.LedgerEntryRepository;
import com.fxplatform.ledger.service.LedgerService;
import com.fxplatform.risk.service.TradingAlgorithmEngine;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Step03FeeNotionalAuditTest {

  @Mock
  private LedgerEntryRepository ledgerEntryRepository;

  @Mock
  private TradingAccountRepository accountRepository;

  private final TradingAlgorithmEngine engine = new TradingAlgorithmEngine();

  @Test
  void forexFeeUsesQuantityPriceUnitSizeNotJustLotsPrice() {
    BigDecimal fee = engine.linearFee(
        new BigDecimal("0.10"),
        new BigDecimal("1.10000"),
        new BigDecimal("0.001"),
        new BigDecimal("100000"));

    AuditAssertions.assertAmountClose(fee, "11.00000000");
  }

  @Test
  void spotAndLinearFeesUseFullQuoteNotional() {
    AuditAssertions.assertAmountClose(
        engine.linearFee(new BigDecimal("0.1"), new BigDecimal("50000"), new BigDecimal("0.001"), BigDecimal.ONE),
        "5.00000000");
    AuditAssertions.assertAmountClose(
        engine.linearFee(BigDecimal.ONE, new BigDecimal("50000"), new BigDecimal("0.001"), BigDecimal.ONE),
        "50.00000000");
  }

  @Test
  void inverseFeeUsesUsdNotionalConvertedToCoin() {
    BigDecimal usdNotional = engine.inverseUsdNotional(new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ONE);

    AuditAssertions.assertBtcClose(
        engine.inverseFee(usdNotional, new BigDecimal("50000"), new BigDecimal("0.001")),
        "0.00020000");
  }

  @Test
  void tradeFeeLedgerStoresNegativeAmountAndBalanceAfter() {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(UUID.randomUUID());
    account.setBaseCurrency("USD");
    account.setBalance(new BigDecimal("9989.00000000"));
    when(ledgerEntryRepository.save(any(LedgerEntryEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    new LedgerService(ledgerEntryRepository, accountRepository)
        .recordTradeFee(account, new BigDecimal("11.00000000"), UUID.randomUUID(), "Audit fee");

    ArgumentCaptor<LedgerEntryEntity> captor = ArgumentCaptor.forClass(LedgerEntryEntity.class);
    verify(ledgerEntryRepository).save(captor.capture());
    assertThat(captor.getValue().getEntryType()).isEqualTo(LedgerEntryType.TRADE_FEE);
    AuditAssertions.assertAmountClose(captor.getValue().getAmount(), "-11.00000000");
    AuditAssertions.assertAmountClose(captor.getValue().getBalanceAfter(), "9989.00000000");
  }
}
