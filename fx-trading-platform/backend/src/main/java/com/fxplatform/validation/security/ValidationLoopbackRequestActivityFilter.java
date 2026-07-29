package com.fxplatform.validation.security;

import com.fxplatform.validation.service.ValidationLoopbackRequestActivityBarrier;
import com.fxplatform.validation.service.ValidationLoopbackRequestActivityBarrier.Activity;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.Operation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Fences every validation API handler that can observe or mutate reset-owned runtime state. */
@Profile("validation")
@Component
public class ValidationLoopbackRequestActivityFilter extends OncePerRequestFilter {

  public static final String CORRELATION_HEADER = "X-Validation-Correlation-Id";
  public static final String GENERATION_HEADER = "X-Validation-Generation";

  private static final String RESET_PATH = "/internal/validation/reset";
  private static final String STATE_PATH = "/internal/validation/state";
  private static final String ACTUATOR_HEALTH_PATH = "/actuator/health";
  private static final String QUIESCING_RESPONSE =
      "{\"success\":false,\"code\":\"VALIDATION_LOOPBACK_QUIESCING\","
          + "\"message\":\"Validation loopback requests are quiescing\"}";

  private final ValidationLoopbackRequestActivityBarrier barrier;

  public ValidationLoopbackRequestActivityFilter(
      ValidationLoopbackRequestActivityBarrier barrier
  ) {
    this.barrier = barrier;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {
    if (isExplicitlyExempt(request)) {
      filterChain.doFilter(request, response);
      return;
    }

    boolean hasCorrelation = request.getHeader(CORRELATION_HEADER) != null;
    boolean hasGeneration = request.getHeader(GENERATION_HEADER) != null;
    Optional<EngineEnvelope> envelope = Optional.empty();
    if (hasCorrelation || hasGeneration) {
      envelope = engineEnvelope(request);
      if (envelope.isEmpty()) {
        writeQuiescing(response);
        return;
      }
    } else if (isPublicApi(request)) {
      writeQuiescing(response);
      return;
    }

    Optional<Activity> entered = envelope.isPresent()
        ? barrier.tryEnter(envelope.orElseThrow().generation())
        : barrier.tryEnter();
    if (entered.isEmpty()) {
      writeQuiescing(response);
      return;
    }
    Activity activity = entered.orElseThrow();
    try {
      filterChain.doFilter(request, response);
    } finally {
      activity.close();
    }
  }

  private static boolean isExplicitlyExempt(HttpServletRequest request) {
    String path = request.getRequestURI();
    String method = request.getMethod();
    if (RESET_PATH.equals(path) && "POST".equals(method)) {
      return true;
    }
    if (STATE_PATH.equals(path) && "GET".equals(method)) {
      return true;
    }
    return ACTUATOR_HEALTH_PATH.equals(path)
        && ("GET".equals(method) || "HEAD".equals(method));
  }

  private static boolean isPublicApi(HttpServletRequest request) {
    String path = request.getRequestURI();
    return "/api".equals(path) || path.startsWith("/api/");
  }

  private static Optional<EngineEnvelope> engineEnvelope(HttpServletRequest request) {
    Optional<String> correlation = uniqueHeader(request, CORRELATION_HEADER);
    Optional<String> generation = uniqueHeader(request, GENERATION_HEADER);
    if (correlation.isEmpty() || generation.isEmpty()) {
      return Optional.empty();
    }
    if (!isCanonicalCorrelation(correlation.orElseThrow())) {
      return Optional.empty();
    }
    try {
      long parsedGeneration = Long.parseLong(generation.orElseThrow());
      if (parsedGeneration <= 0L
          || !Long.toString(parsedGeneration).equals(generation.orElseThrow())) {
        return Optional.empty();
      }
      return Optional.of(new EngineEnvelope(parsedGeneration));
    } catch (NumberFormatException invalid) {
      return Optional.empty();
    }
  }

  private static Optional<String> uniqueHeader(
      HttpServletRequest request,
      String headerName
  ) {
    List<String> values = Collections.list(request.getHeaders(headerName));
    return values.size() == 1 ? Optional.of(values.get(0)) : Optional.empty();
  }

  private static boolean isCanonicalCorrelation(String raw) {
    String[] parts = raw.split(":", -1);
    if (parts.length != 3) {
      return false;
    }
    try {
      UUID runId = UUID.fromString(parts[0]);
      Operation operation = Operation.valueOf(parts[1]);
      long tickSequence = Long.parseLong(parts[2]);
      return runId.toString().equals(parts[0])
          && operation.name().equals(parts[1])
          && tickSequence >= 0L
          && Long.toString(tickSequence).equals(parts[2]);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  private static void writeQuiescing(HttpServletResponse response) throws IOException {
    if (response.isCommitted()) {
      return;
    }
    response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(QUIESCING_RESPONSE);
  }

  private record EngineEnvelope(long generation) {
  }
}
