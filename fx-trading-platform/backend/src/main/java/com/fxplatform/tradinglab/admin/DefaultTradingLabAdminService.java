package com.fxplatform.tradinglab.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fxplatform.admin.dto.AdminPageResponse;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.tradinglab.admin.dto.TradingLabConfigResponse;
import com.fxplatform.tradinglab.admin.dto.TradingLabRunCreateRequest;
import com.fxplatform.tradinglab.admin.dto.TradingLabRunResponse;
import com.fxplatform.tradinglab.admin.dto.TradingLabScenarioResponse;
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
import com.fxplatform.tradinglab.report.TradingLabReportStatus;
import com.fxplatform.tradinglab.repository.TradingLabReportRepository;
import com.fxplatform.tradinglab.repository.TradingLabRunRepository;
import com.fxplatform.tradinglab.repository.TradingLabScenarioRepository;
import com.fxplatform.tradinglab.state.RunTransitionCommand;
import com.fxplatform.tradinglab.state.TradingLabRunState;
import com.fxplatform.tradinglab.state.TradingLabRunTransitionService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultTradingLabAdminService implements TradingLabAdminService {

  private final TradingLabScenarioRepository scenarios;
  private final TradingLabRunRepository runs;
  private final TradingLabReportRepository reports;
  private final TradingLabRunTransitionService transitions;
  private final TradingLabScenarioCanonicalizer canonicalizer;
  private final TradingLabConfigProvider configProvider;
  private final TradingLabValidationStartRequestFactory startRequestFactory;
  private final TradingLabOperationGate operationGate;
  private final TradingLabAuditService audit;
  private final TradingLabReportProperties reportProperties;

  @Override
  public TradingLabConfigResponse config() {
    return configProvider.current();
  }

  @Override
  public AdminPageResponse<TradingLabScenarioResponse> scenarios(int page, int size) {
    if (page < 0 || size < 1 || size > 100) {
      throw new IllegalArgumentException("Trading Lab scenario page is invalid");
    }
    Page<TradingLabScenarioEntity> result = scenarios.selectPage(
        Page.of(page + 1L, size),
        new LambdaQueryWrapper<TradingLabScenarioEntity>()
            .orderByDesc(TradingLabScenarioEntity::getUpdatedAt)
            .orderByDesc(TradingLabScenarioEntity::getId));
    List<TradingLabScenarioResponse> items = result.getRecords().stream()
        .map(this::scenarioResponse)
        .toList();
    return new AdminPageResponse<>(
        items,
        page,
        size,
        result.getTotal(),
        Math.toIntExact(result.getPages()));
  }

  @Override
  public TradingLabScenarioResponse scenario(UUID scenarioId) {
    return scenarioResponse(requireScenario(scenarioId));
  }

  @Override
  @Transactional
  public TradingLabScenarioResponse createScenario(
      UUID actorId,
      String clientIp,
      UUID requestId,
      TradingLabScenarioWriteRequest request
  ) {
    requireContext(actorId, clientIp, requestId);
    PreparedScenario prepared = prepareScenario(request, true);
    TradingLabScenarioEntity scenario = prepared.entity();
    scenario.setId(UUID.randomUUID());
    scenario.setStatus("DRAFT");
    scenario.setCreatedBy(actorId);
    scenario.setUpdatedBy(actorId);
    scenario.setVersion(0L);
    if (scenarios.insert(scenario) != 1) {
      throw conflict("TRADING_LAB_SCENARIO_WRITE_LOST", "Scenario was not created");
    }
    audit.record(
        actorId,
        clientIp,
        requestId,
        scenario.getId(),
        null,
        "TRADING_LAB_SCENARIO_CREATE",
        "SUCCESS",
        Map.of("configSnapshotHash", scenario.getConfigSnapshotHash()));
    return scenarioResponse(requireScenario(scenario.getId()));
  }

  @Override
  @Transactional
  public TradingLabScenarioResponse updateScenario(
      UUID scenarioId,
      UUID actorId,
      String clientIp,
      UUID requestId,
      TradingLabScenarioWriteRequest request
  ) {
    requireContext(actorId, clientIp, requestId);
    if (scenarioId == null || request == null || request.expectedVersion() == null) {
      throw new IllegalArgumentException("Scenario ID and expected version are required");
    }
    TradingLabScenarioEntity current = scenarios.findByIdForUpdate(scenarioId)
        .orElseThrow(DefaultTradingLabAdminService::scenarioNotFound);
    requireDraft(current);
    if (!Objects.equals(current.getVersion(), request.expectedVersion())) {
      throw conflict("TRADING_LAB_SCENARIO_VERSION_CONFLICT", "Scenario version changed");
    }
    PreparedScenario prepared = prepareScenario(request, false);
    TradingLabScenarioEntity update = prepared.entity();
    update.setId(scenarioId);
    update.setUpdatedBy(actorId);
    if (scenarios.updateDraft(update, request.expectedVersion()) != 1) {
      throw conflict("TRADING_LAB_SCENARIO_VERSION_CONFLICT", "Scenario version changed");
    }
    audit.record(
        actorId,
        clientIp,
        requestId,
        scenarioId,
        null,
        "TRADING_LAB_SCENARIO_UPDATE",
        "SUCCESS",
        Map.of("version", request.expectedVersion() + 1L));
    return scenarioResponse(requireScenario(scenarioId));
  }

  @Override
  @Transactional
  public void deleteScenario(
      UUID scenarioId,
      UUID actorId,
      String clientIp,
      UUID requestId,
      long expectedVersion
  ) {
    requireContext(actorId, clientIp, requestId);
    if (scenarioId == null || expectedVersion < 0L) {
      throw new IllegalArgumentException("Scenario ID and expected version are required");
    }
    TradingLabScenarioEntity current = scenarios.findByIdForUpdate(scenarioId)
        .orElseThrow(DefaultTradingLabAdminService::scenarioNotFound);
    requireDraft(current);
    if (!Objects.equals(current.getVersion(), expectedVersion)) {
      throw conflict("TRADING_LAB_SCENARIO_VERSION_CONFLICT", "Scenario version changed");
    }
    audit.record(
        actorId,
        clientIp,
        requestId,
        scenarioId,
        null,
        "TRADING_LAB_SCENARIO_DELETE",
        "SUCCESS",
        Map.of("version", expectedVersion));
    if (scenarios.deleteDraft(scenarioId, expectedVersion) != 1) {
      throw conflict("TRADING_LAB_SCENARIO_VERSION_CONFLICT", "Scenario version changed");
    }
  }

  @Override
  @Transactional
  public TradingLabRunResponse createRun(
      UUID scenarioId,
      UUID actorId,
      String clientIp,
      UUID requestId,
      TradingLabRunCreateRequest request
  ) {
    requireContext(actorId, clientIp, requestId);
    if (scenarioId == null || request == null) {
      throw new IllegalArgumentException("Scenario and run request are required");
    }
    operationGate.awaitRunCreationPermit();
    TradingLabScenarioEntity scenario = scenarios.findByIdForUpdate(scenarioId)
        .orElseThrow(DefaultTradingLabAdminService::scenarioNotFound);
    requireDraft(scenario);
    if (!Objects.equals(scenario.getVersion(), request.scenarioVersion())) {
      throw conflict("TRADING_LAB_SCENARIO_VERSION_CONFLICT", "Scenario version changed");
    }
    TradingLabCanonicalDocument scenarioSnapshot =
        canonicalizer.canonicalize(scenario.getScenarioJson());
    TradingLabCanonicalDocument frozenConfig =
        canonicalizer.canonicalize(scenario.getConfigSnapshotJson());
    if (!Objects.equals(frozenConfig.sha256(), scenario.getConfigSnapshotHash())
        || !Objects.equals(frozenConfig.sha256(), request.configSnapshotHash())) {
      throw conflict("TRADING_LAB_CONFIG_HASH_CONFLICT", "Scenario config hash changed");
    }
    TradingLabConfigResponse currentConfig = configProvider.current();
    TradingLabCanonicalDocument currentConfigDocument =
        requireConsistentConfig(currentConfig);
    if (!Objects.equals(currentConfigDocument.sha256(), frozenConfig.sha256())) {
      throw conflict("TRADING_LAB_CONFIG_STALE", "Scenario config is no longer current");
    }
    if (!Objects.equals(scenario.getModelVersion(), currentConfig.modelVersion())
        || !Objects.equals(scenario.getSymbolConfigVersion(), currentConfig.symbolConfigVersion())
        || !Objects.equals(scenario.getCodeVersion(), currentConfig.codeVersion())) {
      throw conflict(
          "TRADING_LAB_CONFIG_METADATA_CONFLICT",
          "Scenario config metadata no longer matches its authoritative snapshot");
    }
    TradingLabCanonicalDocument localCalculation = canonicalizer.canonicalize(
        request.localCalculation());
    UUID runId = UUID.randomUUID();
    UUID reportId = UUID.randomUUID();
    ValidationRunStartRequest compiled;
    try {
      compiled = startRequestFactory.compile(
          new TradingLabValidationStartSource(
              runId,
              scenarioSnapshot.json(),
              frozenConfig.json(),
              scenario.getConfigSnapshotHash(),
              scenario.getModelVersion(),
              scenario.getSymbolConfigVersion(),
              scenario.getCodeVersion(),
              scenario.getSeed(),
              scenario.getNegativeMode()),
          1L);
    } catch (RuntimeException exception) {
      throw invalidScenario();
    }

    TradingLabRunEntity run = new TradingLabRunEntity();
    run.setId(runId);
    run.setScenarioId(scenarioId);
    run.setState(TradingLabRunState.DRAFT.name());
    run.setCancelRequested(false);
    run.setPauseRequested(false);
    run.setVirtualStartedAt(compiled.virtualStart());
    run.setVirtualCurrentAt(compiled.virtualStart());
    run.setProcessedTicks(0L);
    run.setTotalTicks((long) compiled.ticks().size());
    run.setSpeedMultiplier(compiled.speedMultiplier());
    run.setCurrentStep(0L);
    run.setReportId(reportId);
    run.setScenarioSnapshotJson(scenarioSnapshot.json());
    run.setConfigSnapshotJson(frozenConfig.json());
    run.setLocalCalculationJson(localCalculation.json());
    run.setConfigSnapshotHash(scenario.getConfigSnapshotHash());
    run.setModelVersion(scenario.getModelVersion());
    run.setSymbolConfigVersion(scenario.getSymbolConfigVersion());
    run.setCodeVersion(scenario.getCodeVersion());
    run.setCreatedBy(actorId);
    run.setVersion(0L);

    if (scenarios.freezeDraft(
        scenarioId,
        request.scenarioVersion(),
        request.configSnapshotHash(),
        actorId) != 1) {
      throw conflict("TRADING_LAB_SCENARIO_VERSION_CONFLICT", "Scenario was not frozen");
    }

    TradingLabReportEntity report = new TradingLabReportEntity();
    report.setId(reportId);
    report.setScenarioId(scenarioId);
    report.setStatus(TradingLabReportStatus.PENDING.name());
    report.setModelVersion(scenario.getModelVersion());
    report.setConfigSnapshotHash(scenario.getConfigSnapshotHash());
    report.setCodeVersion(scenario.getCodeVersion());
    report.setMetadataJson("{}");
    report.setUncompressedBytes(0L);
    report.setCompressedBytes(0L);
    report.setChunkCount(0);
    report.setRetainedUntil(Instant.now().plus(reportProperties.retention()));
    report.setPermanent(false);
    report.setCreatedBy(actorId);
    report.setVersion(0L);
    if (reports.insert(report) != 1 || runs.insert(run) != 1) {
      throw conflict("TRADING_LAB_RUN_WRITE_LOST", "Run or report was not created");
    }

    transitions.transition(new RunTransitionCommand(
        runId,
        TradingLabRunState.DRAFT,
        TradingLabRunState.VALIDATING,
        "api-create:validating",
        "Scenario validation succeeded",
        compiled.virtualStart()));
    transitions.transition(new RunTransitionCommand(
        runId,
        TradingLabRunState.VALIDATING,
        TradingLabRunState.QUEUED,
        "api-create:queued",
        "Run queued",
        compiled.virtualStart()));
    audit.record(
        actorId,
        clientIp,
        requestId,
        scenarioId,
        runId,
        "TRADING_LAB_RUN_CREATE",
        "SUCCESS",
        Map.of(
            "reportId", reportId.toString(),
            "configSnapshotHash", scenario.getConfigSnapshotHash(),
            "totalTicks", compiled.ticks().size(),
            "httpMethod", "POST",
            "httpPath", "/api/admin/trading-lab/scenarios/" + scenarioId + "/runs",
            "httpStatus", 200,
            "evidenceType", "ADMIN_RUN_ACCEPTED"));
    return runResponse(requireRun(runId));
  }

  @Override
  public TradingLabRunResponse run(UUID runId) {
    return runResponse(requireRun(runId));
  }

  private PreparedScenario prepareScenario(
      TradingLabScenarioWriteRequest request,
      boolean creating
  ) {
    if (request == null || (creating && request.expectedVersion() != null)) {
      throw new IllegalArgumentException("Scenario request is invalid");
    }
    TradingLabCanonicalDocument scenario = canonicalizer.canonicalize(request.scenario());
    TradingLabCanonicalDocument config = canonicalizer.canonicalize(request.configSnapshot());
    String seed = requireScenarioSeed(request.seed(), scenario.value());
    if (!Objects.equals(config.sha256(), request.configSnapshotHash())) {
      throw conflict("TRADING_LAB_CONFIG_HASH_CONFLICT", "Config snapshot hash is invalid");
    }
    TradingLabConfigResponse current = configProvider.current();
    TradingLabCanonicalDocument currentDocument = requireConsistentConfig(current);
    if (!Objects.equals(currentDocument.sha256(), config.sha256())) {
      throw conflict("TRADING_LAB_CONFIG_STALE", "Config snapshot is no longer current");
    }
    if (!Objects.equals(current.modelVersion(), request.modelVersion())) {
      throw conflict("TRADING_LAB_MODEL_VERSION_CONFLICT", "Model version is no longer current");
    }
    TradingLabScenarioEntity entity = new TradingLabScenarioEntity();
    entity.setName(request.name().trim());
    entity.setDescription(request.description() == null ? null : request.description().trim());
    entity.setNegativeMode(request.negativeMode());
    entity.setSeed(seed);
    entity.setModelVersion(current.modelVersion());
    entity.setScenarioJson(scenario.json());
    entity.setConfigSnapshotJson(config.json());
    entity.setConfigSnapshotHash(config.sha256());
    entity.setSymbolConfigVersion(current.symbolConfigVersion());
    entity.setCodeVersion(current.codeVersion());
    return new PreparedScenario(entity);
  }

  private TradingLabScenarioResponse scenarioResponse(TradingLabScenarioEntity entity) {
    TradingLabCanonicalDocument scenario =
        canonicalizer.canonicalize(entity.getScenarioJson());
    String seed = requireStoredScenarioSeed(entity.getSeed(), scenario.value());
    return new TradingLabScenarioResponse(
        entity.getId(),
        entity.getName(),
        entity.getDescription(),
        entity.getStatus(),
        Boolean.TRUE.equals(entity.getNegativeMode()),
        seed,
        entity.getModelVersion(),
        scenario.value(),
        canonicalizer.canonicalize(entity.getConfigSnapshotJson()).value(),
        entity.getConfigSnapshotHash(),
        entity.getSymbolConfigVersion(),
        entity.getCodeVersion(),
        entity.getCreatedBy(),
        entity.getUpdatedBy(),
        entity.getCreatedAt(),
        entity.getUpdatedAt(),
        requiredVersion(entity.getVersion(), "scenario"));
  }

  private static TradingLabRunResponse runResponse(TradingLabRunEntity entity) {
    return new TradingLabRunResponse(
        entity.getId(),
        entity.getScenarioId(),
        entity.getReportId(),
        entity.getState(),
        value(entity.getQueueSequence()),
        Boolean.TRUE.equals(entity.getPauseRequested()),
        Boolean.TRUE.equals(entity.getCancelRequested()),
        entity.getVirtualStartedAt(),
        entity.getVirtualCurrentAt(),
        value(entity.getProcessedTicks()),
        value(entity.getTotalTicks()),
        entity.getSpeedMultiplier(),
        value(entity.getCurrentStep()),
        entity.getFailureCode(),
        entity.getFailureMessage(),
        entity.getConfigSnapshotHash(),
        entity.getModelVersion(),
        entity.getSymbolConfigVersion(),
        entity.getCodeVersion(),
        entity.getCreatedBy(),
        entity.getCreatedAt(),
        entity.getUpdatedAt(),
        entity.getStartedAt(),
        entity.getFinishedAt(),
        requiredVersion(entity.getVersion(), "run"));
  }

  private TradingLabScenarioEntity requireScenario(UUID scenarioId) {
    if (scenarioId == null) {
      throw new IllegalArgumentException("Scenario ID is required");
    }
    return scenarios.findById(scenarioId).orElseThrow(DefaultTradingLabAdminService::scenarioNotFound);
  }

  private static String requireScenarioSeed(String requested, JsonNode scenario) {
    JsonNode frozen = scenario == null ? null : scenario.get("seed");
    if (requested == null
        || requested.isBlank()
        || requested.length() > 256
        || frozen == null
        || !frozen.isTextual()
        || !requested.equals(frozen.textValue())) {
      throw new IllegalArgumentException(
          "Scenario seed must be one exact nonblank JSON string");
    }
    return requested;
  }

  private static String requireStoredScenarioSeed(String stored, JsonNode scenario) {
    try {
      return requireScenarioSeed(stored, scenario);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Stored Trading Lab scenario seed is invalid");
    }
  }

  private TradingLabRunEntity requireRun(UUID runId) {
    if (runId == null) {
      throw new IllegalArgumentException("Run ID is required");
    }
    return runs.findById(runId)
        .orElseThrow(() -> new BusinessException(
            "TRADING_LAB_RUN_NOT_FOUND", "Trading Lab run was not found"));
  }

  private static void requireDraft(TradingLabScenarioEntity scenario) {
    if (!"DRAFT".equals(scenario.getStatus())) {
      throw conflict("TRADING_LAB_SCENARIO_FROZEN", "Frozen scenarios cannot be changed");
    }
  }

  private static void requireContext(UUID actorId, String clientIp, UUID requestId) {
    if (actorId == null || clientIp == null || clientIp.isBlank() || requestId == null) {
      throw new IllegalArgumentException("Trading Lab audit context is required");
    }
  }

  private TradingLabCanonicalDocument requireConsistentConfig(
      TradingLabConfigResponse current
  ) {
    if (current == null
        || current.configSnapshot() == null
        || current.configSnapshotHash() == null) {
      throw conflict(
          "TRADING_LAB_CONFIG_HASH_CONFLICT",
          "Authoritative Trading Lab config is unavailable");
    }
    TradingLabCanonicalDocument canonical =
        canonicalizer.canonicalize(current.configSnapshot());
    if (!Objects.equals(canonical.sha256(), current.configSnapshotHash())) {
      throw conflict(
          "TRADING_LAB_CONFIG_HASH_CONFLICT",
          "Authoritative Trading Lab config hash is inconsistent");
    }
    return canonical;
  }

  private static long requiredVersion(Long value, String target) {
    if (value == null || value < 0L) {
      throw new IllegalStateException("Trading Lab " + target + " version is invalid");
    }
    return value;
  }

  private static long value(Long value) {
    return value == null ? 0L : value;
  }

  private static BusinessException scenarioNotFound() {
    return new BusinessException(
        "TRADING_LAB_SCENARIO_NOT_FOUND", "Trading Lab scenario was not found");
  }

  private static BusinessException invalidScenario() {
    return new BusinessException(
        "TRADING_LAB_SCENARIO_INVALID", "Trading Lab scenario validation failed");
  }

  private static BusinessException conflict(String code, String message) {
    return new BusinessException(code, message);
  }

  private record PreparedScenario(TradingLabScenarioEntity entity) {
  }
}
