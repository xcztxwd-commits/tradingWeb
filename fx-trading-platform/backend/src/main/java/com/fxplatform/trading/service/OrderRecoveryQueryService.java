package com.fxplatform.trading.service;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.trading.dto.response.OrderResponse;
import com.fxplatform.trading.repository.OrderRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Narrow authenticated read seam used to reconcile an uncertain create-order outcome. */
@Service
public class OrderRecoveryQueryService {

  private final OrderRepository orderRepository;
  private final OrderResponseMapper responseMapper;

  public OrderRecoveryQueryService(
      OrderRepository orderRepository,
      OrderResponseMapper responseMapper
  ) {
    this.orderRepository = orderRepository;
    this.responseMapper = responseMapper;
  }

  public OrderResponse find(
      UserPrincipal principal,
      UUID accountId,
      String clientOrderId
  ) {
    if (principal == null
        || accountId == null
        || clientOrderId == null
        || clientOrderId.isBlank()) {
      throw new BusinessException("BAD_REQUEST", "Order recovery identity is required");
    }
    return orderRepository.findAnyByUserIdAndAccountIdAndClientOrderId(
            principal.id(), accountId, clientOrderId)
        .map(responseMapper::toResponse)
        .orElse(null);
  }
}
