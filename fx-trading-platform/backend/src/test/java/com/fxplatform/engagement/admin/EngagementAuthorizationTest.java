package com.fxplatform.engagement.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fxplatform.admin.controller.AdminContentController;
import com.fxplatform.admin.service.AdminContentCommandService;
import com.fxplatform.admin.service.AdminContentQueryService;
import com.fxplatform.admin.service.AdminPermissionCatalog;
import com.fxplatform.engagement.admin.campaign.CampaignAdminController;
import com.fxplatform.engagement.admin.campaign.CampaignAdminService;
import com.fxplatform.engagement.admin.asset.ContentAssetAdminController;
import com.fxplatform.engagement.admin.message.EngagementMessageAdminController;
import com.fxplatform.engagement.admin.message.EngagementMessageAdminService;
import com.fxplatform.engagement.admin.support.AdminUserSearchController;
import com.fxplatform.engagement.admin.support.AdminUserSearchService;
import com.fxplatform.engagement.admin.support.PopupPolicyAdminController;
import com.fxplatform.engagement.admin.support.PopupPolicyAdminService;
import com.fxplatform.engagement.application.content.ContentAssetService;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitWebConfig(EngagementAuthorizationTest.MethodSecurityConfig.class)
class EngagementAuthorizationTest {

  private static final String ROLE_AND_AUTHORITY =
      "hasRole('ADMIN') and hasAuthority('%s')";

  @Autowired
  private CampaignAdminController campaignController;
  @Autowired
  private CampaignAdminService campaignAdminService;
  @Autowired
  private AdminContentController legacyContentController;
  @Autowired
  private EngagementMessageAdminController messageController;
  @Autowired
  private AdminUserSearchController userSearchController;
  @Autowired
  private PopupPolicyAdminController policyController;
  @Autowired
  private PopupPolicyAdminService policyService;
  @Autowired
  private ContentAssetAdminController contentAssetController;
  @Autowired
  private ContentAssetService contentAssetService;
  @Autowired
  private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;

  @BeforeEach
  void setUpMockMvc() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(springSecurity())
        .build();
    clearInvocations(campaignAdminService, policyService, contentAssetService);
  }

  @Test
  void catalogDefinesTheElevenFrozenEngagementAuthoritiesExactly() throws Exception {
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("CONTENT_CAMPAIGN_READ", "content:campaign:read");
    expected.put("CONTENT_CAMPAIGN_EDIT", "content:campaign:edit");
    expected.put("CONTENT_CAMPAIGN_PUBLISH", "content:campaign:publish");
    expected.put("CONTENT_CAMPAIGN_DELETE", "content:campaign:delete");
    expected.put("CONTENT_CAMPAIGN_STATS", "content:campaign:stats");
    expected.put("CONTENT_CAMPAIGN_USER_DETAIL", "content:campaign:user-detail");
    expected.put("CONTENT_POPUP_POLICY_UPDATE", "content:popup-policy:update");
    expected.put("CONTENT_MESSAGE_READ", "content:message:read");
    expected.put("CONTENT_MESSAGE_EDIT", "content:message:edit");
    expected.put("CONTENT_MESSAGE_SEND", "content:message:send");
    expected.put("CONTENT_MESSAGE_DELETE", "content:message:delete");

    Map<String, String> actual = new LinkedHashMap<>();
    for (String fieldName : expected.keySet()) {
      actual.put(fieldName, (String) AdminPermissionCatalog.class.getField(fieldName).get(null));
    }

    assertThat(actual).containsExactlyEntriesOf(expected);
    assertThat(actual.values()).doesNotHaveDuplicates();
  }

  @Test
  void legacyMessageMethodsRequireTheirOwnFineGrainedAuthority() throws Exception {
    assertAuthority("messages", "content:message:read", int.class, int.class,
        String.class, String.class, Map.class);
    assertAuthority("createMessage", "content:message:edit",
        com.fxplatform.common.security.UserPrincipal.class,
        com.fxplatform.admin.dto.request.AdminMessageRequest.class);
    assertAuthority("updateMessage", "content:message:edit",
        com.fxplatform.common.security.UserPrincipal.class,
        java.util.UUID.class,
        com.fxplatform.admin.dto.request.AdminMessageRequest.class);
    assertAuthority("deleteMessage", "content:message:delete",
        com.fxplatform.common.security.UserPrincipal.class,
        java.util.UUID.class,
        com.fxplatform.admin.dto.request.AdminReasonRequest.class);
  }

  @Test
  void everyCampaignEndpointUsesItsFrozenFineGrainedAuthority() {
    assertAuthority(CampaignAdminController.class, "campaigns", "content:campaign:read");
    assertAuthority(CampaignAdminController.class, "campaign", "content:campaign:read");
    assertAuthority(CampaignAdminController.class, "create", "content:campaign:edit");
    assertAuthority(CampaignAdminController.class, "update", "content:campaign:edit");
    assertAuthority(CampaignAdminController.class, "testPopup", "content:campaign:edit");
    assertAuthority(CampaignAdminController.class, "publish", "content:campaign:publish");
    assertAuthority(CampaignAdminController.class, "pause", "content:campaign:publish");
    assertAuthority(CampaignAdminController.class, "resume", "content:campaign:publish");
    assertAuthority(CampaignAdminController.class, "end", "content:campaign:publish");
    assertAuthority(CampaignAdminController.class, "resetDelivery", "content:campaign:publish");
    assertAuthority(CampaignAdminController.class, "delete", "content:campaign:delete");
    assertAuthority(CampaignAdminController.class, "restore", "content:campaign:delete");
    assertAuthority(CampaignAdminController.class, "stats", "content:campaign:stats");
    assertAuthority(CampaignAdminController.class, "users", "content:campaign:user-detail");
  }

  @Test
  void everyMessageEndpointUsesItsFrozenFineGrainedAuthority() {
    assertAuthority(EngagementMessageAdminController.class, "messages", "content:message:read");
    assertAuthority(EngagementMessageAdminController.class, "message", "content:message:read");
    assertAuthority(EngagementMessageAdminController.class, "create", "content:message:edit");
    assertAuthority(EngagementMessageAdminController.class, "update", "content:message:edit");
    assertAuthority(EngagementMessageAdminController.class, "send", "content:message:send");
    assertAuthority(
        EngagementMessageAdminController.class, "cancelSchedule", "content:message:send");
    assertAuthority(EngagementMessageAdminController.class, "delete", "content:message:delete");
    assertAuthority(EngagementMessageAdminController.class, "restore", "content:message:delete");
  }

  @Test
  void userSearchRequiresAdminAndEitherContentEditorAuthority() throws Exception {
    PreAuthorize guard = AdminUserSearchController.class
        .getMethod("search", String.class, int.class, int.class)
        .getAnnotation(PreAuthorize.class);
    assertThat(guard).isNotNull();
    assertThat(guard.value()).isEqualTo(
        "hasRole('ADMIN') and (hasAuthority('content:campaign:edit') or "
            + "hasAuthority('content:message:edit'))");
  }

  @Test
  void contentAssetUploadRequiresAdminAndEitherContentEditorAuthority() throws Exception {
    PreAuthorize guard = ContentAssetAdminController.class
        .getMethod(
            "upload",
            com.fxplatform.common.security.UserPrincipal.class,
            org.springframework.web.multipart.MultipartFile.class)
        .getAnnotation(PreAuthorize.class);
    assertThat(guard).isNotNull();
    assertThat(guard.value()).isEqualTo(
        "hasRole('ADMIN') and (hasAuthority('content:campaign:edit') or "
            + "hasAuthority('content:message:edit'))");
  }

  @Test
  void popupPolicyEndpointsUseReadOrDedicatedUpdateAuthority() throws Exception {
    PreAuthorize readGuard = PopupPolicyAdminController.class
        .getMethod("policy")
        .getAnnotation(PreAuthorize.class);
    PreAuthorize updateGuard = PopupPolicyAdminController.class
        .getMethod(
            "update",
            com.fxplatform.common.security.UserPrincipal.class,
            com.fxplatform.engagement.admin.support.PopupPolicyAdminDtos
                .PopupPolicyUpdateRequest.class)
        .getAnnotation(PreAuthorize.class);

    assertThat(readGuard).isNotNull();
    assertThat(readGuard.value()).isEqualTo(
        "hasRole('ADMIN') and (hasAuthority('content:campaign:read') or "
            + "hasAuthority('content:popup-policy:update'))");
    assertThat(updateGuard).isNotNull();
    assertThat(updateGuard.value()).isEqualTo(
        "hasRole('ADMIN') and hasAuthority('content:popup-policy:update')");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void ordinaryAdminWithoutFineGrainedAuthoritiesIsDeniedEveryOwnedEndpoint() {
    assertDenied(() -> legacyContentController.messages(0, 20, null, "desc", Map.of()));
    assertDenied(() -> legacyContentController.createMessage(null, null));
    assertDenied(() -> legacyContentController.updateMessage(null, null, null));
    assertDenied(() -> legacyContentController.deleteMessage(null, null, null));
    assertDenied(() -> campaignController.campaigns(
        0, 20, null, null, null, null, null, null));
    assertDenied(() -> campaignController.campaign(null));
    assertDenied(() -> campaignController.create(null, null));
    assertDenied(() -> campaignController.update(null, null, null));
    assertDenied(() -> campaignController.publish(null, null, null));
    assertDenied(() -> campaignController.pause(null, null, null));
    assertDenied(() -> campaignController.resume(null, null, null));
    assertDenied(() -> campaignController.end(null, null, null));
    assertDenied(() -> campaignController.delete(null, null, null));
    assertDenied(() -> campaignController.restore(null, null, null));
    assertDenied(() -> campaignController.resetDelivery(null, null, null));
    assertDenied(() -> campaignController.testPopup(null, null, null));
    assertDenied(() -> campaignController.stats(null));
    assertDenied(() -> campaignController.users(null, null, 0, 20, null));
    assertDenied(() -> messageController.messages(0, 20, null, null));
    assertDenied(() -> messageController.message(null));
    assertDenied(() -> messageController.create(null, null));
    assertDenied(() -> messageController.update(null, null, null));
    assertDenied(() -> messageController.send(null, null, null));
    assertDenied(() -> messageController.cancelSchedule(null, null, null));
    assertDenied(() -> messageController.delete(null, null, null));
    assertDenied(() -> messageController.restore(null, null, null));
    assertDenied(() -> userSearchController.search(null, 0, 20));
    assertDenied(() -> contentAssetController.upload(null, null));
    assertDenied(policyController::policy);
    assertDenied(() -> policyController.update(null, null));
  }

  @Test
  @WithMockUser(authorities = {
      "ROLE_ADMIN", AdminPermissionCatalog.CONTENT_CAMPAIGN_EDIT
  })
  void campaignEditDoesNotGrantPublish() {
    assertDenied(() -> campaignController.publish(null, null, null));
  }

  @Test
  @WithMockUser(authorities = {
      "ROLE_ADMIN", AdminPermissionCatalog.CONTENT_CAMPAIGN_STATS
  })
  void campaignStatsDoesNotGrantUserDetail() {
    assertDenied(() -> campaignController.users(null, null, 0, 20, null));
  }

  @Test
  @WithMockUser(authorities = {
      "ROLE_ADMIN", AdminPermissionCatalog.CONTENT_CAMPAIGN_DELETE
  })
  void campaignDeleteDoesNotGrantMessageDelete() {
    assertDenied(() -> messageController.delete(null, null, null));
  }

  @Test
  @WithMockUser(authorities = {
      "ROLE_ADMIN", AdminPermissionCatalog.CONTENT_MESSAGE_DELETE
  })
  void messageDeleteDoesNotGrantCampaignDelete() {
    assertDenied(() -> campaignController.delete(null, null, null));
  }

  @Test
  @WithMockUser(authorities = {
      "ROLE_ADMIN", AdminPermissionCatalog.CONTENT_CAMPAIGN_PUBLISH
  })
  void campaignPublishDoesNotGrantPopupPolicyUpdate() {
    assertDenied(() -> policyController.update(null, null));
  }

  @Test
  @WithMockUser(authorities = {
      "ROLE_ADMIN", AdminPermissionCatalog.CONTENT_CAMPAIGN_READ
  })
  void campaignReaderCanReadPopupPolicy() {
    assertThatCode(policyController::policy).doesNotThrowAnyException();
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void ordinaryAdminReceivesHttp403WithoutCallingPopupPolicyService() throws Exception {
    mockMvc.perform(get("/api/admin/engagement/popup-policy"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(policyService);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void ordinaryAdminReceivesHttp403WithoutCallingContentAssetService() throws Exception {
    mockMvc.perform(multipart("/api/admin/engagement/assets")
            .file("file", new byte[]{1, 2, 3}))
        .andExpect(status().isForbidden());
    verifyNoInteractions(contentAssetService);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void ordinaryAdminReceivesHttp403WithoutCallingCampaignListService() throws Exception {
    mockMvc.perform(get("/api/admin/engagement/campaigns"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(campaignAdminService);
  }

  @Test
  @WithMockUser(authorities = {
      "ROLE_ADMIN", AdminPermissionCatalog.CONTENT_CAMPAIGN_EDIT
  })
  void campaignEditorCanUseUserSearch() {
    assertThatCode(() -> userSearchController.search(null, 0, 20)).doesNotThrowAnyException();
  }

  @Test
  @WithMockUser(authorities = {
      "ROLE_ADMIN", AdminPermissionCatalog.CONTENT_MESSAGE_EDIT
  })
  void messageEditorCanUseUserSearch() {
    assertThatCode(() -> userSearchController.search(null, 0, 20)).doesNotThrowAnyException();
  }

  private static void assertAuthority(String methodName, String authority, Class<?>... parameters)
      throws Exception {
    Method method = AdminContentController.class.getMethod(methodName, parameters);
    assertThat(method.getAnnotation(PreAuthorize.class))
        .extracting(PreAuthorize::value)
        .isEqualTo(ROLE_AND_AUTHORITY.formatted(authority));
  }

  private static void assertAuthority(Class<?> type, String methodName, String authority) {
    Method[] matches = Arrays.stream(type.getDeclaredMethods())
        .filter(method -> method.getName().equals(methodName))
        .toArray(Method[]::new);
    assertThat(matches).as(type.getSimpleName() + "." + methodName).hasSize(1);
    assertThat(matches[0].getAnnotation(PreAuthorize.class))
        .extracting(PreAuthorize::value)
        .isEqualTo(ROLE_AND_AUTHORITY.formatted(authority));
  }

  private static void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action).isInstanceOf(AccessDeniedException.class);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @EnableWebSecurity
  @EnableMethodSecurity
  static class MethodSecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
      http.csrf(csrf -> csrf.disable())
          .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());
      return http.build();
    }

    @Bean
    CampaignAdminService campaignAdminService() {
      return mock(CampaignAdminService.class);
    }

    @Bean
    CampaignAdminController campaignAdminController(CampaignAdminService service) {
      return new CampaignAdminController(service);
    }

    @Bean
    EngagementMessageAdminService engagementMessageAdminService() {
      return mock(EngagementMessageAdminService.class);
    }

    @Bean
    EngagementMessageAdminController engagementMessageAdminController(
        EngagementMessageAdminService service
    ) {
      return new EngagementMessageAdminController(service);
    }

    @Bean
    AdminUserSearchService adminUserSearchService() {
      return mock(AdminUserSearchService.class);
    }

    @Bean
    AdminUserSearchController adminUserSearchController(AdminUserSearchService service) {
      return new AdminUserSearchController(service);
    }

    @Bean
    PopupPolicyAdminService popupPolicyAdminService() {
      return mock(PopupPolicyAdminService.class);
    }

    @Bean
    PopupPolicyAdminController popupPolicyAdminController(PopupPolicyAdminService service) {
      return new PopupPolicyAdminController(service);
    }

    @Bean
    ContentAssetService contentAssetService() {
      return mock(ContentAssetService.class);
    }

    @Bean
    ContentAssetAdminController contentAssetAdminController(ContentAssetService service) {
      return new ContentAssetAdminController(service);
    }

    @Bean
    AdminContentQueryService adminContentQueryService() {
      return mock(AdminContentQueryService.class);
    }

    @Bean
    AdminContentCommandService adminContentCommandService() {
      return mock(AdminContentCommandService.class);
    }

    @Bean
    AdminContentController adminContentController(
        AdminContentQueryService queryService,
        AdminContentCommandService commandService
    ) {
      return new AdminContentController(queryService, commandService);
    }
  }
}
