package com.fxplatform.trading.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("trading.batch_action_requests")
public class BatchActionRequestEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID accountId;
  private String actionType;
  private String requestId;
  private String requestFingerprint;
  private UUID ownerToken;
  private Instant leaseUntil;
  private String status;
  private String scopeIds;
  private String responsePayload;
  private Instant createdAt;
  private Instant updatedAt;
}
