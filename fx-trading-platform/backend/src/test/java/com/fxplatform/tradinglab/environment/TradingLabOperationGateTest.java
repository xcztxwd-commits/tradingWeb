package com.fxplatform.tradinglab.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.tradinglab.environment.repository.TradingLabOperationGateRepository;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class TradingLabOperationGateTest {

  @Test
  void exposesOnlyTheThreeFrozenTransactionScopedOperations() {
    assertThat(Arrays.stream(TradingLabOperationGate.class.getDeclaredMethods())
        .map(Method::getName))
        .containsExactlyInAnyOrder(
            "awaitRunCreationPermit",
            "acquireEnvironmentMutationPermit",
            "hasNonTerminalRuns");

    Transactional transactional =
        PostgresTradingLabOperationGate.class.getAnnotation(Transactional.class);
    assertThat(transactional).isNotNull();
    assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    assertThat(transactional.noRollbackFor())
        .contains(TradingLabEnvironmentException.class);

    Transactional environmentTransaction;
    try {
      environmentTransaction = TradingLabEnvironmentService.class
          .getMethod(
              "action",
              TradingLabEnvironmentAction.class,
              UUID.class,
              String.class,
              UUID.class)
          .getAnnotation(Transactional.class);
    } catch (NoSuchMethodException missingContract) {
      throw new AssertionError(missingContract);
    }
    assertThat(environmentTransaction).isNotNull();
    assertThat(environmentTransaction.noRollbackFor())
        .contains(TradingLabEnvironmentException.class);
  }

  @Test
  void repositoryUsesOneFixedPostgresTransactionAdvisoryKeyForBothCallers()
      throws Exception {
    assertThat(TradingLabOperationGateRepository.LOCK_KEY)
        .isEqualTo(7412918473123457L);
    String blocking = sql("awaitTransactionLock");
    String nonBlocking = sql("tryAcquireTransactionLock");
    String active = sql("hasNonTerminalRuns");

    assertThat(TradingLabOperationGateRepository.class
        .getMethod("awaitTransactionLock")
        .getReturnType())
        .isEqualTo(boolean.class);
    assertThat(blocking)
        .contains("pg_advisory_xact_lock(7412918473123457)")
        .contains("IS NULL")
        .doesNotContain("pg_try");
    assertThat(nonBlocking)
        .contains("pg_try_advisory_xact_lock(7412918473123457)");
    assertThat(active)
        .contains("state NOT IN ('COMPLETED', 'CANCELLED', 'FAILED')")
        .doesNotContain("lease_owner")
        .doesNotContain("lease_until");
  }

  @Test
  void delegatesBlockingRunPermitAndRejectsBusyMutationWithoutFurtherWork() {
    TradingLabOperationGateRepository repository =
        mock(TradingLabOperationGateRepository.class);
    PostgresTradingLabOperationGate gate =
        new PostgresTradingLabOperationGate(repository);

    gate.awaitRunCreationPermit();
    verify(repository).awaitTransactionLock();

    when(repository.tryAcquireTransactionLock()).thenReturn(false);
    assertThatThrownBy(gate::acquireEnvironmentMutationPermit)
        .isInstanceOfSatisfying(
            TradingLabEnvironmentException.class,
            failure -> {
              assertThat(failure.getCode())
                  .isEqualTo("TRADING_LAB_ENVIRONMENT_BUSY");
              assertThat(failure.httpStatus().value()).isEqualTo(409);
            });
  }

  @Test
  void activeRunAuthorityComesOnlyFromThePostgresQuery() {
    TradingLabOperationGateRepository repository =
        mock(TradingLabOperationGateRepository.class);
    PostgresTradingLabOperationGate gate =
        new PostgresTradingLabOperationGate(repository);
    when(repository.hasNonTerminalRuns()).thenReturn(true);

    assertThat(gate.hasNonTerminalRuns()).isTrue();
    verify(repository).hasNonTerminalRuns();
  }

  private static String sql(String methodName) throws Exception {
    return String.join(
        "\n",
        TradingLabOperationGateRepository.class
            .getMethod(methodName)
            .getAnnotation(Select.class)
            .value());
  }
}
