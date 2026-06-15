package com.fxplatform.home.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.auth.repository.UserRepository;
import com.fxplatform.home.dto.HomeCountersResponse;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HomeCountersServiceTest {

  @Mock
  private UserRepository userRepository;

  @Mock
  private PositionRepository positionRepository;

  @Mock
  private OrderRepository orderRepository;

  @Test
  void currentCountersAggregatesHomepageNumbers() {
    when(userRepository.count()).thenReturn(84L);
    when(positionRepository.countByStatus(PositionStatus.OPEN)).thenReturn(12L);
    when(orderRepository.countCreatedAtSince(any(Instant.class))).thenReturn(210L);

    HomeCountersService service = new HomeCountersService(userRepository, positionRepository, orderRepository);

    HomeCountersResponse counters = service.currentCounters();

    assertThat(counters.users()).isEqualTo(84L);
    assertThat(counters.activeTraders()).isEqualTo(12L);
    assertThat(counters.dailyTrades()).isEqualTo(210L);
    verify(positionRepository).countByStatus(PositionStatus.OPEN);
    verify(orderRepository).countCreatedAtSince(any(Instant.class));
  }
}
