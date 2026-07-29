package com.fxplatform.validation.controller;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.validation.service.ValidationResetGate;
import com.fxplatform.validation.service.ValidationRunEngine;
import com.fxplatform.validation.service.ValidationRunEngine.Accepted;
import com.fxplatform.validation.service.ValidationRunEngine.StartRequest;
import com.fxplatform.validation.service.ValidationRunEventStore;
import com.fxplatform.validation.service.ValidationRunEventStore.EventPage;
import com.fxplatform.validation.service.ValidationRunOrchestrator;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.State;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("validation")
@RestController
@RequestMapping("/internal/validation/runs")
public class ValidationRunController {

  private final ValidationResetGate resetGate;
  private final ValidationRunOrchestrator runOrchestrator;
  private final ValidationRunEventStore eventStore;

  public ValidationRunController(
      ValidationResetGate resetGate,
      ValidationRunOrchestrator runOrchestrator,
      ValidationRunEventStore eventStore
  ) {
    this.resetGate = resetGate;
    this.runOrchestrator = runOrchestrator;
    this.eventStore = eventStore;
  }

  @PostMapping
  public ApiResponse<Accepted> start(@RequestBody StartRequest request) {
    try {
      resetGate.requireReadyGeneration(request.generation());
    } catch (IllegalStateException fenced) {
      throw new BusinessException(
          "VALIDATION_GENERATION_FENCED",
          "Validation generation is no longer active");
    }
    return ApiResponse.success(runOrchestrator.start(request));
  }

  @GetMapping("/{runId}/events")
  public ApiResponse<EventPage> events(
      @PathVariable UUID runId,
      @RequestParam(defaultValue = "0") long afterSequence,
      @RequestParam(defaultValue = "200") int limit
  ) {
    return ApiResponse.success(eventStore.eventsAfter(runId, afterSequence, limit));
  }

  @PostMapping("/{runId}/pause")
  public ApiResponse<ControlReceipt> pause(@PathVariable UUID runId) {
    return ApiResponse.success(new ControlReceipt(
        runId, "PAUSE_REQUESTED", runOrchestrator.pause(runId)));
  }

  @PostMapping("/{runId}/resume")
  public ApiResponse<ControlReceipt> resume(@PathVariable UUID runId) {
    return ApiResponse.success(new ControlReceipt(
        runId, "RESUME_REQUESTED", runOrchestrator.resume(runId)));
  }

  @PostMapping("/{runId}/cancel")
  public ApiResponse<ControlReceipt> cancel(@PathVariable UUID runId) {
    return ApiResponse.success(new ControlReceipt(
        runId, "CANCEL_REQUESTED", runOrchestrator.cancel(runId)));
  }

  public record ControlReceipt(UUID runId, String control, State state) {
  }
}
