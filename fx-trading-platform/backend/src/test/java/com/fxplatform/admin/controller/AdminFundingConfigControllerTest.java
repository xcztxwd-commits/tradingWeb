package com.fxplatform.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.dto.request.AdminFundingConfigRequest;
import com.fxplatform.admin.dto.response.AdminFundingConfigResponse;
import com.fxplatform.admin.service.AdminMarketCommandService;
import com.fxplatform.admin.service.AdminMarketQueryService;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.market.service.QuoteService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;

class AdminFundingConfigControllerTest {

  @Test
  void exposesFundingConfigAtCanonicalGetAndPutPath() throws Exception {
    GetMapping getMapping = AdminMarketController.class
        .getMethod("fundingConfig", UUID.class)
        .getAnnotation(GetMapping.class);
    PutMapping putMapping = AdminMarketController.class
        .getMethod(
            "updateFundingConfig",
            UserPrincipal.class,
            UUID.class,
            AdminFundingConfigRequest.class)
        .getAnnotation(PutMapping.class);

    assertThat(getMapping).isNotNull();
    assertThat(getMapping.value()).containsExactly("/symbols/{id}/funding-config");
    assertThat(putMapping).isNotNull();
    assertThat(putMapping.value()).containsExactly("/symbols/{id}/funding-config");
  }

  @Test
  void delegatesFundingConfigReadAndUpdateWithoutLosingActualSourceMetadata() {
    AdminMarketQueryService queryService = Mockito.mock(AdminMarketQueryService.class);
    AdminMarketCommandService commandService = Mockito.mock(AdminMarketCommandService.class);
    QuoteService quoteService = Mockito.mock(QuoteService.class);
    AdminMarketController controller = new AdminMarketController(queryService, commandService, quoteService);
    UUID actorUserId = UUID.randomUUID();
    UUID symbolId = UUID.randomUUID();
    UserPrincipal principal = new UserPrincipal(actorUserId, "admin@example.com", "ADMIN");
    AdminFundingConfigRequest request = request();
    AdminFundingConfigResponse response = response(symbolId);
    when(queryService.fundingConfig(symbolId)).thenReturn(response);
    when(commandService.updateFundingConfig(actorUserId, symbolId, request)).thenReturn(response);

    var readResult = controller.fundingConfig(symbolId);
    var updateResult = controller.updateFundingConfig(principal, symbolId, request);

    assertThat(readResult.success()).isTrue();
    assertThat(readResult.data().actualSource()).isEqualTo("OKX");
    assertThat(readResult.data().sourceMode()).isEqualTo("PUBLIC_EXTERNAL");
    assertThat(updateResult.success()).isTrue();
    assertThat(updateResult.data()).isEqualTo(response);
    verify(queryService).fundingConfig(symbolId);
    verify(commandService).updateFundingConfig(actorUserId, symbolId, request);
  }

  private AdminFundingConfigRequest request() {
    return new AdminFundingConfigRequest(
        List.of("OKX", "BINANCE", "FIXED"),
        new BigDecimal("-0.0002"),
        240,
        120,
        "prefer OKX for later cycles");
  }

  private AdminFundingConfigResponse response(UUID symbolId) {
    return new AdminFundingConfigResponse(
        symbolId,
        "BTCUSDT-PERP",
        List.of("OKX", "BINANCE", "FIXED"),
        new BigDecimal("-0.0002"),
        240,
        120,
        "OKX",
        "PUBLIC_EXTERNAL",
        Instant.parse("2026-07-12T07:59:59Z"),
        Instant.parse("2026-07-12T08:00:00Z"));
  }
}
