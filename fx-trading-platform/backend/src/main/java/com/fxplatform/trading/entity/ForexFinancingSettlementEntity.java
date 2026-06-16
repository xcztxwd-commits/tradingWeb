package com.fxplatform.trading.entity;

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
@TableName("trading.fx_financing_settlements")
public class ForexFinancingSettlementEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID positionId;
  private UUID accountId;
  private String symbol;
  private LocalDate settlementDate;
  private Integer daysCharged;
  private BigDecimal rate;
  private BigDecimal amount;
  private String asset;
  private UUID ledgerEntryId;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
}
