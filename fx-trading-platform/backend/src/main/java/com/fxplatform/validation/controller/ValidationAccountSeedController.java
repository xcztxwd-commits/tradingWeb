package com.fxplatform.validation.controller;

import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.validation.service.ValidationAccountSeedReceipt;
import com.fxplatform.validation.service.ValidationAccountSeedRequest;
import com.fxplatform.validation.service.ValidationAccountSeedService;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("validation")
@RestController
@RequestMapping("/internal/validation/accounts")
public class ValidationAccountSeedController {

  private final ValidationAccountSeedService seedService;

  public ValidationAccountSeedController(ValidationAccountSeedService seedService) {
    this.seedService = seedService;
  }

  @PostMapping("/{accountId}/seed")
  public ApiResponse<ValidationAccountSeedReceipt> seed(
      @PathVariable UUID accountId,
      @RequestBody ValidationAccountSeedRequest request
  ) {
    return ApiResponse.success(seedService.seed(accountId, request));
  }
}
