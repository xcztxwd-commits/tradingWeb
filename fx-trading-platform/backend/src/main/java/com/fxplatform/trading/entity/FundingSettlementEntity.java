package com.fxplatform.trading.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.PositionSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("trading.funding_settlements")
public class FundingSettlementEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID positionId;
  private UUID accountId;
  private String symbol;
  private Instant fundingTime;
  private BigDecimal fundingRate;
  private BigDecimal amount;
  private String asset;
  private UUID ledgerEntryId;
  private PositionSide positionSide;
  private MarginMode marginMode;
  private BigDecimal markPrice;
  private String source;
  private BigDecimal balanceAfter;
  private BigDecimal isolatedMarginAfter;
  private BigDecimal shortfall = BigDecimal.ZERO;

  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
}
