package com.fxplatform.validation.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.security.SecurityErrorResponseWriter;
import com.fxplatform.validation.controller.ValidationResetController;
import com.fxplatform.validation.service.ValidationLoopbackRequestActivityBarrier;
import jakarta.servlet.FilterChain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class ValidationInternalAuthenticationTest {

  private static final String HEADER = "X-Validation-Internal-Token";
  private static final String CONFIGURED_SECRET =
      "validation-internal-test-secret-0123456789";
  private static final String WRONG_SECRET =
      "wrong-validation-internal-secret-987654321";

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void validationSecurityAndResetControllerAreAbsentOutsideValidationProfile() {
    for (String profile : new String[] {"dev", "test"}) {
      new ApplicationContextRunner()
          .withPropertyValues("spring.profiles.active=" + profile)
          .withUserConfiguration(
              ValidationInternalSecurityConfiguration.class,
              ValidationResetController.class)
          .run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ValidationInternalSecurityConfiguration.class);
            assertThat(context).doesNotHaveBean(ValidationResetController.class);
            assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
          });
    }
  }

  @Test
  void validationTypesAndResetMappingCarryTheExactValidationProfile() {
    assertValidationProfile(ValidationInternalSecurityConfiguration.class);
    assertValidationProfile(ValidationResetController.class);
    assertThat(ValidationInternalAuthenticationFilter.class.getAnnotation(Component.class))
        .as("the internal filter must be created only by the validation security configuration")
        .isNull();

    RequestMapping root = ValidationResetController.class.getAnnotation(RequestMapping.class);
    assertThat(root).isNotNull();
    assertThat(root.value()).containsExactly("/internal/validation");
    assertThat(Arrays.stream(ValidationResetController.class.getDeclaredMethods()))
        .anySatisfy(method -> {
          PostMapping mapping = method.getAnnotation(PostMapping.class);
          assertThat(mapping).isNotNull();
          assertThat(mapping.value()).contains("/reset");
        });
  }

  @Test
  void missingInternalTokenReturnsGenericJsonEvenWhenBearerJwtIsPresent() throws Exception {
    MockHttpServletRequest request = internalRequest();
    request.addHeader("Authorization", "Bearer otherwise-valid-user-jwt");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter().doFilter(request, response, chain);

    assertUnauthorized(response, CONFIGURED_SECRET, "otherwise-valid-user-jwt");
    assertThat(chain.getRequest()).isNull();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void internalAuthenticationPrecedesTheClosedResetActivityFence() throws Exception {
    ValidationLoopbackRequestActivityBarrier barrier =
        new ValidationLoopbackRequestActivityBarrier();
    barrier.quiesceAndAwait();
    ValidationLoopbackRequestActivityFilter activityFilter =
        new ValidationLoopbackRequestActivityFilter(barrier);
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/internal/validation/runs");
    request.setRemoteAddr("127.0.0.1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicInteger handlerCalls = new AtomicInteger();

    filter().doFilter(
        request,
        response,
        (authenticatedRequest, authenticatedResponse) -> activityFilter.doFilter(
            authenticatedRequest,
            authenticatedResponse,
            (ignoredRequest, ignoredResponse) -> handlerCalls.incrementAndGet()));

    assertUnauthorized(response, CONFIGURED_SECRET);
    assertThat(response.getContentAsString()).doesNotContain("VALIDATION_LOOPBACK_QUIESCING");
    assertThat(handlerCalls).hasValue(0);
  }

  @Test
  void wrongInternalTokenReturnsTheSameJsonWithoutEchoingEitherSecret() throws Exception {
    MockHttpServletRequest request = internalRequest();
    request.addHeader(HEADER, WRONG_SECRET);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter().doFilter(request, response, chain);

    assertUnauthorized(response, CONFIGURED_SECRET, WRONG_SECRET);
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void duplicateInternalTokenHeadersAreRejectedEvenWhenBothValuesAreCorrect() throws Exception {
    MockHttpServletRequest request = internalRequest();
    request.addHeader(HEADER, CONFIGURED_SECRET);
    request.addHeader(HEADER, CONFIGURED_SECRET);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter().doFilter(request, response, chain);

    assertUnauthorized(response, CONFIGURED_SECRET);
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void correctTokenFromANonLoopbackPeerIsRejectedAndForwardedForCannotOverridePeer() throws Exception {
    MockHttpServletRequest request = internalRequest();
    request.setRemoteAddr("172.19.0.42");
    request.addHeader("X-Forwarded-For", "127.0.0.1");
    request.addHeader(HEADER, CONFIGURED_SECRET);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter().doFilter(request, response, chain);

    assertUnauthorized(response, CONFIGURED_SECRET);
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void correctLoopbackTokenCreatesOnlyTheDedicatedInternalAuthority() throws Exception {
    MockHttpServletRequest request = internalRequest();
    request.addHeader(HEADER, CONFIGURED_SECRET);
    AtomicReference<Authentication> observed = new AtomicReference<>();
    FilterChain chain = (ignoredRequest, ignoredResponse) ->
        observed.set(SecurityContextHolder.getContext().getAuthentication());

    filter().doFilter(request, new MockHttpServletResponse(), chain);

    Authentication authentication = observed.get();
    assertThat(authentication).isNotNull();
    assertThat(authentication.isAuthenticated()).isTrue();
    assertThat(authentication.getCredentials()).isNull();
    assertThat(authentication.getAuthorities())
        .extracting("authority")
        .containsExactly("VALIDATION_INTERNAL");
    assertThat(authentication.getPrincipal().toString()).isEqualTo("VALIDATION_INTERNAL");
  }

  @Test
  void correctInternalTokenIsIndependentOfAnInvalidBearerValue() throws Exception {
    MockHttpServletRequest request = internalRequest();
    request.addHeader(HEADER, CONFIGURED_SECRET);
    request.addHeader("Authorization", "Bearer deliberately-invalid-and-unparseable");
    MockFilterChain chain = new MockFilterChain();

    filter().doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isSameAs(request);
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    assertThat(authentication).isNotNull();
    assertThat(authentication.getAuthorities())
        .extracting("authority")
        .containsExactly("VALIDATION_INTERNAL");
  }

  @Test
  void filterDoesNotAuthorizePublicOrPrefixLookalikePaths() throws Exception {
    for (String path : new String[] {"/api/auth/register", "/internal/validation-evil/reset"}) {
      SecurityContextHolder.clearContext();
      MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
      request.setRemoteAddr("127.0.0.1");
      request.addHeader(HEADER, CONFIGURED_SECRET);
      MockFilterChain chain = new MockFilterChain();

      filter().doFilter(request, new MockHttpServletResponse(), chain);

      assertThat(chain.getRequest()).isSameAs(request);
      assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
  }

  @Test
  void productionConfigurationUsesAProfileOnlyHigherPriorityChainWithoutPermitAll()
      throws Exception {
    String internalSecurity = Files.readString(Path.of(
        "src/main/java/com/fxplatform/validation/security/ValidationInternalSecurityConfiguration.java"));
    String defaultSecurity = Files.readString(Path.of(
        "src/main/java/com/fxplatform/common/security/SecurityConfig.java"));
    String jwtFilter = Files.readString(Path.of(
        "src/main/java/com/fxplatform/common/security/JwtAuthenticationFilter.java"));

    assertThat(internalSecurity)
        .contains("@Profile(\"validation\")")
        .contains("@Order(1)")
        .contains("securityMatcher(\"/internal/validation/**\")")
        .contains("hasAuthority(ValidationInternalAuthenticationFilter.AUTHORITY)")
        .doesNotContain("permitAll()")
        .doesNotContain("JwtAuthenticationFilter");
    assertThat(defaultSecurity).contains("@Order(2)");

    boolean jwtServletRegistrationDisabled =
        defaultSecurity.contains("FilterRegistrationBean<JwtAuthenticationFilter>")
            && defaultSecurity.contains("setEnabled(false)");
    boolean jwtExplicitlySkipsInternalRequests =
        jwtFilter.contains("shouldNotFilter")
            && jwtFilter.contains("/internal/validation");
    assertThat(jwtServletRegistrationDisabled || jwtExplicitlySkipsInternalRequests)
        .as("JWT authentication must not run outside the dedicated internal chain")
        .isTrue();
  }

  @Test
  void requestActivityFenceIsManagedInsideBothSecurityChains() throws Exception {
    String internalSecurity = Files.readString(Path.of(
        "src/main/java/com/fxplatform/validation/security/ValidationInternalSecurityConfiguration.java"));
    String defaultSecurity = Files.readString(Path.of(
        "src/main/java/com/fxplatform/common/security/SecurityConfig.java"));

    assertThat(internalSecurity)
        .contains("FilterRegistrationBean<ValidationLoopbackRequestActivityFilter>")
        .contains("addFilterAfter(")
        .contains("ValidationInternalAuthenticationFilter.class");
    assertThat(defaultSecurity)
        .contains("addFilterBefore(")
        .contains("JwtAuthenticationFilter.class")
        .contains("ValidationLoopbackRequestActivityFilter");
  }

  @Test
  void tokenComparisonUsesSha256ConstantTimeDigestAndDoesNotDeclareALogger() throws Exception {
    String source = Files.readString(Path.of(
        "src/main/java/com/fxplatform/validation/security/ValidationInternalAuthenticationFilter.java"));

    assertThat(source)
        .contains("SHA-256")
        .contains("MessageDigest.isEqual")
        .contains("getHeaders")
        .doesNotContain("Logger", "@Slf4j", "log.");
  }

  private static ValidationInternalAuthenticationFilter filter() {
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    return new ValidationInternalAuthenticationFilter(
        CONFIGURED_SECRET,
        new SecurityErrorResponseWriter(objectMapper));
  }

  private static MockHttpServletRequest internalRequest() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/internal/validation/reset");
    request.setRemoteAddr("127.0.0.1");
    return request;
  }

  private static void assertUnauthorized(
      MockHttpServletResponse response,
      String... forbiddenValues
  ) throws Exception {
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    assertThat(response.getContentAsString())
        .contains("\"success\":false")
        .contains("\"code\":\"VALIDATION_INTERNAL_UNAUTHORIZED\"");
    for (String value : forbiddenValues) {
      assertThat(response.getContentAsString()).doesNotContain(value);
    }
  }

  private static void assertValidationProfile(Class<?> type) {
    Profile profile = type.getAnnotation(Profile.class);
    assertThat(profile).as(type.getName() + " profile").isNotNull();
    assertThat(profile.value()).containsExactly("validation");
  }
}
