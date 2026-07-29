package com.fxplatform.engagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.common.security.UserPrincipal;
import com.fxplatform.engagement.application.message.MessageReceiptService;
import com.fxplatform.engagement.web.dto.UnreadMessageCountResponse;
import com.fxplatform.engagement.web.dto.UserMessagePageResponse;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class UserMessageControllerTest {

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID PUBLICATION_ID = UUID.randomUUID();

  @Test
  void exposesExactlyTheSixFrozenUserRoutesUnderUserIdentity() throws Exception {
    assertThat(UserMessageController.class.getAnnotation(RestController.class)).isNotNull();
    assertThat(UserMessageController.class.getAnnotation(RequestMapping.class).value())
        .containsExactly("/api/me/messages");
    assertThat(UserMessageController.class.getAnnotation(PreAuthorize.class).value())
        .isEqualTo("hasRole('USER')");

    assertGetRoute("list", new Class<?>[] {
        UserPrincipal.class, int.class, int.class, boolean.class}, "");
    assertGetRoute("unreadCount", new Class<?>[] {UserPrincipal.class}, "/unread-count");
    assertPostRoute("markRead", "/{publicationId}/read");
    assertPostRoute("markUnread", "/{publicationId}/unread");
    assertThat(UserMessageController.class
        .getMethod("markAllRead", UserPrincipal.class)
        .getAnnotation(PostMapping.class).value())
        .containsExactly("/read-all");
    assertPostRoute("hide", "/{publicationId}/hide");
    assertThat(Arrays.stream(UserMessageController.class.getDeclaredMethods())
        .filter(method -> method.isAnnotationPresent(GetMapping.class)
            || method.isAnnotationPresent(PostMapping.class))
        .count()).isEqualTo(6);
  }

  @Test
  void listBindsPaginationAndUnreadFilterWithoutAnyCallerSuppliedUserId() throws Exception {
    Method list = UserMessageController.class.getMethod(
        "list", UserPrincipal.class, int.class, int.class, boolean.class);
    assertThat(list.getParameterAnnotations()[0])
        .anySatisfy(annotation -> assertThat(annotation)
            .isInstanceOf(AuthenticationPrincipal.class));
    assertRequestParam(list, 1, "page", "0");
    assertRequestParam(list, 2, "size", "20");
    assertRequestParam(list, 3, "unreadOnly", "false");

    for (String methodName : List.of("markRead", "markUnread", "hide")) {
      Method method = UserMessageController.class.getMethod(
          methodName, UserPrincipal.class, UUID.class);
      assertThat(method.getParameterAnnotations()[0])
          .anySatisfy(annotation -> assertThat(annotation)
              .isInstanceOf(AuthenticationPrincipal.class));
      assertThat(method.getParameters()[1].getAnnotation(PathVariable.class).value())
          .isEqualTo("publicationId");
    }
  }

  @Test
  void everyEndpointDelegatesOnlyTheAuthenticatedPrincipalId() {
    MessageReceiptService service = org.mockito.Mockito.mock(MessageReceiptService.class);
    UserMessageController controller = new UserMessageController(service);
    UserPrincipal principal = new UserPrincipal(USER_ID, "user@example.test", "USER");
    UserMessagePageResponse page = new UserMessagePageResponse(List.of(), 1, 10, 0, 0);
    when(service.listMessages(USER_ID, 1, 10, true)).thenReturn(page);
    when(service.unreadCount(USER_ID)).thenReturn(3L);
    when(service.markAllRead(USER_ID)).thenReturn(3);

    assertThat(controller.list(principal, 1, 10, true).data()).isSameAs(page);
    assertThat(controller.unreadCount(principal).data())
        .isEqualTo(new UnreadMessageCountResponse(3));
    assertThat(controller.markRead(principal, PUBLICATION_ID).success()).isTrue();
    assertThat(controller.markUnread(principal, PUBLICATION_ID).success()).isTrue();
    assertThat(controller.markAllRead(principal).success()).isTrue();
    assertThat(controller.hide(principal, PUBLICATION_ID).success()).isTrue();

    verify(service).listMessages(USER_ID, 1, 10, true);
    verify(service).unreadCount(USER_ID);
    verify(service).markRead(USER_ID, PUBLICATION_ID);
    verify(service).markUnread(USER_ID, PUBLICATION_ID);
    verify(service).markAllRead(USER_ID);
    verify(service).hide(USER_ID, PUBLICATION_ID);
  }

  private static void assertGetRoute(
      String name,
      Class<?>[] parameters,
      String route) throws Exception {
    String[] values = UserMessageController.class
        .getMethod(name, parameters)
        .getAnnotation(GetMapping.class)
        .value();
    if (route.isEmpty()) {
      assertThat(values).isEmpty();
    } else {
      assertThat(values).containsExactly(route);
    }
  }

  private static void assertPostRoute(String name, String route) throws Exception {
    assertThat(UserMessageController.class
        .getMethod(name, UserPrincipal.class, UUID.class)
        .getAnnotation(PostMapping.class).value())
        .containsExactly(route);
  }

  private static void assertRequestParam(
      Method method,
      int parameterIndex,
      String name,
      String defaultValue) {
    RequestParam requestParam = method.getParameters()[parameterIndex]
        .getAnnotation(RequestParam.class);
    assertThat(requestParam.value()).isEqualTo(name);
    assertThat(requestParam.defaultValue()).isEqualTo(defaultValue);
  }
}
