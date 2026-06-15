package com.fxplatform.account.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * AccountResponse 承载账户模块的数据结构。
 */
public record AccountResponse(
    UUID id,
    String accountType,
    String baseCurrency,
    BigDecimal balance,
    BigDecimal equity,
    BigDecimal usedMargin,
    BigDecimal freeMargin,
    BigDecimal marginLevel,
    Integer leverage,
    String status
) {
}
