package com.fxplatform.execution;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExecutionModeStartupValidatorTest {

  @Test
  void disabledModeAcceptsMissingLiveAdapterSettings() throws Exception {
    ExecutionProperties properties = new ExecutionProperties();
    properties.setMode(ExecutionMode.DISABLED);

    new ExecutionModeStartupValidator(properties, List.of()).run(null);
  }

  @Test
  void brokerModeRejectsMissingRequiredSettings() {
    ExecutionProperties properties = new ExecutionProperties();
    properties.setMode(ExecutionMode.BROKER);

    assertThatThrownBy(() -> new ExecutionModeStartupValidator(properties, List.of()).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("execution.broker.endpoint")
        .hasMessageContaining("execution.broker.api-key")
        .hasMessageContaining("execution.broker.account-id");
  }

  @Test
  void brokerModeRejectsPlaceholderAdapterEvenWhenSettingsAreComplete() {
    ExecutionProperties properties = brokerProperties();

    assertThatThrownBy(() -> new ExecutionModeStartupValidator(
        properties,
        List.of(new BrokerExecutionAdapter())).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Broker execution is reserved for future LIVE trading");
  }

  @Test
  void brokerModeAcceptsConfiguredReadyAdapter() throws Exception {
    ExecutionProperties properties = brokerProperties();

    new ExecutionModeStartupValidator(
        properties,
        List.of(new ReadyAdapter(ExecutionMode.BROKER))).run(null);
  }

  private ExecutionProperties brokerProperties() {
    ExecutionProperties properties = new ExecutionProperties();
    properties.setMode(ExecutionMode.BROKER);
    properties.getBroker().setEndpoint("https://broker.example.test");
    properties.getBroker().setApiKey("broker-api-key");
    properties.getBroker().setAccountId("broker-account");
    return properties;
  }

  private record ReadyAdapter(ExecutionMode mode) implements ExecutionAdapterReadiness {

    @Override
    public boolean readyForLiveTrading() {
      return true;
    }

    @Override
    public String notReadyMessage() {
      return "";
    }
  }
}
