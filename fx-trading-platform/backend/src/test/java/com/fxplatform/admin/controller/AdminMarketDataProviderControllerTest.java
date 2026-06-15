package com.fxplatform.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.dto.request.AdminSymbolProviderBindingRequest;
import com.fxplatform.admin.dto.response.AdminSymbolProviderBindingResponse;
import com.fxplatform.admin.service.AdminMarketDataProviderService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.access.prepost.PreAuthorize;

class AdminMarketDataProviderControllerTest {

  @Test
  void requiresAdminRoleForProviderEndpoints() {
    PreAuthorize preAuthorize = AdminMarketDataProviderController.class.getAnnotation(PreAuthorize.class);

    assertThat(preAuthorize).isNotNull();
    assertThat(preAuthorize.value()).isEqualTo("hasRole('ADMIN')");
  }

  @Test
  void updateBindingDelegatesToProviderService() {
    AdminMarketDataProviderService service = Mockito.mock(AdminMarketDataProviderService.class);
    AdminMarketDataProviderController controller = new AdminMarketDataProviderController(service);
    UUID symbolId = UUID.randomUUID();
    UUID bindingId = UUID.randomUUID();
    UUID providerId = UUID.randomUUID();
    var request = new AdminSymbolProviderBindingRequest(providerId, null, "BTC-USDT", 50, true, "{}");
    var response = new AdminSymbolProviderBindingResponse(
        bindingId,
        symbolId,
        providerId,
        null,
        "BTC-USDT",
        50,
        true,
        "{}",
        null,
        null);
    when(service.updateBinding(symbolId, bindingId, request)).thenReturn(response);

    var result = controller.updateBinding(symbolId, bindingId, request);

    assertThat(result.success()).isTrue();
    assertThat(result.data()).isEqualTo(response);
    verify(service).updateBinding(symbolId, bindingId, request);
  }
}
