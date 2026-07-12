package com.fxplatform.trading.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fxplatform.common.exception.AuthorizationException;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.trading.dto.request.CreateOcoOrderRequest;
import com.fxplatform.trading.dto.response.OcoOrderGroupResponse;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.service.OcoOrderService;
import com.fxplatform.trading.service.OrderService;
import com.fxplatform.trading.service.PositionService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TradingControllerTest {

  @Test
  void postOcoDelegatesWithAuthenticatedOwnerAndReturnsGroup() {
    OrderService orderService = org.mockito.Mockito.mock(OrderService.class);
    PositionService positionService = org.mockito.Mockito.mock(PositionService.class);
    OcoOrderService ocoOrderService = org.mockito.Mockito.mock(OcoOrderService.class);
    TradingController controller = new TradingController(orderService, positionService, ocoOrderService);
    UserPrincipal principal = new UserPrincipal(UUID.randomUUID(), "trader@example.com", "TRADER");
    CreateOcoOrderRequest request = new CreateOcoOrderRequest(
        UUID.randomUUID(), "BTCUSDT", OrderSide.SELL, new BigDecimal("0.1"),
        QuantityUnit.BASE, new BigDecimal("55000"), new BigDecimal("49000"),
        TriggerPriceType.LAST_PRICE, "oco-api", "oco-api");
    OcoOrderGroupResponse expected = new OcoOrderGroupResponse(UUID.randomUUID(), null, null);
    when(ocoOrderService.create(principal, request)).thenReturn(expected);

    var response = controller.createOco(principal, request);

    assertThat(response.success()).isTrue();
    assertThat(response.data()).isSameAs(expected);
    verify(ocoOrderService).create(principal, request);
  }

  @Test
  void postOcoBeanValidationReturnsBadRequestWithoutCallingService() throws Exception {
    OrderService orderService = org.mockito.Mockito.mock(OrderService.class);
    PositionService positionService = org.mockito.Mockito.mock(PositionService.class);
    OcoOrderService ocoOrderService = org.mockito.Mockito.mock(OcoOrderService.class);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
        new TradingController(orderService, positionService, ocoOrderService)).build();

    mockMvc.perform(post("/api/trading/oco")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "symbol": "",
                  "side": "SELL",
                  "quantity": 0,
                  "quantityUnit": "BASE",
                  "limitPrice": 55000,
                  "stopTriggerPrice": 49000,
                  "triggerPriceType": "LAST_PRICE",
                  "idempotencyKey": "invalid-oco"
                }
                """))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(ocoOrderService);
  }

  @Test
  void postOcoPropagatesOwnershipFailureForTheAuthenticatedPrincipal() {
    OrderService orderService = org.mockito.Mockito.mock(OrderService.class);
    PositionService positionService = org.mockito.Mockito.mock(PositionService.class);
    OcoOrderService ocoOrderService = org.mockito.Mockito.mock(OcoOrderService.class);
    TradingController controller = new TradingController(orderService, positionService, ocoOrderService);
    UserPrincipal principal = new UserPrincipal(UUID.randomUUID(), "trader@example.com", "TRADER");
    CreateOcoOrderRequest request = new CreateOcoOrderRequest(
        UUID.randomUUID(), "BTCUSDT", OrderSide.SELL, new BigDecimal("0.1"),
        QuantityUnit.BASE, new BigDecimal("55000"), new BigDecimal("49000"),
        TriggerPriceType.LAST_PRICE, "unowned", "unowned");
    when(ocoOrderService.create(principal, request))
        .thenThrow(new AuthorizationException("ACCOUNT_NOT_FOUND", "Account not found"));

    assertThatThrownBy(() -> controller.createOco(principal, request))
        .isInstanceOfSatisfying(AuthorizationException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("ACCOUNT_NOT_FOUND"));

    verify(ocoOrderService).create(principal, request);
  }
}
