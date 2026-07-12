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
import com.fxplatform.trading.dto.request.AdjustPositionMarginRequest;
import com.fxplatform.trading.dto.request.AdjustPositionMarginRequest.Action;
import com.fxplatform.trading.dto.request.ClosePositionRequest;
import com.fxplatform.trading.dto.request.CreateProtectionRequest;
import com.fxplatform.trading.dto.response.AdjustPositionMarginResponse;
import com.fxplatform.trading.dto.response.OcoOrderGroupResponse;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.ProtectionType;
import com.fxplatform.trading.enums.QuantityUnit;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.TriggerPriceType;
import com.fxplatform.trading.enums.TriggerExecutionType;
import com.fxplatform.trading.service.OcoOrderService;
import com.fxplatform.trading.service.OrderService;
import com.fxplatform.trading.service.PositionService;
import com.fxplatform.trading.service.PositionMarginService;
import com.fxplatform.trading.service.ProtectionOrderService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TradingControllerTest {

  @Test
  void postPositionCloseForwardsOptionalExplicitQuantity() {
    OrderService orderService = org.mockito.Mockito.mock(OrderService.class);
    PositionService positionService = org.mockito.Mockito.mock(PositionService.class);
    OcoOrderService ocoOrderService = org.mockito.Mockito.mock(OcoOrderService.class);
    PositionMarginService marginService = org.mockito.Mockito.mock(PositionMarginService.class);
    TradingController controller = new TradingController(
        orderService, positionService, ocoOrderService, marginService);
    UserPrincipal principal = new UserPrincipal(UUID.randomUUID(), "trader@example.com", "TRADER");
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    ClosePositionRequest request = new ClosePositionRequest(
        new BigDecimal("0.25"), QuantityUnit.BASE, "close-api-1");

    controller.closePosition(principal, accountId, positionId, request);

    verify(positionService).closePosition(principal.id(), accountId, positionId, request);
  }

  @Test
  void postPositionCloseTreatsLegacyEmptyJsonObjectAsWholeClose() throws Exception {
    OrderService orderService = org.mockito.Mockito.mock(OrderService.class);
    PositionService positionService = org.mockito.Mockito.mock(PositionService.class);
    OcoOrderService ocoOrderService = org.mockito.Mockito.mock(OcoOrderService.class);
    PositionMarginService marginService = org.mockito.Mockito.mock(PositionMarginService.class);
    UserPrincipal principal = new UserPrincipal(
        UUID.randomUUID(), "trader@example.com", "TRADER");
    UUID accountId = UUID.randomUUID();
    UUID positionId = UUID.randomUUID();
    org.springframework.web.method.support.HandlerMethodArgumentResolver principalResolver =
        new org.springframework.web.method.support.HandlerMethodArgumentResolver() {
          @Override
          public boolean supportsParameter(
              org.springframework.core.MethodParameter parameter
          ) {
            return parameter.getParameterType() == UserPrincipal.class;
          }

          @Override
          public Object resolveArgument(
              org.springframework.core.MethodParameter parameter,
              org.springframework.web.method.support.ModelAndViewContainer container,
              org.springframework.web.context.request.NativeWebRequest request,
              org.springframework.web.bind.support.WebDataBinderFactory binderFactory
          ) {
            return principal;
          }
        };
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
            new TradingController(orderService, positionService, ocoOrderService, marginService))
        .setCustomArgumentResolvers(principalResolver)
        .build();

    mockMvc.perform(post("/api/trading/positions/{positionId}/close", positionId)
            .queryParam("accountId", accountId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk());

    verify(positionService).closePosition(
        principal.id(), accountId, positionId, null);
  }

  @Test
  void postPositionProtectionDelegatesAuthenticatedOwner() {
    OrderService orderService = org.mockito.Mockito.mock(OrderService.class);
    PositionService positionService = org.mockito.Mockito.mock(PositionService.class);
    OcoOrderService ocoOrderService = org.mockito.Mockito.mock(OcoOrderService.class);
    PositionMarginService marginService = org.mockito.Mockito.mock(PositionMarginService.class);
    ProtectionOrderService protectionOrderService =
        org.mockito.Mockito.mock(ProtectionOrderService.class);
    TradingController controller = new TradingController(
        orderService, positionService, ocoOrderService, marginService);
    controller.setProtectionOrderService(protectionOrderService);
    UserPrincipal principal = new UserPrincipal(UUID.randomUUID(), "trader@example.com", "TRADER");
    UUID positionId = UUID.randomUUID();
    CreateProtectionRequest request = new CreateProtectionRequest(
        ProtectionType.TAKE_PROFIT,
        new BigDecimal("0.25"),
        QuantityUnit.BASE,
        new BigDecimal("55000"),
        TriggerExecutionType.MARKET,
        null,
        "tp-api-1");

    controller.createProtection(principal, positionId, request);

    verify(protectionOrderService).create(principal.id(), positionId, request);
  }

  @Test
  void postPositionMarginDelegatesAuthenticatedOwnerWithoutAnAccountOverride() {
    OrderService orderService = org.mockito.Mockito.mock(OrderService.class);
    PositionService positionService = org.mockito.Mockito.mock(PositionService.class);
    OcoOrderService ocoOrderService = org.mockito.Mockito.mock(OcoOrderService.class);
    PositionMarginService marginService = org.mockito.Mockito.mock(PositionMarginService.class);
    TradingController controller = new TradingController(
        orderService, positionService, ocoOrderService, marginService);
    UserPrincipal principal = new UserPrincipal(UUID.randomUUID(), "trader@example.com", "TRADER");
    UUID positionId = UUID.randomUUID();
    AdjustPositionMarginRequest request = new AdjustPositionMarginRequest(
        Action.ADD, new BigDecimal("100"), 3L);
    AdjustPositionMarginResponse expected = new AdjustPositionMarginResponse(
        UUID.randomUUID(), positionId, "BTCUSDT-PERP", PositionSide.BOTH,
        MarginMode.ISOLATED, Action.ADD, new BigDecimal("100"), new BigDecimal("1000"),
        new BigDecimal("1100"), new BigDecimal("1100"), new BigDecimal("50"),
        new BigDecimal("5"), new BigDecimal("45000"), new BigDecimal("1100"),
        new BigDecimal("8900"), 4L);
    when(marginService.adjust(principal.id(), positionId, request)).thenReturn(expected);

    var response = controller.adjustPositionMargin(principal, positionId, request);

    assertThat(response.data()).isSameAs(expected);
    verify(marginService).adjust(principal.id(), positionId, request);
  }

  @Test
  void postOcoDelegatesWithAuthenticatedOwnerAndReturnsGroup() {
    OrderService orderService = org.mockito.Mockito.mock(OrderService.class);
    PositionService positionService = org.mockito.Mockito.mock(PositionService.class);
    OcoOrderService ocoOrderService = org.mockito.Mockito.mock(OcoOrderService.class);
    PositionMarginService marginService = org.mockito.Mockito.mock(PositionMarginService.class);
    TradingController controller = new TradingController(
        orderService, positionService, ocoOrderService, marginService);
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
    PositionMarginService marginService = org.mockito.Mockito.mock(PositionMarginService.class);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
        new TradingController(orderService, positionService, ocoOrderService, marginService)).build();

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
    PositionMarginService marginService = org.mockito.Mockito.mock(PositionMarginService.class);
    TradingController controller = new TradingController(
        orderService, positionService, ocoOrderService, marginService);
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
