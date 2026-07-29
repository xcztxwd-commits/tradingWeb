package com.fxplatform.validation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.validation.service.ValidationResetCommand;
import com.fxplatform.validation.service.ValidationResetReceipt;
import com.fxplatform.validation.service.ValidationResetService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ValidationResetControllerTest {

  private static final UUID RUN_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000721");
  private static final UUID OPERATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000722");

  @Test
  void missingStrongHeadersPreserveTheLegacyBodylessResetContract() {
    ValidationResetService service = mock(ValidationResetService.class);
    ValidationResetReceipt receipt = receipt();
    when(service.reset()).thenReturn(receipt);
    ValidationResetController controller = new ValidationResetController(service);

    assertThat(controller.reset(null, null, null, null).data()).isEqualTo(receipt);

    verify(service).reset();
    verifyNoMoreInteractions(service);
  }

  @Test
  void completeHeadersCreateOneTypedIdentityBoundCommand() {
    ValidationResetService service = mock(ValidationResetService.class);
    ValidationResetReceipt receipt = receipt();
    ValidationResetCommand command = new ValidationResetCommand(
        RUN_ID,
        OPERATION_ID,
        ValidationResetCommand.Mode.FINAL,
        41L);
    when(service.reset(command)).thenReturn(receipt);
    ValidationResetController controller = new ValidationResetController(service);

    assertThat(controller.reset(
        RUN_ID.toString(),
        OPERATION_ID.toString(),
        "FINAL",
        "41").data()).isEqualTo(receipt);

    verify(service).reset(command);
    verifyNoMoreInteractions(service);
  }

  @Test
  void initialResetAcceptsTheCurrentNonZeroGenerationFence() {
    ValidationResetService service = mock(ValidationResetService.class);
    ValidationResetReceipt receipt = receipt();
    ValidationResetCommand command = new ValidationResetCommand(
        RUN_ID,
        OPERATION_ID,
        ValidationResetCommand.Mode.INITIAL,
        40L);
    when(service.reset(command)).thenReturn(receipt);
    ValidationResetController controller = new ValidationResetController(service);

    assertThat(controller.reset(
        RUN_ID.toString(),
        OPERATION_ID.toString(),
        "INITIAL",
        "40").data()).isEqualTo(receipt);

    verify(service).reset(command);
    verifyNoMoreInteractions(service);
  }

  @Test
  void partialOrMalformedStrongHeadersFailClosedWithoutCallingReset() {
    ValidationResetService service = mock(ValidationResetService.class);
    ValidationResetController controller = new ValidationResetController(service);

    assertBusinessCode(
        () -> controller.reset(RUN_ID.toString(), null, "INITIAL", "0"),
        "VALIDATION_RESET_COMMAND_REQUIRED");
    assertBusinessCode(
        () -> controller.reset("not-a-uuid", OPERATION_ID.toString(), "INITIAL", "0"),
        "VALIDATION_RESET_COMMAND_INVALID");
    assertBusinessCode(
        () -> controller.reset(RUN_ID.toString(), OPERATION_ID.toString(), "initial", "0"),
        "VALIDATION_RESET_COMMAND_INVALID");
    assertBusinessCode(
        () -> controller.reset(RUN_ID.toString(), OPERATION_ID.toString(), "INITIAL", "-1"),
        "VALIDATION_RESET_COMMAND_INVALID");

    verifyNoMoreInteractions(service);
  }

  private static ValidationResetReceipt receipt() {
    Instant now = Instant.parse("2026-07-23T08:00:00Z");
    return new ValidationResetReceipt(
        ValidationResetReceipt.Status.SUCCEEDED,
        "fx_validation_lab",
        41L,
        41L,
        now,
        now,
        List.of(),
        null);
  }

  private static void assertBusinessCode(Runnable action, String code) {
    assertThatThrownBy(action::run)
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).getCode())
        .isEqualTo(code);
  }
}
