package com.fxplatform.wallet.enums;

import com.fxplatform.common.exception.BusinessException;
import java.util.Locale;

public enum WalletType {
  FX_MARGIN,
  SPOT,
  USDT_PERP,
  COIN_PERP,
  FUNDING;

  public String code() {
    return name();
  }

  public static WalletType fromCode(String value) {
    String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    for (WalletType type : values()) {
      if (type.name().equals(normalized)) {
        return type;
      }
    }
    throw new BusinessException("INVALID_WALLET_TYPE", "Invalid wallet type");
  }
}
