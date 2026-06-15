package com.fxplatform.home.controller;

import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.home.dto.HomeCountersResponse;
import com.fxplatform.home.service.HomeCountersService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class HomeController {

  private final HomeCountersService homeCountersService;

  @GetMapping("/home-counters")
  public ApiResponse<HomeCountersResponse> homeCounters() {
    return ApiResponse.success(homeCountersService.currentCounters());
  }
}
