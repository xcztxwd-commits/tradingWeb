package com.fxplatform.admin.enums;

public enum AdminFeatureRecordStatus {
  ACTIVE,
  DELETED;

  public String code() {
    return name();
  }

  public static AdminFeatureRecordStatus fromAction(String action) {
    return "delete".equals(action) ? DELETED : ACTIVE;
  }

  public static AdminFeatureRecordStatus fromCode(String value) {
    return valueOf(value.trim().toUpperCase());
  }
}
