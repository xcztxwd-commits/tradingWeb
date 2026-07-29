package com.fxplatform.engagement.application.content;

import java.util.UUID;

/** Keeps the content application layer independent from admin audit implementation details. */
public interface ContentAssetAuditPort {

  void recordUpload(
      UUID actorUserId,
      UUID assetId,
      String mimeType,
      long byteSize,
      int width,
      int height
  );
}
