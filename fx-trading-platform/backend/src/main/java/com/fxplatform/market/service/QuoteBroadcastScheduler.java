package com.fxplatform.market.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!validation")
@ConditionalOnProperty(
    prefix = "market",
    name = "quote-broadcast-enabled",
    havingValue = "true",
    matchIfMissing = false)
@RequiredArgsConstructor
public class QuoteBroadcastScheduler {

  private final QuoteBroadcastService quoteBroadcastService;

  @Scheduled(fixedDelayString = "${market.quote-broadcast-ms:1000}")
  public void broadcastLatestQuotes() {
    quoteBroadcastService.broadcastLatestQuotes();
  }
}
