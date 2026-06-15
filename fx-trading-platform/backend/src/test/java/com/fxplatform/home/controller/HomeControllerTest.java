package com.fxplatform.home.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.home.dto.HomeCountersResponse;
import com.fxplatform.home.service.HomeCountersService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class HomeControllerTest {

  @Test
  void homeCountersReturnsPublicHomepagePayload() {
    HomeCountersService service = Mockito.mock(HomeCountersService.class);
    HomeCountersResponse payload = new HomeCountersResponse(10L, 2L, 30L);
    when(service.currentCounters()).thenReturn(payload);

    ApiResponse<HomeCountersResponse> response = new HomeController(service).homeCounters();

    assertThat(response.success()).isTrue();
    assertThat(response.data()).isEqualTo(payload);
  }
}
