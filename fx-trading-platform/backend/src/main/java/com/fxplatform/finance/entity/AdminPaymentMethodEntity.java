package com.fxplatform.finance.entity;

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
 * AdminPaymentMethodEntity 是后台支付方式数据库实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("finance.payment_methods")
public class AdminPaymentMethodEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private String name;
  private String methodType;
  private String currency = "USD";
  private Boolean enabled = true;
  private Integer displayOrder = 0;
  private String instructions;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
