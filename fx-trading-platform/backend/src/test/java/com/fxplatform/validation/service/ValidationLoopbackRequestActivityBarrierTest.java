package com.fxplatform.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.validation.security.ValidationLoopbackRequestActivityFilter;
import jakarta.servlet.ServletException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ValidationLoopbackRequestActivityBarrierTest {

  private static final String ENGINE_CORRELATION =
      "018f47d2-a157-7d35-8f77-6f5d5ca1d831:QUERY_STATE:1";

  @Test
  void filterAlwaysReleasesAnEngineRequestWhenTheServerHandlerFails() throws Exception {
    ValidationLoopbackRequestActivityBarrier barrier =
        new ValidationLoopbackRequestActivityBarrier(Duration.ofSeconds(1));
    ValidationLoopbackRequestActivityFilter filter =
        new ValidationLoopbackRequestActivityFilter(barrier);
    MockHttpServletRequest request = engineRequest();

    assertThatThrownBy(() -> filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (ignoredRequest, ignoredResponse) -> {
          assertThat(barrier.activeRequests()).isEqualTo(1);
          throw new ServletException("handler failed");
        }))
        .isInstanceOf(ServletException.class)
        .hasMessage("handler failed");

    assertThat(barrier.activeRequests()).isZero();
    assertThatCode(barrier::quiesceAndAwait).doesNotThrowAnyException();
  }

  @Test
  void timedOutDrainStaysClosedAndPreventsTheDatabaseResetFromStarting() {
    ValidationLoopbackRequestActivityBarrier barrier =
        new ValidationLoopbackRequestActivityBarrier(Duration.ofMillis(25));
    ValidationLoopbackRequestActivityBarrier.Activity active =
        barrier.tryEnter().orElseThrow();
    ValidationAdministrativeDatabase database = mock(ValidationAdministrativeDatabase.class);
    DefaultValidationRunPacer runPacer = mock(DefaultValidationRunPacer.class);
    DefaultValidationResetOperations operations = new DefaultValidationResetOperations(
        database,
        mock(ValidationRedisResetter.class),
        mock(ValidationMarketState.class),
        mock(ValidationMarketClock.class),
        mock(ValidationDemoExecutionPolicyProvider.class),
        runPacer,
        barrier,
        mock(ValidationRunOrchestrator.class),
        List.of());

    try {
      assertThatThrownBy(() ->
          operations.markResetting("fx_validation_lab", Instant.parse("2026-07-23T00:00:00Z")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("did not become idle");

      verify(runPacer).quiesceAndJoin();
      verify(database, never()).markResetting(
          "fx_validation_lab",
          Instant.parse("2026-07-23T00:00:00Z"));
      assertThat(barrier.tryEnter()).isEmpty();
    } finally {
      active.close();
    }
  }

  @Test
  void slowDurableEntryCannotHoldTheBarrierMonitorOrCrossQuiesce() throws Exception {
    ValidationDatabaseFence durableFence = mock(ValidationDatabaseFence.class);
    ValidationResetGate gate = mock(ValidationResetGate.class);
    ValidationDatabaseFence.RequestFence requestFence =
        mock(ValidationDatabaseFence.RequestFence.class);
    CountDownLatch acquisitionStarted = new CountDownLatch(1);
    CountDownLatch releaseAcquisition = new CountDownLatch(1);
    when(gate.readyGeneration()).thenReturn(19L);
    when(durableFence.tryEnterRequest(19L)).thenAnswer(ignored -> {
      acquisitionStarted.countDown();
      releaseAcquisition.await(2L, TimeUnit.SECONDS);
      return Optional.of(requestFence);
    });
    ValidationLoopbackRequestActivityBarrier barrier =
        new ValidationLoopbackRequestActivityBarrier(durableFence, gate);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<Optional<ValidationLoopbackRequestActivityBarrier.Activity>> pending =
          executor.submit(() -> {
            return barrier.tryEnter();
          });
      assertThat(acquisitionStarted.await(1L, TimeUnit.SECONDS)).isTrue();
      Future<?> quiesced = executor.submit(barrier::quiesceAndAwait);

      quiesced.get(500L, TimeUnit.MILLISECONDS);
      releaseAcquisition.countDown();

      assertThat(pending.get(1L, TimeUnit.SECONDS)).isEmpty();
      verify(requestFence).close();
      assertThat(barrier.activeRequests()).isZero();
    } finally {
      releaseAcquisition.countDown();
    }
  }

  @Test
  void closedBarrierRejectsNewEngineRequestsWithoutCallingTheHandler() throws Exception {
    ValidationLoopbackRequestActivityBarrier barrier =
        new ValidationLoopbackRequestActivityBarrier(Duration.ofSeconds(1));
    ValidationLoopbackRequestActivityFilter filter =
        new ValidationLoopbackRequestActivityFilter(barrier);
    AtomicInteger handlerCalls = new AtomicInteger();
    barrier.quiesceAndAwait();
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(
        engineRequest(),
        response,
        (ignoredRequest, ignoredResponse) -> handlerCalls.incrementAndGet());

    assertThat(handlerCalls).hasValue(0);
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString())
        .contains("VALIDATION_LOOPBACK_QUIESCING")
        .doesNotContain(ENGINE_CORRELATION);
  }

  @Test
  void closedBarrierRejectsOrdinaryPublicApiRequestsFromANonLoopbackPeer() throws Exception {
    ValidationLoopbackRequestActivityBarrier barrier =
        new ValidationLoopbackRequestActivityBarrier(Duration.ofSeconds(1));
    ValidationLoopbackRequestActivityFilter filter =
        new ValidationLoopbackRequestActivityFilter(barrier);
    AtomicInteger handlerCalls = new AtomicInteger();
    barrier.quiesceAndAwait();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/register");
    request.setRemoteAddr("172.18.0.1");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(
        request,
        response,
        (ignoredRequest, ignoredResponse) -> handlerCalls.incrementAndGet());

    assertThat(handlerCalls).hasValue(0);
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString()).contains("VALIDATION_LOOPBACK_QUIESCING");
  }

  @Test
  void openBarrierStillRejectsPublicApiRequestsWithoutAnEngineEnvelope() throws Exception {
    ValidationLoopbackRequestActivityBarrier barrier =
        new ValidationLoopbackRequestActivityBarrier(Duration.ofSeconds(1));
    ValidationLoopbackRequestActivityFilter filter =
        new ValidationLoopbackRequestActivityFilter(barrier);
    AtomicInteger handlerCalls = new AtomicInteger();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/register");
    request.setRemoteAddr("127.0.0.1");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(
        request,
        response,
        (ignoredRequest, ignoredResponse) -> handlerCalls.incrementAndGet());

    assertThat(handlerCalls).hasValue(0);
    assertThat(response.getStatus()).isEqualTo(503);
  }

  @Test
  void resetRequestDoesNotJoinItsOwnDrainBarrier() throws Exception {
    ValidationLoopbackRequestActivityBarrier barrier =
        new ValidationLoopbackRequestActivityBarrier(Duration.ofSeconds(1));
    ValidationLoopbackRequestActivityFilter filter =
        new ValidationLoopbackRequestActivityFilter(barrier);
    AtomicInteger handlerCalls = new AtomicInteger();
    barrier.quiesceAndAwait();
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/internal/validation/reset");
    request.setRemoteAddr("172.18.0.1");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(
        request,
        response,
        (ignoredRequest, ignoredResponse) -> handlerCalls.incrementAndGet());

    assertThat(handlerCalls).hasValue(1);
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void stateAndActuatorReadsAreTheOnlyAdditionalResetSafeExemptions() throws Exception {
    ValidationLoopbackRequestActivityBarrier barrier =
        new ValidationLoopbackRequestActivityBarrier(Duration.ofSeconds(1));
    ValidationLoopbackRequestActivityFilter filter =
        new ValidationLoopbackRequestActivityFilter(barrier);
    barrier.quiesceAndAwait();

    for (String[] allowed : List.of(
        new String[] {"GET", "/internal/validation/state"},
        new String[] {"GET", "/actuator/health"},
        new String[] {"HEAD", "/actuator/health"})) {
      AtomicInteger calls = new AtomicInteger();
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilter(
          new MockHttpServletRequest(allowed[0], allowed[1]),
          response,
          (ignoredRequest, ignoredResponse) -> calls.incrementAndGet());
      assertThat(calls).as(allowed[0] + " " + allowed[1]).hasValue(1);
      assertThat(response.getStatus()).as(allowed[0] + " " + allowed[1]).isEqualTo(200);
    }

    for (String[] fenced : List.of(
        new String[] {"GET", "/internal/validation/reset"},
        new String[] {"POST", "/internal/validation/state"},
        new String[] {"GET", "/internal/validation/state/extra"},
        new String[] {"GET", "/actuator/healthcheck"},
        new String[] {"POST", "/actuator/health"},
        new String[] {"GET", "/actuator/info"})) {
      AtomicInteger calls = new AtomicInteger();
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilter(
          new MockHttpServletRequest(fenced[0], fenced[1]),
          response,
          (ignoredRequest, ignoredResponse) -> calls.incrementAndGet());
      assertThat(calls).as(fenced[0] + " " + fenced[1]).hasValue(0);
      assertThat(response.getStatus()).as(fenced[0] + " " + fenced[1]).isEqualTo(503);
    }
  }

  @Test
  void ordinaryControlRequestHoldsActivityUntilItsHandlerReturns() throws Exception {
    ValidationLoopbackRequestActivityBarrier barrier =
        new ValidationLoopbackRequestActivityBarrier(Duration.ofSeconds(1));
    ValidationLoopbackRequestActivityFilter filter =
        new ValidationLoopbackRequestActivityFilter(barrier);
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/internal/validation/runs");

    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (ignoredRequest, ignoredResponse) -> assertThat(barrier.activeRequests()).isEqualTo(1));

    assertThat(barrier.activeRequests()).isZero();
  }

  @Test
  void canonicalEngineRequestWithoutGenerationIsRejected() throws Exception {
    ValidationLoopbackRequestActivityBarrier barrier =
        new ValidationLoopbackRequestActivityBarrier(Duration.ofSeconds(1));
    ValidationLoopbackRequestActivityFilter filter =
        new ValidationLoopbackRequestActivityFilter(barrier);
    AtomicInteger handlerCalls = new AtomicInteger();
    MockHttpServletRequest request = engineRequest();
    request.removeHeader("X-Validation-Generation");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(
        request,
        response,
        (ignoredRequest, ignoredResponse) -> handlerCalls.incrementAndGet());

    assertThat(handlerCalls).hasValue(0);
    assertThat(response.getStatus()).isEqualTo(503);
  }

  @Test
  void queuedOldGenerationEngineRequestCannotEnterAfterResetReopens() throws Exception {
    ValidationDatabaseFence durableFence = mock(ValidationDatabaseFence.class);
    ValidationResetGate gate = mock(ValidationResetGate.class);
    ValidationDatabaseFence.RequestFence currentFence =
        mock(ValidationDatabaseFence.RequestFence.class);
    when(gate.readyGeneration()).thenReturn(67L);
    when(durableFence.tryEnterRequest(67L)).thenReturn(Optional.of(currentFence));
    ValidationLoopbackRequestActivityBarrier barrier =
        new ValidationLoopbackRequestActivityBarrier(durableFence, gate);
    ValidationLoopbackRequestActivityFilter filter =
        new ValidationLoopbackRequestActivityFilter(barrier);
    AtomicInteger handlerCalls = new AtomicInteger();
    MockHttpServletRequest request = engineRequest();
    request.removeHeader(ValidationLoopbackRequestActivityFilter.GENERATION_HEADER);
    request.addHeader(ValidationLoopbackRequestActivityFilter.GENERATION_HEADER, "66");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(
        request,
        response,
        (ignoredRequest, ignoredResponse) -> handlerCalls.incrementAndGet());

    assertThat(handlerCalls).hasValue(0);
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString()).doesNotContain("66");
    verify(durableFence, never()).tryEnterRequest(67L);
  }

  @Test
  void duplicateMalformedOrPartialEngineEnvelopesFailClosed() throws Exception {
    List<MockHttpServletRequest> requests = new ArrayList<>();
    requests.add(publicRequestWithHeaders(List.of(ENGINE_CORRELATION), List.of()));
    requests.add(publicRequestWithHeaders(List.of(), List.of("66")));
    requests.add(publicRequestWithHeaders(
        List.of(ENGINE_CORRELATION, ENGINE_CORRELATION),
        List.of("66")));
    requests.add(publicRequestWithHeaders(
        List.of(ENGINE_CORRELATION),
        List.of("66", "66")));
    for (String generation : List.of("066", "+66", "0", "-1", "not-a-number")) {
      requests.add(publicRequestWithHeaders(List.of(ENGINE_CORRELATION), List.of(generation)));
    }
    for (String correlation : List.of(
        "018f47d2-a157-7d35-8f77-6f5d5ca1d831:QUERY_STATE:01",
        "018F47D2-A157-7D35-8F77-6F5D5CA1D831:QUERY_STATE:1",
        "018f47d2-a157-7d35-8f77-6f5d5ca1d831:query_state:1",
        "not-a-correlation")) {
      requests.add(publicRequestWithHeaders(List.of(correlation), List.of("66")));
    }

    ValidationLoopbackRequestActivityBarrier barrier =
        new ValidationLoopbackRequestActivityBarrier(Duration.ofSeconds(1));
    ValidationLoopbackRequestActivityFilter filter =
        new ValidationLoopbackRequestActivityFilter(barrier);
    for (MockHttpServletRequest request : requests) {
      AtomicInteger calls = new AtomicInteger();
      MockHttpServletResponse response = new MockHttpServletResponse();

      filter.doFilter(
          request,
          response,
          (ignoredRequest, ignoredResponse) -> calls.incrementAndGet());

      assertThat(calls).hasValue(0);
      assertThat(response.getStatus()).isEqualTo(503);
      assertThat(response.getContentAsString()).doesNotContain(ENGINE_CORRELATION);
    }
  }

  @Test
  void declaredGenerationMustMatchBothTheLocalGateAndDurableFence() {
    ValidationDatabaseFence durableFence = mock(ValidationDatabaseFence.class);
    ValidationResetGate gate = mock(ValidationResetGate.class);
    ValidationDatabaseFence.RequestFence requestFence =
        mock(ValidationDatabaseFence.RequestFence.class);
    when(gate.readyGeneration()).thenReturn(67L);
    when(durableFence.tryEnterRequest(67L)).thenReturn(Optional.of(requestFence));
    ValidationLoopbackRequestActivityBarrier barrier =
        new ValidationLoopbackRequestActivityBarrier(durableFence, gate);

    assertThat(barrier.tryEnter(66L)).isEmpty();
    ValidationLoopbackRequestActivityBarrier.Activity entered =
        barrier.tryEnter(67L).orElseThrow();
    entered.close();

    verify(durableFence).tryEnterRequest(67L);
  }

  private static MockHttpServletRequest engineRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts");
    request.setRemoteAddr("127.0.0.1");
    request.addHeader(
        ValidationLoopbackRequestActivityFilter.CORRELATION_HEADER,
        ENGINE_CORRELATION);
    request.addHeader(ValidationLoopbackRequestActivityFilter.GENERATION_HEADER, "66");
    return request;
  }

  private static MockHttpServletRequest publicRequestWithHeaders(
      List<String> correlations,
      List<String> generations
  ) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/register");
    correlations.forEach(value -> request.addHeader(
        ValidationLoopbackRequestActivityFilter.CORRELATION_HEADER,
        value));
    generations.forEach(value -> request.addHeader(
        ValidationLoopbackRequestActivityFilter.GENERATION_HEADER,
        value));
    return request;
  }
}
