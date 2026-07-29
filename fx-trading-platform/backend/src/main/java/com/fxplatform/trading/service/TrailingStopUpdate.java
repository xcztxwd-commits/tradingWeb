package com.fxplatform.trading.service;

import java.math.BigDecimal;
import java.util.UUID;

public record TrailingStopUpdate(
    UUID orderId,
    BigDecimal nextExtreme,
    boolean activated,
    boolean triggered
) {
}
