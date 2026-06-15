package com.fxplatform.trading.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.trading.enums.OrderStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * OrderEventEntity 是订单事件数据库实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("trading.order_events")
public class OrderEventEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID orderId;
  private String eventType;
  private OrderStatus fromStatus;
  private OrderStatus toStatus;
  private String reasonCode;
  private String message;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
}
