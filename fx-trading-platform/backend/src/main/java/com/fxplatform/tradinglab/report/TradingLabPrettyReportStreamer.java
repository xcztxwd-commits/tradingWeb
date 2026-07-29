package com.fxplatform.tradinglab.report;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/**
 * Incrementally transforms the compact report stream into pretty UTF-8 JSON.
 */
@Service
public class TradingLabPrettyReportStreamer {

  private static final int PIPE_BYTES = 64 * 1024;

  private final TradingLabReportStreamer compactStreamer;
  private final JsonFactory jsonFactory;

  public TradingLabPrettyReportStreamer(TradingLabReportStreamer compactStreamer) {
    this.compactStreamer = Objects.requireNonNull(compactStreamer, "compactStreamer");
    this.jsonFactory = new JsonFactory();
  }

  public void stream(
      TradingLabReportReadTicket ticket,
      OutputStream destination
  ) throws IOException {
    Objects.requireNonNull(ticket, "ticket");
    Objects.requireNonNull(destination, "destination");

    AtomicReference<Throwable> producerFailure = new AtomicReference<>();
    try (PipedInputStream input = new PipedInputStream(PIPE_BYTES)) {
      PipedOutputStream pipe = new PipedOutputStream(input);
      Thread producer = Thread.ofVirtual()
          .name("trading-lab-report-pretty-" + ticket.reportId())
          .start(() -> produceCompact(ticket, pipe, producerFailure));

      IOException consumerFailure = null;
      try {
        transform(input, destination);
      } catch (IOException exception) {
        consumerFailure = exception;
        input.close();
      }
      awaitProducer(producer, input);

      Throwable failure = producerFailure.get();
      if (failure instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (consumerFailure != null) {
        throw consumerFailure;
      }
      if (failure instanceof IOException ioException) {
        throw ioException;
      }
      if (failure != null) {
        throw new IOException("Trading Lab compact report producer failed", failure);
      }
    }
  }

  private void produceCompact(
      TradingLabReportReadTicket ticket,
      PipedOutputStream pipe,
      AtomicReference<Throwable> producerFailure
  ) {
    try (pipe) {
      compactStreamer.stream(ticket, pipe);
    } catch (Throwable failure) {
      producerFailure.compareAndSet(null, failure);
    }
  }

  private void transform(
      PipedInputStream input,
      OutputStream destination
  ) throws IOException {
    try (JsonParser parser = jsonFactory.createParser(input)) {
      JsonGenerator generator =
          jsonFactory.createGenerator(destination, JsonEncoding.UTF8);
      generator.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
      generator.setPrettyPrinter(new DefaultPrettyPrinter());
      while (parser.nextToken() != null) {
        generator.copyCurrentEvent(parser);
      }
      generator.flush();
    }
  }

  private void awaitProducer(
      Thread producer,
      PipedInputStream input
  ) throws IOException {
    try {
      producer.join();
    } catch (InterruptedException exception) {
      input.close();
      producer.interrupt();
      Thread.currentThread().interrupt();
      throw new IOException("Trading Lab pretty report streaming was interrupted", exception);
    }
  }
}
