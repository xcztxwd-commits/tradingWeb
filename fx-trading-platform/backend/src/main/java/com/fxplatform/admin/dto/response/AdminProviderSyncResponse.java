package com.fxplatform.admin.dto.response;

import java.util.UUID;

public record AdminProviderSyncResponse(
    UUID providerId,
    int syncedCount
) {
}
