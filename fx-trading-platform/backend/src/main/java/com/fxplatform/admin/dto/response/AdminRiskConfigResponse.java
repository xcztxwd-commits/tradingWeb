package com.fxplatform.admin.dto.response;

import com.fxplatform.risk.entity.RiskConfigEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * AdminRiskConfigResponse 是后台风控配置响应 DTO。
 */
public record AdminRiskConfigResponse(
    UUID id,
    String symbol,
    Integer maxLeverage,
    BigDecimal maxLots,
    BigDecimal marginCallLevel,
    BigDecimal stopOutLevel,
    Boolean enabled,
    Instant updatedAt
) {

  /** 将风控配置实体映射为后台 DTO。 */
  public static AdminRiskConfigResponse from(RiskConfigEntity entity) {
    return new AdminRiskConfigResponse(
        entity.getId(),
        entity.getSymbol(),
        entity.getMaxLeverage(),
        entity.getMaxLots(),
        entity.getMarginCallLevel(),
        entity.getStopOutLevel(),
        entity.getEnabled(),
        entity.getUpdatedAt());
  }
}
