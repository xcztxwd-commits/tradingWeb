package com.fxplatform.engagement.admin.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fxplatform.engagement.admin.EngagementAuditService;
import com.fxplatform.engagement.admin.EngagementAuditService.Action;
import com.fxplatform.engagement.admin.EngagementAuditService.Metadata;
import com.fxplatform.engagement.admin.EngagementAuditService.TargetType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ContentAssetAuditAdapterTest {

  @Test
  void mapsUploadToTypedAuditWithoutFilenamePathKeyOrBytes() {
    EngagementAuditService auditService = mock(EngagementAuditService.class);
    ContentAssetAuditAdapter adapter = new ContentAssetAuditAdapter(auditService);
    UUID actorId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();

    adapter.recordUpload(actorId, assetId, "image/webp", 42, 3, 4);

    ArgumentCaptor<Metadata> metadata = ArgumentCaptor.forClass(Metadata.class);
    verify(auditService).record(
        org.mockito.ArgumentMatchers.eq(actorId),
        org.mockito.ArgumentMatchers.eq(Action.CONTENT_ASSET_UPLOAD),
        org.mockito.ArgumentMatchers.eq(TargetType.CONTENT_ASSET),
        org.mockito.ArgumentMatchers.eq(assetId.toString()),
        metadata.capture());
    assertThat(metadata.getValue().after())
        .contains("image/webp", "42", "3", "4")
        .doesNotContain("filename", "path", "storage", "bytes");
    assertThat(metadata.getValue().revisionId()).isNull();
    assertThat(metadata.getValue().audienceType()).isNull();
    assertThat(metadata.getValue().targetCount()).isNull();
    assertThat(metadata.getValue().before()).isNull();
    assertThat(metadata.getValue().reason()).isNull();
  }
}
