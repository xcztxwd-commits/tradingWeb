package com.fxplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

class ExecutionSchedulerWiringTest {

  @Test
  void pendingExecutionServiceIsAlwaysInjectableAndOnlyItsSchedulerIsConditional() throws Exception {
    assertThat(PendingOrderExecutionService.class.getAnnotation(Service.class)).isNotNull();
    assertThat(PendingOrderExecutionService.class.getAnnotation(ConditionalOnProperty.class)).isNull();
    assertThat(PendingOrderExecutionService.class
        .getDeclaredMethod("executePendingOrders")
        .getAnnotation(Scheduled.class)).isNull();

    Class<?> scheduler = requireType(
        "com.fxplatform.trading.service.PendingOrderExecutionScheduler");
    assertConditionalScheduler(
        scheduler,
        "pending-order-execution-enabled",
        "scanPendingOrders");
  }

  @Test
  void protectiveExecutionServiceIsAlwaysInjectableAndOnlyItsSchedulerIsConditional()
      throws Exception {
    assertThat(ProtectiveOrderExecutionService.class.getAnnotation(Service.class)).isNotNull();
    assertThat(ProtectiveOrderExecutionService.class.getAnnotation(ConditionalOnProperty.class))
        .isNull();
    assertThat(ProtectiveOrderExecutionService.class
        .getDeclaredMethod("executeProtectiveOrders")
        .getAnnotation(Scheduled.class)).isNull();

    Class<?> scheduler = requireType(
        "com.fxplatform.trading.service.ProtectiveOrderExecutionScheduler");
    assertConditionalScheduler(
        scheduler,
        "protective-order-execution-enabled",
        "scanProtectiveOrders");
  }

  private static void assertConditionalScheduler(
      Class<?> scheduler,
      String property,
      String methodName
  ) throws Exception {
    assertThat(scheduler.getAnnotation(Service.class)).isNotNull();
    ConditionalOnProperty condition = scheduler.getAnnotation(ConditionalOnProperty.class);
    assertThat(condition).isNotNull();
    assertThat(condition.prefix()).isEqualTo("trading");
    assertThat(condition.name()).containsExactly(property);
    assertThat(condition.havingValue()).isEqualTo("true");
    assertThat(condition.matchIfMissing()).isFalse();
    Method method = scheduler.getDeclaredMethod(methodName);
    assertThat(method.getAnnotation(Scheduled.class)).isNotNull();
  }

  private static Class<?> requireType(String className) {
    try {
      return Class.forName(className);
    } catch (ClassNotFoundException exception) {
      return fail("Missing conditional scheduler wrapper " + className, exception);
    }
  }
}
