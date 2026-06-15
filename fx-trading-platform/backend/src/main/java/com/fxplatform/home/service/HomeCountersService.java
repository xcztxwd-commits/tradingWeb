package com.fxplatform.home.service;

import com.fxplatform.home.dto.HomeCountersResponse;
import com.fxplatform.home.dto.HomePromoCardResponse;
import com.fxplatform.home.entity.HomePromoCardEntity;
import com.fxplatform.home.repository.HomeCounterRepository;
import com.fxplatform.home.repository.HomePromoCardRepository;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HomeCountersService {

  private static final String USERS_COUNTER_KEY = "users";

  private final HomeCounterRepository homeCounterRepository;
  private final HomePromoCardRepository homePromoCardRepository;
  private final PositionRepository positionRepository;
  private final OrderRepository orderRepository;

  public HomeCountersResponse currentCounters() {
    Instant dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
    return new HomeCountersResponse(
        homeCounterRepository.currentValue(USERS_COUNTER_KEY),
        positionRepository.countByStatus(PositionStatus.OPEN),
        orderRepository.countCreatedAtSince(dayStart),
        homePromoCardRepository.findVisibleCards().stream()
            .map(this::toResponse)
            .toList()
    );
  }

  @Scheduled(fixedDelayString = "${home.counters.users-growth-ms:1000}")
  public void growUsersCounter() {
    homeCounterRepository.increment(USERS_COUNTER_KEY, ThreadLocalRandom.current().nextLong(1, 10));
  }

  private HomePromoCardResponse toResponse(HomePromoCardEntity card) {
    return new HomePromoCardResponse(
        card.getSlot(),
        card.getFrontRank(),
        card.getFrontLabel(),
        card.getBackTitle(),
        card.getBackValue());
  }
}
