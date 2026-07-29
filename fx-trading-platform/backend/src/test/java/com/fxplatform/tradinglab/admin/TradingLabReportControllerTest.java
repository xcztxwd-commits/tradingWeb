package com.fxplatform.tradinglab.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fxplatform.admin.service.AdminPermissionCatalog;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.tradinglab.admin.report.TradingLabAdminRequestContext;
import com.fxplatform.tradinglab.admin.report.TradingLabPermanentRequest;
import com.fxplatform.tradinglab.admin.report.TradingLabPermanentResponse;
import com.fxplatform.tradinglab.admin.report.TradingLabPrintConfirmationResponse;
import com.fxplatform.tradinglab.admin.report.TradingLabPrintInfoResponse;
import com.fxplatform.tradinglab.admin.report.TradingLabReportAdminException;
import com.fxplatform.tradinglab.admin.report.TradingLabReportAdminService;
import com.fxplatform.tradinglab.admin.report.TradingLabReportExceptionHandler;
import com.fxplatform.tradinglab.admin.report.TradingLabReportResponse;
import com.fxplatform.tradinglab.report.TradingLabReportReadTicket;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig(TradingLabReportControllerTest.TestConfig.class)
class TradingLabReportControllerTest {

  private static final UUID ACTOR_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID REPORT_ID =
      UUID.fromString("20000000-0000-0000-0000-000000000002");
  private static final UUID RUN_ID =
      UUID.fromString("30000000-0000-0000-0000-000000000003");
  private static final UUID SCENARIO_ID =
      UUID.fromString("40000000-0000-0000-0000-000000000004");
  private static final UUID REQUEST_ID =
      UUID.fromString("50000000-0000-0000-0000-000000000005");
  private static final String CONFIRMATION = "c".repeat(43);
  private static final TradingLabReportReadTicket TICKET =
      new TradingLabReportReadTicket(REPORT_ID, 7L, 123L, 80L, 2);

  @Autowired
  private WebApplicationContext context;

  @Autowired
  private TradingLabReportAdminService service;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    reset(service);
    mockMvc = MockMvcBuilders.webAppContextSetup(context)
        .apply(springSecurity())
        .build();
  }

  @Test
  void viewDownloadPreparesSynchronouslyBefore200AndStreamsCompactJson() throws Exception {
    byte[] compact = "{\"metadata\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    CountDownLatch allowStream = new CountDownLatch(1);
    when(service.prepareDownload(REPORT_ID)).thenReturn(TICKET);
    doAnswer(invocation -> {
      if (!allowStream.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out waiting for the synchronous response assertions");
      }
      OutputStream output = invocation.getArgument(1);
      output.write(compact);
      return null;
    }).when(service).streamCompact(eq(TICKET), any(OutputStream.class));

    MvcResult started;
    try {
      started = mockMvc.perform(get(
              "/api/admin/trading-lab/reports/{id}/download", REPORT_ID)
              .with(user(viewAdmin()))
              .header("X-Request-Id", REQUEST_ID))
          .andExpect(status().isOk())
          .andExpect(request().asyncStarted())
          .andExpect(header().string("Content-Type", "application/json"))
          .andReturn();

      // prepareDownload and the HTTP headers are complete before streaming is released.
      verify(service).prepareDownload(REPORT_ID);
    } finally {
      allowStream.countDown();
    }
    mockMvc.perform(asyncDispatch(started))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/json"))
        .andExpect(content().bytes(compact));
    verify(service).streamCompact(eq(TICKET), any(OutputStream.class));
  }

  @Test
  void preflightNotFoundFailsBeforeAsyncResponseCommit() throws Exception {
    when(service.prepareDownload(REPORT_ID))
        .thenThrow(TradingLabReportAdminException.notFound());

    mockMvc.perform(get("/api/admin/trading-lab/reports/{id}/download", REPORT_ID)
            .with(user(viewAdmin())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TRADING_LAB_REPORT_NOT_FOUND"));

    verify(service).prepareDownload(REPORT_ID);
    verifyNoInteractionsAfterPrepare();
  }

  @Test
  void printUsesUtf8TextAndOnlyTheConfirmationHeader() throws Exception {
    CountDownLatch streamStarted = new CountDownLatch(1);
    CountDownLatch allowStream = new CountDownLatch(1);
    when(service.preparePrint(ACTOR_ID, REPORT_ID, CONFIRMATION)).thenReturn(TICKET);
    doAnswer(invocation -> {
      streamStarted.countDown();
      if (!allowStream.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out waiting for the print timeout assertion");
      }
      OutputStream output = invocation.getArgument(1);
      output.write("{\n  \"metadata\" : { }\n}"
          .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return null;
    }).when(service).streamPretty(eq(TICKET), any(OutputStream.class));

    MvcResult started;
    try {
      started = mockMvc.perform(get(
              "/api/admin/trading-lab/reports/{id}/print", REPORT_ID)
              .with(user(viewAdmin()))
              .header("X-Trading-Lab-Print-Confirmation", CONFIRMATION))
          .andExpect(status().isOk())
          .andExpect(request().asyncStarted())
          .andExpect(header().string(
              "Content-Type", "text/plain;charset=UTF-8"))
          .andReturn();
      assertThat(streamStarted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(started.getRequest().getAsyncContext().getTimeout())
          .isEqualTo(360_000L);
    } finally {
      allowStream.countDown();
    }

    verify(service).preparePrint(ACTOR_ID, REPORT_ID, CONFIRMATION);
    mockMvc.perform(asyncDispatch(started))
        .andExpect(status().isOk())
        .andExpect(content().contentType("text/plain;charset=UTF-8"))
        .andExpect(content().string("{\n  \"metadata\" : { }\n}"));
  }

  @Test
  void roleAdminAndViewCanReadButCannotDeleteOrIssueConfirmation() throws Exception {
    when(service.detail(REPORT_ID)).thenReturn(reportResponse());

    mockMvc.perform(get("/api/admin/trading-lab/reports/{id}", REPORT_ID)
            .with(user(viewAdmin())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(REPORT_ID.toString()))
        .andExpect(jsonPath("$.data.runId").value(RUN_ID.toString()))
        .andExpect(jsonPath("$.data.leaseOwner").doesNotExist());

    mockMvc.perform(delete("/api/admin/trading-lab/reports/{id}", REPORT_ID)
            .with(user(viewAdmin())))
        .andExpect(status().isForbidden());
    mockMvc.perform(post(
            "/api/admin/trading-lab/reports/{id}/print-confirmation", REPORT_ID)
            .with(user(viewAdmin())))
        .andExpect(status().isForbidden());
  }

  @Test
  void printInfoReturnsExactCompactBytesPageEstimateAndThreshold() throws Exception {
    when(service.printInfo(REPORT_ID)).thenReturn(
        new TradingLabPrintInfoResponse(52_428_801L, 12_801L, 52_428_800L, true));

    mockMvc.perform(get(
            "/api/admin/trading-lab/reports/{id}/print-info", REPORT_ID)
            .with(user(viewAdmin())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.uncompressedBytes").value(52_428_801L))
        .andExpect(jsonPath("$.data.estimatedPageCount").value(12_801L))
        .andExpect(jsonPath("$.data.thresholdBytes").value(52_428_800L))
        .andExpect(jsonPath("$.data.requiresConfirmation").value(true));
  }

  @Test
  void roleAdminAndExecuteCanDeleteAndSetPermanentButCannotRead() throws Exception {
    when(service.setPermanent(
        any(TradingLabAdminRequestContext.class), eq(REPORT_ID), eq(true)))
        .thenReturn(new TradingLabPermanentResponse(REPORT_ID, true, 8L));

    mockMvc.perform(delete("/api/admin/trading-lab/reports/{id}", REPORT_ID)
            .with(user(executeAdmin()))
            .header("X-Request-Id", REQUEST_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    mockMvc.perform(post("/api/admin/trading-lab/reports/{id}/permanent", REPORT_ID)
            .with(user(executeAdmin()))
            .header("X-Request-Id", REQUEST_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"permanent\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.permanent").value(true))
        .andExpect(jsonPath("$.data.version").value(8));

    mockMvc.perform(get("/api/admin/trading-lab/reports/{id}", REPORT_ID)
            .with(user(executeAdmin())))
        .andExpect(status().isForbidden());

    TradingLabAdminRequestContext expectedContext =
        new TradingLabAdminRequestContext(ACTOR_ID, "127.0.0.1", REQUEST_ID);
    verify(service).delete(expectedContext, REPORT_ID);
    verify(service).setPermanent(expectedContext, REPORT_ID, true);
  }

  @Test
  void roleAdminAndSuperCanIssueButCannotPrintWithoutView() throws Exception {
    when(service.issuePrintConfirmation(ACTOR_ID, REPORT_ID))
        .thenReturn(new TradingLabPrintConfirmationResponse(
            REPORT_ID,
            CONFIRMATION,
            Instant.parse("2026-07-24T12:05:00Z")));

    mockMvc.perform(post(
            "/api/admin/trading-lab/reports/{id}/print-confirmation", REPORT_ID)
            .with(user(superAdmin())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.token").value(CONFIRMATION));

    mockMvc.perform(get("/api/admin/trading-lab/reports/{id}/print", REPORT_ID)
            .with(user(superAdmin()))
            .header("X-Trading-Lab-Print-Confirmation", CONFIRMATION))
        .andExpect(status().isForbidden());
  }

  @Test
  void permanentBodyIsExactlyOneRequiredBoolean() throws Exception {
    mockMvc.perform(post("/api/admin/trading-lab/reports/{id}/permanent", REPORT_ID)
            .with(user(executeAdmin()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/api/admin/trading-lab/reports/{id}/permanent", REPORT_ID)
            .with(user(executeAdmin()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"permanent\":true,\"unexpected\":1}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(service);
  }

  private void verifyNoInteractionsAfterPrepare() throws Exception {
    org.mockito.Mockito.verify(service, org.mockito.Mockito.never())
        .streamCompact(any(), any());
  }

  private static TradingLabReportResponse reportResponse() {
    return new TradingLabReportResponse(
        REPORT_ID,
        RUN_ID,
        SCENARIO_ID,
        "COMPLETED",
        "model-v1",
        "a".repeat(64),
        "working-tree:test",
        123L,
        80L,
        2,
        Instant.parse("2026-08-23T12:00:00Z"),
        false,
        null,
        null,
        Instant.parse("2026-07-24T11:00:00Z"),
        Instant.parse("2026-07-24T12:00:00Z"),
        7L);
  }

  private static UserPrincipal viewAdmin() {
    return principal(AdminPermissionCatalog.TRADING_LAB_VIEW);
  }

  private static UserPrincipal executeAdmin() {
    return principal(AdminPermissionCatalog.TRADING_LAB_EXECUTE);
  }

  private static UserPrincipal superAdmin() {
    return principal(AdminPermissionCatalog.SUPER_ADMIN);
  }

  private static UserPrincipal principal(String authority) {
    return new UserPrincipal(
        ACTOR_ID,
        "admin@example.test",
        "ADMIN",
        List.of("ROLE_ADMIN", authority));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @EnableWebSecurity
  @EnableMethodSecurity
  static class TestConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
      http.csrf(csrf -> csrf.disable())
          .exceptionHandling(exception -> exception.authenticationEntryPoint(
              (request, response, error) -> response.sendError(401)))
          .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());
      return http.build();
    }

    @Bean
    TradingLabReportAdminService reportAdminService() {
      return mock(TradingLabReportAdminService.class);
    }

    @Bean
    TradingLabReportController reportController(TradingLabReportAdminService service) {
      return new TradingLabReportController(service);
    }

    @Bean
    TradingLabReportExceptionHandler reportExceptionHandler() {
      return new TradingLabReportExceptionHandler();
    }
  }
}
