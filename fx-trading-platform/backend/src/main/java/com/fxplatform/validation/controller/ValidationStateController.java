package com.fxplatform.validation.controller;

import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.validation.service.ValidationStateService;
import com.fxplatform.validation.service.ValidationStateService.Snapshot;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("validation")
@RestController
@RequestMapping("/internal/validation")
public class ValidationStateController {

  private final ValidationStateService stateService;

  public ValidationStateController(ValidationStateService stateService) {
    this.stateService = stateService;
  }

  @GetMapping("/state")
  public ApiResponse<Snapshot> state(@RequestParam(required = false) UUID runId) {
    return ApiResponse.success(stateService.snapshot(runId));
  }
}
