package com.fxplatform.validation.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.validation.service.ValidationDemoExecutionPolicyProvider;
import com.fxplatform.validation.service.ValidationMarketState;
import com.fxplatform.validation.service.ValidationResetGate;
import com.fxplatform.validation.service.ValidationRunEventStore;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.LeaseClaim;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcValidationRunRuntimeStoreTest {

  @Test
  @SuppressWarnings("unchecked")
  void uncheckedLivenessFailureRemovesAndClosesRunOwnership() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.getDataSource()).thenReturn(dataSource);
    JdbcValidationRunRuntimeStore store = new JdbcValidationRunRuntimeStore(
        jdbc,
        new ObjectMapper(),
        mock(ValidationResetGate.class),
        mock(ValidationMarketState.class),
        mock(ValidationDemoExecutionPolicyProvider.class),
        mock(ValidationRunEventStore.class));
    Connection connection = mock(Connection.class);
    when(connection.isValid(2)).thenThrow(new IllegalStateException("isValid failed"));
    when(connection.getAutoCommit()).thenReturn(true);
    PreparedStatement unlock = mock(PreparedStatement.class);
    ResultSet unlockResult = mock(ResultSet.class);
    when(connection.prepareStatement("SELECT pg_advisory_unlock(?, ?)"))
        .thenReturn(unlock);
    when(unlock.executeQuery()).thenReturn(unlockResult);
    when(unlockResult.next()).thenReturn(true);
    when(unlockResult.getBoolean(1)).thenReturn(false);
    LeaseClaim claim = new LeaseClaim(
        UUID.fromString("00000000-0000-0000-0000-000000000541"),
        7L,
        "owner",
        UUID.fromString("00000000-0000-0000-0000-000000000542"));
    Class<?> ownershipType = Arrays.stream(JdbcValidationRunRuntimeStore.class.getDeclaredClasses())
        .filter(type -> type.getSimpleName().equals("RunOwnership"))
        .findFirst()
        .orElseThrow();
    Constructor<?> constructor = ownershipType.getDeclaredConstructor(
        JdbcValidationRunRuntimeStore.class,
        Connection.class,
        LeaseClaim.class);
    constructor.setAccessible(true);
    Object ownership = constructor.newInstance(store, connection, claim);
    Field liveLeasesField = JdbcValidationRunRuntimeStore.class.getDeclaredField("liveLeases");
    liveLeasesField.setAccessible(true);
    ConcurrentMap<UUID, Object> liveLeases =
        (ConcurrentMap<UUID, Object>) liveLeasesField.get(store);
    liveLeases.put(claim.token(), ownership);

    org.assertj.core.api.Assertions.catchThrowable(() -> store.heartbeat(claim));

    assertThat(liveLeases).isEmpty();
    verify(connection).abort(any(Executor.class));
    verify(connection).close();
  }

  @ParameterizedTest
  @MethodSource("resourceCloseFailures")
  void runLockAcquisitionFailureAlwaysAbortsAndClosesTheSession(Exception closeFailure)
      throws Exception {
    UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000521");
    DataSource dataSource = mock(DataSource.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.getDataSource()).thenReturn(dataSource);
    when(jdbc.query(
        anyString(),
        org.mockito.ArgumentMatchers.<RowMapper<Long>>any(),
        any(UUID.class)))
        .thenReturn(List.of(7L));
    Connection connection = mock(Connection.class);
    when(dataSource.getConnection()).thenReturn(connection);
    PreparedStatement lock = mock(PreparedStatement.class);
    ResultSet lockResult = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(lock);
    when(lock.executeQuery()).thenReturn(lockResult);
    when(lockResult.next()).thenReturn(true);
    when(lockResult.getBoolean(1)).thenReturn(true);
    doThrow(closeFailure).when(lockResult).close();
    JdbcValidationRunRuntimeStore store = new JdbcValidationRunRuntimeStore(
        jdbc,
        new ObjectMapper(),
        mock(ValidationResetGate.class),
        mock(ValidationMarketState.class),
        mock(ValidationDemoExecutionPolicyProvider.class),
        mock(ValidationRunEventStore.class));

    org.assertj.core.api.Assertions.catchThrowable(() -> store.claim(runId));

    InOrder release = inOrder(connection);
    release.verify(connection).abort(any(Executor.class));
    release.verify(connection).close();
  }

  @Test
  void rollbackCleanupFailureIsSuppressedUnderTheOriginalClaimFailure() throws Exception {
    UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000531");
    IllegalStateException original = new IllegalStateException("claim row failed");
    IllegalStateException cleanup = new IllegalStateException("rollback failed");
    DataSource dataSource = mock(DataSource.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.getDataSource()).thenReturn(dataSource);
    when(jdbc.query(
        anyString(),
        org.mockito.ArgumentMatchers.<RowMapper<Long>>any(),
        any(UUID.class)))
        .thenReturn(List.of(7L));
    Connection connection = mock(Connection.class);
    when(dataSource.getConnection()).thenReturn(connection);
    PreparedStatement lock = mock(PreparedStatement.class);
    ResultSet lockResult = mock(ResultSet.class);
    when(lock.executeQuery()).thenReturn(lockResult);
    when(lockResult.next()).thenReturn(true);
    when(lockResult.getBoolean(1)).thenReturn(true);
    PreparedStatement unlock = mock(PreparedStatement.class);
    ResultSet unlockResult = mock(ResultSet.class);
    when(unlock.executeQuery()).thenReturn(unlockResult);
    when(unlockResult.next()).thenReturn(true);
    when(unlockResult.getBoolean(1)).thenReturn(true);
    when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (sql.contains("pg_try_advisory_lock")) {
        return lock;
      }
      if (sql.contains("validation_control.reset_state")) {
        throw original;
      }
      if (sql.contains("pg_advisory_unlock")) {
        return unlock;
      }
      throw new AssertionError("Unexpected SQL: " + sql);
    });
    when(connection.getAutoCommit()).thenReturn(true);
    doThrow(cleanup).when(connection).rollback();
    JdbcValidationRunRuntimeStore store = new JdbcValidationRunRuntimeStore(
        jdbc,
        new ObjectMapper(),
        mock(ValidationResetGate.class),
        mock(ValidationMarketState.class),
        mock(ValidationDemoExecutionPolicyProvider.class),
        mock(ValidationRunEventStore.class));

    Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> store.claim(runId));

    assertThat(thrown).isSameAs(original);
    assertThat(thrown.getSuppressed()).containsExactly(cleanup);
    verify(connection).close();
  }

  @Test
  void ownershipCleanupFailuresCannotReplaceTheOriginalClaimFailure() throws Exception {
    UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000511");
    IllegalStateException original = new IllegalStateException("claim row failed");
    DataSource dataSource = mock(DataSource.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.getDataSource()).thenReturn(dataSource);
    when(jdbc.query(
        anyString(),
        org.mockito.ArgumentMatchers.<RowMapper<Long>>any(),
        any(UUID.class)))
        .thenReturn(List.of(7L));
    Connection connection = mock(Connection.class);
    when(dataSource.getConnection()).thenReturn(connection);
    PreparedStatement lock = mock(PreparedStatement.class);
    ResultSet lockResult = mock(ResultSet.class);
    when(lock.executeQuery()).thenReturn(lockResult);
    when(lockResult.next()).thenReturn(true);
    when(lockResult.getBoolean(1)).thenReturn(true);
    PreparedStatement unlock = mock(PreparedStatement.class);
    ResultSet unlockResult = mock(ResultSet.class);
    when(unlock.executeQuery()).thenReturn(unlockResult);
    when(unlockResult.next()).thenReturn(true);
    when(unlockResult.getBoolean(1)).thenReturn(false);
    when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
      String sql = invocation.getArgument(0);
      if (sql.contains("pg_try_advisory_lock")) {
        return lock;
      }
      if (sql.contains("validation_control.reset_state")) {
        throw original;
      }
      if (sql.contains("pg_advisory_unlock")) {
        return unlock;
      }
      throw new AssertionError("Unexpected SQL: " + sql);
    });
    when(connection.getAutoCommit()).thenReturn(true);
    doThrow(new IllegalStateException("abort failed"))
        .when(connection).abort(any(Executor.class));
    doThrow(new IllegalStateException("close failed")).when(connection).close();
    JdbcValidationRunRuntimeStore store = new JdbcValidationRunRuntimeStore(
        jdbc,
        new ObjectMapper(),
        mock(ValidationResetGate.class),
        mock(ValidationMarketState.class),
        mock(ValidationDemoExecutionPolicyProvider.class),
        mock(ValidationRunEventStore.class));

    Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> store.claim(runId));

    assertThat(thrown).isSameAs(original);
    InOrder release = inOrder(connection);
    release.verify(connection).abort(any(Executor.class));
    release.verify(connection).close();
  }

  @Test
  void abortRuntimeFailureCannotSkipClosingUncertainRunOwnershipSession() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.getDataSource()).thenReturn(dataSource);
    JdbcValidationRunRuntimeStore store = new JdbcValidationRunRuntimeStore(
        jdbc,
        new ObjectMapper(),
        mock(ValidationResetGate.class),
        mock(ValidationMarketState.class),
        mock(ValidationDemoExecutionPolicyProvider.class),
        mock(ValidationRunEventStore.class));

    Connection connection = mock(Connection.class);
    PreparedStatement unlock = mock(PreparedStatement.class);
    ResultSet unlockResult = mock(ResultSet.class);
    when(connection.getAutoCommit()).thenReturn(true);
    when(connection.prepareStatement("SELECT pg_advisory_unlock(?, ?)"))
        .thenReturn(unlock);
    when(unlock.executeQuery()).thenReturn(unlockResult);
    when(unlockResult.next()).thenReturn(true);
    when(unlockResult.getBoolean(1)).thenReturn(false);
    doThrow(new IllegalStateException("abort failed"))
        .when(connection).abort(any(Executor.class));

    LeaseClaim claim = new LeaseClaim(
        UUID.fromString("00000000-0000-0000-0000-000000000501"),
        7L,
        "owner",
        UUID.fromString("00000000-0000-0000-0000-000000000502"));
    Class<?> ownershipType = Arrays.stream(JdbcValidationRunRuntimeStore.class.getDeclaredClasses())
        .filter(type -> type.getSimpleName().equals("RunOwnership"))
        .findFirst()
        .orElseThrow();
    Constructor<?> constructor = ownershipType.getDeclaredConstructor(
        JdbcValidationRunRuntimeStore.class,
        Connection.class,
        LeaseClaim.class);
    constructor.setAccessible(true);
    AutoCloseable ownership = (AutoCloseable) constructor.newInstance(store, connection, claim);

    assertThatCode(ownership::close).doesNotThrowAnyException();
    verify(connection).abort(any(Executor.class));
    verify(connection).close();
  }

  private static Stream<Exception> resourceCloseFailures() {
    return Stream.of(
        new java.sql.SQLException("result close failed"),
        new IllegalStateException("result close failed"));
  }
}
