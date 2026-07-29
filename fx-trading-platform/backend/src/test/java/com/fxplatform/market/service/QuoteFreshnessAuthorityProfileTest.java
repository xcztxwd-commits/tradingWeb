package com.fxplatform.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.validation.service.ValidationMarketClock;
import com.fxplatform.validation.service.ValidationQuoteFreshnessAuthority;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class QuoteFreshnessAuthorityProfileTest {

  @Test
  void defaultProfileSelectsOnlyTheWallClockAuthority() {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext()) {
      context.register(
          WallClockQuoteFreshnessAuthority.class,
          ValidationMarketClock.class,
          ValidationQuoteFreshnessAuthority.class);
      context.refresh();

      assertThat(context.getBeansOfType(QuoteFreshnessAuthority.class))
          .hasSize(1)
          .allSatisfy((name, authority) ->
              assertThat(authority).isInstanceOf(WallClockQuoteFreshnessAuthority.class));
    }
  }

  @Test
  void validationProfileSelectsOnlyTheVirtualTickAuthority() {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext()) {
      context.getEnvironment().setActiveProfiles("validation");
      context.register(
          WallClockQuoteFreshnessAuthority.class,
          ValidationMarketClock.class,
          ValidationQuoteFreshnessAuthority.class);
      context.refresh();

      assertThat(context.getBeansOfType(QuoteFreshnessAuthority.class))
          .hasSize(1)
          .allSatisfy((name, authority) ->
              assertThat(authority).isInstanceOf(ValidationQuoteFreshnessAuthority.class));
    }
  }
}
