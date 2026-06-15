package com.fxplatform.home.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.home.dto.HomeCountersResponse;
import com.fxplatform.home.entity.HomePromoCardEntity;
import com.fxplatform.home.repository.HomeCounterRepository;
import com.fxplatform.home.repository.HomePromoCardRepository;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HomeCountersServiceTest {

  @Mock
  private HomeCounterRepository homeCounterRepository;

  @Mock
  private HomePromoCardRepository homePromoCardRepository;

  @Mock
  private PositionRepository positionRepository;

  @Mock
  private OrderRepository orderRepository;

  @Test
  void currentCountersReadsEditableHomepageNumbersAndCards() {
    when(homeCounterRepository.currentValue("users")).thenReturn(321_443_508L);
    when(positionRepository.countByStatus(PositionStatus.OPEN)).thenReturn(12L);
    when(orderRepository.countCreatedAtSince(any(Instant.class))).thenReturn(210L);
    when(homePromoCardRepository.findVisibleCards()).thenReturn(List.of(
        promoCard("asset", "No.1", "客户资产", "资产", "$134,166,872,529", 10),
        promoCard("volume", "No.1", "交易量", "24H", "$44,301,728,218", 20)
    ));

    HomeCountersService service = new HomeCountersService(
        homeCounterRepository,
        homePromoCardRepository,
        positionRepository,
        orderRepository);

    HomeCountersResponse counters = service.currentCounters();

    assertThat(counters.users()).isEqualTo(321_443_508L);
    assertThat(counters.activeTraders()).isEqualTo(12L);
    assertThat(counters.dailyTrades()).isEqualTo(210L);
    assertThat(counters.metricCards())
        .extracting("slot", "frontRank", "frontLabel", "backTitle", "backValue")
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("asset", "No.1", "客户资产", "资产", "$134,166,872,529"),
            org.assertj.core.groups.Tuple.tuple("volume", "No.1", "交易量", "24H", "$44,301,728,218")
        );
    verify(positionRepository).countByStatus(PositionStatus.OPEN);
    verify(orderRepository).countCreatedAtSince(any(Instant.class));
  }

  @Test
  void growUsersCounterPersistsRandomIncrementBetweenOneAndNine() {
    HomeCountersService service = new HomeCountersService(
        homeCounterRepository,
        homePromoCardRepository,
        positionRepository,
        orderRepository);
    ArgumentCaptor<Long> increment = ArgumentCaptor.forClass(Long.class);

    service.growUsersCounter();

    verify(homeCounterRepository).increment(eq("users"), increment.capture());
    assertThat(increment.getValue()).isBetween(1L, 9L);
  }

  private HomePromoCardEntity promoCard(
      String slot,
      String frontRank,
      String frontLabel,
      String backTitle,
      String backValue,
      int displayOrder
  ) {
    HomePromoCardEntity card = new HomePromoCardEntity();
    card.setSlot(slot);
    card.setFrontRank(frontRank);
    card.setFrontLabel(frontLabel);
    card.setBackTitle(backTitle);
    card.setBackValue(backValue);
    card.setDisplayOrder(displayOrder);
    card.setEnabled(true);
    return card;
  }
}
