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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("validation-loopback-port-8080")
class JdkValidationLoopbackStateResponseTest {

  private static final UUID RUN_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000581");
  private static final UUID USER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000582");
  private static final UUID ACCOUNT_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000583");
  private static final String SECRET = "s".repeat(48);

  @Test
  void rejectsHttp200WhenSummaryDataIsMissing() throws Exception {
    HttpServer server = server("{\"success\":true,\"code\":\"OK\",\"data\":null}");
    try {
      JdkValidationLoopbackHttpClient client = registeredClient();

      assertThatThrownBy(() -> client.execute(command(Operation.QUERY_STATE, "query-state")))
          .isInstanceOfSatisfying(
              BusinessException.class,
              exception -> assertThat(exception.getCode())
                  .isEqualTo("VALIDATION_STATE_RESPONSE_INVALID"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsHttp200WhenACollectionUsesTheWrongShape() throws Exception {
    HttpServer server = server(ok(Map.of("id", ACCOUNT_ID.toString())), true);
    try {
      JdkValidationLoopbackHttpClient client = registeredClient();

      assertThatThrownBy(() -> client.execute(command(Operation.QUERY_STATE, "query-state")))
          .isInstanceOfSatisfying(
              BusinessException.class,
              exception -> assertThat(exception.getCode())
                  .isEqualTo("VALIDATION_STATE_RESPONSE_INVALID"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void queryStateHopsKeepBoundedSafeLedgerAndPagingParameters() throws Exception {
    HttpServer server = server(ok(Map.of("id", ACCOUNT_ID.toString())));
    try {
      JdkValidationLoopbackHttpClient client = registeredClient();

      HttpResult result = client.execute(command(Operation.QUERY_STATE, "query-state-safe-query"));

      assertThat(result.status()).isEqualTo(200);
      var ledger = result.hops().stream()
          .filter(hop -> "/api/ledger".equals(hop.path()))
          .findFirst()
          .orElseThrow();
      assertThat(ledger.trace().get("queryParameters"))
          .isEqualTo(Map.of("accountId", java.util.List.of(ACCOUNT_ID.toString())));
      var orders = result.hops().stream()
          .filter(hop -> "/api/trading/orders".equals(hop.path()))
          .findFirst()
          .orElseThrow();
      assertThat(orders.trace().get("queryParameters")).isEqualTo(Map.of(
          "accountId", java.util.List.of(ACCOUNT_ID.toString()),
          "page", java.util.List.of("0"),
          "size", java.util.List.of("100")));
      assertThat(result.hops().toString().toLowerCase())
          .doesNotContain(
              "authorization",
              "x-validation-internal-token",
              "access-token",
              SECRET.toLowerCase());
    } finally {
      server.stop(0);
    }
  }

  private static JdkValidationLoopbackHttpClient registeredClient() {
    JdkValidationLoopbackHttpClient client =
        new JdkValidationLoopbackHttpClient(new ObjectMapper(), SECRET);
    assertThat(client.execute(command(Operation.REGISTER, "register")).status()).isEqualTo(200);
    return client;
  }

  private static Command command(Operation operation, String key) {
    return new Command(operation, RUN_ID, 1L, 0L, key, key, Map.of());
  }

  private static HttpServer server(String summary) throws IOException {
    return server(summary, false);
  }

  private static HttpServer server(String summary, boolean malformedWallet) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
    server.createContext("/api/auth/register", exchange -> write(exchange, ok(Map.of(
        "accessToken", "test-access-token",
        "userId", USER_ID.toString()))));
    server.createContext("/api/accounts", exchange -> {
      if ("/api/accounts".equals(exchange.getRequestURI().getPath())) {
        write(exchange, ok(java.util.List.of(Map.of(
            "id", ACCOUNT_ID.toString(),
            "accountType", "DEMO"))));
        return;
      }
      String path = exchange.getRequestURI().getPath();
      if (path.endsWith("/summary")) {
        write(exchange, summary);
      } else if (path.endsWith("/wallet-balances")) {
        write(exchange, malformedWallet ? ok(Map.of()) : ok(java.util.List.of()));
      } else if (path.endsWith("/asset-ledger")) {
        write(exchange, ok(java.util.List.of()));
      } else {
        write(exchange, 404, "{}");
      }
    });
    server.createContext("/api/ledger", exchange -> write(exchange, ok(java.util.List.of())));
    String emptyPage = ok(Map.of(
        "items", java.util.List.of(),
        "page", 0,
        "size", 100,
        "total", 0,
        "totalPages", 0));
    server.createContext("/api/trading/orders", exchange -> write(exchange, emptyPage));
    server.createContext("/api/trading/trades", exchange -> write(exchange, emptyPage));
    server.createContext("/api/trading/positions", exchange -> write(exchange, emptyPage));
    server.createContext("/api/trading/funding/settlements", exchange -> write(exchange, emptyPage));
    server.start();
    return server;
  }

  private static String ok(Object data) throws IOException {
    return new ObjectMapper().writeValueAsString(Map.of(
        "success", true,
        "code", "OK",
        "message", "success",
        "data", data));
  }

  private static void write(HttpExchange exchange, String body) throws IOException {
    write(exchange, 200, body);
  }

  private static void write(HttpExchange exchange, int status, String body) throws IOException {
    assertThat(exchange.getRequestHeaders().get("X-Validation-Generation"))
        .containsExactly("1");
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
