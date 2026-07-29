package com.fxplatform.engagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.admin.service.AdminPermissionCatalog;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.engagement.admin.asset.ContentAssetAdminController;
import com.fxplatform.engagement.application.content.ContentAssetService;
import com.fxplatform.engagement.application.content.ContentAssetService.AssetPayload;
import com.fxplatform.engagement.application.content.ContentAssetService.UploadResult;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class ContentAssetControllerTest {

  @Test
  void publicReadUsesCanonicalUrlAndNonSniffingNonCacheableHeaders() {
    ContentAssetService service = mock(ContentAssetService.class);
    ContentAssetController controller = new ContentAssetController(service);
    UUID assetId = UUID.randomUUID();
    byte[] bytes = {1, 2, 3};
    when(service.load(assetId)).thenReturn(new AssetPayload("image/png", bytes.length, bytes));

    var response = controller.asset(assetId);

    assertThat(ContentAssetController.class.getAnnotation(RequestMapping.class).value())
        .containsExactly("/api/public/engagement/assets");
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    assertThat(response.getHeaders().getContentLength()).isEqualTo(bytes.length);
    assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getHeaders().getCacheControl())
        .isEqualTo(CacheControl.noStore().getHeaderValue());
    assertThat(response.getBody()).isEqualTo(bytes);
    verify(service).load(assetId);
  }

  @Test
  void adminUploadUsesEitherFrozenContentEditAuthorityAndReturnsNoStorageKey()
      throws Exception {
    ContentAssetService service = mock(ContentAssetService.class);
    ContentAssetAdminController controller = new ContentAssetAdminController(service);
    UUID actorId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    byte[] bytes = {1, 2, 3};
    UserPrincipal principal = new UserPrincipal(actorId, "admin@example.test", "ADMIN");
    MockMultipartFile file = new MockMultipartFile(
        "file", "safe.png", "image/png", bytes);
    UploadResult result = new UploadResult(
        assetId,
        "/api/public/engagement/assets/" + assetId,
        "image/png",
        bytes.length,
        1,
        1);
    when(service.upload(actorId, "safe.png", "image/png", bytes)).thenReturn(result);

    var response = controller.upload(principal, file);

    Method upload = ContentAssetAdminController.class
        .getMethod("upload", UserPrincipal.class, org.springframework.web.multipart.MultipartFile.class);
    assertThat(ContentAssetAdminController.class.getAnnotation(RequestMapping.class).value())
        .containsExactly("/api/admin/engagement/assets");
    assertThat(upload.getAnnotation(PostMapping.class).consumes())
        .containsExactly(MediaType.MULTIPART_FORM_DATA_VALUE);
    assertThat(upload.getAnnotation(PreAuthorize.class).value()).isEqualTo(
        "hasRole('ADMIN') and (hasAuthority('"
            + AdminPermissionCatalog.CONTENT_CAMPAIGN_EDIT
            + "') or hasAuthority('"
            + AdminPermissionCatalog.CONTENT_MESSAGE_EDIT
            + "'))");
    assertThat(response.data()).isEqualTo(result);
    assertThat(response.data().toString()).doesNotContain("storageKey", "safe.png");
    verify(service).upload(actorId, "safe.png", "image/png", bytes);
  }
}
