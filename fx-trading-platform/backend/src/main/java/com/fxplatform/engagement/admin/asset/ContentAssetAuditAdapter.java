package com.fxplatform.engagement.admin.asset;

import com.fxplatform.engagement.admin.EngagementAuditService;
import com.fxplatform.engagement.admin.EngagementAuditService.Action;
import com.fxplatform.engagement.admin.EngagementAuditService.Metadata;
import com.fxplatform.engagement.admin.EngagementAuditService.TargetType;
import com.fxplatform.engagement.application.content.ContentAssetAuditPort;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContentAssetAuditAdapter implements ContentAssetAuditPort {

  private final EngagementAuditService auditService;

  @Override
  public void recordUpload(
      UUID actorUserId,
      UUID assetId,
      String mimeType,
      long byteSize,
      int width,
      int height
  ) {
    auditService.record(
        actorUserId,
        Action.CONTENT_ASSET_UPLOAD,
        TargetType.CONTENT_ASSET,
        assetId.toString(),
        new Metadata(
            null,
            null,
            null,
            null,
            "mimeType=%s;size=%d;width=%d;height=%d"
                .formatted(mimeType, byteSize, width, height),
            null));
  }
}
