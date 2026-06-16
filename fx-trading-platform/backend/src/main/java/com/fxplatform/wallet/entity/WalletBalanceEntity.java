package com.fxplatform.wallet.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("core.wallet_balances")
public class WalletBalanceEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID accountId;
  private String walletType = "SPOT";
  private String asset;
  private BigDecimal total = BigDecimal.ZERO;
  private BigDecimal available = BigDecimal.ZERO;
  private BigDecimal locked = BigDecimal.ZERO;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
