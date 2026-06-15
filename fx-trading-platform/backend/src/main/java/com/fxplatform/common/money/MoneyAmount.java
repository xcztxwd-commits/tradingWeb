package com.fxplatform.common.money;

import com.fxplatform.account.entity.TradingAccountEntity;
import java.math.BigDecimal;

public final class MoneyAmount {

  private MoneyAmount() {
  }

  public static BigDecimal orZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  public static BigDecimal accountEquity(TradingAccountEntity account) {
    return account.getEquity() != null ? account.getEquity() : orZero(account.getBalance());
  }
}
