package com.fxplatform.tradinglab.environment;

import com.fxplatform.tradinglab.environment.repository.TradingLabOperationGateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(
    propagation = Propagation.MANDATORY,
    noRollbackFor = TradingLabEnvironmentException.class)
public class PostgresTradingLabOperationGate implements TradingLabOperationGate {

  private final TradingLabOperationGateRepository repository;

  @Override
  public void awaitRunCreationPermit() {
    repository.awaitTransactionLock();
  }

  @Override
  public void acquireEnvironmentMutationPermit() {
    if (!repository.tryAcquireTransactionLock()) {
      throw TradingLabEnvironmentException.mutationBusy();
    }
  }

  @Override
  public boolean hasNonTerminalRuns() {
    return repository.hasNonTerminalRuns();
  }
}
