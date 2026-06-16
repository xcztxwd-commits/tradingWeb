package com.fxplatform.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.finance.enums.FundOrderStatus;
import com.fxplatform.finance.enums.FundOrderType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 充值/提现审核订单实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("finance.fund_orders")
public class FundOrderEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID userId;
  private UUID accountId;
  private FundOrderType orderType;
  private BigDecimal amount;
  private String currency = "USD";
  private FundOrderStatus status = FundOrderStatus.PENDING;
  private UUID paymentMethodId;
  private String applicantNote;
  private String reviewReason;
  private UUID reviewedBy;
  private Instant reviewedAt;
  private UUID fundOperationId;
  private UUID createdBy;
  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;

  public void setOrderType(FundOrderType orderType) {
    this.orderType = orderType;
  }

  public void setOrderType(String orderType) {
    this.orderType = FundOrderType.fromCode(orderType);
  }

  public void setStatus(FundOrderStatus status) {
    this.status = status;
  }

  public void setStatus(String status) {
    this.status = FundOrderStatus.fromCode(status);
  }
}
