package com.fxplatform.ledger.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.ledger.enums.LedgerEntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * LedgerEntryEntity 是资金流水数据库实体。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("ledger.ledger_entries")
public class LedgerEntryEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID accountId;
  private LedgerEntryType entryType;
  private String operationType;
  private BigDecimal amount;
  private BigDecimal balanceAfter;
  private String currency = "USD";
  private String referenceType;
  private UUID referenceId;
  private String description;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
}
