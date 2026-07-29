package com.fxplatform.tradinglab.queue;

@FunctionalInterface
public interface TradingLabRunCoordinator {

  void coordinate(TradingLabRunClaim claim);
}
