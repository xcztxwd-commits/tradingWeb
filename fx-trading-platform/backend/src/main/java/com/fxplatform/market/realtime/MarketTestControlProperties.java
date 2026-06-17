package com.fxplatform.market.realtime;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "market.test-control")
public class MarketTestControlProperties {

  private boolean enabled = false;
  private Duration maxTtl = Duration.ofMinutes(5);

  public boolean enabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Duration maxTtl() {
    return maxTtl;
  }

  public void setMaxTtl(Duration maxTtl) {
    this.maxTtl = maxTtl;
  }
}
