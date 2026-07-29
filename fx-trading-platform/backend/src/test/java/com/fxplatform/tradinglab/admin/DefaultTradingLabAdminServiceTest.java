package com.fxplatform.tradinglab.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.tradinglab.admin.dto.TradingLabConfigResponse;
import com.fxplatform.tradinglab.admin.dto.TradingLabRunCreateRequest;
import com.fxplatform.tradinglab.admin.dto.TradingLabScenarioWriteRequest;
import com.fxplatform.tradinglab.application.TradingLabAuditService;
import com.fxplatform.tradinglab.application.TradingLabValidationStartRequestFactory;
import com.fxplatform.tradinglab.application.TradingLabValidationStartRequestFactory
    .TradingLabValidationStartSource;
import com.fxplatform.tradinglab.client.ValidationRunStartRequest;
import com.fxplatform.tradinglab.entity.TradingLabReportEntity;
import com.fxplatform.tradinglab.entity.TradingLabRunEntity;
import com.fxplatform.tradinglab.entity.TradingLabScenarioEntity;
import com.fxplatform.tradinglab.environment.TradingLabOperationGate;
import com.fxplatform.tradinglab.report.TradingLabReportProperties;
import com.fxplatform.tradinglab.repository.TradingLabReportRepository;
import com.fxplatform.tradinglab.repository.TradingLabRunRepository;
import com.fxplatform.tradinglab.repository.TradingLabScenarioRepository;
import com.fxplatform.tradinglab.state.RunTransitionCommand;
import com.fxplatform.tradinglab.state.TradingLabRunState;
import com.fxplatform.tradinglab.state.TradingLabRunTransitionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class DefaultTradingLabAdminServiceTest {

  private static final UUID ACTOR_ID = UUID.fromString(
      "20000000-0000-0000-0000-000000000801");
  private static final UUID SCENARIO_ID = UUID.fromString(
      "20000000-0000-0000-0000-000000000802");
  private static final UUID REQUEST_ID = UUID.fromString(
      "20000000-0000-0000-0000-000000000803");

  private final ObjectMapper json = new ObjectMapper();
  private final TradingLabScenarioCanonicalizer canonicalizer =
      new TradingLabScenarioCanonicalizer(json, 1_048_576);
  private final TradingLabScenarioRepository scenarios = mock(TradingLabScenarioRepository.class);
  private final TradingLabRunRepository runs = mock(TradingLabRunRepository.class);
  private final TradingLabReportRepository reports = mock(TradingLabReportRepository.class);
  private final TradingLabRunTransitionService transitions =
      mock(TradingLabRunTransitionService.class);
  private final TradingLabConfigProvider configProvider = mock(TradingLabConfigProvider.class);
  private final TradingLabValidationStartRequestFactory startFactory =
      mock(TradingLabValidationStartRequestFactory.class);
  private final TradingLabOperationGate gate = mock(TradingLabOperationGate.class);
  private final TradingLabAuditService audit = mock(TradingLabAuditService.class);
  private final TradingLabReportProperties reportProperties = new TradingLabReportProperties();
  private final DefaultTradingLabAdminService service = new DefaultTradingLabAdminService(
      scenarios,
      runs,
      reports,
      transitions,
      canonicalizer,
      configProvider,
      startFactory,
      gate,
      audit,
      reportProperties);

  private ObjectNode config;
  private String configHash;

  @BeforeEach
  void setUp() {
    config = json.createObjectNode();
    config.put("codeVersion", "local+working-tree");
    config.put("modelVersion", "model-v1");
    config.put("symbolConfigVersion", "symbol-v1");
    var canonical = canonicalizer.canonicalize(config);
    configHash = canonical.sha256();
    when(configProvider.current()).thenReturn(new TradingLabConfigResponse(
        canonical.value(),
        canonical.sha256(),
        "model-v1",
        "symbol-v1",
        "local+working-tree"));
    when(startFactory.compile(any(TradingLabValidationStartSource.class), eq(1L)))
        .thenAnswer(invocation -> {
          TradingLabValidationStartSource source = invocation.getArgument(0);
          return new ValidationRunStartRequest(
              source.runId(),
              1L,
              "a".repeat(64),
              "seed-007",
              Instant.parse("2026-07-24T01:00:00Z"),
              Map.of("matchingMode", "SIMPLE"),
              Map.of("USDT", new BigDecimal("100000.00000000")),
              Map.of("positionMode", "HEDGE"),
              List.of(Map.of("sequence", 1L), Map.of("sequence", 2L)),
              List.of(),
              new BigDecimal("7.000000"));
        });
  }

  @Test
  void createsOneFrozenRunAndReportWithDurableLocalCalculation() {
    TradingLabScenarioEntity scenario = scenario();
    when(scenarios.findByIdForUpdate(SCENARIO_ID)).thenReturn(Optional.of(scenario));
    when(scenarios.freezeDraft(SCENARIO_ID, 0L, configHash, ACTOR_ID)).thenReturn(1);
    when(reports.insert(any(TradingLabReportEntity.class))).thenReturn(1);
    when(runs.insert(any(TradingLabRunEntity.class))).thenReturn(1);
    AtomicReference<TradingLabRunEntity> insertedRun = new AtomicReference<>();
    org.mockito.Mockito.doAnswer(invocation -> {
      insertedRun.set(invocation.getArgument(0));
      return 1;
    }).when(runs).insert(any(TradingLabRunEntity.class));
    org.mockito.Mockito.doAnswer(invocation -> {
      RunTransitionCommand command = invocation.getArgument(0);
      TradingLabRunEntity run = insertedRun.get();
      run.setState(command.target().name());
      run.setVersion(run.getVersion() + 1L);
      return null;
    }).when(transitions).transition(any());
    when(runs.findById(any())).thenAnswer(ignored -> Optional.of(insertedRun.get()));

    var response = service.createRun(
        SCENARIO_ID,
        ACTOR_ID,
        "127.0.0.1",
        REQUEST_ID,
        new TradingLabRunCreateRequest(
            0L,
            configHash,
            json.createObjectNode().put("expectedEquity", "100000.00")));

    assertThat(response.state()).isEqualTo(TradingLabRunState.QUEUED.name());
    assertThat(response.totalTicks()).isEqualTo(2L);
    ArgumentCaptor<TradingLabRunEntity> runCaptor = ArgumentCaptor.forClass(TradingLabRunEntity.class);
    verify(runs).insert(runCaptor.capture());
    assertThat(runCaptor.getValue().getLocalCalculationJson())
        .isEqualTo("{\"expectedEquity\":\"100000\"}");
    assertThat(runCaptor.getValue().getScenarioSnapshotJson()).isEqualTo(scenario.getScenarioJson());
    assertThat(runCaptor.getValue().getVirtualStartedAt())
        .isEqualTo(Instant.parse("2026-07-24T01:00:00Z"));
    assertThat(runCaptor.getValue().getSpeedMultiplier())
        .isEqualTo(new BigDecimal("7.000000"));
    ArgumentCaptor<TradingLabReportEntity> reportCaptor =
        ArgumentCaptor.forClass(TradingLabReportEntity.class);
    verify(reports).insert(reportCaptor.capture());
    assertThat(reportCaptor.getValue().getStatus()).isEqualTo("PENDING");
    assertThat(reportCaptor.getValue().getScenarioId()).isEqualTo(SCENARIO_ID);
    verify(startFactory).compile(
        org.mockito.ArgumentMatchers.argThat(source ->
            source.scenarioSnapshotJson().equals(scenario.getScenarioJson())
                && source.expectedSeed().equals("seed-007")
                && !source.expectedNegativeMode()),
        eq(1L));
    verify(transitions, org.mockito.Mockito.times(2)).transition(any());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> auditDetails =
        ArgumentCaptor.forClass(Map.class);
    verify(audit).record(
        eq(ACTOR_ID),
        eq("127.0.0.1"),
        eq(REQUEST_ID),
        eq(SCENARIO_ID),
        eq(response.id()),
        eq("TRADING_LAB_RUN_CREATE"),
        eq("SUCCESS"),
        auditDetails.capture());
    assertThat(auditDetails.getValue())
        .containsEntry("httpMethod", "POST")
        .containsEntry(
            "httpPath",
            "/api/admin/trading-lab/scenarios/" + SCENARIO_ID + "/runs")
        .containsEntry("httpStatus", 200)
        .containsEntry("evidenceType", "ADMIN_RUN_ACCEPTED");

    InOrder order = inOrder(gate, scenarios);
    order.verify(gate).awaitRunCreationPermit();
    order.verify(scenarios).findByIdForUpdate(SCENARIO_ID);
  }

  @Test
  void createsAndReturnsTheExactStringSeed() {
    AtomicReference<TradingLabScenarioEntity> inserted = new AtomicReference<>();
    org.mockito.Mockito.doAnswer(invocation -> {
      TradingLabScenarioEntity entity = invocation.getArgument(0);
      inserted.set(entity);
      return 1;
    }).when(scenarios).insert(any(TradingLabScenarioEntity.class));
    when(scenarios.findById(any())).thenAnswer(ignored -> Optional.of(inserted.get()));

    var response = service.createScenario(
        ACTOR_ID,
        "127.0.0.1",
        REQUEST_ID,
        scenarioRequest(null));

    assertThat(inserted.get().getSeed()).isEqualTo("seed-007");
    assertThat(response.seed()).isEqualTo("seed-007");
    assertThat(response.scenario().path("seed").asText()).isEqualTo("seed-007");
  }

  @Test
  void rejectsAStoredNullSeedInsteadOfInventingAResponseIdentity() {
    TradingLabScenarioEntity scenario = scenario();
    scenario.setSeed(null);
    when(scenarios.findById(SCENARIO_ID)).thenReturn(Optional.of(scenario));

    assertThatThrownBy(() -> service.scenario(SCENARIO_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("seed");
  }

  @Test
  void hashMismatchCreatesNoRunReportOrFreeze() {
    TradingLabScenarioEntity scenario = scenario();
    when(scenarios.findByIdForUpdate(SCENARIO_ID)).thenReturn(Optional.of(scenario));

    assertThatThrownBy(() -> service.createRun(
        SCENARIO_ID,
        ACTOR_ID,
        "127.0.0.1",
        REQUEST_ID,
        new TradingLabRunCreateRequest(
            0L,
            "f".repeat(64),
            json.createObjectNode())))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo("TRADING_LAB_CONFIG_HASH_CONFLICT");

    verify(scenarios, never()).freezeDraft(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    verify(runs, never()).insert(any(TradingLabRunEntity.class));
    verify(reports, never()).insert(any(TradingLabReportEntity.class));
  }

  @Test
  void storedConfigIsRehashedBeforeAnyRunMutation() {
    TradingLabScenarioEntity scenario = scenario();
    scenario.setConfigSnapshotJson("{\"tampered\":true}");
    when(scenarios.findByIdForUpdate(SCENARIO_ID)).thenReturn(Optional.of(scenario));

    assertThatThrownBy(() -> service.createRun(
        SCENARIO_ID,
        ACTOR_ID,
        "127.0.0.1",
        REQUEST_ID,
        new TradingLabRunCreateRequest(
            0L,
            configHash,
            json.createObjectNode())))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo("TRADING_LAB_CONFIG_HASH_CONFLICT");

    verify(scenarios, never()).freezeDraft(
        any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    verify(runs, never()).insert(any(TradingLabRunEntity.class));
    verify(reports, never()).insert(any(TradingLabReportEntity.class));
  }

  @Test
  void compilerFailureCreatesNoRunReportOrFreeze() {
    TradingLabScenarioEntity scenario = scenario();
    when(scenarios.findByIdForUpdate(SCENARIO_ID)).thenReturn(Optional.of(scenario));
    when(startFactory.compile(any(TradingLabValidationStartSource.class), eq(1L)))
        .thenThrow(new IllegalArgumentException("bad browser scenario"));

    assertThatThrownBy(() -> service.createRun(
        SCENARIO_ID,
        ACTOR_ID,
        "127.0.0.1",
        REQUEST_ID,
        new TradingLabRunCreateRequest(
            0L,
            configHash,
            json.createObjectNode())))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo("TRADING_LAB_SCENARIO_INVALID");

    verify(scenarios, never()).freezeDraft(
        any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    verify(runs, never()).insert(any(TradingLabRunEntity.class));
    verify(reports, never()).insert(any(TradingLabReportEntity.class));
  }

  @Test
  void frozenScenarioCannotBeUpdatedOrDeleted() {
    TradingLabScenarioEntity scenario = scenario();
    scenario.setStatus("FROZEN");
    when(scenarios.findByIdForUpdate(SCENARIO_ID)).thenReturn(Optional.of(scenario));

    assertThatThrownBy(() -> service.updateScenario(
        SCENARIO_ID,
        ACTOR_ID,
        "127.0.0.1",
        REQUEST_ID,
        scenarioRequest(0L)))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo("TRADING_LAB_SCENARIO_FROZEN");
    assertThatThrownBy(() -> service.deleteScenario(
        SCENARIO_ID, ACTOR_ID, "127.0.0.1", REQUEST_ID, 0L))
        .isInstanceOf(BusinessException.class);
  }

  private TradingLabScenarioEntity scenario() {
    TradingLabScenarioEntity scenario = new TradingLabScenarioEntity();
    scenario.setId(SCENARIO_ID);
    scenario.setName("Scenario");
    scenario.setDescription("description");
    scenario.setStatus("DRAFT");
    scenario.setNegativeMode(false);
    scenario.setSeed("seed-007");
    scenario.setModelVersion("model-v1");
    scenario.setScenarioJson(canonicalizer.canonicalize(scenarioJson()).json());
    scenario.setConfigSnapshotJson(canonicalizer.canonicalize(config).json());
    scenario.setConfigSnapshotHash(configHash);
    scenario.setSymbolConfigVersion("symbol-v1");
    scenario.setCodeVersion("local+working-tree");
    scenario.setCreatedBy(ACTOR_ID);
    scenario.setUpdatedBy(ACTOR_ID);
    scenario.setVersion(0L);
    return scenario;
  }

  private TradingLabScenarioWriteRequest scenarioRequest(Long expectedVersion) {
    return new TradingLabScenarioWriteRequest(
        "Scenario",
        "description",
        false,
        "seed-007",
        "model-v1",
        scenarioJson(),
        config,
        configHash,
        expectedVersion);
  }

  private ObjectNode scenarioJson() {
    ObjectNode validation = json.createObjectNode();
    validation.put("virtualStart", "2026-07-24T00:00:00Z");
    validation.put("speedMultiplier", "2");
    validation.putArray("ticks").addObject().put("sequence", 1);
    validation.putArray("actions");
    ObjectNode scenario = json.createObjectNode();
    scenario.put("seed", "seed-007");
    return scenario.set("validationRun", validation);
  }
}
