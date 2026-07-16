package com.fxplatform.trading.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.QuantityUnit;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("trading.account_symbol_settings")
public class AccountSymbolSettingEntity {

  private UUID accountId;
  private String symbol;
  private Integer leverage = 10;
  private MarginMode marginMode = MarginMode.CROSS;
  private QuantityUnit quantityUnit = QuantityUnit.BASE;
  private Long version = 0L;
}
