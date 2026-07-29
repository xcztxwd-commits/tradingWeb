package com.fxplatform.tradinglab.environment.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TradingLabOperationGateRepository {

  long LOCK_KEY = 7412918473123457L;

  @Select("SELECT pg_advisory_xact_lock(7412918473123457) IS NULL")
  boolean awaitTransactionLock();

  @Select("SELECT pg_try_advisory_xact_lock(7412918473123457)")
  boolean tryAcquireTransactionLock();

  @Select("""
      SELECT EXISTS (
        SELECT 1
        FROM trading_lab.runs
        WHERE state NOT IN ('COMPLETED', 'CANCELLED', 'FAILED')
      )
      """)
  boolean hasNonTerminalRuns();
}
