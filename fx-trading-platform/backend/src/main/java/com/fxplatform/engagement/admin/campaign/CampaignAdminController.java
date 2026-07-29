package com.fxplatform.engagement.admin.campaign;

import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.admin.service.AdminPermissionCatalog;
import com.fxplatform.common.response.ApiResponse;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignActionRequest;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignDetailResponse;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignPreviewResponse;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignResetResponse;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignSaveRequest;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignStatsResponse;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignSummaryResponse;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignUserResponse;
import com.fxplatform.engagement.persistence.enums.AudienceType;
import com.fxplatform.engagement.persistence.enums.PopupCampaignLifecycleStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/admin/engagement/campaigns")
@RequiredArgsConstructor
public class CampaignAdminController {

  private final CampaignAdminService service;

  @GetMapping
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_CAMPAIGN_READ + "')")
  public ApiResponse<AdminPageResponse<CampaignSummaryResponse>> campaigns(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) PopupCampaignLifecycleStatus lifecycleStatus,
      @RequestParam(required = false) String name,
      @RequestParam(required = false) AudienceType audienceType,
      @RequestParam(required = false) Boolean syncToInbox,
      @RequestParam(required = false) Instant effectiveFrom,
      @RequestParam(required = false) Instant effectiveTo) {
    return ApiResponse.success(service.campaigns(
        page, size, lifecycleStatus, name, audienceType, syncToInbox,
        effectiveFrom, effectiveTo));
  }

  @PostMapping
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_CAMPAIGN_EDIT + "')")
  public ApiResponse<CampaignDetailResponse> create(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody CampaignSaveRequest request) {
    return ApiResponse.success(service.create(principal.id(), request));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_CAMPAIGN_READ + "')")
  public ApiResponse<CampaignDetailResponse> campaign(@PathVariable UUID id) {
    return ApiResponse.success(service.campaign(id));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_CAMPAIGN_EDIT + "')")
  public ApiResponse<CampaignDetailResponse> update(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id,
      @Valid @RequestBody CampaignSaveRequest request) {
    return ApiResponse.success(service.update(principal.id(), id, request));
  }

  @PostMapping("/{id}/publish")
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_CAMPAIGN_PUBLISH + "')")
  public ApiResponse<CampaignDetailResponse> publish(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id,
      @Valid @RequestBody CampaignActionRequest request) {
    return ApiResponse.success(service.publish(principal.id(), id, request));
  }

  @PostMapping("/{id}/pause")
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_CAMPAIGN_PUBLISH + "')")
  public ApiResponse<CampaignDetailResponse> pause(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id,
      @Valid @RequestBody CampaignActionRequest request) {
    return ApiResponse.success(service.pause(principal.id(), id, request));
  }

  @PostMapping("/{id}/resume")
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_CAMPAIGN_PUBLISH + "')")
  public ApiResponse<CampaignDetailResponse> resume(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id,
      @Valid @RequestBody CampaignActionRequest request) {
    return ApiResponse.success(service.resume(principal.id(), id, request));
  }

  @PostMapping("/{id}/end")
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_CAMPAIGN_PUBLISH + "')")
  public ApiResponse<CampaignDetailResponse> end(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id,
      @Valid @RequestBody CampaignActionRequest request) {
    return ApiResponse.success(service.end(principal.id(), id, request));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_CAMPAIGN_DELETE + "')")
  public ApiResponse<CampaignDetailResponse> delete(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id,
      @Valid @RequestBody CampaignActionRequest request) {
    return ApiResponse.success(service.delete(principal.id(), id, request));
  }

  @PostMapping("/{id}/restore")
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_CAMPAIGN_DELETE + "')")
  public ApiResponse<CampaignDetailResponse> restore(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id,
      @Valid @RequestBody CampaignActionRequest request) {
    return ApiResponse.success(service.restore(principal.id(), id, request));
  }

  @PostMapping("/{id}/reset-delivery")
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_CAMPAIGN_PUBLISH + "')")
  public ApiResponse<CampaignResetResponse> resetDelivery(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id,
      @Valid @RequestBody CampaignActionRequest request) {
    return ApiResponse.success(service.resetDelivery(principal.id(), id, request));
  }

  @PostMapping("/{id}/test-popup")
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_CAMPAIGN_EDIT + "')")
  public ApiResponse<CampaignPreviewResponse> testPopup(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id,
      @Valid @RequestBody CampaignActionRequest request) {
    return ApiResponse.success(service.testPopup(principal.id(), id, request));
  }

  @GetMapping("/{id}/stats")
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_CAMPAIGN_STATS + "')")
  public ApiResponse<CampaignStatsResponse> stats(@PathVariable UUID id) {
    return ApiResponse.success(service.stats(id));
  }

  @GetMapping("/{id}/users")
  @PreAuthorize("hasRole('ADMIN') and hasAuthority('"
      + AdminPermissionCatalog.CONTENT_CAMPAIGN_USER_DETAIL + "')")
  public ApiResponse<AdminPageResponse<CampaignUserResponse>> users(
      @AuthenticationPrincipal UserPrincipal principal,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam @NotBlank @Size(max = 500) String reason) {
    return ApiResponse.success(service.users(principal.id(), id, page, size, reason));
  }
}
