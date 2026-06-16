package com.fxplatform.admin.enums;

public enum AdminTaskStatus {
  QUEUED,
  PROCESSING,
  COMPLETED,
  FAILED;

  public String code() {
    return name();
  }

  public static AdminTaskStatus fromCode(String value) {
    return valueOf(value.trim().toUpperCase());
  }
}
