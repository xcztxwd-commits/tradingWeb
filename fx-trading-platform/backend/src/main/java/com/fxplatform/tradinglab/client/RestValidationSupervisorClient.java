package com.fxplatform.tradinglab.client;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Bounded, no-redirect, loopback-only adapter for the Task 6 Validation Supervisor.
 */
@Component
@Profile("!validation")
public final class RestValidationSupervisorClient implements ValidationSupervisorClient {

  public static final URI PRODUCTION_BASE_URI =
      URI.create("http://127.0.0.1:18088");
  public static final String SUPERVISOR_PATH = "/validation-supervisor";

  private static final Duration PRODUCTION_CONNECT_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration PRODUCTION_RESPONSE_TIMEOUT = Duration.ofSeconds(305);
  private static final int PRODUCTION_MAX_RESPONSE_BYTES = 512 * 1024;
  private static final int MAX_TOKEN_BYTES = 4096;
  private static final int MIN_TOKEN_BYTES = 32;
  private static final int MAX_JSON_DEPTH = 32;
  private static final int MAX_JSON_NAME_CHARS = 128;
  private static final int MAX_JSON_NUMBER_CHARS = 64;
  private static final Set<String> COMMAND_SUCCESS_FIELDS =
      Set.of("ok", "action", "exitCode", "output", "relayRunning");
  private static final Set<String> HEALTH_SUCCESS_FIELDS =
      Set.of("ok", "action", "status", "health");
  private static final Set<String> FAILURE_FIELDS = Set.of("ok", "error");
  private static final Set<String> ERROR_FIELDS = Set.of("code");

  private final URI endpoint;
  private final String bearerToken;
  private final ObjectMapper strictMapper;
  private final RestClient restClient;
  private final int maxResponseBytes;

  @Autowired
  public RestValidationSupervisorClient(
      @Value("${SUPERVISOR_INTERNAL_TOKEN:}") String bearerToken,
      ObjectMapper objectMapper
  ) {
    this(
        PRODUCTION_BASE_URI,
        bearerToken,
        objectMapper,
        PRODUCTION_CONNECT_TIMEOUT,
        PRODUCTION_RESPONSE_TIMEOUT,
        PRODUCTION_MAX_RESPONSE_BYTES);
  }

  RestValidationSupervisorClient(
      URI baseUri,
      String bearerToken,
      ObjectMapper objectMapper,
      Duration connectTimeout,
      Duration responseTimeout,
      int maxResponseBytes
  ) {
    URI normalizedBase = requireLoopbackBase(baseUri);
    this.endpoint = URI.create(normalizedBase.toASCIIString() + SUPERVISOR_PATH);
    this.bearerToken = normalizeToken(bearerToken);
    requirePositive(connectTimeout, "connectTimeout");
    requirePositive(responseTimeout, "responseTimeout");
    if (maxResponseBytes < 128 || maxResponseBytes > PRODUCTION_MAX_RESPONSE_BYTES) {
      throw new IllegalArgumentException("Supervisor response byte limit is invalid");
    }
    this.maxResponseBytes = maxResponseBytes;
    this.strictMapper = strictMapper(objectMapper, maxResponseBytes);

    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1)
        .build();
    JdkClientHttpRequestFactory requestFactory =
        new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(responseTimeout);
    this.restClient = RestClient.builder()
        .requestFactory(requestFactory)
        .build();
  }

  @Override
  public ValidationSupervisorStatus status() {
    CommandDocument document = command("status");
    return new ValidationSupervisorStatus(document.relayRunning());
  }

  @Override
  public ValidationSupervisorHealth health() {
    WireResponse wire = exchange("health");
    JsonNode root = successRoot(wire, HEALTH_SUCCESS_FIELDS);
    requireText(root, "action", "health");
    if (!root.path("status").canConvertToInt()) {
      throw unavailable("INVALID_RESPONSE", wire.status());
    }
    int healthHttpStatus = root.path("status").intValue();
    if (healthHttpStatus < 200 || healthHttpStatus >= 300) {
      throw unavailable("HEALTH_UNAVAILABLE", wire.status());
    }
    JsonNode health = root.path("health");
    if (!health.isObject()) {
      throw unavailable("INVALID_RESPONSE", wire.status());
    }
    String status = health.path("status").isTextual()
        ? health.path("status").textValue()
        : null;
    if (!safeHealthStatus(status)) {
      throw unavailable("INVALID_RESPONSE", wire.status());
    }
    return new ValidationSupervisorHealth(status);
  }

  @Override
  public ValidationSupervisorActionResult start() {
    CommandDocument document = command("start");
    return ValidationSupervisorActionResult.start(document.relayRunning());
  }

  @Override
  public ValidationSupervisorActionResult stop() {
    CommandDocument document = command("stop");
    return ValidationSupervisorActionResult.stop(document.relayRunning());
  }

  @Override
  public ValidationSupervisorActionResult restart() {
    CommandDocument document = command("restart");
    return ValidationSupervisorActionResult.restart(document.relayRunning());
  }

  private CommandDocument command(String expectedAction) {
    WireResponse wire = exchange(expectedAction);
    JsonNode root = successRoot(wire, COMMAND_SUCCESS_FIELDS);
    requireText(root, "action", expectedAction);
    if (!root.path("exitCode").canConvertToInt()
        || root.path("exitCode").intValue() != 0
        || !root.path("output").isTextual()
        || !root.path("relayRunning").isBoolean()) {
      throw unavailable("INVALID_RESPONSE", wire.status());
    }
    return new CommandDocument(root.path("relayRunning").booleanValue());
  }

  private WireResponse exchange(String action) {
    if (bearerToken == null) {
      throw unavailable("CONFIGURATION_UNAVAILABLE", 0);
    }
    byte[] requestBody = ("{\"action\":\"" + action + "\"}")
        .getBytes(StandardCharsets.UTF_8);
    WireResponse wire;
    try {
      wire = restClient.post()
          .uri(endpoint)
          .headers(headers -> {
            headers.setBearerAuth(bearerToken);
            headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HttpHeaders.CONNECTION, "close");
          })
          .body(requestBody)
          .exchange((request, response) -> readResponse(response));
      if (wire == null) {
        throw new IOException("Empty Supervisor response");
      }
    } catch (RuntimeException | IOException failure) {
      if (containsInterrupted(failure)) {
        Thread.currentThread().interrupt();
      }
      throw unavailable(
          containsTimeout(failure) ? "SUPERVISOR_TIMEOUT" : "SUPERVISOR_UNAVAILABLE",
          0);
    }

    if (!jsonContentType(wire.contentType())) {
      throw unavailable("INVALID_RESPONSE", wire.status());
    }
    if (wire.status() < 200 || wire.status() >= 300) {
      throw remoteFailure(wire);
    }
    return wire;
  }

  private WireResponse readResponse(
      org.springframework.http.client.ClientHttpResponse response
  ) throws IOException {
    int status = response.getStatusCode().value();
    String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
    long declaredLength = response.getHeaders().getContentLength();
    if (declaredLength > maxResponseBytes) {
      throw new ResponseLimitExceeded();
    }
    byte[] body;
    try (InputStream input = response.getBody()) {
      body = input.readNBytes(maxResponseBytes + 1);
    }
    if (body.length > maxResponseBytes) {
      throw new ResponseLimitExceeded();
    }
    return new WireResponse(status, contentType, body);
  }

  private JsonNode successRoot(WireResponse wire, Set<String> expectedFields) {
    JsonNode root = parseRoot(wire);
    if (!fieldNames(root).equals(expectedFields)
        || !root.path("ok").isBoolean()
        || !root.path("ok").booleanValue()) {
      throw unavailable("INVALID_RESPONSE", wire.status());
    }
    return root;
  }

  private ValidationSupervisorClientException remoteFailure(WireResponse wire) {
    String code = "SUPERVISOR_UNAVAILABLE";
    try {
      JsonNode root = parseRoot(wire);
      if (fieldNames(root).equals(FAILURE_FIELDS)
          && root.path("ok").isBoolean()
          && !root.path("ok").booleanValue()
          && root.path("error").isObject()
          && fieldNames(root.path("error")).equals(ERROR_FIELDS)
          && root.path("error").path("code").isTextual()) {
        code = safeRemoteCode(root.path("error").path("code").textValue());
      }
    } catch (ValidationSupervisorClientException ignored) {
      code = "SUPERVISOR_UNAVAILABLE";
    }
    ValidationSupervisorClientException.Reason reason =
        wire.status() == 409 && "MUTATION_BUSY".equals(code)
            ? ValidationSupervisorClientException.Reason.MUTATION_BUSY
            : ValidationSupervisorClientException.Reason.UNAVAILABLE;
    return new ValidationSupervisorClientException(reason, code, wire.status());
  }

  private JsonNode parseRoot(WireResponse wire) {
    try {
      JsonNode root = strictMapper.readTree(wire.body());
      if (root == null || !root.isObject()) {
        throw unavailable("INVALID_RESPONSE", wire.status());
      }
      return root;
    } catch (IOException | RuntimeException failure) {
      if (failure instanceof ValidationSupervisorClientException clientFailure) {
        throw clientFailure;
      }
      throw unavailable("INVALID_RESPONSE", wire.status());
    }
  }

  private static Set<String> fieldNames(JsonNode node) {
    Set<String> names = new HashSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static void requireText(JsonNode root, String field, String expected) {
    if (!root.path(field).isTextual()
        || !expected.equals(root.path(field).textValue())) {
      throw unavailable("INVALID_RESPONSE", 200);
    }
  }

  private static String safeRemoteCode(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 128
        || !value.codePoints().allMatch(codePoint ->
            codePoint >= 'A' && codePoint <= 'Z'
                || codePoint >= '0' && codePoint <= '9'
                || codePoint == '_')) {
      return "SUPERVISOR_UNAVAILABLE";
    }
    return value;
  }

  private static boolean safeHealthStatus(String value) {
    return value != null
        && !value.isBlank()
        && value.length() <= 32
        && value.codePoints().allMatch(codePoint ->
            codePoint >= 'A' && codePoint <= 'Z'
                || codePoint >= '0' && codePoint <= '9'
                || codePoint == '_');
  }

  private static boolean jsonContentType(String value) {
    if (value == null || value.length() > 128) {
      return false;
    }
    try {
      MediaType type = MediaType.parseMediaType(value);
      if (!MediaType.APPLICATION_JSON.includes(type)) {
        return false;
      }
      if (!type.getParameters().keySet().stream()
          .allMatch(parameter -> "charset".equalsIgnoreCase(parameter))) {
        return false;
      }
      return type.getCharset() == null
          || StandardCharsets.UTF_8.equals(type.getCharset());
    } catch (RuntimeException invalid) {
      return false;
    }
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
      throw new IllegalArgumentException("Supervisor base URI must be fixed loopback HTTP");
    }
    String value = baseUri.toASCIIString();
    return value.endsWith("/")
        ? URI.create(value.substring(0, value.length() - 1))
        : baseUri;
  }

  private static String normalizeToken(String value) {
    if (value == null || value.isEmpty()) {
      return null;
    }
    int bytes = value.getBytes(StandardCharsets.UTF_8).length;
    if (value.isBlank()
        || bytes < MIN_TOKEN_BYTES
        || bytes > MAX_TOKEN_BYTES
        || !value.equals(value.trim())
        || value.codePoints().anyMatch(codePoint -> codePoint == '\r' || codePoint == '\n')) {
      throw new IllegalArgumentException("Supervisor bearer configuration is invalid");
    }
    return value;
  }

  private static ObjectMapper strictMapper(ObjectMapper source, int maxBytes) {
    ObjectMapper mapper = Objects.requireNonNull(source, "objectMapper").copy();
    mapper.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
    mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
        .maxNestingDepth(MAX_JSON_DEPTH)
        .maxDocumentLength(maxBytes)
        .maxStringLength(maxBytes)
        .maxNameLength(MAX_JSON_NAME_CHARS)
        .maxNumberLength(MAX_JSON_NUMBER_CHARS)
        .build());
    mapper.configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, true);
    return mapper;
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static boolean containsInterrupted(Throwable failure) {
    return hasCause(failure, InterruptedException.class);
  }

  private static boolean containsTimeout(Throwable failure) {
    return hasCause(failure, java.net.http.HttpTimeoutException.class)
        || hasCause(failure, java.net.SocketTimeoutException.class);
  }

  private static boolean hasCause(
      Throwable failure,
      Class<? extends Throwable> type
  ) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 16; depth++) {
      if (type.isInstance(current)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static ValidationSupervisorClientException unavailable(
      String code,
      int status
  ) {
    return new ValidationSupervisorClientException(
        ValidationSupervisorClientException.Reason.UNAVAILABLE,
        code,
        status);
  }

  private record CommandDocument(boolean relayRunning) {
  }

  private record WireResponse(int status, String contentType, byte[] body) {
  }

  private static final class ResponseLimitExceeded extends IOException {

    private ResponseLimitExceeded() {
      super("Supervisor response exceeded its fixed byte limit");
    }
  }
}
