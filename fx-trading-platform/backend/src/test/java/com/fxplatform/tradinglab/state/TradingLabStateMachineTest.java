package com.fxplatform.tradinglab.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class TradingLabStateMachineTest {

  private static final Set<Edge> LEGAL_EDGES = Set.of(
      edge(TradingLabRunState.DRAFT, TradingLabRunState.VALIDATING),
      edge(TradingLabRunState.VALIDATING, TradingLabRunState.DRAFT),
      edge(TradingLabRunState.VALIDATING, TradingLabRunState.QUEUED),
      edge(TradingLabRunState.QUEUED, TradingLabRunState.RESETTING),
      edge(TradingLabRunState.QUEUED, TradingLabRunState.CANCELLING),
      edge(TradingLabRunState.QUEUED, TradingLabRunState.FAILED),
      edge(TradingLabRunState.RESETTING, TradingLabRunState.RUNNING),
      edge(TradingLabRunState.RESETTING, TradingLabRunState.CANCELLING),
      edge(TradingLabRunState.RESETTING, TradingLabRunState.CLEANING),
      edge(TradingLabRunState.RUNNING, TradingLabRunState.PAUSED),
      edge(TradingLabRunState.RUNNING, TradingLabRunState.CANCELLING),
      edge(TradingLabRunState.RUNNING, TradingLabRunState.CLEANING),
      edge(TradingLabRunState.PAUSED, TradingLabRunState.RUNNING),
      edge(TradingLabRunState.PAUSED, TradingLabRunState.CANCELLING),
      edge(TradingLabRunState.PAUSED, TradingLabRunState.CLEANING),
      edge(TradingLabRunState.CANCELLING, TradingLabRunState.CLEANING),
      edge(TradingLabRunState.CLEANING, TradingLabRunState.COMPLETED),
      edge(TradingLabRunState.CLEANING, TradingLabRunState.CANCELLED),
      edge(TradingLabRunState.CLEANING, TradingLabRunState.FAILED));

  private final TradingLabStateMachine stateMachine = new TradingLabStateMachine();

  @Test
  void exposesTheExactPersistedStateNames() {
    assertThat(TradingLabRunState.values()).extracting(Enum::name).containsExactly(
        "DRAFT",
        "VALIDATING",
        "QUEUED",
        "RESETTING",
        "RUNNING",
        "PAUSED",
        "CANCELLING",
        "CANCELLED",
        "FAILED",
        "COMPLETED",
        "CLEANING");
  }

  @Test
  void allowsEveryEdgeInTheFrozenGraph() {
    assertThat(LEGAL_EDGES).hasSize(19);

    for (Edge edge : LEGAL_EDGES) {
      assertThat(stateMachine.canTransition(edge.from(), edge.to()))
          .as("%s -> %s", edge.from(), edge.to())
          .isTrue();
    }
  }

  @Test
  void rejectsEveryPairOutsideTheFrozenGraphIncludingIllegalSkips() {
    for (TradingLabRunState from : TradingLabRunState.values()) {
      for (TradingLabRunState to : TradingLabRunState.values()) {
        if (!LEGAL_EDGES.contains(edge(from, to))) {
          assertThat(stateMachine.canTransition(from, to))
              .as("%s -> %s", from, to)
              .isFalse();
        }
      }
    }
  }

  @Test
  void rejectsEveryImplicitSelfTransition() {
    for (TradingLabRunState state : TradingLabRunState.values()) {
      assertThat(stateMachine.canTransition(state, state))
          .as("%s -> %s", state, state)
          .isFalse();
    }
  }

  @Test
  void terminalStatesHaveNoOutgoingEdges() {
    for (TradingLabRunState terminal : Set.of(
        TradingLabRunState.CANCELLED,
        TradingLabRunState.FAILED,
        TradingLabRunState.COMPLETED)) {
      for (TradingLabRunState target : TradingLabRunState.values()) {
        assertThat(stateMachine.canTransition(terminal, target))
            .as("%s -> %s", terminal, target)
            .isFalse();
      }
    }
  }

  @Test
  void keepsValidationFailureAsAnExplicitReturnToDraft() {
    assertThat(stateMachine.canTransition(
        TradingLabRunState.VALIDATING,
        TradingLabRunState.DRAFT)).isTrue();
  }

  @Test
  void keepsPreResetQueueFailureAsAnExplicitEdge() {
    assertThat(stateMachine.canTransition(
        TradingLabRunState.QUEUED,
        TradingLabRunState.FAILED)).isTrue();
  }

  @Test
  void keepsPausedResumeAndRunningCancellationAsExplicitRegressionEdges() {
    assertThat(stateMachine.canTransition(
        TradingLabRunState.PAUSED,
        TradingLabRunState.RUNNING)).isTrue();
    assertThat(stateMachine.canTransition(
        TradingLabRunState.RUNNING,
        TradingLabRunState.CANCELLING)).isTrue();
  }

  private static Edge edge(TradingLabRunState from, TradingLabRunState to) {
    return new Edge(from, to);
  }

  private record Edge(TradingLabRunState from, TradingLabRunState to) {
  }
}
