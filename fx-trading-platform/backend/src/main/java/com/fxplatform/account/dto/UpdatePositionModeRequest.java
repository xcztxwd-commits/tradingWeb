package com.fxplatform.account.dto;

import com.fxplatform.trading.enums.PositionMode;
import jakarta.validation.constraints.NotNull;

public record UpdatePositionModeRequest(@NotNull PositionMode positionMode) {
}
