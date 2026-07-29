package com.fxplatform.engagement.admin.asset;

import com.fxplatform.admin.service.AdminPermissionCatalog;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.engagement.application.content.ContentAssetService;
import com.fxplatform.engagement.application.content.ContentAssetService.UploadResult;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/engagement/assets")
@RequiredArgsConstructor
public class ContentAssetAdminController {

  private final ContentAssetService service;

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasRole('ADMIN') and (hasAuthority('"
      + AdminPermissionCatalog.CONTENT_CAMPAIGN_EDIT
      + "') or hasAuthority('"
      + AdminPermissionCatalog.CONTENT_MESSAGE_EDIT
      + "'))")
  public ApiResponse<UploadResult> upload(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestPart("file") MultipartFile file
  ) {
    try {
      return ApiResponse.success(service.upload(
          principal.id(), file.getOriginalFilename(), file.getContentType(), file.getBytes()));
    } catch (IOException exception) {
      throw new BusinessException(
          "CONTENT_ASSET_INVALID", "Content asset could not be read", exception);
    }
  }
}
