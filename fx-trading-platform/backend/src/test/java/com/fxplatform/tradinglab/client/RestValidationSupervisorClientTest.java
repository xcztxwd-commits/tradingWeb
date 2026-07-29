package com.fxplatform.tradinglab.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RestValidationSupervisorClientTest {

  private static final String TOKEN =
      "task8-supervisor-client-token-0123456789-ABCDEFG";

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
  private HttpServer server;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void usesOnlyTheFixedRouteBearerAndFiveTypedBodiesAndReturnsMinimalDtos() {
    String rawOutput = "container-id-and-raw-compose-output-must-never-escape";
    server.createContext("/validation-supervisor", exchange -> {
      CapturedRequest request = capture(exchange);
      String action = objectMapper.readTree(request.body()).path("action").asText();
      if ("health".equals(action)) {
        json(exchange, 200, """
            {"ok":true,"action":"health","status":200,
             "health":{"status":"UP","components":{"db":{"password":"raw-secret"}}}}
            """);
      } else {
        boolean relayRunning = !"stop".equals(action);
        json(exchange, 200, """
            {"ok":true,"action":"%s","exitCode":0,"output":"%s","relayRunning":%s}
            """.formatted(action, rawOutput, relayRunning));
      }
    });
    RestValidationSupervisorClient client = client(Duration.ofSeconds(2), 64 * 1024);

    ValidationSupervisorStatus status = client.status();
    ValidationSupervisorHealth health = client.health();
    ValidationSupervisorActionResult started = client.start();
    ValidationSupervisorActionResult stopped = client.stop();
    ValidationSupervisorActionResult restarted = client.restart();

    assertThat(status.relayRunning()).isTrue();
    assertThat(health.status()).isEqualTo("UP");
    assertThat(started.action()).isEqualTo(ValidationSupervisorAction.START);
    assertThat(stopped.action()).isEqualTo(ValidationSupervisorAction.STOP);
    assertThat(restarted.action()).isEqualTo(ValidationSupervisorAction.RESTART);
    assertThat(List.of(status, health, started, stopped, restarted))
        .allSatisfy(value -> assertThat(value.toString())
            .doesNotContain(rawOutput)
            .doesNotContain("raw-secret")
            .doesNotContain(TOKEN));

    assertThat(requests).hasSize(5);
    assertThat(requests)
        .extracting(CapturedRequest::method)
        .containsOnly("POST");
    assertThat(requests)
        .extracting(request -> request.uri().toString())
        .containsOnly("/validation-supervisor");
    assertThat(requests)
        .allSatisfy(request -> {
          assertThat(request.authorization()).containsExactly("Bearer " + TOKEN);
          assertThat(request.contentType()).containsExactly("application/json");
        });
    assertThat(requests)
        .extracting(CapturedRequest::body)
        .containsExactly(
            "{\"action\":\"status\"}",
            "{\"action\":\"health\"}",
            "{\"action\":\"start\"}",
            "{\"action\":\"stop\"}",
            "{\"action\":\"restart\"}");
  }

  @Test
  void refusesNonLoopbackOrMutableBaseUrisBeforeAnyRequest() {
    List<URI> invalid = List.of(
        URI.create("http://localhost:18088"),
        URI.create("http://[::1]:18088"),
        URI.create("http://127.0.0.2:18088"),
        URI.create("https://127.0.0.1:18088"),
        URI.create("http://127.0.0.1"),
        URI.create("http://127.0.0.1:18088/path"),
        URI.create("http://127.0.0.1:18088?path=/tmp"),
        URI.create("http://user@127.0.0.1:18088"));

    for (URI uri : invalid) {
      assertThatThrownBy(() -> new RestValidationSupervisorClient(
          uri,
          TOKEN,
          objectMapper,
          Duration.ofMillis(100),
          Duration.ofSeconds(1),
          4096))
          .as(uri.toString())
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(requests).isEmpty();
  }

  @Test
  void missingBearerFailsClosedBeforeOpeningALoopbackConnection() {
    AtomicInteger hits = new AtomicInteger();
    server.createContext("/validation-supervisor", exchange -> {
      hits.incrementAndGet();
      json(exchange, 200, """
          {"ok":true,"action":"status","exitCode":0,"output":"","relayRunning":true}
          """);
    });
    RestValidationSupervisorClient client = new RestValidationSupervisorClient(
        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
        "",
        objectMapper,
        Duration.ofMillis(500),
        Duration.ofSeconds(1),
        4096);

    assertThatThrownBy(client::status)
        .isInstanceOfSatisfying(
            ValidationSupervisorClientException.class,
            failure -> assertThat(failure.reason())
                .isEqualTo(ValidationSupervisorClientException.Reason.UNAVAILABLE));
    assertThat(hits).hasValue(0);
  }

  @Test
  void rejectsRedirectWithoutFollowingIt() {
    AtomicInteger hits = new AtomicInteger();
    server.createContext("/validation-supervisor", exchange -> {
      hits.incrementAndGet();
      exchange.getResponseHeaders().set("Location", "/redirected");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });
    server.createContext("/redirected", exchange -> {
      hits.incrementAndGet();
      json(exchange, 200, """
          {"ok":true,"action":"status","exitCode":0,"output":"","relayRunning":true}
          """);
    });

    assertThatThrownBy(() -> client(Duration.ofSeconds(1), 4096).status())
        .isInstanceOf(ValidationSupervisorClientException.class)
        .hasMessageNotContaining(TOKEN)
        .hasMessageNotContaining("/redirected");
    assertThat(hits).hasValue(1);
  }

  @Test
  void rejectsDuplicateTrailingUnknownMismatchedAndMalformedSuccessJson() {
    AtomicReference<String> response = new AtomicReference<>();
    server.createContext("/validation-supervisor", exchange ->
        json(exchange, 200, response.get()));
    RestValidationSupervisorClient client = client(Duration.ofSeconds(1), 8192);
    List<String> invalid = List.of(
        """
        {"ok":true,"ok":true,"action":"status","exitCode":0,"output":"","relayRunning":true}
        """,
        """
        {"ok":true,"action":"status","exitCode":0,"output":"","relayRunning":true} {}
        """,
        """
        {"ok":true,"action":"status","exitCode":0,"output":"","relayRunning":true,"extra":1}
        """,
        """
        {"ok":true,"action":"restart","exitCode":0,"output":"","relayRunning":true}
        """,
        """
        {"ok":true,"action":"status","exitCode":7,"output":"","relayRunning":true}
        """,
        """
        {"ok":"true","action":"status","exitCode":0,"output":"","relayRunning":true}
        """,
        "not-json");

    for (String body : invalid) {
      response.set(body);
      assertThatThrownBy(client::status)
          .as(body)
          .isInstanceOf(ValidationSupervisorClientException.class)
          .hasMessageNotContaining(body)
          .hasMessageNotContaining(TOKEN);
    }
  }

  @Test
  void rejectsWrongContentTypeAndOversizedBodyWithoutReturningRawBytes() {
    AtomicReference<String> body = new AtomicReference<>("not-json-secret");
    AtomicReference<String> contentType = new AtomicReference<>("text/plain");
    server.createContext("/validation-supervisor", exchange -> {
      byte[] payload = body.get().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", contentType.get());
      exchange.sendResponseHeaders(200, payload.length);
      exchange.getResponseBody().write(payload);
      exchange.close();
    });
    RestValidationSupervisorClient client = client(Duration.ofSeconds(1), 128);

    assertThatThrownBy(client::status)
        .isInstanceOf(ValidationSupervisorClientException.class)
        .hasMessageNotContaining(body.get());

    contentType.set("application/json; charset=utf-8");
    body.set("""
        {"ok":true,"action":"status","exitCode":0,"output":"%s","relayRunning":true}
        """.formatted("x".repeat(512)));
    assertThatThrownBy(client::status)
        .isInstanceOf(ValidationSupervisorClientException.class)
        .hasMessageNotContaining("x".repeat(32));
  }

  @Test
  void mapsSupervisorBusySeparatelyAndSanitizesEveryOtherRemoteFailure() {
    AtomicReference<Integer> responseStatus = new AtomicReference<>(409);
    AtomicReference<String> responseBody = new AtomicReference<>(
        "{\"ok\":false,\"error\":{\"code\":\"MUTATION_BUSY\"}}");
    server.createContext("/validation-supervisor", exchange ->
        json(exchange, responseStatus.get(), responseBody.get()));
    RestValidationSupervisorClient client = client(Duration.ofSeconds(1), 4096);

    assertThatThrownBy(client::restart)
        .isInstanceOfSatisfying(
            ValidationSupervisorClientException.class,
            failure -> {
              assertThat(failure.reason())
                  .isEqualTo(ValidationSupervisorClientException.Reason.MUTATION_BUSY);
              assertThat(failure.remoteCode()).isEqualTo("MUTATION_BUSY");
              assertThat(failure.httpStatus()).isEqualTo(409);
            });

    responseStatus.set(502);
    responseBody.set(
        "{\"ok\":false,\"error\":{\"code\":\"COMMAND_FAILED\"}}");
    assertThatThrownBy(client::start)
        .isInstanceOfSatisfying(
            ValidationSupervisorClientException.class,
            failure -> {
              assertThat(failure.reason())
                  .isEqualTo(ValidationSupervisorClientException.Reason.UNAVAILABLE);
              assertThat(failure.getMessage())
                  .doesNotContain(responseBody.get())
                  .doesNotContain(TOKEN);
            });
  }

  @Test
  void productionIdentityIsFrozenToTask6LoopbackEndpoint() {
    assertThat(RestValidationSupervisorClient.PRODUCTION_BASE_URI)
        .isEqualTo(URI.create("http://127.0.0.1:18088"));
    assertThat(RestValidationSupervisorClient.SUPERVISOR_PATH)
        .isEqualTo("/validation-supervisor");
  }

  private RestValidationSupervisorClient client(Duration readTimeout, int responseBytes) {
    return new RestValidationSupervisorClient(
        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
        TOKEN,
        objectMapper,
        Duration.ofMillis(500),
        readTimeout,
        responseBytes);
  }

  private CapturedRequest capture(HttpExchange exchange) throws IOException {
    CapturedRequest captured = new CapturedRequest(
        exchange.getRequestMethod(),
        exchange.getRequestURI(),
        new ArrayList<>(exchange.getRequestHeaders().getOrDefault(
            "Authorization", List.of())),
        new ArrayList<>(exchange.getRequestHeaders().getOrDefault(
            "Content-Type", List.of())),
        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    requests.add(captured);
    return captured;
  }

  private static void json(HttpExchange exchange, int status, String body)
      throws IOException {
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, payload.length);
    exchange.getResponseBody().write(payload);
    exchange.close();
  }

  private record CapturedRequest(
      String method,
      URI uri,
      List<String> authorization,
      List<String> contentType,
      String body
  ) {
  }
}
