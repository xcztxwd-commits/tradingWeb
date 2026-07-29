package com.fxplatform.validation.engine;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fxplatform.validation.service.ValidationLoopbackHttpClient.Command;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.HttpHop;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.HttpResult;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.LookupResult;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.LookupStatus;
import com.fxplatform.validation.service.ValidationLoopbackHttpClient.Operation;
import com.fxplatform.validation.service.ValidationRunEngine.ExpectedHttpError;
import com.fxplatform.validation.service.ValidationRunEngine.PublicAction;
import com.fxplatform.validation.service.ValidationRunEngine.PublicActionType;
import com.fxplatform.validation.service.ValidationRunEngine.StartRequest;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.Completion;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.OperationIntent;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.OperationState;
import com.fxplatform.validation.service.ValidationRunRuntimeStore.State;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ValidationExpectedHttpErrorTest {

  private static final UUID ACTION_ID = UUID.fromString(
      "00000000-0000-0000-0000-000000000091");
  private static final UUID LATER_SAME_TICK_ID = UUID.fromString(
      "00000000-0000-0000-0000-000000000092");
  private static final UUID LATER_NEXT_TICK_ID = UUID.fromString(
      "00000000-0000-0000-0000-000000000093");
  private static final String ACTION_KEY =
      ValidationRunEngineIT.RUN_ID + ":" + ACTION_ID + ":0";
  private static final UUID OPERATION_ID = UUID.nameUUIDFromBytes(
      ACTION_KEY.getBytes(StandardCharsets.UTF_8));

  @Test
  void exactExpected4xxCompletesAndPreservesSanitizedEvidence() {
    ValidationRunEngineIT.Harness harness = harness(expected("ORDER_QUANTITY_INVALID"));
    HttpResult result = error(400, "ORDER_QUANTITY_INVALID");
    when(harness.loopback.execute(publicAction())).thenReturn(result);

    harness.engine.execute(ValidationRunEngineIT.RUN_ID, false);

    verify(harness.runtime).completeIntent(
        eq(ValidationRunEngineIT.CLAIM),
        eq(OPERATION_ID),
        eq(Completion.SUCCEEDED),
        eq(result),
        argThat(writes -> writes.stream().anyMatch(event ->
            "API_TRACE".equals(event.type())
                && "EXPECTED_ERROR".equals(event.payload().get("outcome"))
                && Integer.valueOf(400).equals(event.payload().get("status"))
                && Integer.valueOf(400).equals(event.payload().get("expectedStatus"))
                && "ORDER_QUANTITY_INVALID".equals(event.payload().get("expectedCode"))
                && Map.of(
                    "code", "ORDER_QUANTITY_INVALID",
                    "message", "sanitized rejection").equals(event.payload().get("response")))));
    verify(harness.runtime, never()).failIntent(
        eq(ValidationRunEngineIT.CLAIM), eq(OPERATION_ID), any(), any(), any(), any());
    verify(harness.runtime, never()).transition(
        eq(ValidationRunEngineIT.CLAIM), eq(State.FAILED), any(), any());
  }

  @Test
  void realHttpHopsAndCommandSummaryAreCompletedAtomicallyWithDistinctStableKeys() {
    ValidationRunEngineIT.Harness harness = harness(expected("ORDER_QUANTITY_INVALID"));
    HttpResult result = new HttpResult(
        400,
        "negative-correlation",
        Map.of("code", "ORDER_QUANTITY_INVALID", "message", "sanitized rejection"),
        List.of(
            new HttpHop(
                "GET",
                "/api/accounts/demo/summary",
                200,
                "negative-correlation",
                4L,
                safeTrace("GET", "/api/accounts/demo/summary", 200, 4L)),
            new HttpHop(
                "POST",
                "/api/trading/orders",
                400,
                "negative-correlation",
                7L,
                safeTrace("POST", "/api/trading/orders", 400, 7L))));
    when(harness.loopback.execute(publicAction())).thenReturn(result);

    harness.engine.execute(ValidationRunEngineIT.RUN_ID, false);

    verify(harness.runtime).completeIntent(
        eq(ValidationRunEngineIT.CLAIM),
        eq(OPERATION_ID),
        eq(Completion.SUCCEEDED),
        eq(result),
        argThat(writes -> {
          var traces =
              writes.stream().filter(event -> "API_TRACE".equals(event.type())).toList();
          return traces.size() == 3
              && traces.stream().map(event -> event.durableKey()).distinct().count() == 3L
              && traces.stream().filter(event ->
                  "HTTP_HOP".equals(event.payload().get("traceScope"))).count() == 2L
              && traces.stream().filter(event ->
                  "COMMAND".equals(event.payload().get("traceScope"))).count() == 1L
              && traces.stream().allMatch(event -> event.payload().get("trace") instanceof Map)
              && traces.stream().anyMatch(event ->
                  "COMMAND".equals(event.payload().get("traceScope"))
                      && "EXPECTED_ERROR".equals(event.payload().get("outcome"))
                      && Integer.valueOf(400).equals(event.payload().get("expectedStatus"))
                      && "ORDER_QUANTITY_INVALID".equals(event.payload().get("expectedCode")));
        }));
  }

  @Test
  void expectedCodeMismatchFailsAndStillPreservesTheReal4xx() {
    ValidationRunEngineIT.Harness harness = harness(expected("EXPECTED_CODE"));
    HttpResult actual = error(400, "ACTUAL_CODE");
    when(harness.loopback.execute(publicAction())).thenReturn(actual);

    harness.engine.execute(ValidationRunEngineIT.RUN_ID, false);

    verify(harness.runtime).failIntent(
        eq(ValidationRunEngineIT.CLAIM),
        eq(OPERATION_ID),
        eq("VALIDATION_EXPECTED_HTTP_ERROR_MISMATCH"),
        eq("VALIDATION_EXPECTED_HTTP_ERROR_MISMATCH"),
        eq(actual),
        argThat(writes -> writes.stream().anyMatch(event ->
            "API_TRACE".equals(event.type())
                && Integer.valueOf(400).equals(event.payload().get("status"))
                && "ACTUAL_CODE".equals(
                    ((Map<?, ?>) event.payload().get("response")).get("code")))));
    verify(harness.runtime).transition(
        eq(ValidationRunEngineIT.CLAIM),
        eq(State.FAILED),
        eq("VALIDATION_EXPECTED_HTTP_ERROR_MISMATCH"),
        any());
  }

  @Test
  void expectedStatusMismatchFailsAndStillPreservesTheReal4xx() {
    ValidationRunEngineIT.Harness harness = harness(expected("EXPECTED_CODE"));
    HttpResult actual = error(409, "EXPECTED_CODE");
    when(harness.loopback.execute(publicAction())).thenReturn(actual);

    harness.engine.execute(ValidationRunEngineIT.RUN_ID, false);

    verify(harness.runtime).failIntent(
        eq(ValidationRunEngineIT.CLAIM),
        eq(OPERATION_ID),
        eq("VALIDATION_EXPECTED_HTTP_ERROR_MISMATCH"),
        eq("VALIDATION_EXPECTED_HTTP_ERROR_MISMATCH"),
        eq(actual),
        argThat(writes -> writes.stream().anyMatch(event ->
            "API_TRACE".equals(event.type())
                && Integer.valueOf(409).equals(event.payload().get("status"))
                && "EXPECTED_CODE".equals(
                    ((Map<?, ?>) event.payload().get("response")).get("code")))));
  }

  @Test
  void expectedErrorWithoutResponseCodeFailsClosed() {
    ValidationRunEngineIT.Harness harness = harness(expected("EXPECTED_CODE"));
    HttpResult missingCode = new HttpResult(
        400,
        "negative-correlation",
        Map.of("message", "sanitized rejection"));
    when(harness.loopback.execute(publicAction())).thenReturn(missingCode);

    harness.engine.execute(ValidationRunEngineIT.RUN_ID, false);

    verify(harness.runtime).failIntent(
        eq(ValidationRunEngineIT.CLAIM),
        eq(OPERATION_ID),
        eq("VALIDATION_EXPECTED_HTTP_ERROR_MISMATCH"),
        eq("VALIDATION_EXPECTED_HTTP_ERROR_MISMATCH"),
        eq(missingCode),
        any());
  }

  @Test
  void expectedErrorReturning2xxFailsInsteadOfBecomingAFalsePositive() {
    ValidationRunEngineIT.Harness harness = harness(expected("ORDER_QUANTITY_INVALID"));

    harness.engine.execute(ValidationRunEngineIT.RUN_ID, false);

    verify(harness.runtime).failIntent(
        eq(ValidationRunEngineIT.CLAIM),
        eq(OPERATION_ID),
        eq("VALIDATION_EXPECTED_HTTP_ERROR_NOT_OBSERVED"),
        eq("VALIDATION_EXPECTED_HTTP_ERROR_NOT_OBSERVED"),
        any(HttpResult.class),
        any());
    verify(harness.runtime).transition(
        eq(ValidationRunEngineIT.CLAIM),
        eq(State.FAILED),
        eq("VALIDATION_EXPECTED_HTTP_ERROR_NOT_OBSERVED"),
        any());
  }

  @Test
  void ordinaryAction4xxFailsButKeepsStatusCodeCorrelationAndBodyEvidence() {
    ValidationRunEngineIT.Harness harness = harness(null);
    HttpResult actual = error(422, "ORDER_NOTIONAL_TOO_SMALL");
    when(harness.loopback.execute(publicAction())).thenReturn(actual);

    harness.engine.execute(ValidationRunEngineIT.RUN_ID, false);

    verify(harness.runtime).failIntent(
        eq(ValidationRunEngineIT.CLAIM),
        eq(OPERATION_ID),
        eq("VALIDATION_LOOPBACK_FAILED"),
        eq("VALIDATION_LOOPBACK_FAILED"),
        eq(actual),
        argThat(writes -> writes.stream().anyMatch(event ->
            "API_TRACE".equals(event.type())
                && Integer.valueOf(422).equals(event.payload().get("status"))
                && event.correlationId().equals("negative-correlation")
                && "ORDER_NOTIONAL_TOO_SMALL".equals(
                    ((Map<?, ?>) event.payload().get("response")).get("code")))));
  }

  @Test
  void ordinaryAction409RecordsExactFailurePointAndOrderedUnexecutedActions() {
    StartRequest request = requestWithLaterActions();
    ValidationRunEngineIT.Harness harness = new ValidationRunEngineIT.Harness(request);
    HttpResult actual = error(409, "INSUFFICIENT_BALANCE");
    when(harness.loopback.execute(publicAction())).thenReturn(actual);

    harness.engine.execute(ValidationRunEngineIT.RUN_ID, false);

    verify(harness.runtime).failIntent(
        eq(ValidationRunEngineIT.CLAIM),
        eq(OPERATION_ID),
        eq("VALIDATION_LOOPBACK_FAILED"),
        eq("VALIDATION_LOOPBACK_FAILED"),
        eq(actual),
        argThat(writes -> writes.stream().anyMatch(event ->
            "RUN_EXECUTION_FAILED".equals(event.type())
                && Map.of(
                    "operation", "PUBLIC_ACTION",
                    "actionId", ACTION_ID.toString(),
                    "tickSequence", 1L,
                    "actionSequence", 1L,
                    "status", 409,
                    "code", "INSUFFICIENT_BALANCE")
                    .equals(event.payload().get("failurePoint"))
                && List.of(
                    Map.of(
                        "actionId", LATER_SAME_TICK_ID.toString(),
                        "tickSequence", 1L,
                        "actionSequence", 2L,
                        "type", "PLACE_ORDER"),
                    Map.of(
                        "actionId", LATER_NEXT_TICK_ID.toString(),
                        "tickSequence", 2L,
                        "actionSequence", 0L,
                        "type", "PLACE_ORDER"))
                    .equals(event.payload().get("unexecuted")))));
    verify(harness.runtime).transition(
        eq(ValidationRunEngineIT.CLAIM),
        eq(State.FAILED),
        eq("VALIDATION_LOOPBACK_FAILED"),
        any());
  }

  @Test
  void expected4xxNeverAccepts5xxAndRetainsUncertainRecovery() {
    ValidationRunEngineIT.Harness harness = harness(expected("ORDER_QUANTITY_INVALID"));
    HttpResult observed = error(500, "INTERNAL_ERROR");
    when(harness.loopback.execute(publicAction())).thenReturn(observed);
    when(harness.loopback.lookup(publicAction()))
        .thenReturn(new LookupResult(LookupStatus.UNKNOWN, null));

    harness.engine.execute(ValidationRunEngineIT.RUN_ID, false);

    verify(harness.runtime, never()).completeIntent(
        eq(ValidationRunEngineIT.CLAIM), eq(OPERATION_ID), any(), any(), any());
    verify(harness.runtime, never()).failIntent(
        eq(ValidationRunEngineIT.CLAIM), eq(OPERATION_ID), any(), any(), any(), any());
    verify(harness.runtime).recordFirstHttpObservation(
        eq(ValidationRunEngineIT.CLAIM),
        eq(OPERATION_ID),
        eq(observed),
        argThat(event ->
            "API_TRACE".equals(event.type())
                && event.durableKey().startsWith("api-observation:")
                && "UNCERTAIN_HTTP_RESPONSE".equals(event.payload().get("outcome"))
                && Integer.valueOf(500).equals(event.payload().get("status"))
                && observed.correlationId().equals(event.correlationId())
                && observed.body().equals(event.payload().get("response"))));
    verify(harness.runtime).transition(
        eq(ValidationRunEngineIT.CLAIM),
        eq(State.RECOVERY_BLOCKED),
        eq("UNCERTAIN_OPERATION_OUTCOME"),
        any());
  }

  @Test
  void frozenExpectedErrorIsReusedOnlyWhenItStillMatchesTheActionContract() {
    PublicAction action = action(expected("ORDER_QUANTITY_INVALID"));
    StartRequest request = request(action);
    ValidationRunEngineIT.Harness harness = new ValidationRunEngineIT.Harness(request);
    HttpResult frozen = error(400, "ORDER_QUANTITY_INVALID");
    when(harness.runtime.persistIntent(eq(ValidationRunEngineIT.CLAIM), any()))
        .thenAnswer(invocation -> {
          Command command = invocation.getArgument(1);
          if (command.operation() == Operation.PUBLIC_ACTION) {
            return new OperationIntent(
                OPERATION_ID,
                command,
                Instant.parse("2026-07-23T18:00:00Z"),
                OperationState.COMPLETED,
                frozen);
          }
          return new OperationIntent(
              UUID.nameUUIDFromBytes(
                  command.idempotencyKey().getBytes(StandardCharsets.UTF_8)),
              command,
              Instant.parse("2026-07-23T18:00:00Z"),
              OperationState.NEW,
              null);
        });

    harness.engine.execute(ValidationRunEngineIT.RUN_ID, false);

    verify(harness.loopback, never()).execute(publicAction());
    verify(harness.runtime, never()).transition(
        eq(ValidationRunEngineIT.CLAIM), eq(State.FAILED), any(), any());
  }

  @Test
  void frozenExpectedErrorIsRecognizedFromThePersistedJsonShape() {
    ValidationRunEngineIT.Harness harness =
        new ValidationRunEngineIT.Harness(request(action(expected("ORDER_QUANTITY_INVALID"))));
    HttpResult frozen = error(400, "ORDER_QUANTITY_INVALID");
    when(harness.runtime.persistIntent(eq(ValidationRunEngineIT.CLAIM), any()))
        .thenAnswer(invocation -> {
          Command command = invocation.getArgument(1);
          if (command.operation() != Operation.PUBLIC_ACTION) {
            return new OperationIntent(
                UUID.nameUUIDFromBytes(
                    command.idempotencyKey().getBytes(StandardCharsets.UTF_8)),
                command,
                Instant.parse("2026-07-23T18:00:00Z"),
                OperationState.NEW,
                null);
          }
          Command persisted = new Command(
              command.operation(),
              command.runId(),
              command.generation(),
              command.tickSequence(),
              command.idempotencyKey(),
              command.requestFingerprint(),
              Map.of(
                  "actionId", ACTION_ID.toString(),
                  "expectedError", Map.of(
                      "status", 400,
                      "code", "ORDER_QUANTITY_INVALID")));
          return new OperationIntent(
              OPERATION_ID,
              persisted,
              Instant.parse("2026-07-23T18:00:00Z"),
              OperationState.COMPLETED,
              frozen);
        });

    harness.engine.execute(ValidationRunEngineIT.RUN_ID, false);

    verify(harness.loopback, never()).execute(publicAction());
    verify(harness.runtime, never()).transition(
        eq(ValidationRunEngineIT.CLAIM), eq(State.FAILED), any(), any());
  }

  private static ValidationRunEngineIT.Harness harness(ExpectedHttpError expectedError) {
    return new ValidationRunEngineIT.Harness(request(action(expectedError)));
  }

  private static StartRequest request(PublicAction action) {
    StartRequest base = ValidationRunEngineIT.request();
    return new StartRequest(
        base.runId(),
        base.generation(),
        base.requestFingerprint(),
        base.seed(),
        base.virtualStart(),
        base.executionPolicy(),
        base.initialBalances(),
        base.accountSettings(),
        List.of(ValidationRunEngineIT.tick(1L)),
        List.of(action),
        base.speedMultiplier());
  }

  private static StartRequest requestWithLaterActions() {
    StartRequest base = ValidationRunEngineIT.request();
    PublicAction failed = action(null);
    PublicAction laterSameTick = new PublicAction(
        LATER_SAME_TICK_ID,
        1L,
        2L,
        PublicActionType.PLACE_ORDER,
        "later-same-tick",
        Map.of(
            "symbol", "BTCUSDT",
            "side", "BUY",
            "orderType", "MARKET",
            "quantity", "1"));
    PublicAction laterNextTick = new PublicAction(
        LATER_NEXT_TICK_ID,
        2L,
        0L,
        PublicActionType.PLACE_ORDER,
        "later-next-tick",
        Map.of(
            "symbol", "BTCUSDT",
            "side", "BUY",
            "orderType", "MARKET",
            "quantity", "1"));
    return new StartRequest(
        base.runId(),
        base.generation(),
        base.requestFingerprint(),
        base.seed(),
        base.virtualStart(),
        base.executionPolicy(),
        base.initialBalances(),
        base.accountSettings(),
        List.of(
            ValidationRunEngineIT.tick(1L),
            ValidationRunEngineIT.tick(2L)),
        List.of(failed, laterSameTick, laterNextTick),
        base.speedMultiplier());
  }

  private static PublicAction action(ExpectedHttpError expectedError) {
    return new PublicAction(
        ACTION_ID,
        1L,
        1L,
        PublicActionType.PLACE_ORDER,
        "negative-action",
        Map.of(
            "symbol", "BTCUSDT",
            "side", "BUY",
            "orderType", "MARKET",
            "quantity", "0"),
        expectedError);
  }

  private static ExpectedHttpError expected(String code) {
    return new ExpectedHttpError(400, code);
  }

  private static HttpResult error(int status, String code) {
    return new HttpResult(
        status,
        "negative-correlation",
        Map.of("code", code, "message", "sanitized rejection"));
  }

  private static Map<String, Object> safeTrace(
      String method,
      String path,
      int status,
      long durationMillis
  ) {
    LinkedHashMap<String, Object> request = new LinkedHashMap<>();
    request.put("sequence", 1L);
    request.put("environment", "validation");
    request.put("method", method);
    request.put("url", "http://127.0.0.1:8080" + path);
    request.put("virtualTime", null);
    request.put("realTime", "2026-07-23T18:00:00Z");
    request.put("sanitizedRequest", Map.of());
    LinkedHashMap<String, Object> response = new LinkedHashMap<>();
    response.put("status", status);
    response.put("duration", durationMillis);
    response.put("traceId", null);
    response.put("correlationId", "negative-correlation");
    response.put("recordedException", null);
    response.put("sanitizedResponse", Map.of());
    LinkedHashMap<String, Object> trace = new LinkedHashMap<>();
    trace.put("url", "http://127.0.0.1:8080" + path);
    trace.put("queryParameters", Map.of());
    trace.put("requestHeaders", Map.of());
    trace.put("requestContentType", "application/json");
    trace.put("requestBody", request);
    trace.put("responseHeaders", Map.of());
    trace.put("responseContentType", "application/json");
    trace.put("responseBody", response);
    trace.put("exception", null);
    trace.put("authentication", null);
    return trace;
  }

  private static Command publicAction() {
    return argThat(command -> command.operation() == Operation.PUBLIC_ACTION);
  }
}
