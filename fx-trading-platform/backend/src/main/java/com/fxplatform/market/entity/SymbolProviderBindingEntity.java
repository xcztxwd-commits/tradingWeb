package com.fxplatform.market.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fxplatform.common.mybatis.JsonbStringTypeHandler;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("market.symbol_provider_bindings")
public class SymbolProviderBindingEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID symbolId;
  private UUID providerId;
  private UUID providerInstrumentId;
  private String providerSymbol;
  private Integer priority = 100;
  private Boolean enabled = true;
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String configJson = "{}";
  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
