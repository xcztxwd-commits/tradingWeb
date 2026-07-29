package com.fxplatform.tradinglab.queue;

import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.tradinglab.entity.TradingLabRunEntity;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TradingLabQueueRepository extends FxBaseMapper<TradingLabRunEntity> {

  @Select("""
      SELECT pg_try_advisory_xact_lock(7412918473123456)
      """)
  boolean tryAcquireSingletonTransactionLock();

  @Select("""
      WITH candidate AS MATERIALIZED (
        SELECT singleton.id
        FROM trading_lab.runs singleton
        WHERE singleton.lease_key = 1
        ORDER BY singleton.id
        LIMIT 1
      )
      SELECT run.id, run.state, run.queue_sequence,
             run.lease_key, run.lease_owner, run.lease_until, run.version
      FROM trading_lab.runs run
      JOIN candidate ON candidate.id = run.id
      WHERE run.lease_key = 1
      FOR UPDATE OF run SKIP LOCKED
      """)
  Optional<TradingLabRunEntity> findSingletonLeaseForUpdate();

  @Select("""
      SELECT count(*)
      FROM trading_lab.runs
      WHERE lease_key = 1
      """)
  int countSingletonLeaseRows();

  @Select("""
      SELECT id, state, queue_sequence, lease_key, lease_owner, lease_until, version
      FROM trading_lab.runs
      WHERE id = #{runId}
      FOR UPDATE
      """)
  Optional<TradingLabRunEntity> findByIdForUpdate(@Param("runId") UUID runId);

  @Select("""
      SELECT id, state, queue_sequence, lease_key, lease_owner, lease_until, version
      FROM trading_lab.runs
      WHERE id = #{runId}
      """)
  Optional<TradingLabRunEntity> findClaimRow(@Param("runId") UUID runId);

  @Select("""
      WITH candidate AS MATERIALIZED (
        SELECT recovery.id
        FROM trading_lab.runs recovery
        WHERE recovery.lease_key IS NULL
          AND recovery.state IN ('RESETTING', 'RUNNING', 'PAUSED', 'CANCELLING', 'CLEANING')
        ORDER BY recovery.queue_sequence
        LIMIT 1
      )
      SELECT run.id, run.state, run.queue_sequence,
             run.lease_key, run.lease_owner, run.lease_until, run.version
      FROM trading_lab.runs run
      JOIN candidate ON candidate.id = run.id
      WHERE run.lease_key IS NULL
        AND run.state IN ('RESETTING', 'RUNNING', 'PAUSED', 'CANCELLING', 'CLEANING')
      FOR UPDATE OF run SKIP LOCKED
      """)
  Optional<TradingLabRunEntity> findRecoverableUnleasedForUpdate();

  @Select("""
      SELECT count(*)
      FROM trading_lab.runs
      WHERE lease_key IS NULL
        AND state IN ('RESETTING', 'RUNNING', 'PAUSED', 'CANCELLING', 'CLEANING')
      """)
  int countRecoverableUnleasedRows();

  @Select("""
      WITH candidate AS MATERIALIZED (
        SELECT queued.id
        FROM trading_lab.runs queued
        WHERE queued.lease_key IS NULL
          AND queued.state = 'QUEUED'
        ORDER BY queued.queue_sequence
        LIMIT 1
      )
      SELECT run.id, run.state, run.queue_sequence,
             run.lease_key, run.lease_owner, run.lease_until, run.version
      FROM trading_lab.runs run
      JOIN candidate ON candidate.id = run.id
      WHERE run.lease_key IS NULL
        AND run.state = 'QUEUED'
      FOR UPDATE OF run SKIP LOCKED
      """)
  Optional<TradingLabRunEntity> findOldestQueuedForUpdate();

  @Select("""
      SELECT count(*)
      FROM trading_lab.runs
      WHERE id = #{runId}
        AND lease_key = 1
        AND lease_owner = #{claimOwner}
        AND version = #{expectedVersion}
        AND lease_until <= clock_timestamp()
      """)
  int expiredFenceCount(
      @Param("runId") UUID runId,
      @Param("claimOwner") String claimOwner,
      @Param("expectedVersion") long expectedVersion);

  @Update("""
      UPDATE trading_lab.runs
      SET lease_owner = #{newClaimOwner},
          lease_until = clock_timestamp() + (#{leaseMillis} * interval '1 millisecond'),
          version = version + 1,
          updated_at = clock_timestamp()
      WHERE id = #{runId}
        AND lease_key = 1
        AND lease_owner = #{oldClaimOwner}
        AND version = #{expectedVersion}
        AND lease_until <= clock_timestamp()
      """)
  int reclaimExpired(
      @Param("runId") UUID runId,
      @Param("oldClaimOwner") String oldClaimOwner,
      @Param("newClaimOwner") String newClaimOwner,
      @Param("expectedVersion") long expectedVersion,
      @Param("leaseMillis") long leaseMillis);

  @Update("""
      UPDATE trading_lab.runs
      SET lease_key = 1,
          lease_owner = #{claimOwner},
          lease_until = clock_timestamp() + (#{leaseMillis} * interval '1 millisecond'),
          version = version + 1,
          updated_at = clock_timestamp()
      WHERE id = #{runId}
        AND state = #{expectedState}
        AND version = #{expectedVersion}
        AND lease_key IS NULL
      """)
  int claimUnleased(
      @Param("runId") UUID runId,
      @Param("expectedState") String expectedState,
      @Param("expectedVersion") long expectedVersion,
      @Param("claimOwner") String claimOwner,
      @Param("leaseMillis") long leaseMillis);

  @Update("""
      UPDATE trading_lab.runs
      SET lease_until = clock_timestamp() + (#{leaseMillis} * interval '1 millisecond'),
          version = version + 1,
          updated_at = clock_timestamp()
      WHERE id = #{runId}
        AND version = #{expectedVersion}
        AND lease_key = 1
        AND lease_owner = #{claimOwner}
        AND lease_until > clock_timestamp()
        AND state NOT IN ('COMPLETED', 'CANCELLED', 'FAILED')
      """)
  int renewFenced(
      @Param("runId") UUID runId,
      @Param("claimOwner") String claimOwner,
      @Param("expectedVersion") long expectedVersion,
      @Param("leaseMillis") long leaseMillis);

  @Update("""
      UPDATE trading_lab.runs
      SET lease_key = NULL,
          lease_owner = NULL,
          lease_until = NULL,
          version = version + 1,
          updated_at = clock_timestamp()
      WHERE id = #{runId}
        AND version = #{expectedVersion}
        AND lease_key = 1
        AND lease_owner = #{claimOwner}
        AND lease_until > clock_timestamp()
        AND state IN ('COMPLETED', 'CANCELLED', 'FAILED')
      """)
  int releaseTerminalFenced(
      @Param("runId") UUID runId,
      @Param("claimOwner") String claimOwner,
      @Param("expectedVersion") long expectedVersion);

  @Update("""
      UPDATE trading_lab.runs
      SET lease_key = NULL,
          lease_owner = NULL,
          lease_until = NULL,
          version = version + 1,
          updated_at = clock_timestamp()
      WHERE id = #{runId}
        AND version = #{expectedVersion}
        AND lease_key = 1
        AND lease_owner = #{claimOwner}
        AND state IN ('COMPLETED', 'CANCELLED', 'FAILED')
      """)
  int clearTerminalLeaseForAcquisition(
      @Param("runId") UUID runId,
      @Param("claimOwner") String claimOwner,
      @Param("expectedVersion") long expectedVersion);
}
