package com.fxplatform.market.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * SymbolAdminEventEntity 是品种后台事件数据库实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("market.symbol_admin_events")
public class SymbolAdminEventEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID symbolId;
  private UUID adminUserId;
  private String eventType;
  private String beforeValue;
  private String afterValue;
  private String reason;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
}
