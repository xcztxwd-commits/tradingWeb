package com.fxplatform.finance.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.finance.enums.AdminFundOperationStatus;
import com.fxplatform.finance.enums.AdminFundOperationType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * AdminFundOperationEntity 是后台资金操作数据库实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("finance.admin_fund_operations")
public class AdminFundOperationEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID accountId;
  private UUID userId;
  private AdminFundOperationType operationType;
  private BigDecimal amount;
  private String currency = "USD";
  private BigDecimal beforeBalance;
  private BigDecimal afterBalance;
  private AdminFundOperationStatus status = AdminFundOperationStatus.COMPLETED;
  private UUID adminUserId;
  private String reason;
  private UUID paymentMethodId;
  private String note;
  private String idempotencyKey;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  public void setOperationType(AdminFundOperationType operationType) {
    this.operationType = operationType;
  }

  public void setOperationType(String operationType) {
    this.operationType = AdminFundOperationType.fromCode(operationType);
  }

  public void setStatus(AdminFundOperationStatus status) {
    this.status = status;
  }

  public void setStatus(String status) {
    this.status = AdminFundOperationStatus.fromCode(status);
  }
}
