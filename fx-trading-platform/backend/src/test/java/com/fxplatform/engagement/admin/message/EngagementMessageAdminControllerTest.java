package com.fxplatform.engagement.admin.message;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.admin.service.AdminPermissionCatalog;
import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.engagement.admin.message.MessageAdminDtos.MessageActionRequest;
import com.fxplatform.engagement.admin.message.MessageAdminDtos.MessageSaveRequest;
import com.fxplatform.engagement.admin.message.MessageAdminDtos.MessageSendRequest;
import com.fxplatform.engagement.admin.message.MessageAdminDtos.MessageUpdateRequest;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class EngagementMessageAdminControllerTest {

  @Test
  void exposesExactlyTheEightManualMessageRoutesWithRoleAndFineGrainedAuthority() {
    RequestMapping root = EngagementMessageAdminController.class.getAnnotation(RequestMapping.class);
    assertThat(root.value()).containsExactly("/api/admin/engagement/messages");

    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("GET ", AdminPermissionCatalog.CONTENT_MESSAGE_READ);
    expected.put("POST ", AdminPermissionCatalog.CONTENT_MESSAGE_EDIT);
    expected.put("GET /{id}", AdminPermissionCatalog.CONTENT_MESSAGE_READ);
    expected.put("PUT /{id}", AdminPermissionCatalog.CONTENT_MESSAGE_EDIT);
    expected.put("POST /{id}/send", AdminPermissionCatalog.CONTENT_MESSAGE_SEND);
    expected.put("POST /{id}/cancel-schedule", AdminPermissionCatalog.CONTENT_MESSAGE_SEND);
    expected.put("DELETE /{id}", AdminPermissionCatalog.CONTENT_MESSAGE_DELETE);
    expected.put("POST /{id}/restore", AdminPermissionCatalog.CONTENT_MESSAGE_DELETE);

    Map<String, String> actual = new LinkedHashMap<>();
    for (Method method : EngagementMessageAdminController.class.getDeclaredMethods()) {
      String route = route(method);
      if (route == null) {
        continue;
      }
      PreAuthorize security = method.getAnnotation(PreAuthorize.class);
      assertThat(security).as(method.getName()).isNotNull();
      actual.put(route, security.value());
    }

    assertThat(actual).containsOnlyKeys(expected.keySet());
    expected.forEach((route, authority) -> assertThat(actual.get(route))
        .as(route)
        .isEqualTo("hasRole('ADMIN') and hasAuthority('" + authority + "')"));
  }

  @Test
  void everyMessageCommandTakesItsActorOnlyFromTheAuthenticatedPrincipal() {
    for (String methodName : java.util.Set.of(
        "create", "update", "send", "cancelSchedule", "delete", "restore")) {
      Method method = java.util.Arrays.stream(
              EngagementMessageAdminController.class.getDeclaredMethods())
          .filter(candidate -> candidate.getName().equals(methodName))
          .findFirst()
          .orElseThrow();
      assertThat(method.getParameterTypes()[0]).isEqualTo(UserPrincipal.class);
    }
    for (Class<?> requestType : java.util.List.of(
        MessageSaveRequest.class,
        MessageUpdateRequest.class,
        MessageSendRequest.class,
        MessageActionRequest.class)) {
      assertThat(requestType.getRecordComponents())
          .extracting(java.lang.reflect.RecordComponent::getName)
          .doesNotContain("actorId", "actorUserId", "createdBy", "updatedBy");
    }
  }

  private static String route(Method method) {
    GetMapping get = method.getAnnotation(GetMapping.class);
    if (get != null) {
      return "GET " + path(get.value());
    }
    PostMapping post = method.getAnnotation(PostMapping.class);
    if (post != null) {
      return "POST " + path(post.value());
    }
    PutMapping put = method.getAnnotation(PutMapping.class);
    if (put != null) {
      return "PUT " + path(put.value());
    }
    DeleteMapping delete = method.getAnnotation(DeleteMapping.class);
    return delete == null ? null : "DELETE " + path(delete.value());
  }

  private static String path(String[] values) {
    return values.length == 0 ? "" : values[0];
  }
}
