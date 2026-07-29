package com.fxplatform.tradinglab.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TradingLabPrettyReportStreamerTest {

  @Test
  void prettyTransformProducesOutputBeforeCompactProducerFinishes() throws Exception {
    UUID reportId = UUID.randomUUID();
    TradingLabReportReadTicket ticket =
        new TradingLabReportReadTicket(reportId, 3L, 17L, 11L, 1);
    TradingLabReportStreamer compact = mock(TradingLabReportStreamer.class);
    CountDownLatch prettyOutputObserved = new CountDownLatch(1);
    doAnswer(invocation -> {
      OutputStream pipe = invocation.getArgument(1);
      StringBuilder prefix = new StringBuilder("[");
      for (int index = 0; index < 2_000; index++) {
        if (index > 0) {
          prefix.append(',');
        }
        prefix.append("{\"n\":").append(index).append('}');
      }
      prefix.append(',');
      pipe.write(prefix.toString().getBytes(StandardCharsets.UTF_8));
      if (!prettyOutputObserved.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("Pretty output waited for the whole compact report");
      }
      pipe.write("{\"n\":2000}]".getBytes(StandardCharsets.UTF_8));
      return null;
    }).when(compact).stream(eq(ticket), any(OutputStream.class));
    TradingLabPrettyReportStreamer pretty =
        new TradingLabPrettyReportStreamer(compact);
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    OutputStream observing = new OutputStream() {
      @Override
      public void write(int value) {
        captured.write(value);
        prettyOutputObserved.countDown();
      }

      @Override
      public void write(byte[] bytes, int offset, int length) {
        captured.write(bytes, offset, length);
        if (length > 0) {
          prettyOutputObserved.countDown();
        }
      }
    };

    pretty.stream(ticket, observing);

    String rendered = captured.toString(StandardCharsets.UTF_8);
    assertThat(rendered).contains("\n").contains("\"n\"");
    JsonNode parsed = new ObjectMapper().readTree(rendered);
    assertThat(parsed).hasSize(2_001);
    assertThat(parsed.get(0).get("n").asInt()).isZero();
    assertThat(parsed.get(2_000).get("n").asInt()).isEqualTo(2_000);
  }
}
