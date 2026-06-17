package com.fxplatform.account.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("core.account_daily_snapshots")
public class AccountDailySnapshotEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID accountId;
  private String walletType = "MARGIN";
  private String asset;
  private BigDecimal balance;
  private BigDecimal equity;
  private BigDecimal usedMargin;
  private BigDecimal freeMargin;
  private BigDecimal openPnl;
  private BigDecimal realizedPnl;
  private LocalDate snapshotDate;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
}
