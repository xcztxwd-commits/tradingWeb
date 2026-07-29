package com.fxplatform.tradinglab.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.audit.service.AuditLogService;
import com.fxplatform.tradinglab.entity.TradingLabAuditEventEntity;
import com.fxplatform.tradinglab.repository.TradingLabAuditEventRepository;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

class TradingLabAuditServiceTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<String> RAW_SECRETS = List.of(
      "raw-authorization",
      "raw-cookie",
      "raw-set-cookie",
      "raw-password",
      "raw-token",
      "raw-api-key",
      "raw-database-password",
      "raw-internal-secret",
      "raw-secret-access-key",
      "raw-access-key-id-env",
      "raw-access-key-id",
      "raw-access-key-id-camel",
      "raw-auth-code",
      "raw-auth-code-value",
      "raw-credential",
      "raw-credential-bundle",
      "raw-private-key",
      "raw-private-key-pem",
      "raw-encryption-key-config",
      "raw-encryption-key-camel",
      "raw-password-hash",
      "raw-authorization-header",
      "raw-client-secret-value");

  private final TradingLabAuditEventRepository auditEventRepository =
      mock(TradingLabAuditEventRepository.class);
  private final AuditLogService auditLogService = mock(AuditLogService.class);
  private final TradingLabAuditService service = new TradingLabAuditService(
      auditEventRepository,
      auditLogService,
      new TradingLabCredentialSanitizer());

  @Test
  void recordHasTheFrozenSignatureAndRunsTransactionally() throws NoSuchMethodException {
    Method record = TradingLabAuditService.class.getDeclaredMethod(
        "record",
        UUID.class,
        String.class,
        UUID.class,
        UUID.class,
        UUID.class,
        String.class,
        String.class,
        Map.class);

    assertThat(record.getReturnType()).isEqualTo(void.class);
    assertThat(record.getParameterTypes()).containsExactly(
        UUID.class,
        String.class,
        UUID.class,
        UUID.class,
        UUID.class,
        String.class,
        String.class,
        Map.class);
    assertThat(
        AnnotatedElementUtils.hasAnnotation(record, Transactional.class)
            || AnnotatedElementUtils.hasAnnotation(
                TradingLabAuditService.class,
                Transactional.class))
        .as("the lab and generic audit writes must share one Spring transaction")
        .isTrue();
  }

  @Test
  void writesCorrelatedLabAndGenericRunAuditsOnlyAfterCredentialRemoval() throws Exception {
    UUID actorId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID scenarioId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    String action = "TRADING_LAB_RUN_STARTED";
    String result = "SUCCESS";
    Map<String, Object> details = callerDetailsWithSecrets();

    service.record(
        actorId,
        "203.0.113.7",
        requestId,
        scenarioId,
        runId,
        action,
        result,
        details);

    ArgumentCaptor<TradingLabAuditEventEntity> labCaptor =
        ArgumentCaptor.forClass(TradingLabAuditEventEntity.class);
    verify(auditEventRepository).save(labCaptor.capture());
    TradingLabAuditEventEntity labEvent = labCaptor.getValue();
    assertThat(labEvent.getActorId()).isEqualTo(actorId);
    assertThat(labEvent.getClientIp()).isEqualTo("203.0.113.7");
    assertThat(labEvent.getRequestId()).isEqualTo(requestId);
    assertThat(labEvent.getScenarioId()).isEqualTo(scenarioId);
    assertThat(labEvent.getRunId()).isEqualTo(runId);
    assertThat(labEvent.getAction()).isEqualTo(action);
    assertThat(labEvent.getResult()).isEqualTo(result);

    ArgumentCaptor<String> genericDetails = ArgumentCaptor.forClass(String.class);
    verify(auditLogService).recordWithRequestId(
        eq(actorId),
        eq(action),
        eq("TRADING_LAB_RUN"),
        eq(runId.toString()),
        eq(requestId),
        genericDetails.capture());

    JsonNode labDetails = JSON.readTree(labEvent.getDetailsJson());
    JsonNode genericEnvelope = JSON.readTree(genericDetails.getValue());
    assertCredentialFree(labDetails);
    assertCredentialFree(genericEnvelope);
    assertThat(labDetails.path("safe").asText()).isEqualTo("visible");

    assertThat(genericEnvelope.path("clientIp").asText()).isEqualTo("203.0.113.7");
    assertThat(genericEnvelope.path("result").asText()).isEqualTo(result);
    assertThat(genericEnvelope.path("scenarioId").asText()).isEqualTo(scenarioId.toString());
    assertThat(genericEnvelope.path("runId").asText()).isEqualTo(runId.toString());
    assertThat(genericEnvelope.path("details").path("safe").asText()).isEqualTo("visible");

    assertThat(genericEnvelope.path("details").path("clientIp").asText())
        .isEqualTo("caller-client-ip");
    assertThat(genericEnvelope.path("details").path("result").asText())
        .isEqualTo("caller-result");
    assertThat(genericEnvelope.path("details").path("scenarioId").asText())
        .isEqualTo("caller-scenario-id");
    assertThat(genericEnvelope.path("details").path("runId").asText())
        .isEqualTo("caller-run-id");

    assertThat(details).containsEntry("Authorization", "raw-authorization");
    assertThat(((Map<?, ?>) details.get("nested")).get("password"))
        .isEqualTo("raw-password");
  }

  @Test
  void usesScenarioAsGenericTargetWhenRunIsNull() {
    UUID actorId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID scenarioId = UUID.randomUUID();

    service.record(
        actorId,
        "198.51.100.2",
        requestId,
        scenarioId,
        null,
        "TRADING_LAB_SCENARIO_UPDATED",
        "SUCCESS",
        Map.of("safe", true));

    ArgumentCaptor<TradingLabAuditEventEntity> labCaptor =
        ArgumentCaptor.forClass(TradingLabAuditEventEntity.class);
    verify(auditEventRepository).save(labCaptor.capture());
    assertThat(labCaptor.getValue().getScenarioId()).isEqualTo(scenarioId);
    assertThat(labCaptor.getValue().getRunId()).isNull();
    verify(auditLogService).recordWithRequestId(
        eq(actorId),
        eq("TRADING_LAB_SCENARIO_UPDATED"),
        eq("TRADING_LAB_SCENARIO"),
        eq(scenarioId.toString()),
        eq(requestId),
        any(String.class));
  }

  @Test
  void supportsNullScenarioAndRunAndFallsBackToRequestTarget() throws Exception {
    UUID actorId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();

    service.record(
        actorId,
        "192.0.2.4",
        requestId,
        null,
        null,
        "TRADING_LAB_ENVIRONMENT_CHECKED",
        "SUCCESS",
        Map.of("safe", "visible"));

    ArgumentCaptor<TradingLabAuditEventEntity> labCaptor =
        ArgumentCaptor.forClass(TradingLabAuditEventEntity.class);
    verify(auditEventRepository).save(labCaptor.capture());
    assertThat(labCaptor.getValue().getScenarioId()).isNull();
    assertThat(labCaptor.getValue().getRunId()).isNull();

    ArgumentCaptor<String> genericDetails = ArgumentCaptor.forClass(String.class);
    verify(auditLogService).recordWithRequestId(
        eq(actorId),
        eq("TRADING_LAB_ENVIRONMENT_CHECKED"),
        eq("TRADING_LAB_REQUEST"),
        eq(requestId.toString()),
        eq(requestId),
        genericDetails.capture());
    JsonNode envelope = JSON.readTree(genericDetails.getValue());
    assertThat(envelope.has("scenarioId")).isTrue();
    assertThat(envelope.path("scenarioId").isNull()).isTrue();
    assertThat(envelope.has("runId")).isTrue();
    assertThat(envelope.path("runId").isNull()).isTrue();
  }

  @Test
  void rejectsCircularDetailsBeforeEitherAuditWrite() {
    Map<String, Object> cyclic = new LinkedHashMap<>();
    cyclic.put("self", cyclic);

    assertFailsClosedBeforeWrites(cyclic);
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void rejectsNonStringKeysBeforeEitherAuditWrite() {
    Map invalid = new LinkedHashMap();
    invalid.put(7, "not-a-json-key");

    assertFailsClosedBeforeWrites((Map<String, Object>) invalid);
  }

  @Test
  void rejectsNonJsonValuesBeforeEitherAuditWrite() {
    assertFailsClosedBeforeWrites(Map.of("unsupported", new Object()));
  }

  private void assertFailsClosedBeforeWrites(Map<String, Object> details) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.record(
            UUID.randomUUID(),
            "127.0.0.1",
            UUID.randomUUID(),
            null,
            null,
            "TRADING_LAB_INVALID_AUDIT",
            "FAILED",
            details));

    verifyNoInteractions(auditEventRepository, auditLogService);
  }

  private static Map<String, Object> callerDetailsWithSecrets() {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("safe", "visible");
    details.put("clientIp", "caller-client-ip");
    details.put("result", "caller-result");
    details.put("scenarioId", "caller-scenario-id");
    details.put("runId", "caller-run-id");
    details.put("Authorization", "raw-authorization");
    details.put("nested", Map.ofEntries(
        Map.entry("cookie", "raw-cookie"),
        Map.entry("Set-Cookie", "raw-set-cookie"),
        Map.entry("password", "raw-password"),
        Map.entry("token", "raw-token"),
        Map.entry("Api-Key", "raw-api-key"),
        Map.entry("Database-Password", "raw-database-password"),
        Map.entry("INTERNAL-SECRET", "raw-internal-secret"),
        Map.entry("MASSIVE_S3_SECRET_ACCESS_KEY", "raw-secret-access-key"),
        Map.entry("MASSIVE_S3_ACCESS_KEY_ID", "raw-access-key-id-env"),
        Map.entry("access-key-id", "raw-access-key-id"),
        Map.entry("accessKeyId", "raw-access-key-id-camel"),
        Map.entry("auth-code", "raw-auth-code"),
        Map.entry("authCodeValue", "raw-auth-code-value"),
        Map.entry("credential", "raw-credential"),
        Map.entry("credentialBundle", "raw-credential-bundle"),
        Map.entry("private-key", "raw-private-key"),
        Map.entry("privateKeyPem", "raw-private-key-pem"),
        Map.entry("security.config.encryption-key", "raw-encryption-key-config"),
        Map.entry("encryptionKey", "raw-encryption-key-camel"),
        Map.entry("passwordHash", "raw-password-hash"),
        Map.entry("authorizationHeader", "raw-authorization-header"),
        Map.entry("clientSecretValue", "raw-client-secret-value"),
        Map.entry("safe", "nested-visible")));
    return details;
  }

  private static void assertCredentialFree(JsonNode root) {
    String serialized = root.toString();
    RAW_SECRETS.forEach(secret -> assertThat(serialized).doesNotContain(secret));
    assertThat(serialized.toLowerCase(Locale.ROOT))
        .doesNotContain("authorization")
        .doesNotContain("\"cookie\"")
        .doesNotContain("set-cookie")
        .doesNotContain("password")
        .doesNotContain("\"token\"")
        .doesNotContain("api-key")
        .doesNotContain("database-password")
        .doesNotContain("internal-secret")
        .doesNotContain("access-key")
        .doesNotContain("auth-code")
        .doesNotContain("credential")
        .doesNotContain("private-key")
        .doesNotContain("encryption-key");
  }
}
