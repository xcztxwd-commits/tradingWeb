package com.fxplatform.admin.dto.response;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.fxplatform.admin.entity.AdminTableColumnPreferenceEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 后台表格列偏好响应。
 */
public record AdminTableColumnPreferenceResponse(
    UUID id,
    UUID userId,
    String pageKey,
    List<String> hiddenColumns,
    String tableSize,
    Boolean showBorder,
    Boolean zebra,
    Instant updatedAt
) {

  public static AdminTableColumnPreferenceResponse from(AdminTableColumnPreferenceEntity entity) {
    return new AdminTableColumnPreferenceResponse(
        entity.getId(),
        entity.getUserId(),
        entity.getPageKey(),
        parseStringList(entity.getHiddenColumns()),
        entity.getTableSize(),
        entity.getShowBorder(),
        entity.getZebra(),
        entity.getUpdatedAt());
  }

  static List<String> parseStringList(String json) {
    if (StrUtil.isBlank(json)) {
      return List.of();
    }
    return JSONUtil.parseArray(json).toList(String.class);
  }
}
