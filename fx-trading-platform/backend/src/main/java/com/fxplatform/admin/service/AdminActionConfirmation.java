package com.fxplatform.admin.service;

import com.fxplatform.common.exception.BusinessException;

public final class AdminActionConfirmation {

  public static final String CONFIRM_ADJUSTMENT = "CONFIRM_ADJUSTMENT";
  public static final String CONFIRM_APPROVE = "CONFIRM_APPROVE";
  public static final String CONFIRM_FORCE_CLOSE = "CONFIRM_FORCE_CLOSE";
  public static final String CONFIRM_DISABLE_SYMBOL = "CONFIRM_DISABLE_SYMBOL";
  public static final String CONFIRM_LEVERAGE_CHANGE = "CONFIRM_LEVERAGE_CHANGE";
  public static final String CONFIRM_PROVIDER_STATUS = "CONFIRM_PROVIDER_STATUS";
  public static final String CONFIRM_DISABLE_USER = "CONFIRM_DISABLE_USER";

  private AdminActionConfirmation() {
  }

  public static void require(String confirmationText, String expectedText) {
    if (expectedText.equals(confirmationText == null ? null : confirmationText.trim())) {
      return;
    }
    throw new BusinessException("ADMIN_CONFIRMATION_REQUIRED", "Confirmation text required: " + expectedText);
  }
}
