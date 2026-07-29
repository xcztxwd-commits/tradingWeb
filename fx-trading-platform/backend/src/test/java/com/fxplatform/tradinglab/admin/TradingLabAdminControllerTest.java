package com.fxplatform.tradinglab.admin;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.admin.service.AdminPermissionCatalog;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.tradinglab.admin.dto.TradingLabRunCreateRequest;
import com.fxplatform.tradinglab.admin.dto.TradingLabScenarioWriteRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(TradingLabAdminControllerTest.Config.class)
class TradingLabAdminControllerTest {

  private static final UUID ACTOR_ID = UUID.fromString(
      "10000000-0000-0000-0000-000000000801");
  private static final UUID SCENARIO_ID = UUID.fromString(
      "10000000-0000-0000-0000-000000000802");
  private static final UUID RUN_ID = UUID.fromString(
      "10000000-0000-0000-0000-000000000803");
  private static final UUID REQUEST_ID = UUID.fromString(
      "10000000-0000-0000-0000-000000000804");
  private static final UserPrincipal PRINCIPAL = new UserPrincipal(
      ACTOR_ID,
      "admin@example.test",
      "ADMIN",
      List.of("ROLE_ADMIN"));

  @Autowired
  private TradingLabAdminController controller;

  @Autowired
  private TradingLabAdminRunControlFacade controlFacade;

  @Test
  @WithMockUser(authorities = {"ROLE_ADMIN", AdminPermissionCatalog.TRADING_LAB_VIEW})
  void viewAuthorityCanReadButCannotCreateOrControl() {
    assertThatCode(controller::config).doesNotThrowAnyException();
    assertThatThrownBy(() -> controller.createScenario(
        PRINCIPAL, REQUEST_ID, request(), scenarioRequest()))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> controller.pause(
        RUN_ID, PRINCIPAL, REQUEST_ID, request()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @WithMockUser(authorities = {"ROLE_ADMIN", AdminPermissionCatalog.TRADING_LAB_EXECUTE})
  void executeAuthorityCanMutateAndControlButCannotRead() {
    assertThatCode(() -> controller.createScenario(
        PRINCIPAL, REQUEST_ID, request(), scenarioRequest()))
        .doesNotThrowAnyException();
    assertThatCode(() -> controller.createRun(
        SCENARIO_ID, PRINCIPAL, REQUEST_ID, request(), runRequest()))
        .doesNotThrowAnyException();
    assertThatCode(() -> controller.pause(
        RUN_ID, PRINCIPAL, REQUEST_ID, request())).doesNotThrowAnyException();
    assertThatCode(() -> controller.resume(
        RUN_ID, PRINCIPAL, REQUEST_ID, request())).doesNotThrowAnyException();
    assertThatCode(() -> controller.cancel(
        RUN_ID, PRINCIPAL, REQUEST_ID, request())).doesNotThrowAnyException();
    verify(controlFacade).execute(
        TradingLabRunControlAction.PAUSE,
        RUN_ID,
        ACTOR_ID,
        "127.0.0.1",
        REQUEST_ID);
    verify(controlFacade).execute(
        TradingLabRunControlAction.RESUME,
        RUN_ID,
        ACTOR_ID,
        "127.0.0.1",
        REQUEST_ID);
    verify(controlFacade).execute(
        TradingLabRunControlAction.CANCEL,
        RUN_ID,
        ACTOR_ID,
        "127.0.0.1",
        REQUEST_ID);
    assertThatThrownBy(controller::config).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @WithMockUser(authorities = {AdminPermissionCatalog.TRADING_LAB_VIEW})
  void missingRoleAdminIsAlwaysDenied() {
    assertThatThrownBy(controller::config).isInstanceOf(AccessDeniedException.class);
  }

  private static TradingLabScenarioWriteRequest scenarioRequest() {
    ObjectMapper json = new ObjectMapper();
    return new TradingLabScenarioWriteRequest(
        "Scenario",
        "description",
        false,
        "seed-7",
        "model-v1",
        json.createObjectNode(),
        json.createObjectNode(),
        "0".repeat(64),
        null);
  }

  private static TradingLabRunCreateRequest runRequest() {
    return new TradingLabRunCreateRequest(
        0L,
        "0".repeat(64),
        new ObjectMapper().createObjectNode());
  }

  private static HttpServletRequest request() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    org.mockito.Mockito.when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    return request;
  }

  @Configuration(proxyBeanMethods = false)
  @EnableMethodSecurity
  static class Config {

    @Bean
    TradingLabAdminService tradingLabAdminService() {
      return mock(TradingLabAdminService.class);
    }

    @Bean
    TradingLabAdminRunControlFacade tradingLabAdminRunControlFacade() {
      return mock(TradingLabAdminRunControlFacade.class);
    }

    @Bean
    TradingLabAdminController tradingLabAdminController(
        TradingLabAdminService service,
        TradingLabAdminRunControlFacade controls) {
      return new TradingLabAdminController(service, controls);
    }
  }
}
