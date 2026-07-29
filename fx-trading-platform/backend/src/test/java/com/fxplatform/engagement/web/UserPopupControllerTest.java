package com.fxplatform.engagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.GlobalExceptionHandler;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.engagement.application.popup.PopupClaimService;
import com.fxplatform.engagement.application.popup.PopupClaimService.PopupClaim;
import com.fxplatform.engagement.application.popup.PopupClaimService.PopupSurface;
import com.fxplatform.engagement.application.popup.PopupOutcomeService;
import com.fxplatform.engagement.application.popup.PopupOutcomeService.PopupOutcomeResult;
import com.fxplatform.engagement.domain.popup.PopupOutcome;
import com.fxplatform.engagement.domain.popup.PopupQueuePolicy;
import com.fxplatform.engagement.persistence.enums.DeviceClass;
import com.fxplatform.engagement.persistence.enums.PopupDeliveryStatus;
import com.fxplatform.engagement.persistence.enums.TemplateSize;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig(UserPopupControllerTest.MethodSecurityConfig.class)
class UserPopupControllerTest {

  private static final UUID USER_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID SESSION_ID =
      UUID.fromString("20000000-0000-0000-0000-000000000002");
  private static final UUID DELIVERY_ID =
      UUID.fromString("30000000-0000-0000-0000-000000000003");
  private static final UUID CAMPAIGN_ID =
      UUID.fromString("40000000-0000-0000-0000-000000000004");
  private static final UUID REVISION_ID =
      UUID.fromString("50000000-0000-0000-0000-000000000005");
  private static final UUID COVER_ASSET_ID =
      UUID.fromString("60000000-0000-0000-0000-000000000006");
  private static final String TOKEN = "a".repeat(43);
  private static final PopupSurface SURFACE =
      new PopupSurface("LOGIN", "dashboard", DeviceClass.PC);
  private static final UserPrincipal PRINCIPAL =
      new UserPrincipal(USER_ID, "user@example.test", "USER");
  private static final String SURFACE_JSON =
      "{\"triggerType\":\"LOGIN\",\"pageKey\":\"dashboard\",\"deviceClass\":\"PC\"}";

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private PopupClaimService securedClaimService;
  @Autowired private PopupOutcomeService securedOutcomeService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUpMockMvc() {
    reset(securedClaimService, securedOutcomeService);
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(springSecurity())
        .build();
  }

  @Test
  void anonymousCallerReceives401WithoutCallingPopupServices() throws Exception {
    mockMvc.perform(post("/api/me/engagement/popup-queues")
            .contentType(MediaType.APPLICATION_JSON)
            .content(SURFACE_JSON))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(securedClaimService, securedOutcomeService);
  }

  @Test
  void adminCallerReceives403WithoutCallingPopupServices() throws Exception {
    UserPrincipal admin = new UserPrincipal(UUID.randomUUID(), "admin@example.test", "ADMIN");

    mockMvc.perform(post("/api/me/engagement/popup-queues")
            .with(user(admin))
            .contentType(MediaType.APPLICATION_JSON)
            .content(SURFACE_JSON))
        .andExpect(status().isForbidden());

    verifyNoInteractions(securedClaimService, securedOutcomeService);
  }

  @Test
  void roleUserReceives200AndServiceGetsOnlyTheAuthenticatedPrincipalId() throws Exception {
    when(securedClaimService.claimNextPopup(USER_ID, SURFACE, null)).thenReturn(Optional.empty());

    mockMvc.perform(post("/api/me/engagement/popup-queues")
            .with(user(PRINCIPAL))
            .contentType(MediaType.APPLICATION_JSON)
            .content(SURFACE_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.code").value("OK"));

    verify(securedClaimService).claimNextPopup(USER_ID, SURFACE, null);
    verifyNoInteractions(securedOutcomeService);
  }

  @Test
  void popupBusinessFailuresAreRenderedAsExplicitHttp400Responses() throws Exception {
    when(securedClaimService.claimNextPopup(USER_ID, SURFACE, SESSION_ID))
        .thenThrow(new BusinessException(
            "POPUP_QUEUE_SESSION_INVALID", "Popup queue session is invalid"));
    when(securedOutcomeService.recordPopupOutcome(USER_ID, TOKEN, PopupOutcome.SHOWN))
        .thenThrow(new BusinessException(
            "POPUP_DELIVERY_CREDENTIAL_INVALID", "Popup delivery credential is invalid"));

    mockMvc.perform(post("/api/me/engagement/popup-queues/{sessionId}/next", SESSION_ID)
            .with(user(PRINCIPAL))
            .contentType(MediaType.APPLICATION_JSON)
            .content(SURFACE_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("POPUP_QUEUE_SESSION_INVALID"));
    mockMvc.perform(post("/api/me/engagement/popup-deliveries/{deliveryToken}/shown", TOKEN)
            .with(user(PRINCIPAL)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("POPUP_DELIVERY_CREDENTIAL_INVALID"));
  }

  @Test
  void exposesExactlyTheSixFrozenRoleUserRoutes() {
    assertThat(UserPopupController.class.getAnnotation(RestController.class)).isNotNull();
    assertThat(UserPopupController.class.getAnnotation(RequestMapping.class).value())
        .containsExactly("/api/me/engagement");
    assertThat(UserPopupController.class.getAnnotation(PreAuthorize.class).value())
        .isEqualTo("hasRole('USER')");

    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("startQueue", "/popup-queues");
    expected.put("next", "/popup-queues/{sessionId}/next");
    expected.put("shown", "/popup-deliveries/{deliveryToken}/shown");
    expected.put("close", "/popup-deliveries/{deliveryToken}/close");
    expected.put("optOut", "/popup-deliveries/{deliveryToken}/opt-out");
    expected.put("click", "/popup-deliveries/{deliveryToken}/click");

    Map<String, String> actual = new LinkedHashMap<>();
    for (Method method : UserPopupController.class.getDeclaredMethods()) {
      PostMapping mapping = method.getAnnotation(PostMapping.class);
      if (mapping != null) {
        actual.put(method.getName(), mapping.value()[0]);
      }
    }
    assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
  }

  @Test
  void callerIdentityComesOnlyFromPrincipalAndRequestCannotSupplyAUserId() {
    assertThat(PopupSurface.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .containsExactly("triggerType", "pageKey", "deviceClass")
        .doesNotContain("userId", "user", "principalId");

    for (Method method : UserPopupController.class.getDeclaredMethods()) {
      if (method.getAnnotation(PostMapping.class) == null) {
        continue;
      }
      assertThat(method.getParameterTypes()[0]).as(method.getName())
          .isEqualTo(UserPrincipal.class);
      assertThat(method.getParameters()[0].getAnnotation(AuthenticationPrincipal.class))
          .as(method.getName()).isNotNull();
      assertThat(Arrays.stream(method.getParameters())
          .noneMatch(parameter -> parameter.getName().equalsIgnoreCase("userId")))
          .as(method.getName()).isTrue();
    }

    Method start = method("startQueue", UserPrincipal.class, PopupSurface.class);
    assertThat(start.getParameters()[1].getAnnotation(RequestBody.class)).isNotNull();
    Method next = method("next", UserPrincipal.class, UUID.class, PopupSurface.class);
    assertThat(next.getParameters()[1].getAnnotation(PathVariable.class).value())
        .isEqualTo("sessionId");
    assertThat(next.getParameters()[2].getAnnotation(RequestBody.class)).isNotNull();
    for (String outcome : java.util.List.of("shown", "close", "optOut", "click")) {
      Method endpoint = method(outcome, UserPrincipal.class, String.class);
      assertThat(endpoint.getParameters()[1].getAnnotation(PathVariable.class).value())
          .isEqualTo("deliveryToken");
    }
  }

  @Test
  void queueStartAndNextDelegateThePrincipalSurfaceAndSessionAndNoCandidateIsSuccessNull() {
    PopupClaimService claimService = org.mockito.Mockito.mock(PopupClaimService.class);
    PopupOutcomeService outcomeService = org.mockito.Mockito.mock(PopupOutcomeService.class);
    UserPopupController controller = new UserPopupController(claimService, outcomeService);
    PopupClaim claim = claim();
    when(claimService.claimNextPopup(USER_ID, SURFACE, null)).thenReturn(Optional.of(claim));
    when(claimService.claimNextPopup(USER_ID, SURFACE, SESSION_ID)).thenReturn(Optional.empty());

    ApiResponse<PopupClaim> started = controller.startQueue(PRINCIPAL, SURFACE);
    ApiResponse<PopupClaim> exhausted = controller.next(PRINCIPAL, SESSION_ID, SURFACE);

    assertThat(started.success()).isTrue();
    assertThat(started.data()).isSameAs(claim);
    assertThat(exhausted.success()).isTrue();
    assertThat(exhausted.code()).isEqualTo("OK");
    assertThat(exhausted.data()).isNull();
    verify(claimService).claimNextPopup(USER_ID, SURFACE, null);
    verify(claimService).claimNextPopup(USER_ID, SURFACE, SESSION_ID);
  }

  @Test
  void outcomeRoutesMapToTheExistingServiceAndReturnItsIdempotentResult() {
    PopupClaimService claimService = org.mockito.Mockito.mock(PopupClaimService.class);
    PopupOutcomeService outcomeService = org.mockito.Mockito.mock(PopupOutcomeService.class);
    UserPopupController controller = new UserPopupController(claimService, outcomeService);
    PopupOutcomeResult shown = result(
        PopupDeliveryStatus.SHOWN, PopupQueuePolicy.OutcomeDecision.KEEP_CURRENT);
    PopupOutcomeResult closed = result(PopupDeliveryStatus.CLOSED, PopupQueuePolicy.OutcomeDecision.CONTINUE);
    PopupOutcomeResult optedOut = result(PopupDeliveryStatus.CLOSED, PopupQueuePolicy.OutcomeDecision.CONTINUE);
    PopupOutcomeResult clicked = result(PopupDeliveryStatus.CLICKED, PopupQueuePolicy.OutcomeDecision.TERMINATE);
    when(outcomeService.recordPopupOutcome(USER_ID, TOKEN, PopupOutcome.SHOWN)).thenReturn(shown);
    when(outcomeService.recordPopupOutcome(USER_ID, TOKEN, PopupOutcome.CLOSE)).thenReturn(closed);
    when(outcomeService.recordPopupOutcome(USER_ID, TOKEN, PopupOutcome.OPT_OUT)).thenReturn(optedOut);
    when(outcomeService.recordPopupOutcome(USER_ID, TOKEN, PopupOutcome.CTA_CLICK)).thenReturn(clicked);

    assertThat(controller.shown(PRINCIPAL, TOKEN).data()).isSameAs(shown);
    assertThat(controller.close(PRINCIPAL, TOKEN).data()).isSameAs(closed);
    assertThat(controller.optOut(PRINCIPAL, TOKEN).data()).isSameAs(optedOut);
    assertThat(controller.click(PRINCIPAL, TOKEN).data()).isSameAs(clicked);
    // Exact replay remains observable to the client instead of being replaced by an empty 200.
    assertThat(controller.close(PRINCIPAL, TOKEN).data()).isSameAs(closed);

    verify(outcomeService).recordPopupOutcome(USER_ID, TOKEN, PopupOutcome.SHOWN);
    verify(outcomeService, times(2)).recordPopupOutcome(USER_ID, TOKEN, PopupOutcome.CLOSE);
    verify(outcomeService).recordPopupOutcome(USER_ID, TOKEN, PopupOutcome.OPT_OUT);
    verify(outcomeService).recordPopupOutcome(USER_ID, TOKEN, PopupOutcome.CTA_CLICK);
  }

  private static Method method(String name, Class<?>... parameterTypes) {
    try {
      return UserPopupController.class.getMethod(name, parameterTypes);
    } catch (NoSuchMethodException exception) {
      throw new AssertionError(exception);
    }
  }

  private static PopupClaim claim() {
    return new PopupClaim(
        SESSION_ID,
        DELIVERY_ID,
        CAMPAIGN_ID,
        REVISION_ID,
        TOKEN,
        Instant.parse("2026-07-20T12:05:00Z"),
        TemplateSize.MEDIUM,
        "Pinned campaign title",
        "<p>Pinned safe body</p>",
        COVER_ASSET_ID,
        "Open dashboard",
        "DASHBOARD",
        "{\"tab\":\"overview\"}");
  }

  private static PopupOutcomeResult result(
      PopupDeliveryStatus status,
      PopupQueuePolicy.OutcomeDecision directive) {
    return new PopupOutcomeResult(true, status, directive);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @EnableWebSecurity
  @EnableMethodSecurity
  static class MethodSecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
      http.csrf(csrf -> csrf.disable())
          .exceptionHandling(exception -> exception.authenticationEntryPoint(
              (request, response, error) -> response.sendError(401)))
          .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());
      return http.build();
    }

    @Bean
    PopupClaimService popupClaimService() {
      return mock(PopupClaimService.class);
    }

    @Bean
    PopupOutcomeService popupOutcomeService() {
      return mock(PopupOutcomeService.class);
    }

    @Bean
    UserPopupController userPopupController(
        PopupClaimService claimService,
        PopupOutcomeService outcomeService) {
      return new UserPopupController(claimService, outcomeService);
    }

    @Bean
    GlobalExceptionHandler globalExceptionHandler() {
      return new GlobalExceptionHandler();
    }
  }
}
