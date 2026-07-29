package com.fxplatform.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;

class ValidationDatabaseFenceTest {

  @Test
  void requestFenceUncheckedCleanupFailureStillClosesSessionWithoutEscaping()
      throws Exception {
    DataSource sessionDataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet resultSet = mock(ResultSet.class);
    when(sessionDataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(true);
    when(resultSet.getLong("generation")).thenReturn(19L);
    when(resultSet.getString("state")).thenReturn("READY");
    when(resultSet.getLong("redis_generation")).thenReturn(19L);
    when(resultSet.getLong("memory_generation")).thenReturn(19L);
    ValidationDatabaseFence fence =
        new ValidationDatabaseFence(mock(DataSource.class), sessionDataSource);
    ValidationDatabaseFence.RequestFence retained = fence.tryEnterRequest(19L).orElseThrow();
    doThrow(new IllegalStateException("rollback failed")).when(connection).rollback();
    doThrow(new IllegalStateException("close failed")).when(connection).close();

    assertThatCode(retained::close).doesNotThrowAnyException();

    InOrder release = inOrder(connection);
    release.verify(connection).rollback();
    release.verify(connection).abort(any(Executor.class));
    release.verify(connection).close();
  }

  @Test
  void requestFenceCloseFailurePhysicallyAbortsBeforeRetryingClose() throws Exception {
    DataSource sessionDataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet resultSet = mock(ResultSet.class);
    when(sessionDataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(true);
    when(resultSet.getLong("generation")).thenReturn(19L);
    when(resultSet.getString("state")).thenReturn("READY");
    when(resultSet.getLong("redis_generation")).thenReturn(19L);
    when(resultSet.getLong("memory_generation")).thenReturn(19L);
    ValidationDatabaseFence fence =
        new ValidationDatabaseFence(mock(DataSource.class), sessionDataSource);
    ValidationDatabaseFence.RequestFence retained = fence.tryEnterRequest(19L).orElseThrow();
    doThrow(new IllegalStateException("close failed")).when(connection).close();

    assertThatCode(retained::close).doesNotThrowAnyException();

    InOrder release = inOrder(connection);
    release.verify(connection).rollback();
    release.verify(connection).close();
    release.verify(connection).abort(any(Executor.class));
    release.verify(connection).close();
  }

  @ParameterizedTest
  @MethodSource("resourceCloseFailures")
  void resetLockAcquisitionFailureAlwaysAbortsAndClosesTheSession(Exception closeFailure)
      throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement acquireStatement = mock(PreparedStatement.class);
    ResultSet acquireResult = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(acquireStatement);
    when(acquireStatement.executeQuery()).thenReturn(acquireResult);
    when(acquireResult.next()).thenReturn(true);
    when(acquireResult.getBoolean(1)).thenReturn(true);
    doThrow(closeFailure).when(acquireResult).close();
    ValidationDatabaseFence fence = new ValidationDatabaseFence(dataSource);

    org.assertj.core.api.Assertions.catchThrowable(fence::tryAcquireResetOwnership);

    InOrder release = inOrder(connection);
    release.verify(connection).abort(any(Executor.class));
    release.verify(connection).close();
  }

  @Test
  void requestFenceConnectionFailureAlwaysReleasesAdmissionCapacity() throws Exception {
    DataSource sessionDataSource = mock(DataSource.class);
    when(sessionDataSource.getConnection()).thenThrow(new SQLException("unavailable"));
    ValidationDatabaseFence fence =
        new ValidationDatabaseFence(mock(DataSource.class), sessionDataSource);
    int attempts = ValidationDatabaseFence.MAX_CONCURRENT_REQUEST_FENCES + 2;

    for (int index = 0; index < attempts; index++) {
      assertThat(fence.tryEnterRequest(19L)).isEmpty();
    }

    verify(sessionDataSource, times(attempts)).getConnection();
  }

  @Test
  void requestFenceAdmissionIsBoundedAndCloseAlwaysReleasesCapacity() throws Exception {
    DataSource transactionalDataSource = mock(DataSource.class);
    DataSource sessionDataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet resultSet = mock(ResultSet.class);
    when(sessionDataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(true);
    when(resultSet.getLong("generation")).thenReturn(19L);
    when(resultSet.getString("state")).thenReturn("READY");
    when(resultSet.getLong("redis_generation")).thenReturn(19L);
    when(resultSet.getLong("memory_generation")).thenReturn(19L);
    ValidationDatabaseFence fence =
        new ValidationDatabaseFence(transactionalDataSource, sessionDataSource);
    List<ValidationDatabaseFence.RequestFence> retained = new ArrayList<>();

    for (int index = 0;
         index < ValidationDatabaseFence.MAX_CONCURRENT_REQUEST_FENCES;
         index++) {
      retained.add(fence.tryEnterRequest(19L).orElseThrow());
    }
    assertThat(fence.tryEnterRequest(19L)).isEmpty();
    verify(sessionDataSource, times(ValidationDatabaseFence.MAX_CONCURRENT_REQUEST_FENCES))
        .getConnection();
    verifyNoInteractions(transactionalDataSource);

    retained.remove(0).close();
    retained.add(fence.tryEnterRequest(19L).orElseThrow());
    retained.forEach(ValidationDatabaseFence.RequestFence::close);

    verify(sessionDataSource, times(ValidationDatabaseFence.MAX_CONCURRENT_REQUEST_FENCES + 1))
        .getConnection();
  }

  @Test
  void uncertainUnlockAbortsPhysicalSessionBeforeRecyclingPoolProxy() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement acquireStatement = mock(PreparedStatement.class);
    PreparedStatement unlockStatement = mock(PreparedStatement.class);
    ResultSet acquireResult = mock(ResultSet.class);
    ResultSet unlockResult = mock(ResultSet.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(true);
    when(connection.prepareStatement(anyString())).thenAnswer(invocation ->
        invocation.<String>getArgument(0).contains("pg_try_advisory_lock")
            ? acquireStatement
            : unlockStatement);
    when(acquireStatement.executeQuery()).thenReturn(acquireResult);
    when(acquireResult.next()).thenReturn(true);
    when(acquireResult.getBoolean(1)).thenReturn(true);
    when(unlockStatement.executeQuery()).thenReturn(unlockResult);
    when(unlockResult.next()).thenReturn(true);
    when(unlockResult.getBoolean(1)).thenReturn(false);

    ValidationDatabaseFence.ResetOwnership ownership =
        new ValidationDatabaseFence(dataSource).tryAcquireResetOwnership().orElseThrow();
    ownership.close();

    InOrder release = inOrder(connection);
    release.verify(connection).abort(any(Executor.class));
    release.verify(connection).close();
  }

  private static Stream<Exception> resourceCloseFailures() {
    return Stream.of(
        new SQLException("result close failed"),
        new IllegalStateException("result close failed"));
  }
}
