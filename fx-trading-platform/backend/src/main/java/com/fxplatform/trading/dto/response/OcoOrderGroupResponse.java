package com.fxplatform.trading.dto.response;

import java.util.UUID;

/** One OCO contingency group with its LIMIT and STOP_MARKET legs. */
public record OcoOrderGroupResponse(
    UUID contingencyGroupId,
    OrderResponse limitOrder,
    OrderResponse stopOrder
) {
}
