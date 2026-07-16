package com.fxplatform.account.dto;

import com.fxplatform.account.dto.AccountTransferRequest.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountTransferResponse(
    UUID accountId,
    UUID transferId,
    Direction direction,
    BigDecimal amount,
    BigDecimal spotAvailable,
    BigDecimal perpBalance,
    BigDecimal perpFreeMargin,
    boolean replayed,
    Instant createdAt
) {
}
