package com.fxplatform.validation.service;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL-backed reset linearization point shared by every validation backend instance.
 *
 * <p>Ordinary mutations take a shared row lock inside their transaction. Loopback handlers keep
 * the same lock for the whole HTTP request. Reset ownership is a live PostgreSQL session lock bound
 * to the one physical connection used for every destructive database step.
 */
@Profile("validation")
@Component
public class ValidationDatabaseFence {

  private static final int ADVISORY_NAMESPACE = 0x56414C49; // "VALI"
  private static final int RESET_OWNER_KEY = 0x52535431; // "RST1"
  private static final int RUN_OWNER_KEY = 0x52554E31; // "RUN1"
  static final int MAX_CONCURRENT_REQUEST_FENCES = 8;
  private static final String LOCK_RESET_STATE = """
      SELECT generation, state, redis_generation, memory_generation
        FROM validation_control.reset_state
       WHERE singleton_key = 1
       FOR SHARE
      """;

  private final DataSource sessionDataSource;
  private final JdbcTemplate jdbc;
  private final Semaphore requestFencePermits =
      new Semaphore(MAX_CONCURRENT_REQUEST_FENCES, true);

  @Autowired
  public ValidationDatabaseFence(DataSource dataSource) {
    this(dataSource, isolatedSessionDataSource(dataSource));
  }

  /**
   * Uses the first source for caller-bound transactions and the second for retained lock sessions.
   * Both sources must target the same validation database.
   */
  public ValidationDatabaseFence(
      DataSource transactionalDataSource,
      DataSource sessionDataSource
  ) {
    this.sessionDataSource = Objects.requireNonNull(sessionDataSource, "sessionDataSource");
    this.jdbc = new JdbcTemplate(Objects.requireNonNull(
        transactionalDataSource,
        "transactionalDataSource"));
  }

  /** Acquires the reset row lock on the caller's current Spring transaction. */
  public void requireReadyGeneration(long requiredGeneration) {
    ResetState state = jdbc.query(LOCK_RESET_STATE, ValidationDatabaseFence::mapState)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "Validation durable reset state is unavailable"));
    requireReady(state, requiredGeneration);
  }

  /**
   * Starts a dedicated request transaction and retains its shared reset-row lock until close.
   */
  public Optional<RequestFence> tryEnterRequest(long requiredGeneration) {
    if (!tryAcquireRequestPermit()) {
      return Optional.empty();
    }
    Connection connection = null;
    boolean retained = false;
    try {
      connection = sessionDataSource.getConnection();
      connection.setAutoCommit(false);
      ResetState state = queryState(connection);
      if (!ready(state, requiredGeneration)) {
        rollbackAndClose(connection);
        return Optional.empty();
      }
      RequestFence fence = new RequestFence(connection, requestFencePermits);
      retained = true;
      return Optional.of(fence);
    } catch (SQLException | RuntimeException failure) {
      rollbackAndClose(connection);
      return Optional.empty();
    } finally {
      if (!retained) {
        requestFencePermits.release();
      }
    }
  }

  /**
   * Tries to own reset through a PostgreSQL session-scoped advisory lock.
   * The returned capability retains the exact physical session until the terminal receipt.
   */
  public Optional<ResetOwnership> tryAcquireResetOwnership() {
    Connection connection = null;
    try {
      connection = sessionDataSource.getConnection();
      connection.setAutoCommit(true);
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT pg_try_advisory_lock(?, ?)")) {
        statement.setInt(1, ADVISORY_NAMESPACE);
        statement.setInt(2, RESET_OWNER_KEY);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next() || !resultSet.getBoolean(1)) {
            closeConnection(connection);
            return Optional.empty();
          }
        }
      }
      return Optional.of(new ResetOwnership(connection));
    } catch (SQLException | RuntimeException failure) {
      abortAndCloseConnection(connection);
      throw failure instanceof RuntimeException runtimeFailure
          ? runtimeFailure
          : new IllegalStateException("Validation reset ownership is unavailable", failure);
    }
  }

  private static ResetState queryState(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(LOCK_RESET_STATE);
         ResultSet resultSet = statement.executeQuery()) {
      if (!resultSet.next()) {
        throw new IllegalStateException("Validation durable reset state is unavailable");
      }
      return new ResetState(
          resultSet.getLong("generation"),
          resultSet.getString("state"),
          nullableLong(resultSet, "redis_generation"),
          nullableLong(resultSet, "memory_generation"));
    }
  }

  private static ResetState mapState(ResultSet resultSet, int ignored) throws SQLException {
    return new ResetState(
        resultSet.getLong("generation"),
        resultSet.getString("state"),
        nullableLong(resultSet, "redis_generation"),
        nullableLong(resultSet, "memory_generation"));
  }

  private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
    long value = resultSet.getLong(column);
    return resultSet.wasNull() ? null : value;
  }

  private static void requireReady(ResetState state, long requiredGeneration) {
    if (!ready(state, requiredGeneration)) {
      throw new IllegalStateException("Validation generation is not ready");
    }
  }

  private static boolean ready(ResetState state, long requiredGeneration) {
    return state != null
        && requiredGeneration > 0L
        && state.generation() == requiredGeneration
        && "READY".equals(state.state())
        && Long.valueOf(requiredGeneration).equals(state.redisGeneration())
        && Long.valueOf(requiredGeneration).equals(state.memoryGeneration());
  }

  private static void rollbackAndClose(Connection connection) {
    if (connection == null) {
      return;
    }
    boolean rolledBack = false;
    try {
      connection.rollback();
      rolledBack = true;
    } catch (SQLException | RuntimeException ignored) {
      // An uncertain rollback requires physical session termination below.
    }
    if (rolledBack) {
      closeConnection(connection);
    } else {
      abortAndCloseConnection(connection);
    }
  }

  private static void closeConnection(Connection connection) {
    if (connection == null) {
      return;
    }
    if (!tryCloseConnection(connection)) {
      abortAndCloseConnection(connection);
    }
  }

  private static boolean tryCloseConnection(Connection connection) {
    try {
      connection.close();
      return true;
    } catch (SQLException | RuntimeException ignored) {
      return false;
    }
  }

  private static void abortAndCloseConnection(Connection connection) {
    if (connection == null) {
      return;
    }
    try {
      connection.abort(Runnable::run);
    } catch (SQLException | RuntimeException ignored) {
      // Closing below is still required to recycle a pool proxy.
    } finally {
      tryCloseConnection(connection);
    }
  }

  private static DataSource isolatedSessionDataSource(DataSource transactionalDataSource) {
    Objects.requireNonNull(transactionalDataSource, "transactionalDataSource");
    HikariDataSource hikari = unwrapHikari(transactionalDataSource);
    if (hikari == null) {
      return transactionalDataSource;
    }
    String jdbcUrl = hikari.getJdbcUrl();
    if (jdbcUrl == null || jdbcUrl.isBlank()) {
      throw new IllegalStateException(
          "Validation fence sessions require a JDBC URL when Hikari is configured");
    }
    DriverManagerDataSource isolated = new DriverManagerDataSource();
    if (hikari.getDriverClassName() != null && !hikari.getDriverClassName().isBlank()) {
      isolated.setDriverClassName(hikari.getDriverClassName());
    }
    isolated.setUrl(jdbcUrl);
    isolated.setUsername(hikari.getUsername());
    isolated.setPassword(hikari.getPassword());
    Properties connectionProperties = new Properties();
    connectionProperties.putAll(hikari.getDataSourceProperties());
    isolated.setConnectionProperties(connectionProperties);
    return isolated;
  }

  private static HikariDataSource unwrapHikari(DataSource dataSource) {
    if (dataSource instanceof HikariDataSource hikari) {
      return hikari;
    }
    try {
      return dataSource.isWrapperFor(HikariDataSource.class)
          ? dataSource.unwrap(HikariDataSource.class)
          : null;
    } catch (SQLException ignored) {
      return null;
    }
  }

  private boolean tryAcquireRequestPermit() {
    try {
      return requestFencePermits.tryAcquire(0L, TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private record ResetState(
      long generation,
      String state,
      Long redisGeneration,
      Long memoryGeneration
  ) {
  }

  public static final class RequestFence implements AutoCloseable {

    private final Connection connection;
    private final Semaphore permit;
    private final AtomicBoolean closed = new AtomicBoolean();

    private RequestFence(Connection connection, Semaphore permit) {
      this.connection = connection;
      this.permit = permit;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        try {
          rollbackAndClose(connection);
        } finally {
          permit.release();
        }
      }
    }
  }

  public static final class ResetOwnership implements AutoCloseable {

    private final Connection connection;
    private final SingleConnectionDataSource ownerDataSource;
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean runOwnershipHeld;

    private ResetOwnership(Connection connection) {
      this.connection = connection;
      this.ownerDataSource = new SingleConnectionDataSource(connection, true);
    }

    public synchronized void requireAlive() {
      if (closed.get()) {
        throw new IllegalStateException("Validation reset ownership is unavailable");
      }
      try {
        if (!connection.isValid(2)) {
          throw new IllegalStateException("Validation reset ownership is unavailable");
        }
        if (!holdsAdvisoryLock(RESET_OWNER_KEY)
            || (runOwnershipHeld && !holdsAdvisoryLock(RUN_OWNER_KEY))) {
          throw new IllegalStateException("Validation reset ownership is unavailable");
        }
      } catch (SQLException failure) {
        throw new IllegalStateException("Validation reset ownership is unavailable", failure);
      }
    }

    synchronized void awaitRunOwnership(Duration timeout) {
      requireAlive();
      if (runOwnershipHeld) {
        return;
      }
      long deadline = System.nanoTime() + timeout.toNanos();
      while (true) {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT pg_try_advisory_lock(?, ?)")) {
          statement.setInt(1, ADVISORY_NAMESPACE);
          statement.setInt(2, RUN_OWNER_KEY);
          try (ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next() && resultSet.getBoolean(1)) {
              runOwnershipHeld = true;
              return;
            }
          }
        } catch (SQLException failure) {
          throw new IllegalStateException("Validation run ownership drain failed", failure);
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) {
          throw new IllegalStateException("Validation run ownership did not drain before reset");
        }
        try {
          TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25L)));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Validation run ownership drain was interrupted");
        }
        requireAlive();
      }
    }

    synchronized <T> T inTransaction(SqlWork<T> work) {
      requireAlive();
      try {
        if (!connection.getAutoCommit()) {
          throw new IllegalStateException("Validation reset owner session is already in a transaction");
        }
        connection.setAutoCommit(false);
        try {
          T result = work.execute(connection);
          connection.commit();
          return result;
        } catch (SQLException failure) {
          rollback(connection);
          throw new IllegalStateException("Validation reset owner transaction failed", failure);
        } catch (RuntimeException failure) {
          rollback(connection);
          throw failure;
        } finally {
          restoreAutoCommit(connection);
        }
      } catch (SQLException failure) {
        throw new IllegalStateException("Validation reset owner transaction failed", failure);
      }
    }

    synchronized DataSource dataSource() {
      requireAlive();
      return ownerDataSource;
    }

    synchronized int backendPid() {
      requireAlive();
      try (PreparedStatement statement = connection.prepareStatement("SELECT pg_backend_pid()");
             ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new IllegalStateException("Validation reset ownership is unavailable");
        }
        return resultSet.getInt(1);
      } catch (SQLException failure) {
        throw new IllegalStateException("Validation reset ownership is unavailable", failure);
      }
    }

    @Override
    public synchronized void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      boolean unlocked = false;
      try {
        if (!connection.getAutoCommit()) {
          rollback(connection);
          restoreAutoCommit(connection);
        }
        boolean runUnlocked = !runOwnershipHeld || unlock(RUN_OWNER_KEY);
        boolean resetUnlocked = unlock(RESET_OWNER_KEY);
        unlocked = runUnlocked && resetUnlocked;
      } catch (SQLException | RuntimeException ignored) {
        // A session whose unlock result is uncertain must be physically aborted below.
      } finally {
        if (unlocked) {
          closeConnection(connection);
        } else {
          abortAndCloseConnection(connection);
        }
      }
    }

    private boolean holdsAdvisoryLock(int key) throws SQLException {
      try (PreparedStatement statement = connection.prepareStatement("""
          SELECT EXISTS (
            SELECT 1
              FROM pg_locks
             WHERE locktype = 'advisory'
               AND pid = pg_backend_pid()
               AND classid::bigint = ?
               AND objid::bigint = ?
               AND objsubid = 2
               AND granted
          )
          """)) {
        statement.setInt(1, ADVISORY_NAMESPACE);
        statement.setInt(2, key);
        try (ResultSet resultSet = statement.executeQuery()) {
          return resultSet.next() && resultSet.getBoolean(1);
        }
      }
    }

    private boolean unlock(int key) throws SQLException {
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT pg_advisory_unlock(?, ?)")) {
        statement.setInt(1, ADVISORY_NAMESPACE);
        statement.setInt(2, key);
        try (ResultSet resultSet = statement.executeQuery()) {
          return resultSet.next() && resultSet.getBoolean(1);
        }
      }
    }

    private static void rollback(Connection connection) {
      try {
        connection.rollback();
      } catch (SQLException | RuntimeException ignored) {
        // The caller will fail closed and the owner session will be aborted on close.
      }
    }

    private static void restoreAutoCommit(Connection connection) {
      try {
        connection.setAutoCommit(true);
      } catch (SQLException | RuntimeException ignored) {
        // requireAlive will reject the damaged capability before another operation.
      }
    }

    @FunctionalInterface
    interface SqlWork<T> {

      T execute(Connection connection) throws SQLException;
    }
  }
}
