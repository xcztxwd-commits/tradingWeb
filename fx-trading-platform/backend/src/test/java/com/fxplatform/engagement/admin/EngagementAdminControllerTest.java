package com.fxplatform.engagement.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.admin.service.AdminPermissionCatalog;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.engagement.admin.campaign.CampaignAdminController;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignActionRequest;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.CampaignSaveRequest;
import com.fxplatform.engagement.admin.campaign.CampaignAdminDtos.PopupPageKey;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

class EngagementAdminControllerTest {

  private static final String BASE = "/api/admin/engagement/campaigns";

  @Test
  void campaignControllerExposesExactlyTheFourteenFrozenRoutes() {
    Map<String, String> actual = new LinkedHashMap<>();
    int routeCount = 0;
    String base = CampaignAdminController.class.getAnnotation(RequestMapping.class).value()[0];
    for (Method method : CampaignAdminController.class.getDeclaredMethods()) {
      RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(
          method, RequestMapping.class);
      if (mapping != null) {
        routeCount++;
        String path = mapping.path().length == 0 ? "" : mapping.path()[0];
        actual.put(mapping.method()[0].name() + " " + base + path, method.getName());
      }
    }

    Map<String, String> expected = Map.ofEntries(
        Map.entry("GET " + BASE, "campaigns"),
        Map.entry("POST " + BASE, "create"),
        Map.entry("GET " + BASE + "/{id}", "campaign"),
        Map.entry("PUT " + BASE + "/{id}", "update"),
        Map.entry("POST " + BASE + "/{id}/publish", "publish"),
        Map.entry("POST " + BASE + "/{id}/pause", "pause"),
        Map.entry("POST " + BASE + "/{id}/resume", "resume"),
        Map.entry("POST " + BASE + "/{id}/end", "end"),
        Map.entry("DELETE " + BASE + "/{id}", "delete"),
        Map.entry("POST " + BASE + "/{id}/restore", "restore"),
        Map.entry("POST " + BASE + "/{id}/reset-delivery", "resetDelivery"),
        Map.entry("POST " + BASE + "/{id}/test-popup", "testPopup"),
        Map.entry("GET " + BASE + "/{id}/stats", "stats"),
        Map.entry("GET " + BASE + "/{id}/users", "users"));
    assertThat(routeCount).isEqualTo(expected.size());
    assertThat(actual).hasSameSizeAs(expected).containsAllEntriesOf(expected);
  }

  @Test
  void everyCampaignRouteRequiresAdminRoleAndItsExactFineGrainedAuthority() {
    assertAuthority("campaigns", AdminPermissionCatalog.CONTENT_CAMPAIGN_READ);
    assertAuthority("create", AdminPermissionCatalog.CONTENT_CAMPAIGN_EDIT);
    assertAuthority("campaign", AdminPermissionCatalog.CONTENT_CAMPAIGN_READ);
    assertAuthority("update", AdminPermissionCatalog.CONTENT_CAMPAIGN_EDIT);
    assertAuthority("publish", AdminPermissionCatalog.CONTENT_CAMPAIGN_PUBLISH);
    assertAuthority("pause", AdminPermissionCatalog.CONTENT_CAMPAIGN_PUBLISH);
    assertAuthority("resume", AdminPermissionCatalog.CONTENT_CAMPAIGN_PUBLISH);
    assertAuthority("end", AdminPermissionCatalog.CONTENT_CAMPAIGN_PUBLISH);
    assertAuthority("delete", AdminPermissionCatalog.CONTENT_CAMPAIGN_DELETE);
    assertAuthority("restore", AdminPermissionCatalog.CONTENT_CAMPAIGN_DELETE);
    assertAuthority("resetDelivery", AdminPermissionCatalog.CONTENT_CAMPAIGN_PUBLISH);
    assertAuthority("testPopup", AdminPermissionCatalog.CONTENT_CAMPAIGN_EDIT);
    assertAuthority("stats", AdminPermissionCatalog.CONTENT_CAMPAIGN_STATS);
    assertAuthority("users", AdminPermissionCatalog.CONTENT_CAMPAIGN_USER_DETAIL);
  }

  @Test
  void everyCampaignCommandAndUserDetailTakeTheActorOnlyFromPrincipal() {
    for (String methodName : java.util.Set.of(
        "create", "update", "publish", "pause", "resume", "end", "delete", "restore",
        "resetDelivery", "testPopup", "users")) {
      Method method = java.util.Arrays.stream(CampaignAdminController.class.getDeclaredMethods())
          .filter(candidate -> candidate.getName().equals(methodName))
          .findFirst()
          .orElseThrow();
      assertThat(method.getParameterTypes()[0]).isEqualTo(UserPrincipal.class);
    }
    for (Class<?> requestType : java.util.List.of(
        CampaignSaveRequest.class, CampaignActionRequest.class)) {
      assertThat(requestType.getRecordComponents())
          .extracting(java.lang.reflect.RecordComponent::getName)
          .doesNotContain("actorUserId", "createdBy", "updatedBy");
    }
  }

  @Test
  void campaignPagesAreAFixedBusinessSurfaceEnumWithoutAuthenticationRoutes() {
    assertThat(PopupPageKey.values()).extracting(Enum::name)
        .containsExactlyInAnyOrder(
            "HOME", "TRADE_SPOT", "TRADE_PERPETUAL", "DASHBOARD", "MARKETS",
            "ORDERS", "POSITIONS", "WALLET", "ACCOUNT_OVERVIEW", "ACCOUNT_ASSETS",
            "FUNDING_RECORDS", "TRADE_RECORDS", "KYC", "ACCOUNT_SETTINGS", "SECURITY",
            "SETTINGS", "MESSAGES")
        .noneMatch(name -> name.contains("LOGIN") || name.contains("REGISTER")
            || name.contains("PASSWORD") || name.contains("URL"));
  }

  private static void assertAuthority(String methodName, String authority) {
    Method method = java.util.Arrays.stream(CampaignAdminController.class.getDeclaredMethods())
        .filter(candidate -> candidate.getName().equals(methodName))
        .findFirst()
        .orElseThrow();
    assertThat(method.getAnnotation(PreAuthorize.class))
        .extracting(PreAuthorize::value)
        .isEqualTo("hasRole('ADMIN') and hasAuthority('" + authority + "')");
  }
}
