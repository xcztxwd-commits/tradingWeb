package com.fxplatform.finance.service;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.finance.dto.request.FundOrderRequest;
import com.fxplatform.finance.dto.response.FundOrderResponse;
import com.fxplatform.finance.entity.FundOrderEntity;
import com.fxplatform.finance.enums.FundOrderStatus;
import com.fxplatform.finance.enums.FundOrderType;
import com.fxplatform.finance.repository.FundOrderRepository;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FundOrderService {

  private final FundOrderRepository fundOrderRepository;
  private final TradingAccountRepository accountRepository;

  public List<FundOrderResponse> orders(UUID userId, UUID accountId) {
    requireOwnedAccount(userId, accountId);
    return fundOrderRepository.findByUserIdAndAccountIdOrderByCreatedAtDesc(userId, accountId).stream()
        .map(FundOrderResponse::from)
        .toList();
  }

  @Transactional
  public FundOrderResponse createOrder(UUID userId, FundOrderRequest request) {
    TradingAccountEntity account = requireOwnedAccount(userId, request.accountId());
    FundOrderEntity order = new FundOrderEntity();
    order.setUserId(userId);
    order.setAccountId(account.getId());
    order.setOrderType(FundOrderType.fromCode(request.orderType()));
    order.setAmount(request.amount());
    order.setCurrency(request.currency().toUpperCase(Locale.ROOT));
    order.setPaymentMethodId(request.paymentMethodId());
    order.setApplicantNote(request.note());
    order.setStatus(FundOrderStatus.PENDING_REVIEW);
    order.setCreatedBy(userId);
    return FundOrderResponse.from(fundOrderRepository.save(order));
  }

  private TradingAccountEntity requireOwnedAccount(UUID userId, UUID accountId) {
    return accountRepository.findByIdAndUserId(accountId, userId)
        .orElseThrow(() -> new AuthorizationException("ACCOUNT_NOT_FOUND", "Account not found"));
  }

}
