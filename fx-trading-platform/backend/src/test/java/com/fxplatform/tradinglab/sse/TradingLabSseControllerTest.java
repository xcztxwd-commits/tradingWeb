package com.fxplatform.tradinglab.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fxplatform.admin.service.AdminPermissionCatalog;
import com.fxplatform.common.exception.GlobalExceptionHandler;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class TradingLabSseControllerTest {

  @Test
  void routeIsTheFrozenRawEventStreamEndpointAndDelegatesLastEventId() throws Exception {
    TradingLabSseService service = mock(TradingLabSseService.class);
    TradingLabSseController controller = new TradingLabSseController(service);
    UUID runId = UUID.fromString("10000000-0000-0000-0000-000000000008");
    SseEmitter response = new SseEmitter();
    when(service.events(runId, "17")).thenReturn(response);

    assertThat(controller.events(runId, "17")).isSameAs(response);
    verify(service).events(runId, "17");

    RequestMapping base = TradingLabSseController.class.getAnnotation(RequestMapping.class);
    assertThat(base.value()).containsExactly("/api/admin/trading-lab/runs");
    Method method = TradingLabSseController.class.getMethod(
        "events", UUID.class, String.class);
    RequestMapping route = AnnotatedElementUtils.findMergedAnnotation(
        method, RequestMapping.class);
    assertThat(route.method()).containsExactly(RequestMethod.GET);
    assertThat(route.path()).containsExactly("/{id}/events");
    assertThat(route.produces()).containsExactly(MediaType.TEXT_EVENT_STREAM_VALUE);

    RequestHeader header = method.getParameters()[1].getAnnotation(RequestHeader.class);
    assertThat(header.name()).isEqualTo("Last-Event-ID");
    assertThat(header.required()).isFalse();
  }

  @Test
  void endpointRequiresAdminRoleAndTheIndependentTradingLabViewAuthority()
      throws Exception {
    Method method = TradingLabSseController.class.getMethod(
        "events", UUID.class, String.class);

    assertThat(method.getAnnotation(PreAuthorize.class))
        .extracting(PreAuthorize::value)
        .isEqualTo(
            "hasRole('ADMIN') and hasAuthority('"
                + AdminPermissionCatalog.TRADING_LAB_VIEW
                + "')");
  }

  @Test
  void narrowErrorAdviceReturnsRawEmpty400And404Responses() {
    TradingLabSseExceptionHandler handler = new TradingLabSseExceptionHandler();

    assertThat(handler.handle(TradingLabSseRequestException.badLastEventId()))
        .satisfies(response -> {
          assertThat(response.getStatusCode().value()).isEqualTo(400);
          assertThat(response.getBody()).isNull();
        });
    assertThat(handler.handle(TradingLabSseRequestException.runNotFound()))
        .satisfies(response -> {
          assertThat(response.getStatusCode().value()).isEqualTo(404);
          assertThat(response.getBody()).isNull();
        });
  }

  @Test
  void malformedRunIdReturnsRaw400BeforeTheServiceIsInvoked() throws Exception {
    TradingLabSseService service = mock(TradingLabSseService.class);
    MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new TradingLabSseController(service))
        .setControllerAdvice(
            new TradingLabSseExceptionHandler(),
            new GlobalExceptionHandler())
        .build();

    mvc.perform(get("/api/admin/trading-lab/runs/not-a-uuid/events")
            .accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(""));
    verifyNoInteractions(service);

    assertThat(Arrays.stream(TradingLabSseExceptionHandler.class.getDeclaredMethods())
        .map(method -> method.getAnnotation(ExceptionHandler.class))
        .filter(annotation -> annotation != null)
        .flatMap(annotation -> Arrays.stream(annotation.value())))
        .contains(MethodArgumentTypeMismatchException.class);
  }
}
