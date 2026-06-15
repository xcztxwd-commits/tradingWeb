package com.fxplatform.common.money;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.account.entity.TradingAccountEntity;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyAmountTest {

  @Test
  void returnsZeroForNullAmounts() {
    assertThat(MoneyAmount.orZero(null)).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(MoneyAmount.orZero(new BigDecimal("12.34"))).isEqualByComparingTo("12.34");
  }

  @Test
  void accountEquityFallsBackToBalance() {
    TradingAccountEntity account = new TradingAccountEntity();
    account.setBalance(new BigDecimal("100.00"));
    account.setEquity(null);

    assertThat(MoneyAmount.accountEquity(account)).isEqualByComparingTo("100.00");

    account.setEquity(new BigDecimal("98.50"));
    assertThat(MoneyAmount.accountEquity(account)).isEqualByComparingTo("98.50");
  }
}
