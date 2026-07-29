package com.fxplatform.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.connection.RedisStringCommands;

class ValidationRedisResetterTest {

  private static final byte[] GENERATION = bytes("validation:reset:generation");
  private static final byte[] EPOCH = bytes("validation:reset:epoch");

  @Test
  void exactEpochRetryIsANoOpAndDoesNotDeleteLateData() {
    Fixture fixture = new Fixture("7", "11");

    assertThat(fixture.resetter.resetToGeneration(7L, 11L)).isEqualTo(7L);

    verify(fixture.server, never()).flushDb();
    verify(fixture.connection, never()).multi();
    verify(fixture.connection, never()).exec();
  }

  @Test
  void staleEpochIsRejectedBeforeFlush() {
    Fixture fixture = new Fixture("7", "12");

    assertThatThrownBy(() -> fixture.resetter.resetToGeneration(7L, 11L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stale");

    verify(fixture.server, never()).flushDb();
  }

  @Test
  void newerEpochAtomicallyFlushesAndPublishesTheFrozenTarget() {
    Fixture fixture = new Fixture("6", "10");
    when(fixture.connection.exec()).thenReturn(List.of("OK", true, true));

    assertThat(fixture.resetter.resetToGeneration(7L, 11L)).isEqualTo(7L);

    verify(fixture.connection).watch(GENERATION, EPOCH);
    verify(fixture.connection).multi();
    verify(fixture.server).flushDb();
    verify(fixture.strings).set(GENERATION, bytes("7"));
    verify(fixture.strings).set(EPOCH, bytes("11"));
    verify(fixture.connection).exec();
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static final class Fixture {

    private final RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
    private final RedisConnection connection = mock(RedisConnection.class);
    private final RedisStringCommands strings = mock(RedisStringCommands.class);
    private final RedisServerCommands server = mock(RedisServerCommands.class);
    private final ValidationRedisResetter resetter;

    private Fixture(String generation, String epoch) {
      when(factory.getConnection()).thenReturn(connection);
      when(connection.stringCommands()).thenReturn(strings);
      when(connection.serverCommands()).thenReturn(server);
      when(strings.get(GENERATION)).thenReturn(bytes(generation));
      when(strings.get(EPOCH)).thenReturn(bytes(epoch));
      resetter = new ValidationRedisResetter(factory, 0);
    }
  }
}
