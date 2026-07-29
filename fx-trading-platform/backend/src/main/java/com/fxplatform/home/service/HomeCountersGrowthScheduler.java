package com.fxplatform.home.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!validation")
@ConditionalOnProperty(
    prefix = "home.counters",
    name = "growth-enabled",
    havingValue = "true",
    matchIfMissing = true)
@RequiredArgsConstructor
public class HomeCountersGrowthScheduler {

  private final HomeCountersService homeCountersService;

  @Scheduled(fixedDelayString = "${home.counters.users-growth-ms:1000}")
  public void growUsersCounter() {
    homeCountersService.growUsersCounter();
  }
}
