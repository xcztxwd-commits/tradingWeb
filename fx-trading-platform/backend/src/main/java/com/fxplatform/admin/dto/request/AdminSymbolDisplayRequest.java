package com.fxplatform.admin.dto.request;

public record AdminSymbolDisplayRequest(
    String iconUrl,
    Boolean displayEnabled,
    Boolean quoteEnabled,
    Boolean chartEnabled,
    Boolean orderBookEnabled,
    Boolean tradable,
    Boolean featured,
    String displayGroup,
    Integer displayOrder
) {
}
