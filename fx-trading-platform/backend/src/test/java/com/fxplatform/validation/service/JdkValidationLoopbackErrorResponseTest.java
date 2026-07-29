package com.fxplatform.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.Command;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.HttpResult;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.Operation;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("validation-loopback-port-8080")
class JdkValidationLoopbackErrorResponseTest {

  private static final UUID RUN_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000599");
  private static final String SECRET = "s".repeat(48);

  @Test
  void malformed4xxBodyRemainsAKnownSanitizedHttpResult() throws Exception {
    HttpServer server = errorServer("not-json");
    try {
      HttpResult result = client().execute(registerCommand("malformed"));

      assertThat(result.status()).isEqualTo(400);
      assertThat(result.correlationId()).isEqualTo(
          RUN_ID + ":REGISTER:0");
      assertThat(result.body())
          .containsEntry("malformedBody", true)
          .doesNotContainKey("rawBody");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void everyRealLoopbackExchangeReturnsBoundedSafeHopEvidence() throws Exception {
    HttpServer server = errorServer("""
        {
          "success": false,
          "code": "ORDER_REJECTED",
          "password": "leaked-password",
          "accessToken": "leaked-access-token",
          "message": "sanitized rejection"
        }
        """);
    try {
      HttpResult result = client().execute(registerCommand("hop-evidence"));

      assertThat(result.hops()).hasSize(2);
      assertThat(result.hops())
          .extracting(
              ValidationLoopbackHttpClient.HttpHop::method,
              ValidationLoopbackHttpClient.HttpHop::path,
              ValidationLoopbackHttpClient.HttpHop::status)
          .containsExactly(
              org.assertj.core.groups.Tuple.tuple("POST", "/api/auth/register", 400),
              org.assertj.core.groups.Tuple.tuple("POST", "/api/auth/login", 400));
      assertThat(result.hops())
          .allSatisfy(hop -> {
            assertThat(hop.correlationId()).isEqualTo(RUN_ID + ":REGISTER:0");
            assertThat(hop.durationMillis()).isNotNegative();
            assertThat(hop.trace()).containsOnlyKeys(
                "url",
                "queryParameters",
                "requestHeaders",
                "requestContentType",
                "requestBody",
                "responseHeaders",
                "responseContentType",
                "responseBody",
                "exception",
                "authentication");
            assertThat(hop.trace().get("authentication")).isNull();
            assertThat(hop.trace().toString().toLowerCase())
                .doesNotContain(
                    "authorization",
                    "x-validation-internal-token",
                    "leaked-password",
                    "leaked-access-token",
                    SECRET.toLowerCase());
          });
      assertThatThrownBy(() -> result.hops().add(result.hops().getFirst()))
          .isInstanceOf(UnsupportedOperationException.class);
      assertThatThrownBy(() ->
          result.hops().getFirst().trace().put("password", "late mutation"))
          .isInstanceOf(UnsupportedOperationException.class);
      assertThat(result.hops().stream().map(hop -> hop.trace().keySet()).toList())
          .allMatch(Set.of(
              "url",
              "queryParameters",
              "requestHeaders",
              "requestContentType",
              "requestBody",
              "responseHeaders",
              "responseContentType",
              "responseBody",
              "exception",
              "authentication")::equals);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void trailingTokensIn4xxBodyAreRejectedInsteadOfTrustingTheLeadingEnvelope()
      throws Exception {
    HttpServer server = errorServer("""
        {"success":false,"code":"ORDER_REJECTED"} trailing
        """);
    try {
      HttpResult result = client().execute(registerCommand("trailing-token"));

      assertThat(result.status()).isEqualTo(400);
      assertThat(result.correlationId()).isEqualTo(
          RUN_ID + ":REGISTER:0");
      assertThat(result.body())
          .containsEntry("malformedBody", true)
          .doesNotContainKey("code");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void arbitraryCredentialShapedTextIsRedactedFromKnownErrorEvidence()
      throws Exception {
    HttpServer server = errorServer("""
        {
          "success": false,
          "code": "ORDER_REJECTED",
          "message": "safe prefix\\nBearer foreign-token",
          "detail": "safe prefix\\npassword=abc"
        }
        """);
    try {
      HttpResult result = client().execute(registerCommand("credential-shaped-text"));

      assertThat(result.status()).isEqualTo(400);
      assertThat(result.body())
          .containsEntry("code", "ORDER_REJECTED")
          .containsEntry("message", "[REDACTED]")
          .containsEntry("detail", "[REDACTED]");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void responseBodyReadCannotOutliveTheFixedRequestDeadline() throws Exception {
    CountDownLatch releaseBody = new CountDownLatch(1);
    HttpServer server = stalledBodyServer(releaseBody);
    try {
      JdkValidationLoopbackHttpClient boundedClient =
          new JdkValidationLoopbackHttpClient(
              new ObjectMapper(),
              SECRET,
              Duration.ofMillis(250L));
      long beganAt = System.nanoTime();

      assertThatThrownBy(() ->
          boundedClient.execute(registerCommand("stalled-body")))
          .isInstanceOf(BusinessException.class)
          .hasMessage("Validation loopback response timed out");
      assertThat(Duration.ofNanos(System.nanoTime() - beganAt))
          .isLessThan(Duration.ofSeconds(2L));
    } finally {
      releaseBody.countDown();
      server.stop(0);
    }
  }

  @Test
  void realErrorEnvelopeCanRetainJsonNullWithoutLosingStatusOrCorrelation()
      throws Exception {
    String envelope = """
        {
          "success": false,
          "code": "ORDER_REJECTED",
          "message": "sanitized",
          "data": null,
          "timestamp": "2030-01-01T00:00:00Z"
        }
        """;
    HttpServer server = errorServer(envelope);
    try {
      HttpResult result = client().execute(registerCommand("json-null"));

      assertThat(result.status()).isEqualTo(400);
      assertThat(result.correlationId()).isEqualTo(
          RUN_ID + ":REGISTER:0");
      assertThat(result.body()).containsEntry("code", "ORDER_REJECTED");
      assertThat(result.body()).containsKey("data");
      assertThat(result.body().get("data")).isNull();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void oversized4xxBodyKeepsTheKnownStatusWithoutRetainingRawContent()
      throws Exception {
    HttpServer server = errorServer("x".repeat(2 * 1024 * 1024 + 1));
    try {
      HttpResult result = client().execute(registerCommand("oversized"));

      assertThat(result.status()).isEqualTo(400);
      assertThat(result.correlationId()).isEqualTo(
          RUN_ID + ":REGISTER:0");
      assertThat(result.body())
          .containsEntry("responseTooLarge", true)
          .doesNotContainKey("rawBody");
    } finally {
      server.stop(0);
    }
  }

  private static JdkValidationLoopbackHttpClient client() {
    return new JdkValidationLoopbackHttpClient(new ObjectMapper(), SECRET);
  }

  private static Command registerCommand(String suffix) {
    return new Command(
        Operation.REGISTER,
        RUN_ID,
        1L,
        0L,
        "register-" + suffix,
        "register-" + suffix,
        Map.of());
  }

  private static HttpServer errorServer(String body) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
    server.createContext("/api/auth/register", exchange -> write(exchange, body));
    server.createContext("/api/auth/login", exchange -> write(exchange, body));
    server.start();
    return server;
  }

  private static HttpServer stalledBodyServer(CountDownLatch releaseBody)
      throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
    server.createContext("/api/auth/register", exchange -> {
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(400, 2L);
      exchange.getResponseBody().write('{');
      exchange.getResponseBody().flush();
      try {
        releaseBody.await();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    server.start();
    return server;
  }

  private static void write(HttpExchange exchange, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(400, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
