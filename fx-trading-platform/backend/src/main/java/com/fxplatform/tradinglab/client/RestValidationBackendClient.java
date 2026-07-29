package com.fxplatform.tradinglab.client;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fxplatform.tradinglab.report.SafeTradingLabHttpTrace;
import com.fxplatform.tradinglab.report.TradingLabHttpTraceInput;
import com.fxplatform.tradinglab.report.TradingLabHttpTraceSanitizer;
import com.fxplatform.tradinglab.report.TradingLabReportProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Bounded loopback-only HTTP adapter. It owns transport, strict JSON, evidence and safe failure
 * classification so callers never need to handle raw response bodies or transport throwables.
 */
@Component
@Profile("!validation")
@ConditionalOnProperty(name = "trading-lab.queue.enabled", havingValue = "true")
public final class RestValidationBackendClient implements ValidationBackendClient {

  public static final URI PRODUCTION_BASE_URI = URI.create("http://127.0.0.1:18087");

  static final String INTERNAL_TOKEN_HEADER = "X-Validation-Internal-Token";
  static final String RUN_ID_HEADER = "X-Validation-Run-Id";
  static final String RESET_OPERATION_ID_HEADER = "X-Validation-Reset-Operation-Id";
  static final String RESET_MODE_HEADER = "X-Validation-Reset-Mode";
  static final String EXPECTED_GENERATION_HEADER = "X-Validation-Expected-Generation";

  private static final String REQUEST_ID_HEADER = "X-Request-Id";
  private static final String CORRELATION_ID_HEADER = "X-Validation-Correlation-Id";
  private static final String JSON_CONTENT_TYPE = "application/json";
  private static final String ENVIRONMENT = "validation";
  private static final int MAX_JSON_DEPTH = 64;
  private static final int MAX_JSON_NUMBER_CHARS = 1_000;
  private static final int MAX_JSON_NAME_CHARS = 16_384;
  private static final int TRACE_BODY_CAPTURE_BYTES = 256 * 1024;
  private static final int MAX_HEADER_COUNT = 256;
  private static final int MAX_HEADER_VALUES = 64;
  private static final int MAX_HEADER_CHARS = 16_384;
  private static final Set<String> ENVELOPE_FIELDS =
      Set.of("success", "code", "message", "data", "timestamp");

  private final URI baseUri;
  private final String internalToken;
  private final ObjectMapper strictMapper;
  private final TradingLabHttpTraceSanitizer traceSanitizer;
  private final Clock clock;
  private final RestClient restClient;
  private final int maxRequestBytes;
  private final int maxResponseBytes;

  @Autowired
  public RestValidationBackendClient(
      @Value("${trading-lab.validation.internal-token}") String internalToken,
      ObjectMapper objectMapper,
      TradingLabHttpTraceSanitizer traceSanitizer,
      TradingLabReportProperties reportProperties
  ) {
    this(
        PRODUCTION_BASE_URI,
        internalToken,
        objectMapper,
        traceSanitizer,
        Clock.systemUTC(),
        Duration.ofSeconds(5),
        Duration.ofSeconds(20),
        reportProperties.maxLogicalValueBytes(),
        reportProperties.maxLogicalValueBytes());
  }

  RestValidationBackendClient(
      URI baseUri,
      String internalToken,
      ObjectMapper objectMapper,
      TradingLabHttpTraceSanitizer traceSanitizer,
      Clock clock,
      Duration connectTimeout,
      Duration readTimeout,
      int maxRequestBytes,
      int maxResponseBytes
  ) {
    this.baseUri = requireLoopbackBase(baseUri);
    this.internalToken = requireToken(internalToken);
    this.traceSanitizer = Objects.requireNonNull(traceSanitizer, "traceSanitizer");
    this.clock = Objects.requireNonNull(clock, "clock");
    requirePositive(connectTimeout, "connectTimeout");
    requirePositive(readTimeout, "readTimeout");
    if (maxRequestBytes < 1
        || maxRequestBytes > TradingLabReportProperties.MAX_LOGICAL_VALUE_BYTES
        || maxResponseBytes < 1
        || maxResponseBytes > TradingLabReportProperties.MAX_LOGICAL_VALUE_BYTES) {
      throw new IllegalArgumentException("Validation HTTP byte limits are invalid");
    }
    this.maxRequestBytes = maxRequestBytes;
    this.maxResponseBytes = maxResponseBytes;
    this.strictMapper = strictMapper(objectMapper, maxRequestBytes, maxResponseBytes);

    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1)
        .build();
    JdkClientHttpRequestFactory requestFactory =
        new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(readTimeout);
    this.restClient = RestClient.builder()
        .requestFactory(requestFactory)
        .build();
  }

  @Override
  public ValidationBackendExchange<ValidationResetReceipt> reset(
      ValidationResetRequest request
  ) {
    Objects.requireNonNull(request, "request");
    Map<String, List<String>> headers = baseHeaders(false);
    putSingle(headers, RUN_ID_HEADER, request.runId().toString());
    putSingle(headers, RESET_OPERATION_ID_HEADER, request.operationId().toString());
    putSingle(headers, RESET_MODE_HEADER, request.mode().name());
    putSingle(headers, EXPECTED_GENERATION_HEADER,
        Long.toString(request.expectedGeneration()));
    return exchange(
        HttpMethod.POST,
        route("/internal/validation/reset"),
        headers,
        null,
        Map.of(
            "runId", request.runId().toString(),
            "operationId", request.operationId().toString(),
            "mode", request.mode().name(),
            "expectedGeneration", request.expectedGeneration()),
        null,
        ValidationResetReceipt.class,
        data -> validateReset(data, request));
  }

  @Override
  public ValidationBackendExchange<ValidationRunAccepted> startRun(
      ValidationRunStartRequest request
  ) {
    Objects.requireNonNull(request, "request");
    URI uri = route("/internal/validation/runs");
    Map<String, List<String>> headers = baseHeaders(true);
    EncodedBody encoded;
    try {
      encoded = encode(request);
    } catch (RuntimeException failure) {
      String code = hasCause(failure, RequestLimitExceeded.class)
          ? "VALIDATION_HTTP_REQUEST_TOO_LARGE"
          : "VALIDATION_HTTP_REQUEST_INVALID";
      return localFailure(
          HttpMethod.POST,
          uri,
          headers,
          requestSummary(request, -1L, code),
          request.virtualStart(),
          code,
          "Validation start request could not be encoded");
    }
    Object traceBody = encoded.bytes().length <= TRACE_BODY_CAPTURE_BYTES
        ? encoded.text()
        : requestSummary(request, encoded.bytes().length, "BODY_OMITTED");
    return exchange(
        HttpMethod.POST,
        uri,
        headers,
        encoded,
        traceBody,
        request.virtualStart(),
        ValidationRunAccepted.class,
        data -> validateAccepted(data, request));
  }

  @Override
  public ValidationBackendExchange<ValidationRunStateObservation> state(UUID runId) {
    Objects.requireNonNull(runId, "runId");
    URI uri = URI.create(route("/internal/validation/state").toASCIIString()
        + "?runId=" + runId);
    return exchange(
        HttpMethod.GET,
        uri,
        baseHeaders(false),
        null,
        null,
        null,
        ValidationRunStateObservation.class,
        observation -> {
          if (observation.run() != null) {
            requireRunId(observation.run().runId(), runId);
          }
        });
  }

  @Override
  public ValidationBackendExchange<ValidationEventPage> eventsAfter(
      UUID runId,
      long afterSequence
  ) {
    Objects.requireNonNull(runId, "runId");
    if (afterSequence < 0L) {
      throw new IllegalArgumentException("afterSequence must be non-negative");
    }
    URI uri = URI.create(runRoute(runId, "/events").toASCIIString()
        + "?afterSequence=" + afterSequence + "&limit=1");
    return exchange(
        HttpMethod.GET,
        uri,
        baseHeaders(false),
        null,
        null,
        null,
        ValidationEventPage.class,
        page -> validateEventPage(page, runId, afterSequence));
  }

  @Override
  public ValidationBackendExchange<ValidationControlReceipt> pause(UUID runId) {
    return control(runId, "pause", "PAUSE_REQUESTED");
  }

  @Override
  public ValidationBackendExchange<ValidationControlReceipt> resume(UUID runId) {
    return control(runId, "resume", "RESUME_REQUESTED");
  }

  @Override
  public ValidationBackendExchange<ValidationControlReceipt> cancel(UUID runId) {
    return control(runId, "cancel", "CANCEL_REQUESTED");
  }

  private ValidationBackendExchange<ValidationControlReceipt> control(
      UUID runId,
      String route,
      String expectedControl
  ) {
    Objects.requireNonNull(runId, "runId");
    return exchange(
        HttpMethod.POST,
        runRoute(runId, "/" + route),
        baseHeaders(false),
        null,
        null,
        null,
        ValidationControlReceipt.class,
        receipt -> {
          requireRunId(receipt.runId(), runId);
          if (!expectedControl.equals(receipt.control())) {
            throw protocolFailure();
          }
        });
  }

  private <T> ValidationBackendExchange<T> exchange(
      HttpMethod method,
      URI uri,
      Map<String, List<String>> requestHeaders,
      EncodedBody requestBody,
      Object requestTraceBody,
      Instant virtualTime,
      Class<T> responseType,
      ResponseValidator<T> validator
  ) {
    Instant realTime = clock.instant();
    long startedNanos = System.nanoTime();
    WireResponse wire;
    try {
      RestClient.RequestBodySpec request = restClient.method(method)
          .uri(uri)
          .headers(headers -> requestHeaders.forEach(headers::put));
      if (requestBody != null) {
        request.contentType(MediaType.APPLICATION_JSON);
        request.body(requestBody.bytes());
      }
      wire = request.exchange((ignored, response) -> readResponse(response));
      if (wire == null) {
        throw new IOException("Empty validation HTTP response");
      }
    } catch (RuntimeException | IOException failure) {
      if (containsInterrupted(failure)) {
        Thread.currentThread().interrupt();
      }
      ThrowableInfo info = new ThrowableInfo(
          "TRANSPORT",
          containsTimeout(failure)
              ? "VALIDATION_HTTP_TIMEOUT"
              : "VALIDATION_HTTP_TRANSPORT_FAILURE",
          "Validation backend transport failed",
          true);
      return outcome(
          null,
          method,
          uri,
          virtualTime,
          realTime,
          elapsed(startedNanos),
          0,
          requestHeaders,
          requestBody == null ? null : JSON_CONTENT_TYPE,
          requestTraceBody,
          Map.of(),
          null,
          Map.of("bodyOmitted", true, "reason", "TRANSPORT_FAILURE"),
          requestHeaders.get(REQUEST_ID_HEADER).getFirst(),
          null,
          info);
    }

    String traceId = firstSafeHeader(wire.headers(), REQUEST_ID_HEADER);
    if (traceId == null) {
      traceId = requestHeaders.get(REQUEST_ID_HEADER).getFirst();
    }
    String correlationId = firstSafeHeader(wire.headers(), CORRELATION_ID_HEADER);
    ThrowableInfo failure = null;
    T data = null;
    Object responseTraceBody = traceResponseBody(wire);

    if (wire.tooLarge()) {
      failure = new ThrowableInfo(
          "PROTOCOL",
          "VALIDATION_HTTP_RESPONSE_TOO_LARGE",
          "Validation backend response exceeded the byte limit",
          false);
    } else if (!jsonContentType(wire.contentType())) {
      failure = wire.status() >= 200 && wire.status() < 300
          ? new ThrowableInfo(
              "PROTOCOL",
              "VALIDATION_HTTP_CONTENT_TYPE_INVALID",
              "Validation backend response content type was invalid",
              false)
          : remoteFailure(wire.status(), null);
    } else {
      Envelope envelope;
      try {
        envelope = parseEnvelope(wire.body());
      } catch (RuntimeException protocol) {
        failure = wire.status() >= 200 && wire.status() < 300
            ? protocolInfo("VALIDATION_HTTP_RESPONSE_INVALID")
            : remoteFailure(wire.status(), null);
        responseTraceBody = Map.of(
            "bodyOmitted", true,
            "byteLength", wire.body().length,
            "reason", "INVALID_JSON");
        envelope = null;
      }
      if (envelope != null) {
        if (wire.status() < 200 || wire.status() >= 300 || !envelope.success()) {
          failure = remoteFailure(wire.status(), envelope.code());
        } else {
          try {
            data = strictMapper.treeToValue(envelope.data(), responseType);
            if (data == null) {
              throw protocolFailure();
            }
            validator.validate(data);
          } catch (RuntimeException | JsonProcessingException protocol) {
            data = null;
            failure = protocolInfo("VALIDATION_HTTP_RESPONSE_INVALID");
          }
        }
      }
    }

    return outcome(
        data,
        method,
        uri,
        virtualTimeFor(virtualTime, data),
        realTime,
        elapsed(startedNanos),
        wire.status(),
        requestHeaders,
        requestBody == null ? null : JSON_CONTENT_TYPE,
        requestTraceBody,
        wire.headers(),
        wire.contentType(),
        responseTraceBody,
        traceId,
        correlationId,
        failure);
  }

  private <T> ValidationBackendExchange<T> localFailure(
      HttpMethod method,
      URI uri,
      Map<String, List<String>> headers,
      Object requestTraceBody,
      Instant virtualTime,
      String code,
      String message
  ) {
    Instant realTime = clock.instant();
    ThrowableInfo failure = new ThrowableInfo("REQUEST", code, message, false);
    return outcome(
        null,
        method,
        uri,
        virtualTime,
        realTime,
        Duration.ZERO,
        0,
        headers,
        JSON_CONTENT_TYPE,
        requestTraceBody,
        Map.of(),
        null,
        Map.of("bodyOmitted", true, "reason", "REQUEST_REJECTED"),
        headers.get(REQUEST_ID_HEADER).getFirst(),
        null,
        failure);
  }

  private <T> ValidationBackendExchange<T> outcome(
      T data,
      HttpMethod method,
      URI uri,
      Instant virtualTime,
      Instant realTime,
      Duration duration,
      int status,
      Map<String, List<String>> requestHeaders,
      String requestContentType,
      Object requestTraceBody,
      Map<String, List<String>> responseHeaders,
      String responseContentType,
      Object responseTraceBody,
      String traceId,
      String correlationId,
      ThrowableInfo failure
  ) {
    DataSnapshot dataSnapshot = snapshotData(uri, data);
    TraceCapture capture = safeTrace(
        uri,
        requestHeaders,
        responseHeaders,
        requestContentType,
        requestTraceBody,
        responseContentType,
        responseTraceBody,
        failure,
        dataSnapshot.secrets());
    SafeTradingLabHttpTrace reportTrace = capture.trace();
    Map<String, Object> safe = reportTrace.toSafeMap();
    String safeTraceId = safeEvidenceIdentifier(
        reportTrace, capture.complete() ? traceId : null, true);
    String safeCorrelationId = safeEvidenceIdentifier(
        reportTrace, capture.complete() ? correlationId : null, false);
    boolean dataSafe = capture.complete()
        && dataSnapshot.valid()
        && safeData(reportTrace, dataSnapshot.json());
    T safeData = dataSafe ? data : null;
    ThrowableInfo safeFailure;
    if (!capture.complete()) {
      safeFailure = protocolInfo("VALIDATION_HTTP_TRACE_REJECTED");
    } else if (!dataSafe) {
      safeFailure = protocolInfo("VALIDATION_HTTP_DATA_REJECTED");
    } else {
      safeFailure = failure;
    }
    safeFailure = sanitizeFailure(reportTrace, safeFailure);
    ValidationHttpResult result = new ValidationHttpResult(
        0L,
        ENVIRONMENT,
        method.name(),
        uri,
        virtualTime,
        realTime,
        status,
        duration,
        requestEvidence(safe),
        responseEvidence(safe),
        safeTraceId,
        safeCorrelationId,
        safeFailure);
    return new ValidationBackendExchange<>(safeData, result, reportTrace);
  }

  private static String safeEvidenceIdentifier(
      SafeTradingLabHttpTrace trace,
      String candidate,
      boolean required
  ) {
    if (candidate != null && !trace.containsRegisteredSecret(candidate)) {
      return candidate;
    }
    if (!required) {
      return null;
    }
    for (int attempt = 0; attempt < 4; attempt++) {
      String replacement = UUID.randomUUID().toString();
      if (!trace.containsRegisteredSecret(replacement)) {
        return replacement;
      }
    }
    throw new IllegalArgumentException("Validation HTTP trace identity is unsafe");
  }

  private boolean safeData(SafeTradingLabHttpTrace trace, byte[] dataJson) {
    if (dataJson == null) {
      return true;
    }
    try {
      trace.requireSafeJson(dataJson);
      return true;
    } catch (RuntimeException unsafeData) {
      return false;
    }
  }

  private DataSnapshot snapshotData(URI uri, Object data) {
    if (data == null) {
      return new DataSnapshot(null, null, true);
    }
    try {
      byte[] json = strictMapper.writeValueAsBytes(data);
      SafeTradingLabHttpTrace secrets = traceSanitizer.sanitize(
          new TradingLabHttpTraceInput(
              uri,
              Map.of(),
              Map.of(),
              null,
              null,
              JSON_CONTENT_TYPE,
              new String(json, StandardCharsets.UTF_8),
              null,
              null,
              List.of(),
              null));
      return new DataSnapshot(json, secrets, true);
    } catch (RuntimeException | JsonProcessingException unsafeData) {
      return new DataSnapshot(null, null, false);
    }
  }

  private static ThrowableInfo sanitizeFailure(
      SafeTradingLabHttpTrace trace,
      ThrowableInfo candidate
  ) {
    if (candidate == null || safeEvidence(trace, candidate.toSafeMap())) {
      return candidate;
    }
    for (int attempt = 0; attempt < 4; attempt++) {
      ThrowableInfo replacement = new ThrowableInfo(
          "TRACE",
          "VALIDATION_HTTP_UNSAFE_" + UUID.randomUUID().toString().replace("-", ""),
          "Validation HTTP evidence was rejected",
          false);
      if (safeEvidence(trace, replacement.toSafeMap())) {
        return replacement;
      }
    }
    throw new IllegalArgumentException("Validation HTTP failure evidence is unsafe");
  }

  private static boolean safeEvidence(
      SafeTradingLabHttpTrace trace,
      Object candidate
  ) {
    try {
      trace.requireSafeEvidence(candidate);
      return true;
    } catch (RuntimeException unsafe) {
      return false;
    }
  }

  private TraceCapture safeTrace(
      URI uri,
      Map<String, List<String>> requestHeaders,
      Map<String, List<String>> responseHeaders,
      String requestContentType,
      Object requestBody,
      String responseContentType,
      Object responseBody,
      ThrowableInfo failure,
      SafeTradingLabHttpTrace inheritedSecrets
  ) {
    try {
      TradingLabHttpTraceInput input = new TradingLabHttpTraceInput(
          uri,
          requestHeaders,
          responseHeaders,
          requestContentType,
          requestBody,
          responseContentType,
          responseBody,
          failure == null ? null : new RecordedFailure(failure.code()),
          null,
          List.of(),
          null);
      SafeTradingLabHttpTrace trace = inheritedSecrets == null
          ? traceSanitizer.sanitize(input)
          : traceSanitizer.sanitizeWithInheritedSecrets(input, inheritedSecrets);
      return new TraceCapture(trace, true);
    } catch (RuntimeException unsafeRawTrace) {
      TradingLabHttpTraceInput fallback = new TradingLabHttpTraceInput(
          uri,
          Map.of(),
          Map.of(),
          "application/json",
          Map.of("bodyOmitted", true, "reason", "TRACE_FALLBACK"),
          "application/json",
          Map.of("bodyOmitted", true, "reason", "TRACE_FALLBACK"),
          null,
          null,
          List.of(),
          null);
      SafeTradingLabHttpTrace trace = inheritedSecrets == null
          ? traceSanitizer.sanitize(fallback)
          : traceSanitizer.sanitizeWithInheritedSecrets(fallback, inheritedSecrets);
      return new TraceCapture(trace, false);
    }
  }

  private WireResponse readResponse(
      org.springframework.http.client.ClientHttpResponse response
  ) throws IOException {
    int status = response.getStatusCode().value();
    Map<String, List<String>> headers = boundedHeaders(response.getHeaders());
    String contentType = firstHeader(headers, HttpHeaders.CONTENT_TYPE);
    long declaredLength = response.getHeaders().getContentLength();
    if (declaredLength > maxResponseBytes) {
      return new WireResponse(
          status,
          headers,
          contentType,
          new byte[0],
          true,
          declaredLength);
    }
    byte[] body;
    try (InputStream input = response.getBody()) {
      body = input.readNBytes(maxResponseBytes + 1);
    }
    if (body.length > maxResponseBytes) {
      return new WireResponse(
          status,
          headers,
          contentType,
          new byte[0],
          true,
          body.length);
    }
    return new WireResponse(status, headers, contentType, body, false, body.length);
  }

  private Envelope parseEnvelope(byte[] body) {
    try {
      JsonNode root = strictMapper.readTree(body);
      if (root == null || !root.isObject()) {
        throw protocolFailure();
      }
      Set<String> names = new java.util.HashSet<>();
      root.fieldNames().forEachRemaining(names::add);
      if (!names.equals(ENVELOPE_FIELDS)
          || !root.path("success").isBoolean()
          || !root.path("code").isTextual()
          || !root.path("message").isTextual()
          || !root.path("timestamp").isTextual()) {
        throw protocolFailure();
      }
      Instant.parse(root.path("timestamp").textValue());
      boolean success = root.path("success").booleanValue();
      String code = safeRemoteCode(root.path("code").textValue());
      if ((success && !"OK".equals(code)) || (!success && code == null)) {
        throw protocolFailure();
      }
      return new Envelope(
          success,
          code,
          root.path("data"));
    } catch (IOException | DateTimeParseException failure) {
      throw protocolFailure();
    }
  }

  private EncodedBody encode(Object value) {
    BoundedOutputStream bounded = new BoundedOutputStream(maxRequestBytes);
    try {
      strictMapper.writeValue(bounded, value);
      byte[] bytes = bounded.toByteArray();
      return new EncodedBody(bytes, new String(bytes, StandardCharsets.UTF_8));
    } catch (IOException | RuntimeException failure) {
      throw new IllegalArgumentException("Validation request encoding failed", failure);
    }
  }

  private Map<String, List<String>> baseHeaders(boolean jsonBody) {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    putSingle(headers, HttpHeaders.ACCEPT, JSON_CONTENT_TYPE);
    if (jsonBody) {
      putSingle(headers, HttpHeaders.CONTENT_TYPE, JSON_CONTENT_TYPE);
    }
    putSingle(headers, INTERNAL_TOKEN_HEADER, internalToken);
    putSingle(headers, REQUEST_ID_HEADER, UUID.randomUUID().toString());
    return headers;
  }

  private static Map<String, List<String>> boundedHeaders(HttpHeaders raw) {
    Map<String, List<String>> copy = new LinkedHashMap<>();
    int headerCount = 0;
    int totalChars = 0;
    for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
      if (++headerCount > MAX_HEADER_COUNT
          || entry.getKey() == null
          || entry.getKey().length() > MAX_HEADER_CHARS
          || entry.getValue() == null
          || entry.getValue().size() > MAX_HEADER_VALUES) {
        return Map.of("X-Validation-Headers-Omitted", List.of("true"));
      }
      List<String> values = new ArrayList<>(entry.getValue().size());
      for (String value : entry.getValue()) {
        if (value == null || value.length() > MAX_HEADER_CHARS) {
          return Map.of("X-Validation-Headers-Omitted", List.of("true"));
        }
        totalChars = Math.addExact(totalChars, entry.getKey().length() + value.length());
        if (totalChars > MAX_HEADER_CHARS) {
          return Map.of("X-Validation-Headers-Omitted", List.of("true"));
        }
        values.add(value);
      }
      copy.put(entry.getKey(), List.copyOf(values));
    }
    return Collections.unmodifiableMap(copy);
  }

  private Object traceResponseBody(WireResponse wire) {
    if (wire.tooLarge()) {
      return Map.of(
          "bodyOmitted", true,
          "byteLength", wire.observedLength(),
          "reason", "RESPONSE_TOO_LARGE");
    }
    if (wire.body().length > TRACE_BODY_CAPTURE_BYTES) {
      return Map.of(
          "bodyOmitted", true,
          "byteLength", wire.body().length,
          "reason", "BODY_OMITTED");
    }
    return new String(wire.body(), StandardCharsets.UTF_8);
  }

  private static Map<String, Object> requestSummary(
      ValidationRunStartRequest request,
      long byteLength,
      String reason
  ) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("bodyOmitted", true);
    summary.put("reason", reason);
    summary.put("byteLength", byteLength);
    summary.put("runId", request.runId().toString());
    summary.put("generation", request.generation());
    summary.put("requestFingerprint", request.requestFingerprint());
    summary.put("tickCount", request.ticks().size());
    summary.put("actionCount", request.actions().size());
    return summary;
  }

  private static Map<String, Object> requestEvidence(Map<String, Object> trace) {
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("queryParameters", trace.get("queryParameters"));
    evidence.put("headers", trace.get("requestHeaders"));
    evidence.put("contentType", trace.get("requestContentType"));
    evidence.put("body", trace.get("requestBody"));
    return ValidationClientSafeValues.freezeMap(evidence);
  }

  private static Map<String, Object> responseEvidence(Map<String, Object> trace) {
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("headers", trace.get("responseHeaders"));
    evidence.put("contentType", trace.get("responseContentType"));
    evidence.put("body", trace.get("responseBody"));
    evidence.put("exception", trace.get("exception"));
    return ValidationClientSafeValues.freezeMap(evidence);
  }

  private static Instant virtualTimeFor(Instant requested, Object data) {
    if (requested != null) {
      return requested;
    }
    if (data instanceof ValidationEventPage page && !page.events().isEmpty()) {
      return page.events().getLast().virtualTime();
    }
    return null;
  }

  private static void validateReset(
      ValidationResetReceipt receipt,
      ValidationResetRequest request
  ) {
    if (receipt.status() == ValidationResetReceipt.Status.SUCCEEDED) {
      long targetGeneration;
      try {
        targetGeneration = Math.addExact(request.expectedGeneration(), 1L);
      } catch (ArithmeticException overflow) {
        throw protocolFailure();
      }
      if (receipt.redisGeneration() == null
          || receipt.memoryGeneration() == null
          || receipt.redisGeneration() != targetGeneration
          || !receipt.redisGeneration().equals(receipt.memoryGeneration())
          || targetGeneration <= 0L) {
        throw protocolFailure();
      }
    }
  }

  private static void validateAccepted(
      ValidationRunAccepted accepted,
      ValidationRunStartRequest request
  ) {
    requireRunId(accepted.runId(), request.runId());
    if (!request.requestFingerprint().equals(accepted.requestFingerprint())
        || accepted.state() != ValidationRunState.ACCEPTED) {
      throw protocolFailure();
    }
  }

  private static void validateEventPage(
      ValidationEventPage page,
      UUID runId,
      long afterSequence
  ) {
    if (page.highWatermark() < afterSequence || page.events().size() > 1) {
      throw protocolFailure();
    }
    long expected = afterSequence;
    for (ValidationRunEvent event : page.events()) {
      requireRunId(event.runId(), runId);
      if (event.sequence() != ++expected || event.sequence() > page.highWatermark()) {
        throw protocolFailure();
      }
    }
    long remaining = page.highWatermark() - afterSequence;
    if ((!page.hasMore() && remaining != page.events().size())
        || (page.hasMore() && remaining <= page.events().size())) {
      throw protocolFailure();
    }
  }

  private static void requireRunId(UUID actual, UUID expected) {
    if (!expected.equals(actual)) {
      throw protocolFailure();
    }
  }

  private static ThrowableInfo remoteFailure(int status, String remoteCode) {
    String code = safeRemoteCode(remoteCode);
    if (code == null) {
      code = status == 0
          ? "VALIDATION_HTTP_REMOTE_FAILURE"
          : "VALIDATION_HTTP_STATUS_" + status;
    }
    boolean retryable = status == 0
        || status == 408
        || status == 425
        || status == 429
        || status >= 500;
    return new ThrowableInfo(
        "REMOTE",
        code,
        "Validation backend rejected the request",
        retryable);
  }

  private static ThrowableInfo protocolInfo(String code) {
    return new ThrowableInfo(
        "PROTOCOL",
        code,
        "Validation backend response violated the protocol",
        false);
  }

  private static String safeRemoteCode(String value) {
    if (value == null || value.isBlank() || value.length() > 128) {
      return null;
    }
    return value.codePoints().allMatch(codePoint ->
        (codePoint >= 'A' && codePoint <= 'Z')
            || (codePoint >= '0' && codePoint <= '9')
            || codePoint == '_')
        ? value
        : null;
  }

  private static String firstSafeHeader(
      Map<String, List<String>> headers,
      String name
  ) {
    String value = firstHeader(headers, name);
    if (value == null || value.isBlank() || value.length() > 256) {
      return null;
    }
    return value.codePoints().allMatch(codePoint ->
        (codePoint >= 'a' && codePoint <= 'z')
            || (codePoint >= 'A' && codePoint <= 'Z')
            || (codePoint >= '0' && codePoint <= '9')
            || codePoint == '-'
            || codePoint == '_'
            || codePoint == '.'
            || codePoint == ':')
        ? value
        : null;
  }

  private static String firstHeader(Map<String, List<String>> headers, String name) {
    return headers.entrySet().stream()
        .filter(entry -> entry.getKey().equalsIgnoreCase(name))
        .map(Map.Entry::getValue)
        .filter(values -> values.size() == 1)
        .map(List::getFirst)
        .findFirst()
        .orElse(null);
  }

  private static boolean jsonContentType(String contentType) {
    if (contentType == null || contentType.length() > 256) {
      return false;
    }
    try {
      MediaType parsed = MediaType.parseMediaType(contentType);
      return MediaType.APPLICATION_JSON.isCompatibleWith(parsed)
          || parsed.getSubtype().toLowerCase(Locale.ROOT).endsWith("+json");
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private URI route(String path) {
    return URI.create(baseUri.toASCIIString() + path);
  }

  private URI runRoute(UUID runId, String suffix) {
    return route("/internal/validation/runs/" + runId + suffix);
  }

  private static void putSingle(
      Map<String, List<String>> headers,
      String name,
      String value
  ) {
    headers.put(name, List.of(value));
  }

  private static URI requireLoopbackBase(URI baseUri) {
    if (baseUri == null
        || !"http".equals(baseUri.getScheme())
        || !"127.0.0.1".equals(baseUri.getHost())
        || baseUri.getPort() < 1
        || baseUri.getUserInfo() != null
        || baseUri.getQuery() != null
        || baseUri.getFragment() != null
        || !(baseUri.getPath().isEmpty() || "/".equals(baseUri.getPath()))) {
      throw new IllegalArgumentException("Validation HTTP base must be loopback");
    }
    String canonical = baseUri.toASCIIString();
    return canonical.endsWith("/")
        ? URI.create(canonical.substring(0, canonical.length() - 1))
        : baseUri;
  }

  private static String requireToken(String token) {
    if (token == null
        || token.isBlank()
        || token.length() < 32
        || token.length() > MAX_HEADER_CHARS
        || token.codePoints().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("Validation internal token configuration is invalid");
    }
    return token;
  }

  private static void requirePositive(Duration duration, String name) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static ObjectMapper strictMapper(
      ObjectMapper source,
      int maxRequestBytes,
      int maxResponseBytes
  ) {
    Objects.requireNonNull(source, "objectMapper");
    int maxDocumentBytes = Math.max(maxRequestBytes, maxResponseBytes);
    ObjectMapper strict = source.copy();
    strict.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
    strict.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    strict.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    strict.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    strict.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    strict.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
        .maxNestingDepth(MAX_JSON_DEPTH)
        .maxDocumentLength(maxDocumentBytes)
        .maxStringLength(maxDocumentBytes)
        .maxNameLength(MAX_JSON_NAME_CHARS)
        .maxNumberLength(MAX_JSON_NUMBER_CHARS)
        .build());
    strict.getFactory().setStreamWriteConstraints(StreamWriteConstraints.builder()
        .maxNestingDepth(MAX_JSON_DEPTH)
        .build());
    strict.configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, true);
    return strict;
  }

  private static Duration elapsed(long startedNanos) {
    long elapsed;
    try {
      elapsed = Math.max(0L, Math.subtractExact(System.nanoTime(), startedNanos));
    } catch (ArithmeticException wrapped) {
      elapsed = 0L;
    }
    return Duration.ofNanos(elapsed);
  }

  private static boolean containsInterrupted(Throwable failure) {
    return hasCause(failure, InterruptedException.class);
  }

  private static boolean containsTimeout(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 16; depth++) {
      if (current instanceof java.net.http.HttpTimeoutException
          || current instanceof java.net.SocketTimeoutException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 16; depth++) {
      if (type.isInstance(current)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static IllegalArgumentException protocolFailure() {
    return new IllegalArgumentException("Validation HTTP protocol failure");
  }

  @FunctionalInterface
  private interface ResponseValidator<T> {
    void validate(T data);
  }

  private record EncodedBody(byte[] bytes, String text) {
  }

  private record Envelope(boolean success, String code, JsonNode data) {
  }

  private record WireResponse(
      int status,
      Map<String, List<String>> headers,
      String contentType,
      byte[] body,
      boolean tooLarge,
      long observedLength
  ) {
  }

  private record TraceCapture(SafeTradingLabHttpTrace trace, boolean complete) {
  }

  private record DataSnapshot(
      byte[] json,
      SafeTradingLabHttpTrace secrets,
      boolean valid
  ) {
  }

  private static final class RecordedFailure extends RuntimeException {

    private RecordedFailure(String code) {
      super(code, null, false, false);
    }
  }

  private static final class RequestLimitExceeded extends IOException {

    private RequestLimitExceeded() {
      super("Validation request exceeded the byte limit");
    }
  }

  private static final class BoundedOutputStream extends OutputStream {

    private final int limit;
    private final ByteArrayOutputStream delegate;
    private int count;

    private BoundedOutputStream(int limit) {
      this.limit = limit;
      this.delegate = new ByteArrayOutputStream(Math.min(limit, 8 * 1024));
    }

    @Override
    public void write(int value) throws IOException {
      requireCapacity(1);
      delegate.write(value);
      count++;
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      Objects.checkFromIndexSize(offset, length, bytes.length);
      requireCapacity(length);
      delegate.write(bytes, offset, length);
      count += length;
    }

    private void requireCapacity(int requested) throws RequestLimitExceeded {
      if (requested < 0 || requested > limit - count) {
        throw new RequestLimitExceeded();
      }
    }

    private byte[] toByteArray() {
      return delegate.toByteArray();
    }
  }
}
