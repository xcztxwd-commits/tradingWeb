package com.fxplatform.validation.controller;

import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.validation.service.ValidationMarketPathService;
import com.fxplatform.validation.service.ValidationMarketPathService.PathReceipt;
import com.fxplatform.validation.service.ValidationMarketPathService.PathRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("validation")
@RestController
@RequestMapping("/internal/validation/market")
public class ValidationMarketController {

  private final ValidationMarketPathService marketPathService;

  public ValidationMarketController(ValidationMarketPathService marketPathService) {
    this.marketPathService = marketPathService;
  }

  @PostMapping("/path")
  public ApiResponse<PathReceipt> start(@RequestBody PathRequest request) {
    return ApiResponse.success(marketPathService.start(request));
  }
}
