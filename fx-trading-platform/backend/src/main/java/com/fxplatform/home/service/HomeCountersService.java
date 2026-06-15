package com.fxplatform.home.service;

import com.fxplatform.auth.repository.UserRepository;
import com.fxplatform.home.dto.HomeCountersResponse;
import com.fxplatform.trading.enums.PositionStatus;
import com.fxplatform.trading.repository.OrderRepository;
import com.fxplatform.trading.repository.PositionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HomeCountersService {

  private final UserRepository userRepository;
  private final PositionRepository positionRepository;
  private final OrderRepository orderRepository;

  public HomeCountersResponse currentCounters() {
    Instant dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
    return new HomeCountersResponse(
        userRepository.count(),
        positionRepository.countByStatus(PositionStatus.OPEN),
        orderRepository.countCreatedAtSince(dayStart)
    );
  }
}
