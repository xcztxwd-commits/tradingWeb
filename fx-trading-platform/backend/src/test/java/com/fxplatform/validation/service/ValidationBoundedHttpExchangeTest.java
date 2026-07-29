package com.fxplatform.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class ValidationBoundedHttpExchangeTest {

  private static final int BODY_LIMIT = 64;
  private static final Duration TEST_BUDGET = Duration.ofSeconds(2L);

  @Test
  void bodyExactlyAtTheFixedLimitIsAccepted() throws Exception {
    byte[] body = "x".repeat(BODY_LIMIT).getBytes(StandardCharsets.UTF_8);
    try (RunningServer server = server(exchange -> {
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    })) {
      ValidationBoundedHttpExchange.Result result = send(server.uri(), TEST_BUDGET);

      assertThat(result.statusCode()).isEqualTo(200);
      assertThat(result.bodyTooLarge()).isFalse();
      assertThat(result.body()).containsExactly(body);
    }
  }

  @Test
  void responseCompletedAfterBudgetIsRejectedWhenTheWaiterResumesLate()
      throws Exception {
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    AtomicInteger clockReads = new AtomicInteger();
    LongSupplier monotonicClock = () ->
        clockReads.getAndIncrement() < 2 ? 0L : TEST_BUDGET.toNanos() + 1L;
    try (RunningServer server = server(exchange -> {
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    })) {
      assertThatThrownBy(() -> send(server.uri(), TEST_BUDGET, monotonicClock))
          .isInstanceOf(HttpTimeoutException.class)
          .hasMessage("HTTP response body deadline exceeded");
      assertThat(clockReads).hasValueGreaterThanOrEqualTo(3);
    }
  }

  @Test
  void declaredOversizeIsRejectedAfterHeadersWithoutWaitingForTheBody()
      throws Exception {
    CountDownLatch releaseBody = new CountDownLatch(1);
    RunningServer server = server(exchange -> {
      exchange.sendResponseHeaders(400, BODY_LIMIT + 1L);
      try {
        releaseBody.await();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    try {
      ValidationBoundedHttpExchange.Result result = send(server.uri(), TEST_BUDGET);

      assertThat(result.statusCode()).isEqualTo(400);
      assertThat(result.bodyTooLarge()).isTrue();
      assertThat(result.body()).isEmpty();
    } finally {
      releaseBody.countDown();
      server.close();
    }
  }

  @Test
  void chunkedOversizeIsRejectedWithoutRetainingPartialContent() throws Exception {
    byte[] body = "x".repeat(BODY_LIMIT + 1).getBytes(StandardCharsets.UTF_8);
    try (RunningServer server = server(exchange -> {
      exchange.sendResponseHeaders(400, 0L);
      exchange.getResponseBody().write(body);
      exchange.close();
    })) {
      ValidationBoundedHttpExchange.Result result = send(server.uri(), TEST_BUDGET);

      assertThat(result.statusCode()).isEqualTo(400);
      assertThat(result.bodyTooLarge()).isTrue();
      assertThat(result.body()).isEmpty();
    }
  }

  @Test
  void stalledBodyCannotOutliveTheMonotonicDeadline() throws Exception {
    CountDownLatch releaseBody = new CountDownLatch(1);
    RunningServer server = server(exchange -> {
      exchange.sendResponseHeaders(200, 2L);
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
    try {
      long beganAt = System.nanoTime();

      assertThatThrownBy(() -> send(server.uri(), Duration.ofMillis(250L)))
          .isInstanceOf(HttpTimeoutException.class)
          .hasMessage("HTTP response body deadline exceeded");
      assertThat(Duration.ofNanos(System.nanoTime() - beganAt))
          .isLessThan(Duration.ofSeconds(2L));
    } finally {
      releaseBody.countDown();
      server.close();
    }
  }

  private static ValidationBoundedHttpExchange.Result send(URI uri, Duration budget)
      throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(5L))
        .GET()
        .build();
    return ValidationBoundedHttpExchange.send(
        HttpClient.newHttpClient(),
        request,
        budget,
        BODY_LIMIT);
  }

  private static ValidationBoundedHttpExchange.Result send(
      URI uri,
      Duration budget,
      LongSupplier monotonicClock
  ) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(5L))
        .GET()
        .build();
    return ValidationBoundedHttpExchange.send(
        HttpClient.newHttpClient(),
        request,
        budget,
        BODY_LIMIT,
        monotonicClock);
  }

  private static RunningServer server(HttpHandler handler) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
      Thread thread = new Thread(runnable, "validation-bounded-http-test");
      thread.setDaemon(true);
      return thread;
    });
    server.setExecutor(executor);
    server.createContext("/exchange", handler);
    server.start();
    return new RunningServer(server, executor);
  }

  private record RunningServer(HttpServer server, ExecutorService executor)
      implements AutoCloseable {

    URI uri() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/exchange");
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
