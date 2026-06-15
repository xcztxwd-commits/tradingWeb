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
@TableName("market.provider_instruments")
public class ProviderInstrumentEntity {

  @TableId(value = "id", type = IdType.INPUT)
  private UUID id;
  private UUID providerId;
  private String providerSymbol;
  private String assetClass;
  private String baseAsset;
  private String quoteAsset;
  private String displayName;
  private Boolean listed = true;
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String rawJson = "{}";
  private Instant lastSyncedAt;
  @TableField(fill = FieldFill.INSERT)
  private Instant createdAt;
  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Instant updatedAt;
}
