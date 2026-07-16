package com.fxplatform.trading.websocket;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fxplatform.trading.event.TradingAccountMutationEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@SpringJUnitConfig
@ContextConfiguration(classes = TradingWsPublisherTest.TestConfig.class)
class TradingWsPublisherTest {

  @Autowired ApplicationEventPublisher eventPublisher;
  @Autowired SimpMessagingTemplate messagingTemplate;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

  @BeforeEach
  void resetMessagingTemplate() {
    reset(messagingTemplate);
  }

  @Test
  void committedMutationRoutesOnlyIdentifiersAndVersionToTheAuthenticatedUser() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID tradeId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-07-13T01:00:00Z");
    TradingAccountMutationEvent event = new TradingAccountMutationEvent(
        userId,
        accountId,
        "TRADE_CREATED",
        "TRADE",
        tradeId,
        orderId,
        7L,
        occurredAt);

    new TransactionTemplate(transactionManager).executeWithoutResult(
        ignored -> eventPublisher.publishEvent(event));

    verify(messagingTemplate).convertAndSendToUser(
        userId.toString(),
        "/queue/trading-events",
        event.toResponse());
  }

  @Test
  void rolledBackMutationDoesNotPublishAPhantomTradingEvent() {
    TradingAccountMutationEvent event = new TradingAccountMutationEvent(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "BALANCE_UPDATED",
        "ACCOUNT",
        UUID.randomUUID(),
        UUID.randomUUID(),
        3L,
        Instant.parse("2026-07-13T01:00:00Z"));

    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      eventPublisher.publishEvent(event);
      status.setRollbackOnly();
    });

    verifyNoInteractions(messagingTemplate);
  }

  @Test
  void anAlreadyCommittedMutationUsesFallbackExecution() {
    TradingAccountMutationEvent event = new TradingAccountMutationEvent(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "TRANSFER_COMPLETED",
        "TRANSFER",
        UUID.randomUUID(),
        null,
        null,
        Instant.parse("2026-07-13T01:00:00Z"));

    eventPublisher.publishEvent(event);

    verify(messagingTemplate).convertAndSendToUser(
        event.userId().toString(),
        "/queue/trading-events",
        event.toResponse());
  }

  @Test
  void fallbackExecutionDoesNotPropagateBrokerFailureToMutationCaller() {
    TradingAccountMutationEvent event = new TradingAccountMutationEvent(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "TRANSFER_COMPLETED",
        "TRANSFER",
        UUID.randomUUID(),
        null,
        null,
        Instant.parse("2026-07-13T01:00:00Z"));
    doThrow(new IllegalStateException("broker unavailable"))
        .when(messagingTemplate)
        .convertAndSendToUser(
            event.userId().toString(),
            "/queue/trading-events",
            event.toResponse());

    assertThatCode(() -> eventPublisher.publishEvent(event)).doesNotThrowAnyException();

    verify(messagingTemplate).convertAndSendToUser(
        event.userId().toString(),
        "/queue/trading-events",
        event.toResponse());
  }

  @Test
  void afterCommitExecutionDoesNotPropagateBrokerFailureToMutationCaller() {
    TradingAccountMutationEvent event = new TradingAccountMutationEvent(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "ORDER_FILLED",
        "ORDER",
        UUID.randomUUID(),
        UUID.randomUUID(),
        8L,
        Instant.parse("2026-07-13T01:00:00Z"));
    doThrow(new IllegalStateException("broker unavailable"))
        .when(messagingTemplate)
        .convertAndSendToUser(
            event.userId().toString(),
            "/queue/trading-events",
            event.toResponse());

    assertThatCode(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
        ignored -> eventPublisher.publishEvent(event))).doesNotThrowAnyException();

    verify(messagingTemplate).convertAndSendToUser(
        event.userId().toString(),
        "/queue/trading-events",
        event.toResponse());
  }

  @Configuration
  @EnableTransactionManagement
  static class TestConfig {

    @Bean
    SimpMessagingTemplate messagingTemplate() {
      return org.mockito.Mockito.mock(SimpMessagingTemplate.class);
    }

    @Bean
    TradingWsPublisher tradingWsPublisher(SimpMessagingTemplate messagingTemplate) {
      return new TradingWsPublisher(messagingTemplate);
    }

    @Bean
    org.springframework.transaction.PlatformTransactionManager transactionManager() {
      return new StubTransactionManager();
    }
  }

  private static final class StubTransactionManager extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
    }
  }
}
