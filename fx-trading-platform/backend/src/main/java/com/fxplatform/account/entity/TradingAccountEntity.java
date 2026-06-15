package com.fxplatform.account.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.account.enums.AccountStatus;
import com.fxplatform.account.enums.AccountType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * TradingAccountEntity 是账户模块的数据库实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("core.trading_accounts")
public class TradingAccountEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID userId;
  private AccountType accountType = AccountType.DEMO;
  private String baseCurrency = "USD";
  private BigDecimal balance = BigDecimal.ZERO;
  private BigDecimal equity = BigDecimal.ZERO;
  private BigDecimal usedMargin = BigDecimal.ZERO;
  private BigDecimal freeMargin = BigDecimal.ZERO;
  private BigDecimal marginLevel;
  private Integer leverage = 100;
  private AccountStatus status = AccountStatus.ACTIVE;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
