package com.fxplatform.tradinglab.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fxplatform.admin.service.AdminPermissionCatalog;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.tradinglab.application.TradingLabAuditService;
import com.fxplatform.tradinglab.client.ValidationSupervisorActionResult;
import com.fxplatform.tradinglab.client.ValidationSupervisorClient;
import com.fxplatform.tradinglab.client.ValidationSupervisorClientException;
import com.fxplatform.tradinglab.client.ValidationSupervisorHealth;
import com.fxplatform.tradinglab.client.ValidationSupervisorStatus;
import com.fxplatform.tradinglab.environment.TradingLabEnvironmentAction;
import com.fxplatform.tradinglab.environment.TradingLabEnvironmentException;
import com.fxplatform.tradinglab.environment.TradingLabEnvironmentService;
import com.fxplatform.tradinglab.environment.TradingLabOperationGate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig(TradingLabEnvironmentControllerTest.WebConfig.class)
class TradingLabEnvironmentControllerTest {

  private static final UUID ACTOR_ID =
      UUID.fromString("81000000-0000-0000-0000-000000000001");
  private static final UUID REQUEST_ID =
      UUID.fromString("82000000-0000-0000-0000-000000000002");
  private static final String REMOTE_ADDRESS = "203.0.113.19";
  private static final UserPrincipal VIEW_ADMIN = principal(
      AdminPermissionCatalog.TRADING_LAB_VIEW);
  private static final UserPrincipal EXECUTE_ADMIN = principal(
      AdminPermissionCatalog.TRADING_LAB_EXECUTE);
  private static final UserPrincipal SUPER_ADMIN = principal(
      AdminPermissionCatalog.SUPER_ADMIN);

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ValidationSupervisorClient supervisorClient;
  @Autowired private TradingLabOperationGate operationGate;
  @Autowired private TradingLabAuditService auditService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    reset(supervisorClient, operationGate, auditService);
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(springSecurity())
        .build();
  }

  @Test
  void getRequiresRoleAdminAndViewIndependentlyFromExecuteAndSuperAdmin() throws Exception {
    when(supervisorClient.status()).thenReturn(new ValidationSupervisorStatus(true));
    when(supervisorClient.health()).thenReturn(new ValidationSupervisorHealth("UP"));

    mockMvc.perform(get("/api/admin/trading-lab/environment"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/admin/trading-lab/environment")
            .with(user(principal())))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/api/admin/trading-lab/environment")
            .with(user(EXECUTE_ADMIN)))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/api/admin/trading-lab/environment")
            .with(user(SUPER_ADMIN)))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/api/admin/trading-lab/environment")
            .with(user(new UserPrincipal(
                ACTOR_ID,
                "view-without-role@example.test",
                "USER",
                List.of(AdminPermissionCatalog.TRADING_LAB_VIEW)))))
        .andExpect(status().isForbidden());

    mockMvc.perform(get("/api/admin/trading-lab/environment")
            .with(user(VIEW_ADMIN))
            .with(remoteAddress(REMOTE_ADDRESS))
            .header("X-Forwarded-For", "198.51.100.77")
            .header("X-Request-Id", REQUEST_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.relayRunning").value(true))
        .andExpect(jsonPath("$.data.validationHealth").value("UP"))
        .andExpect(jsonPath("$.data.output").doesNotExist())
        .andExpect(jsonPath("$.data.containerId").doesNotExist())
        .andExpect(jsonPath("$.data.stderr").doesNotExist());

    verify(supervisorClient).status();
    verify(supervisorClient).health();
    verify(auditService).record(
        eq(ACTOR_ID),
        eq(REMOTE_ADDRESS),
        eq(REQUEST_ID),
        eq(null),
        eq(null),
        eq("TRADING_LAB_ENVIRONMENT_CHECKED"),
        eq("SUCCESS"),
        any(Map.class));
  }

  @Test
  void postRequiresRoleAdminAndSuperAdminAndDoesNotInheritViewOrExecute() throws Exception {
    when(supervisorClient.start()).thenReturn(ValidationSupervisorActionResult.start(true));

    mockMvc.perform(post("/api/admin/trading-lab/environment/start"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/admin/trading-lab/environment/start")
            .with(user(principal())))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/admin/trading-lab/environment/start")
            .with(user(VIEW_ADMIN)))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/admin/trading-lab/environment/start")
            .with(user(EXECUTE_ADMIN)))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/admin/trading-lab/environment/start")
            .with(user(new UserPrincipal(
                ACTOR_ID,
                "super-without-role@example.test",
                "USER",
                List.of(AdminPermissionCatalog.SUPER_ADMIN)))))
        .andExpect(status().isForbidden());

    mockMvc.perform(post("/api/admin/trading-lab/environment/start")
            .with(user(SUPER_ADMIN))
            .with(remoteAddress(REMOTE_ADDRESS))
            .header("X-Request-Id", REQUEST_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.action").value("start"))
        .andExpect(jsonPath("$.data.relayRunning").value(true));

    verify(operationGate).acquireEnvironmentMutationPermit();
    verify(supervisorClient).start();
  }

  @Test
  void postAcceptsExactlyLowercaseStartStopRestartAndNeverStatusOrHealth() throws Exception {
    when(supervisorClient.start()).thenReturn(ValidationSupervisorActionResult.start(true));
    when(supervisorClient.stop()).thenReturn(ValidationSupervisorActionResult.stop(false));
    when(supervisorClient.restart()).thenReturn(ValidationSupervisorActionResult.restart(true));

    for (String action : List.of("start", "stop", "restart")) {
      mockMvc.perform(post("/api/admin/trading-lab/environment/{action}", action)
              .with(user(SUPER_ADMIN))
              .header("X-Request-Id", UUID.randomUUID()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.action").value(action));
    }

    reset(supervisorClient, operationGate, auditService);
    for (String action : List.of(
        "status",
        "health",
        "START",
        "Stop",
        "restart ",
        "start;whoami")) {
      mockMvc.perform(post("/api/admin/trading-lab/environment/{action}", action)
              .with(user(SUPER_ADMIN))
              .header("X-Request-Id", UUID.randomUUID()))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(supervisorClient, operationGate, auditService);
  }

  @Test
  void stopAndRestartRejectEveryNonTerminalRunBeforeAnySupervisorCall() throws Exception {
    when(operationGate.hasNonTerminalRuns()).thenReturn(true);

    for (String action : List.of("stop", "restart")) {
      mockMvc.perform(post("/api/admin/trading-lab/environment/{action}", action)
              .with(user(SUPER_ADMIN))
              .with(remoteAddress(REMOTE_ADDRESS))
              .header("X-Request-Id", REQUEST_ID))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("TRADING_LAB_ACTIVE_RUN_CONFLICT"));
    }

    verifyNoInteractions(supervisorClient);
    verify(auditService, org.mockito.Mockito.times(2)).record(
        eq(ACTOR_ID),
        eq(REMOTE_ADDRESS),
        eq(REQUEST_ID),
        eq(null),
        eq(null),
        eq("TRADING_LAB_ENVIRONMENT_ACTION"),
        eq("FAILED"),
        any(Map.class));
  }

  @Test
  void databaseSingleFlightConflictMakesZeroSupervisorCallsAndIsAudited() throws Exception {
    doThrow(TradingLabEnvironmentException.mutationBusy())
        .when(operationGate).acquireEnvironmentMutationPermit();

    mockMvc.perform(post("/api/admin/trading-lab/environment/restart")
            .with(user(SUPER_ADMIN))
            .with(remoteAddress(REMOTE_ADDRESS))
            .header("X-Request-Id", REQUEST_ID))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRADING_LAB_ENVIRONMENT_BUSY"));

    verifyNoInteractions(supervisorClient);
    verify(auditService).record(
        eq(ACTOR_ID),
        eq(REMOTE_ADDRESS),
        eq(REQUEST_ID),
        eq(null),
        eq(null),
        eq("TRADING_LAB_ENVIRONMENT_ACTION"),
        eq("FAILED"),
        any(Map.class));
  }

  @Test
  void supervisorFailureIsTruthful503UsesRemoteAddrAndWritesFailureAudit() throws Exception {
    when(supervisorClient.start()).thenThrow(new ValidationSupervisorClientException(
        ValidationSupervisorClientException.Reason.UNAVAILABLE,
        "COMMAND_TIMEOUT",
        504));

    mockMvc.perform(post("/api/admin/trading-lab/environment/start")
            .with(user(SUPER_ADMIN))
            .with(remoteAddress(REMOTE_ADDRESS))
            .header("X-Forwarded-For", "198.51.100.200")
            .header("X-Request-Id", REQUEST_ID))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("TRADING_LAB_SUPERVISOR_UNAVAILABLE"))
        .andExpect(jsonPath("$.data").isEmpty());

    verify(auditService).record(
        eq(ACTOR_ID),
        eq(REMOTE_ADDRESS),
        eq(REQUEST_ID),
        eq(null),
        eq(null),
        eq("TRADING_LAB_ENVIRONMENT_ACTION"),
        eq("FAILED"),
        any(Map.class));
  }

  private static UserPrincipal principal(String... authorities) {
    java.util.ArrayList<String> values = new java.util.ArrayList<>();
    values.add("ROLE_ADMIN");
    values.addAll(List.of(authorities));
    return new UserPrincipal(
        ACTOR_ID,
        "admin@example.test",
        "ADMIN",
        List.copyOf(values));
  }

  private static RequestPostProcessor remoteAddress(String value) {
    return request -> {
      request.setRemoteAddr(value);
      return request;
    };
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @EnableWebSecurity
  @EnableMethodSecurity
  static class WebConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
      http.csrf(csrf -> csrf.disable())
          .exceptionHandling(exceptions -> exceptions
              .authenticationEntryPoint((request, response, error) -> response.sendError(401))
              .accessDeniedHandler((request, response, error) -> response.sendError(403)))
          .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());
      return http.build();
    }

    @Bean
    ValidationSupervisorClient validationSupervisorClient() {
      return mock(ValidationSupervisorClient.class);
    }

    @Bean
    TradingLabOperationGate tradingLabOperationGate() {
      return mock(TradingLabOperationGate.class);
    }

    @Bean
    TradingLabAuditService tradingLabAuditService() {
      return mock(TradingLabAuditService.class);
    }

    @Bean
    TradingLabEnvironmentService tradingLabEnvironmentService(
        ValidationSupervisorClient client,
        TradingLabOperationGate gate,
        TradingLabAuditService auditService
    ) {
      return new TradingLabEnvironmentService(client, gate, auditService);
    }

    @Bean
    TradingLabEnvironmentController tradingLabEnvironmentController(
        TradingLabEnvironmentService service
    ) {
      return new TradingLabEnvironmentController(service);
    }
  }
}
