package com.fxplatform.account.dto;

import com.fxplatform.wallet.entity.WalletBalanceEntity;
import java.math.BigDecimal;
import java.util.UUID;

public record WalletBalanceResponse(
    UUID id,
    UUID accountId,
    String asset,
    BigDecimal total,
    BigDecimal available,
    BigDecimal locked
) {

  public static WalletBalanceResponse from(WalletBalanceEntity balance) {
    return new WalletBalanceResponse(
        balance.getId(),
        balance.getAccountId(),
        balance.getAsset(),
        balance.getTotal(),
        balance.getAvailable(),
        balance.getLocked());
  }
}
