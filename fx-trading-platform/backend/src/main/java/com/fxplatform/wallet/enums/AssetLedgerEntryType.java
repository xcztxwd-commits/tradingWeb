package com.fxplatform.wallet.enums;

import com.fxplatform.common.exception.BusinessException;
import java.util.Locale;

public enum AssetLedgerEntryType {
  CREDIT_AVAILABLE,
  DEBIT_AVAILABLE,
  LOCK_AVAILABLE,
  RELEASE_LOCKED,
  DEBIT_LOCKED;

  public String code() {
    return name();
  }

  public static AssetLedgerEntryType fromCode(String value) {
    String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    for (AssetLedgerEntryType type : values()) {
      if (type.name().equals(normalized)) {
        return type;
      }
    }
    throw new BusinessException("INVALID_ASSET_LEDGER_ENTRY_TYPE", "Invalid asset ledger entry type");
  }
}
