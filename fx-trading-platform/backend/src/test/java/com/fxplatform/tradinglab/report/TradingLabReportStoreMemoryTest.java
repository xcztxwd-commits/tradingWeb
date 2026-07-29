package com.fxplatform.tradinglab.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.tradinglab.entity.TradingLabReportEntity;
import com.fxplatform.tradinglab.repository.TradingLabReportAppendRepository;
import com.fxplatform.tradinglab.repository.TradingLabReportChunkRepository;
import com.fxplatform.tradinglab.repository.TradingLabReportRepository;
import com.fxplatform.tradinglab.repository.TradingLabReportWriteFenceRepository;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TradingLabReportStoreMemoryTest {

  private static final int LOGICAL_BYTES = 16 * 1024 * 1024;
  private static final int CHUNK_BYTES = 1024 * 1024;

  @Test
  void persistsSixteenMiBLogicalValueWithOneMiBChunksUnderBoundedHeap() {
    UUID reportId = UUID.randomUUID();
    TradingLabReportEntity report = new TradingLabReportEntity();
    report.setId(reportId);
    report.setStatus("PENDING");
    report.setVersion(0L);
    AtomicInteger insertedChunks = new AtomicInteger();
    AtomicLong insertedRawBytes = new AtomicLong();

    TradingLabReportRepository reports = proxy(
        TradingLabReportRepository.class,
        (method, arguments) -> switch (method.getName()) {
          case "lockById" -> Optional.of(report);
          case "addChunkTotals" -> {
            insertedRawBytes.set((long) arguments[1]);
            yield 1;
          }
          default -> defaultValue(method.getReturnType());
        });
    TradingLabReportChunkRepository chunks = proxy(
        TradingLabReportChunkRepository.class,
        (method, arguments) -> switch (method.getName()) {
          case "sequenceState" -> new TradingLabReportChunkSequenceState(0L, null, null);
          case "insertIfAbsent" -> {
            insertedChunks.incrementAndGet();
            yield 1;
          }
          default -> defaultValue(method.getReturnType());
        });
    TradingLabReportAppendRepository appends = proxy(
        TradingLabReportAppendRepository.class,
        (method, arguments) -> defaultValue(method.getReturnType()));
    TradingLabReportWriteFenceRepository fences = proxy(
        TradingLabReportWriteFenceRepository.class,
        (method, arguments) -> "lockRunForReport".equals(method.getName())
            ? Optional.empty()
            : defaultValue(method.getReturnType()));
    MyBatisTradingLabReportStore store = new MyBatisTradingLabReportStore(
        reports,
        chunks,
        appends,
        fences,
        new TradingLabReportChunkCodec(CHUNK_BYTES));

    byte[] logical = incompressibleAscii(LOGICAL_BYTES);
    TradingLabCanonicalValue canonical = new TradingLabCanonicalValue(
        logical, sha256(logical));
    store.persist(new TradingLabReportWriteBatch(
        reportId,
        null,
        List.of(new TradingLabReportSectionWrite(
            TradingLabReportSection.MARKET_TICKS,
            0L,
            List.of(new TradingLabLogicalAppend(null, canonical))))));

    assertThat(insertedChunks).hasValue(16);
    assertThat(insertedRawBytes).hasValue(LOGICAL_BYTES);
  }

  private static byte[] incompressibleAscii(int length) {
    byte[] value = new byte[length];
    int state = 0x6d2b79f5;
    for (int index = 0; index < value.length; index++) {
      state ^= state << 13;
      state ^= state >>> 17;
      state ^= state << 5;
      value[index] = (byte) (32 + Math.floorMod(state, 95));
    }
    return value;
  }

  private static String sha256(byte[] value) {
    try {
      return "sha256:" + HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, Invocation invocation) {
    return (T) Proxy.newProxyInstance(
        type.getClassLoader(),
        new Class<?>[] {type},
        (proxy, method, arguments) -> {
          if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
              case "toString" -> type.getSimpleName() + " memory probe";
              case "hashCode" -> System.identityHashCode(proxy);
              case "equals" -> proxy == arguments[0];
              default -> throw new UnsupportedOperationException(method.getName());
            };
          }
          return invocation.invoke(method, arguments == null ? new Object[0] : arguments);
        });
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == int.class) {
      return 0;
    }
    throw new UnsupportedOperationException(type.getName());
  }

  @FunctionalInterface
  private interface Invocation {
    Object invoke(java.lang.reflect.Method method, Object[] arguments) throws Throwable;
  }
}
