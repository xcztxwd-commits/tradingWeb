package com.fxplatform.execution;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "execution")
public class ExecutionProperties {

  private ExecutionMode mode = ExecutionMode.DISABLED;
  private DemoProperties demo = new DemoProperties();
  private LiveAdapterProperties broker = new LiveAdapterProperties();
  private LiveAdapterProperties fix = new LiveAdapterProperties();
  private LiveAdapterProperties lp = new LiveAdapterProperties();

  public ExecutionMode mode() {
    return mode == null ? ExecutionMode.DISABLED : mode;
  }

  public ExecutionMode getMode() {
    return mode();
  }

  public void setMode(ExecutionMode mode) {
    this.mode = mode;
  }

  public DemoProperties getDemo() {
    return demo;
  }

  public void setDemo(DemoProperties demo) {
    this.demo = demo == null ? new DemoProperties() : demo;
  }

  public LiveAdapterProperties getBroker() {
    return broker;
  }

  public void setBroker(LiveAdapterProperties broker) {
    this.broker = broker == null ? new LiveAdapterProperties() : broker;
  }

  public LiveAdapterProperties getFix() {
    return fix;
  }

  public void setFix(LiveAdapterProperties fix) {
    this.fix = fix == null ? new LiveAdapterProperties() : fix;
  }

  public LiveAdapterProperties getLp() {
    return lp;
  }

  public void setLp(LiveAdapterProperties lp) {
    this.lp = lp == null ? new LiveAdapterProperties() : lp;
  }

  public LiveAdapterProperties adapterFor(ExecutionMode executionMode) {
    return switch (executionMode) {
      case BROKER -> broker;
      case FIX -> fix;
      case LP -> lp;
      default -> throw new IllegalArgumentException("No live adapter settings for " + executionMode.propertyValue());
    };
  }

  public static class DemoProperties {

    private static final DemoExecutionPolicy DEFAULTS = DemoExecutionPolicy.defaults();

    private DemoMatchingMode matchingMode = DEFAULTS.matchingMode();
    private java.math.BigDecimal makerFeeRate = DEFAULTS.makerFeeRate();
    private java.math.BigDecimal takerFeeRate = DEFAULTS.takerFeeRate();
    private java.math.BigDecimal liquidationFeeRate = DEFAULTS.liquidationFeeRate();
    private java.math.BigDecimal slippageRate = DEFAULTS.slippageRate();
    private List<DemoBookLevel> bids = DEFAULTS.bids();
    private List<DemoBookLevel> asks = DEFAULTS.asks();
    private java.math.BigDecimal maxFillQuantityPerTick = DEFAULTS.maxFillQuantityPerTick();

    public DemoMatchingMode getMatchingMode() {
      return matchingMode;
    }

    public void setMatchingMode(DemoMatchingMode matchingMode) {
      this.matchingMode = matchingMode;
    }

    public java.math.BigDecimal getMakerFeeRate() {
      return makerFeeRate;
    }

    public void setMakerFeeRate(java.math.BigDecimal makerFeeRate) {
      this.makerFeeRate = makerFeeRate;
    }

    public java.math.BigDecimal getTakerFeeRate() {
      return takerFeeRate;
    }

    public void setTakerFeeRate(java.math.BigDecimal takerFeeRate) {
      this.takerFeeRate = takerFeeRate;
    }

    public java.math.BigDecimal getLiquidationFeeRate() {
      return liquidationFeeRate;
    }

    public void setLiquidationFeeRate(java.math.BigDecimal liquidationFeeRate) {
      this.liquidationFeeRate = liquidationFeeRate;
    }

    public java.math.BigDecimal getSlippageRate() {
      return slippageRate;
    }

    public void setSlippageRate(java.math.BigDecimal slippageRate) {
      this.slippageRate = slippageRate;
    }

    public List<DemoBookLevel> getBids() {
      return bids;
    }

    public void setBids(List<DemoBookLevel> bids) {
      this.bids = bids == null ? List.of() : bids;
    }

    public List<DemoBookLevel> getAsks() {
      return asks;
    }

    public void setAsks(List<DemoBookLevel> asks) {
      this.asks = asks == null ? List.of() : asks;
    }

    public java.math.BigDecimal getMaxFillQuantityPerTick() {
      return maxFillQuantityPerTick;
    }

    public void setMaxFillQuantityPerTick(java.math.BigDecimal maxFillQuantityPerTick) {
      this.maxFillQuantityPerTick = maxFillQuantityPerTick;
    }
  }

  public static class LiveAdapterProperties {

    private String endpoint = "";
    private String apiKey = "";
    private String accountId = "";

    public String getEndpoint() {
      return endpoint;
    }

    public void setEndpoint(String endpoint) {
      this.endpoint = endpoint;
    }

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }

    public String getAccountId() {
      return accountId;
    }

    public void setAccountId(String accountId) {
      this.accountId = accountId;
    }

    public List<String> missingRequiredFields(String prefix) {
      List<String> missing = new ArrayList<>();
      addIfBlank(missing, prefix + ".endpoint", endpoint);
      addIfBlank(missing, prefix + ".api-key", apiKey);
      addIfBlank(missing, prefix + ".account-id", accountId);
      return missing;
    }

    private void addIfBlank(List<String> missing, String name, String value) {
      if (value == null || value.trim().isEmpty()) {
        missing.add(name);
      }
    }
  }
}
