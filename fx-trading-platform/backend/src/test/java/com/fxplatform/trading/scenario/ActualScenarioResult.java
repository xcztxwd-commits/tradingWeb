package com.fxplatform.trading.scenario;

import com.fxplatform.trading.scenario.ExpectedScenarioResult.Checkpoint;
import com.fxplatform.trading.scenario.ExpectedScenarioResult.FailureState;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ActualScenarioResult(
    String caseId,
    List<Checkpoint> checkpoints
) {

  public ActualScenarioResult {
    Objects.requireNonNull(caseId, "caseId");
    checkpoints = List.copyOf(Objects.requireNonNull(checkpoints, "checkpoints"));
  }

  public Checkpoint finalCheckpoint() {
    if (checkpoints.isEmpty()) {
      throw new IllegalStateException(caseId + " has no actual checkpoints");
    }
    return checkpoints.getLast();
  }

  public Optional<FailureState> failure() {
    return checkpoints.stream()
        .map(Checkpoint::failure)
        .filter(Objects::nonNull)
        .reduce((first, second) -> second);
  }
}
