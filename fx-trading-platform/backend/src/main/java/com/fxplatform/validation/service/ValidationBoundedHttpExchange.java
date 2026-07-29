package com.fxplatform.validation.service;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * Completes an HTTP exchange only after its bounded body is consumed.
 *
 * <p>JDK request timeouts stop guarding an {@code ofInputStream()} response after headers arrive.
 * This adapter keeps headers and body under one monotonic deadline without an executor or an
 * unbounded byte-array subscriber.</p>
 */
public final class ValidationBoundedHttpExchange {

  private ValidationBoundedHttpExchange() {
  }

  public static Result send(
      HttpClient client,
      HttpRequest request,
      Duration budget,
      int maxBodyBytes
  ) throws IOException, InterruptedException {
    return send(client, request, budget, maxBodyBytes, System::nanoTime);
  }

  static Result send(
      HttpClient client,
      HttpRequest request,
      Duration budget,
      int maxBodyBytes,
      LongSupplier monotonicNanos
  ) throws IOException, InterruptedException {
    Objects.requireNonNull(client, "client");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(monotonicNanos, "monotonicNanos");
    if (budget == null || budget.isZero() || budget.isNegative()) {
      throw new IllegalArgumentException("HTTP exchange budget must be positive");
    }
    if (maxBodyBytes <= 0) {
      throw new IllegalArgumentException("HTTP body byte limit must be positive");
    }

    long startedAt = monotonicNanos.getAsLong();
    long budgetNanos = budget.toNanos();
    CompletableFuture<HttpResponse<BoundedBody>> pending = client.sendAsync(
        request,
        responseInfo -> new BoundedBodySubscriber(
            maxBodyBytes,
            responseInfo.headers()
                .firstValueAsLong("Content-Length")
                .orElse(-1L) > maxBodyBytes));
    try {
      long remainingNanos =
          budgetNanos - (monotonicNanos.getAsLong() - startedAt);
      if (remainingNanos <= 0L) {
        throw new TimeoutException("HTTP response body deadline expired");
      }
      HttpResponse<BoundedBody> response =
          pending.get(remainingNanos, TimeUnit.NANOSECONDS);
      if (budgetNanos - (monotonicNanos.getAsLong() - startedAt) <= 0L) {
        throw new TimeoutException("HTTP response body deadline expired");
      }
      BoundedBody body = response.body();
      return new Result(
          response.statusCode(),
          response.headers(),
          body.bytes(),
          body.tooLarge());
    } catch (TimeoutException exception) {
      pending.cancel(true);
      HttpTimeoutException timeout =
          new HttpTimeoutException("HTTP response body deadline exceeded");
      timeout.initCause(exception);
      throw timeout;
    } catch (InterruptedException exception) {
      pending.cancel(true);
      throw exception;
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof IOException ioException) {
        throw ioException;
      }
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new IOException("HTTP response body failed", cause);
    }
  }

  public record Result(
      int statusCode,
      HttpHeaders headers,
      byte[] body,
      boolean bodyTooLarge
  ) {

    public Result {
      Objects.requireNonNull(headers, "headers");
      body = Objects.requireNonNull(body, "body").clone();
    }

    @Override
    public byte[] body() {
      return body.clone();
    }
  }

  private record BoundedBody(byte[] bytes, boolean tooLarge) {
  }

  private static final class BoundedBodySubscriber
      implements HttpResponse.BodySubscriber<BoundedBody> {

    private final int maxBodyBytes;
    private final boolean declaredTooLarge;
    private final CompletableFuture<BoundedBody> body = new CompletableFuture<>();
    private byte[] buffer;
    private int size;
    private Flow.Subscription subscription;
    private boolean completed;

    private BoundedBodySubscriber(int maxBodyBytes, boolean declaredTooLarge) {
      this.maxBodyBytes = maxBodyBytes;
      this.declaredTooLarge = declaredTooLarge;
      this.buffer = new byte[Math.min(maxBodyBytes, 8192)];
    }

    @Override
    public CompletionStage<BoundedBody> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription candidate) {
      if (subscription != null) {
        candidate.cancel();
        return;
      }
      subscription = candidate;
      if (declaredTooLarge) {
        completeTooLarge();
        return;
      }
      candidate.request(1L);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      if (completed) {
        return;
      }
      long incomingBytes = 0L;
      for (ByteBuffer bufferPart : buffers) {
        incomingBytes += bufferPart.remaining();
        if (incomingBytes > maxBodyBytes - size) {
          completeTooLarge();
          return;
        }
      }

      int requiredSize = size + (int) incomingBytes;
      ensureCapacity(requiredSize);
      for (ByteBuffer bufferPart : buffers) {
        int partSize = bufferPart.remaining();
        bufferPart.get(buffer, size, partSize);
        size += partSize;
      }
      subscription.request(1L);
    }

    @Override
    public void onError(Throwable throwable) {
      if (!completed) {
        completed = true;
        body.completeExceptionally(throwable);
      }
    }

    @Override
    public void onComplete() {
      if (!completed) {
        completed = true;
        body.complete(new BoundedBody(Arrays.copyOf(buffer, size), false));
      }
    }

    private void ensureCapacity(int requiredSize) {
      if (requiredSize <= buffer.length) {
        return;
      }
      int doubled = buffer.length <= maxBodyBytes / 2
          ? buffer.length * 2
          : maxBodyBytes;
      int capacity = Math.min(maxBodyBytes, Math.max(requiredSize, doubled));
      buffer = Arrays.copyOf(buffer, capacity);
    }

    private void completeTooLarge() {
      if (!completed) {
        completed = true;
        size = 0;
        buffer = new byte[0];
        subscription.cancel();
        body.complete(new BoundedBody(buffer, true));
      }
    }
  }
}
