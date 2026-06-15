package com.fxplatform.admin.dto.request;

import java.util.List;

/**
 * 后台表格列偏好保存请求。
 */
public record AdminTableColumnPreferenceRequest(
    List<String> hiddenColumns,
    String tableSize,
    Boolean showBorder,
    Boolean zebra
) {
}
