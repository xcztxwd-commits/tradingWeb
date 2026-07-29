package com.fxplatform.tradinglab.state;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class TradingLabStateMachine {

  private static final Map<TradingLabRunState, Set<TradingLabRunState>> LEGAL = legalGraph();

  public boolean canTransition(TradingLabRunState from, TradingLabRunState to) {
    return from != null && to != null && LEGAL.get(from).contains(to);
  }

  private static Map<TradingLabRunState, Set<TradingLabRunState>> legalGraph() {
    EnumMap<TradingLabRunState, Set<TradingLabRunState>> graph =
        new EnumMap<>(TradingLabRunState.class);
    for (TradingLabRunState state : TradingLabRunState.values()) {
      graph.put(state, EnumSet.noneOf(TradingLabRunState.class));
    }
    graph.put(TradingLabRunState.DRAFT, EnumSet.of(TradingLabRunState.VALIDATING));
    graph.put(TradingLabRunState.VALIDATING, EnumSet.of(
        TradingLabRunState.DRAFT,
        TradingLabRunState.QUEUED));
    graph.put(TradingLabRunState.QUEUED, EnumSet.of(
        TradingLabRunState.RESETTING,
        TradingLabRunState.CANCELLING,
        TradingLabRunState.FAILED));
    graph.put(TradingLabRunState.RESETTING, EnumSet.of(
        TradingLabRunState.RUNNING,
        TradingLabRunState.CANCELLING,
        TradingLabRunState.CLEANING));
    graph.put(TradingLabRunState.RUNNING, EnumSet.of(
        TradingLabRunState.PAUSED,
        TradingLabRunState.CANCELLING,
        TradingLabRunState.CLEANING));
    graph.put(TradingLabRunState.PAUSED, EnumSet.of(
        TradingLabRunState.RUNNING,
        TradingLabRunState.CANCELLING,
        TradingLabRunState.CLEANING));
    graph.put(TradingLabRunState.CANCELLING, EnumSet.of(TradingLabRunState.CLEANING));
    graph.put(TradingLabRunState.CLEANING, EnumSet.of(
        TradingLabRunState.COMPLETED,
        TradingLabRunState.CANCELLED,
        TradingLabRunState.FAILED));
    return Map.copyOf(graph);
  }
}
