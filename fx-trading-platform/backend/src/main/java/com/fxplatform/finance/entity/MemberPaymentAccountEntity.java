package com.fxplatform.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 会员银行卡或钱包账户实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("finance.member_payment_accounts")
public class MemberPaymentAccountEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID userId;
  private String accountType;
  private String currency;
  private String network;
  private String holderName;
  private String bankName;
  private String branchName;
  private String bankCode;
  private String accountNo;
  private Boolean enabled = true;
  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
