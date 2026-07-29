package com.fxplatform.validation.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.validation.service.ValidationResetGate;
import com.fxplatform.validation.service.ValidationRunEngine.StartRequest;
import com.fxplatform.validation.service.ValidationRunEventStore;
import com.fxplatform.validation.service.ValidationRunOrchestrator;
import org.junit.jupiter.api.Test;

class ValidationRunControllerGenerationFenceTest {

  @Test
  void staleGenerationIsExposedAsAStableBusinessFenceInsteadOfAnInternalError() {
    ValidationResetGate resetGate = mock(ValidationResetGate.class);
    ValidationRunOrchestrator orchestrator = mock(ValidationRunOrchestrator.class);
    ValidationRunEventStore eventStore = mock(ValidationRunEventStore.class);
    StartRequest request = mock(StartRequest.class);
    when(request.generation()).thenReturn(41L);
    doThrow(new IllegalStateException("Validation generation is not ready"))
        .when(resetGate)
        .requireReadyGeneration(41L);

    ValidationRunController controller =
        new ValidationRunController(resetGate, orchestrator, eventStore);

    assertThatThrownBy(() -> controller.start(request))
        .isInstanceOfSatisfying(BusinessException.class, failure ->
            org.assertj.core.api.Assertions.assertThat(failure.getCode())
                .isEqualTo("VALIDATION_GENERATION_FENCED"));
    verifyNoInteractions(orchestrator);
  }
}
