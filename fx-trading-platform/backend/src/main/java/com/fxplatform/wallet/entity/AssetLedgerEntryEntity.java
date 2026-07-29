package com.fxplatform.wallet.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
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
@TableName("ledger.asset_ledger_entries")
public class AssetLedgerEntryEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID accountId;
  private String walletType = "SPOT";
  private String asset;
  private BigDecimal amount;
  private BigDecimal balanceAfter;
  private String entryType;
  private String operationType;
  private String referenceType;
  private UUID referenceId;
  private String description;

  @TableField(
      insertStrategy = FieldStrategy.NEVER,
      updateStrategy = FieldStrategy.NEVER
  )
  private Long sequenceNo;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
}
