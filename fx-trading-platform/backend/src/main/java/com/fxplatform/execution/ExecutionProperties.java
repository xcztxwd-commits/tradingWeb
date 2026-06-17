package com.fxplatform.execution;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "execution")
public class ExecutionProperties {

  private ExecutionMode mode = ExecutionMode.DISABLED;
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
