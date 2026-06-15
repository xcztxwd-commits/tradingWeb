package com.fxplatform.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.account.entity.TradingAccountEntity;
import com.fxplatform.account.repository.TradingAccountRepository;
import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.finance.dto.request.FundOrderRequest;
import com.fxplatform.finance.entity.FundOrderEntity;
import com.fxplatform.finance.repository.FundOrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class FundOrderServiceTest {

  private final FundOrderRepository fundOrderRepository = Mockito.mock(FundOrderRepository.class);
  private final TradingAccountRepository accountRepository = Mockito.mock(TradingAccountRepository.class);

  @Test
  void createsPendingRechargeOrderForOwnedAccount() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(fundOrderRepository.save(any(FundOrderEntity.class))).thenAnswer(invocation -> {
      FundOrderEntity order = invocation.getArgument(0);
      order.setId(orderId);
      order.setCreatedAt(Instant.parse("2026-06-12T08:00:00Z"));
      return order;
    });

    FundOrderService service = new FundOrderService(fundOrderRepository, accountRepository);

    var response = service.createOrder(userId, new FundOrderRequest(
        accountId,
        "DEPOSIT",
        new BigDecimal("125.50"),
        "USD",
        null,
        "demo top up"));

    assertThat(response.id()).isEqualTo(orderId);
    assertThat(response.userId()).isEqualTo(userId);
    assertThat(response.accountId()).isEqualTo(accountId);
    assertThat(response.orderType()).isEqualTo("RECHARGE");
    assertThat(response.status()).isEqualTo("PENDING_REVIEW");

    ArgumentCaptor<FundOrderEntity> orderCaptor = ArgumentCaptor.forClass(FundOrderEntity.class);
    verify(fundOrderRepository).save(orderCaptor.capture());
    assertThat(orderCaptor.getValue().getCreatedBy()).isEqualTo(userId);
    assertThat(orderCaptor.getValue().getApplicantNote()).isEqualTo("demo top up");
  }

  @Test
  void rejectsCreateWhenAccountDoesNotBelongToUser() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.empty());

    FundOrderService service = new FundOrderService(fundOrderRepository, accountRepository);

    assertThatThrownBy(() -> service.createOrder(userId, new FundOrderRequest(
        accountId,
        "WITHDRAWAL",
        new BigDecimal("25.00"),
        "USD",
        null,
        "cash out")))
        .isInstanceOf(AuthorizationException.class)
        .hasMessageContaining("Account not found");

    verify(fundOrderRepository, never()).save(any());
  }

  @Test
  void listsOnlyOrdersForOwnedAccount() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    TradingAccountEntity account = new TradingAccountEntity();
    account.setId(accountId);
    account.setUserId(userId);
    FundOrderEntity order = new FundOrderEntity();
    order.setId(UUID.randomUUID());
    order.setUserId(userId);
    order.setAccountId(accountId);
    order.setOrderType("WITHDRAWAL");
    order.setAmount(new BigDecimal("50.00"));
    order.setCurrency("USD");
    order.setStatus("PENDING_REVIEW");

    when(accountRepository.findByIdAndUserId(accountId, userId)).thenReturn(Optional.of(account));
    when(fundOrderRepository.findByUserIdAndAccountIdOrderByCreatedAtDesc(userId, accountId)).thenReturn(List.of(order));

    FundOrderService service = new FundOrderService(fundOrderRepository, accountRepository);

    var responses = service.orders(userId, accountId);

    assertThat(responses).hasSize(1);
    assertThat(responses.getFirst().orderType()).isEqualTo("WITHDRAWAL");
    verify(fundOrderRepository).findByUserIdAndAccountIdOrderByCreatedAtDesc(userId, accountId);
  }
}
