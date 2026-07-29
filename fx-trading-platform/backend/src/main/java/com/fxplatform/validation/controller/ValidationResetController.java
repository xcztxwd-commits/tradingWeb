package com.fxplatform.validation.controller;

import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.validation.service.ValidationResetCommand;
import com.fxplatform.validation.service.ValidationResetReceipt;
import com.fxplatform.validation.service.ValidationResetService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Validation-only endpoint for resetting the isolated runtime. */
@Profile("validation")
@RestController
@RequestMapping("/internal/validation")
public class ValidationResetController {

  public static final String RUN_ID_HEADER = "X-Validation-Run-Id";
  public static final String OPERATION_ID_HEADER = "X-Validation-Reset-Operation-Id";
  public static final String MODE_HEADER = "X-Validation-Reset-Mode";
  public static final String EXPECTED_GENERATION_HEADER =
      "X-Validation-Expected-Generation";

  private final ValidationResetService resetService;

  public ValidationResetController(ValidationResetService resetService) {
    this.resetService = resetService;
  }

  @PostMapping("/reset")
  public ApiResponse<ValidationResetReceipt> reset(
      @RequestHeader(name = RUN_ID_HEADER, required = false) String runId,
      @RequestHeader(name = OPERATION_ID_HEADER, required = false) String operationId,
      @RequestHeader(name = MODE_HEADER, required = false) String mode,
      @RequestHeader(name = EXPECTED_GENERATION_HEADER, required = false)
      String expectedGeneration
  ) {
    if (runId == null
        && operationId == null
        && mode == null
        && expectedGeneration == null) {
      return ApiResponse.success(resetService.reset());
    }
    return ApiResponse.success(resetService.reset(ValidationResetCommand.parse(
        runId,
        operationId,
        mode,
        expectedGeneration)));
  }
}
