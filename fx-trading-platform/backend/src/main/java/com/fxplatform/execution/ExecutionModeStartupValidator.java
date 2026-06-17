package com.fxplatform.execution;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ExecutionModeStartupValidator implements ApplicationRunner {

  private final ExecutionProperties properties;
  private final List<ExecutionAdapterReadiness> adapterReadiness;

  public ExecutionModeStartupValidator(
      ExecutionProperties properties,
      List<ExecutionAdapterReadiness> adapterReadiness
  ) {
    this.properties = properties;
    this.adapterReadiness = adapterReadiness;
  }

  @Override
  public void run(ApplicationArguments args) {
    ExecutionMode mode = properties.mode();
    if (!mode.liveMode()) {
      return;
    }

    List<String> missingFields = properties
        .adapterFor(mode)
        .missingRequiredFields("execution." + mode.propertyValue());
    if (!missingFields.isEmpty()) {
      throw new IllegalStateException("Missing live execution settings: " + String.join(", ", missingFields));
    }

    ExecutionAdapterReadiness readiness = adapterReadiness.stream()
        .filter(candidate -> candidate.mode() == mode)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "No execution adapter registered for execution.mode=" + mode.propertyValue()));
    if (!readiness.readyForLiveTrading()) {
      throw new IllegalStateException(readiness.notReadyMessage());
    }
  }
}
