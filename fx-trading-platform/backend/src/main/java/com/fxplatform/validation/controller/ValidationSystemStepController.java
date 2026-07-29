package com.fxplatform.validation.controller;

import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.validation.service.ValidationResetGate;
import com.fxplatform.validation.service.ValidationSystemStepService;
import com.fxplatform.validation.service.ValidationSystemStepService.Receipt;
import com.fxplatform.validation.service.ValidationSystemStepService.Request;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("validation")
@RestController
@RequestMapping("/internal/validation")
public class ValidationSystemStepController {

  private final ValidationResetGate resetGate;
  private final ValidationSystemStepService systemStepService;

  public ValidationSystemStepController(
      ValidationResetGate resetGate,
      ValidationSystemStepService systemStepService
  ) {
    this.resetGate = resetGate;
    this.systemStepService = systemStepService;
  }

  @PostMapping("/system/step")
  public ApiResponse<Receipt> execute(@RequestBody Request request) {
    resetGate.requireReadyGeneration(request.tick().generation());
    return ApiResponse.success(systemStepService.execute(request));
  }
}
