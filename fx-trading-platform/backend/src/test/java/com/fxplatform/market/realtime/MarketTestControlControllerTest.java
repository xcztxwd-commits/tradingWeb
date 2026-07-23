package com.fxplatform.market.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.admin.service.AdminAuthorityService;
import com.fxplatform.auth.repository.UserRepository;
import com.fxplatform.auth.service.AuthSessionService;
import com.fxplatform.common.security.JwtAuthenticationFilter;
import com.fxplatform.common.security.JwtService;
import com.fxplatform.common.security.SecurityConfig;
import com.fxplatform.common.security.SecurityErrorResponseWriter;
import com.fxplatform.common.security.TokenRevocationService;
import com.fxplatform.market.dto.QuoteResponse;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class MarketTestControlControllerTest {

  private static final String OVERRIDES_PATH = "/api/admin/market/test-control/overrides";
  private static final String OVERRIDE_JSON = """
      {"symbol":"BTCUSDT","bid":100,"ask":102,"ttl":"PT1M"}
      """;

  @Test
  void exposesAdminOnlyTestControlRoutes() throws NoSuchMethodException {
    RequestMapping requestMapping = MarketTestControlController.class.getAnnotation(RequestMapping.class);
    PreAuthorize preAuthorize = MarketTestControlController.class.getAnnotation(PreAuthorize.class);
    Profile profile = MarketTestControlController.class.getAnnotation(Profile.class);
    ConditionalOnProperty conditional =
        MarketTestControlController.class.getAnnotation(ConditionalOnProperty.class);
    Method override = MarketTestControlController.class.getMethod("override", MarketTestControlRequest.class);
    Method endOverride = MarketTestControlController.class.getMethod("endOverride", String.class);

    assertThat(requestMapping.value()).containsExactly("/api/admin/market/test-control");
    assertThat(preAuthorize.value()).isEqualTo("hasRole('ADMIN')");
    assertThat(profile.value()).containsExactly("(dev | test) & !prod");
    assertThat(conditional.prefix()).isEqualTo("market.test-control");
    assertThat(conditional.name()).containsExactly("enabled");
    assertThat(conditional.havingValue()).isEqualTo("true");
    assertThat(conditional.matchIfMissing()).isFalse();
    assertThat(override.getAnnotation(PostMapping.class).value()).containsExactly("/overrides");
    assertThat(endOverride.getAnnotation(DeleteMapping.class).value()).containsExactly("/overrides/{symbol}");
  }

  @Test
  void delegatesOverrideAndEndOverrideToService() {
    MarketTestControlService service = Mockito.mock(MarketTestControlService.class);
    RealtimeQuoteSink sink = Mockito.mock(RealtimeQuoteSink.class);
    MarketTestControlController controller = new MarketTestControlController(service, sink);
    MarketTestControlRequest request = new MarketTestControlRequest(
        "BTCUSDT",
        new BigDecimal("100.00"),
        new BigDecimal("102.00"),
        Duration.ofMinutes(1));
    QuoteResponse response = new QuoteResponse(
        "quote",
        "BTCUSDT",
        new BigDecimal("100.00"),
        new BigDecimal("102.00"),
        new BigDecimal("101.0000000000"),
        new BigDecimal("2.00"),
        "test-control",
        1780000000000L);
    when(service.startOverride(request)).thenReturn(response);

    assertThat(controller.override(request).data()).isSameAs(response);
    assertThat(controller.endOverride("btc-usdt").success()).isTrue();

    verify(service).startOverride(request);
    verify(sink).acceptTestControl(response);
    verify(service).endOverride("btc-usdt");
  }

  @Test
  void enabledDevMappingEnforcesRealHttpSecurity() throws Exception {
    try (AnnotationConfigWebApplicationContext context = webContext(true, "dev")) {
      MockMvc mockMvc = mockMvc(context);

      mockMvc.perform(post(OVERRIDES_PATH)
              .contentType(MediaType.APPLICATION_JSON)
              .content(OVERRIDE_JSON))
          .andExpect(status().isUnauthorized());
      mockMvc.perform(post(OVERRIDES_PATH)
              .with(user("user").roles("USER"))
              .contentType(MediaType.APPLICATION_JSON)
              .content(OVERRIDE_JSON))
          .andExpect(status().isForbidden());
      mockMvc.perform(post(OVERRIDES_PATH)
              .with(user("admin").roles("ADMIN"))
              .contentType(MediaType.APPLICATION_JSON)
              .content(OVERRIDE_JSON))
          .andExpect(status().isOk());
    }
  }

  @Test
  void enabledTestProfileExposesMapping() throws Exception {
    try (AnnotationConfigWebApplicationContext context = webContext(true, "test")) {
      mockMvc(context).perform(post(OVERRIDES_PATH)
              .with(user("admin").roles("ADMIN"))
              .contentType(MediaType.APPLICATION_JSON)
              .content(OVERRIDE_JSON))
          .andExpect(status().isOk());
    }
  }

  @Test
  void disabledAndProductionProfilesDoNotExposeMapping() throws Exception {
    assertNoMapping(false, "dev");
    assertNoMapping(true, "prod");
    assertNoMapping(true, "prod", "dev");
  }

  private void assertNoMapping(boolean enabled, String... profiles) throws Exception {
    try (AnnotationConfigWebApplicationContext context = webContext(enabled, profiles)) {
      mockMvc(context).perform(post(OVERRIDES_PATH)
              .with(user("admin").roles("ADMIN"))
              .contentType(MediaType.APPLICATION_JSON)
              .content(OVERRIDE_JSON))
          .andExpect(status().isNotFound());
    }
  }

  private AnnotationConfigWebApplicationContext webContext(
      boolean enabled,
      String... profiles
  ) {
    AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
    context.setServletContext(new MockServletContext());
    context.getEnvironment().setActiveProfiles(profiles);
    TestPropertyValues.of("market.test-control.enabled=" + enabled).applyTo(context);
    context.register(TestWebConfiguration.class);
    context.refresh();
    return context;
  }

  private MockMvc mockMvc(AnnotationConfigWebApplicationContext context) {
    return MockMvcBuilders.webAppContextSetup(context)
        .apply(springSecurity())
        .build();
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @Import({MarketTestControlController.class, SecurityConfig.class})
  static class TestWebConfiguration {

    @Bean
    MarketTestControlService marketTestControlService() {
      return Mockito.mock(MarketTestControlService.class);
    }

    @Bean
    RealtimeQuoteSink realtimeQuoteSink() {
      return Mockito.mock(RealtimeQuoteSink.class);
    }

    @Bean
    UserRepository userRepository() {
      return Mockito.mock(UserRepository.class);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    SecurityErrorResponseWriter securityErrorResponseWriter(ObjectMapper objectMapper) {
      return new SecurityErrorResponseWriter(objectMapper);
    }

    @Bean
    JwtAuthenticationFilter jwtAuthenticationFilter(UserRepository userRepository) {
      return new JwtAuthenticationFilter(
          Mockito.mock(JwtService.class),
          userRepository,
          Mockito.mock(TokenRevocationService.class),
          Mockito.mock(AuthSessionService.class),
          Mockito.mock(AdminAuthorityService.class));
    }
  }
}
