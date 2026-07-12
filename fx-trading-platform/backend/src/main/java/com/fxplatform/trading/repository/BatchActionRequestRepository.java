package com.fxplatform.trading.repository;

import com.fxplatform.common.mybatis.FxBaseMapper;
import com.fxplatform.trading.entity.BatchActionRequestEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BatchActionRequestRepository extends FxBaseMapper<BatchActionRequestEntity> {

  @Insert("""
      INSERT INTO trading.batch_action_requests (
          id, account_id, action_type, request_id, request_fingerprint, owner_token, lease_until,
          status, scope_ids, response_payload, created_at, updated_at
      ) VALUES (
          #{id}, #{accountId}, #{actionType}, #{requestId}, #{requestFingerprint},
          #{ownerToken}, #{leaseUntil},
          #{status}, #{scopeIds}, #{responsePayload}, #{createdAt}, #{updatedAt}
      )
      ON CONFLICT (account_id, action_type, request_id) DO NOTHING
      """)
  int insertIfAbsent(BatchActionRequestEntity entity);

  @Select("""
      SELECT id, account_id, action_type, request_id, request_fingerprint,
             owner_token, lease_until,
             status, scope_ids, response_payload, created_at, updated_at
      FROM trading.batch_action_requests
      WHERE account_id = #{accountId}
        AND action_type = #{actionType}
        AND request_id = #{requestId}
      FOR UPDATE
      """)
  @Results(id = "batchActionRequestMap", value = {
      @Result(column = "id", property = "id", id = true),
      @Result(column = "account_id", property = "accountId"),
      @Result(column = "action_type", property = "actionType"),
      @Result(column = "request_id", property = "requestId"),
      @Result(column = "request_fingerprint", property = "requestFingerprint"),
      @Result(column = "owner_token", property = "ownerToken"),
      @Result(column = "lease_until", property = "leaseUntil"),
      @Result(column = "status", property = "status"),
      @Result(column = "scope_ids", property = "scopeIds"),
      @Result(column = "response_payload", property = "responsePayload"),
      @Result(column = "created_at", property = "createdAt"),
      @Result(column = "updated_at", property = "updatedAt")
  })
  Optional<BatchActionRequestEntity> findForUpdate(
      @Param("accountId") UUID accountId,
      @Param("actionType") String actionType,
      @Param("requestId") String requestId);

  @Update("""
      UPDATE trading.batch_action_requests
      SET owner_token = #{ownerToken},
          lease_until = #{leaseUntil},
          updated_at = #{now}
      WHERE id = #{id}
        AND status = 'PROCESSING'
        AND lease_until <= #{now}
      """)
  int claimExpired(
      @Param("id") UUID id,
      @Param("ownerToken") UUID ownerToken,
      @Param("leaseUntil") Instant leaseUntil,
      @Param("now") Instant now);

  @Update("""
      UPDATE trading.batch_action_requests
      SET lease_until = #{leaseUntil},
          updated_at = #{now}
      WHERE id = #{id}
        AND status = 'PROCESSING'
        AND owner_token = #{ownerToken}
        AND lease_until > #{now}
      """)
  int renewLease(
      @Param("id") UUID id,
      @Param("ownerToken") UUID ownerToken,
      @Param("leaseUntil") Instant leaseUntil,
      @Param("now") Instant now);

  @Update("""
      UPDATE trading.batch_action_requests
      SET status = 'COMPLETED',
          response_payload = #{responsePayload},
          updated_at = #{updatedAt}
      WHERE id = #{id}
        AND status = 'PROCESSING'
        AND owner_token = #{ownerToken}
        AND lease_until > #{updatedAt}
      """)
  int markCompleted(
      @Param("id") UUID id,
      @Param("ownerToken") UUID ownerToken,
      @Param("responsePayload") String responsePayload,
      @Param("updatedAt") Instant updatedAt);
}
