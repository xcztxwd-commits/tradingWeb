package com.fxplatform.tradinglab.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.tradinglab.admin.dto.TradingLabConfigResponse;
import com.fxplatform.tradinglab.admin.dto.TradingLabRunCreateRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("database-it")
@Testcontainers(disabledWithoutDocker = true)
class TradingLabRunCreationPostgresIT {

  private static final String CLIENT_IP = "127.0.0.1";
  private static final String MODEL_VERSION = "model-task8-postgres";
  private static final String SYMBOL_CONFIG_VERSION = "symbols-task8-postgres";
  private static final String CODE_VERSION = "task8-postgres+working-tree";

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private DefaultTradingLabAdminService service;
  @Autowired private TradingLabScenarioCanonicalizer canonicalizer;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper json;

  @MockitoBean
  private TradingLabConfigProvider configProvider;

  @MockitoBean(name = "taskScheduler")
  private TaskScheduler taskScheduler;

  private TradingLabCanonicalDocument config;
  private TradingLabCanonicalDocument scenario;

  @BeforeEach
  void setUp() {
    ObjectNode configJson = json.createObjectNode();
    configJson.put("codeVersion", CODE_VERSION);
    configJson.putObject("execution").put("mode", "demo");
    configJson.put("modelVersion", MODEL_VERSION);
    configJson.put("symbolConfigVersion", SYMBOL_CONFIG_VERSION);
    config = canonicalizer.canonicalize(configJson);
    scenario = canonicalizer.canonicalize(scenarioJson());
    when(configProvider.current()).thenReturn(new TradingLabConfigResponse(
        config.value(),
        config.sha256(),
        MODEL_VERSION,
        SYMBOL_CONFIG_VERSION,
        CODE_VERSION));
  }

  @Test
  void v68RunCreationFreezesEverySnapshotAndWritesCorrelatedAuditAtomically() {
    assertV68Applied();
    UUID actorId = insertUser();
    UUID scenarioId = insertDraftScenario(actorId);
    UUID requestId = UUID.randomUUID();
    TradingLabCanonicalDocument localCalculation = canonicalizer.canonicalize(
        json.createObjectNode()
            .put("z", "last")
            .put("expectedEquity", "100000.00")
            .set("nested", json.createObjectNode()
                .put("b", 2)
                .put("a", "1.00")));

    var response = service.createRun(
        scenarioId,
        actorId,
        CLIENT_IP,
        requestId,
        new TradingLabRunCreateRequest(0L, config.sha256(), localCalculation.value()));

    assertThat(response.state()).isEqualTo("QUEUED");
    assertThat(response.version()).isEqualTo(2L);
    assertThat(response.queueSequence()).isPositive();
    assertThat(response.scenarioId()).isEqualTo(scenarioId);
    assertThat(response.createdBy()).isEqualTo(actorId);

    Map<String, Object> storedScenario = jdbcTemplate.queryForMap("""
        select status, version, updated_by,
               scenario_json::text as scenario_json,
               config_snapshot_json::text as config_snapshot_json,
               config_snapshot_hash, model_version, symbol_config_version, code_version
        from trading_lab.scenarios
        where id = ?
        """, scenarioId);
    assertThat(storedScenario)
        .containsEntry("status", "FROZEN")
        .containsEntry("version", 1L)
        .containsEntry("updated_by", actorId)
        .containsEntry("config_snapshot_hash", config.sha256())
        .containsEntry("model_version", MODEL_VERSION)
        .containsEntry("symbol_config_version", SYMBOL_CONFIG_VERSION)
        .containsEntry("code_version", CODE_VERSION);
    assertThat(canonical((String) storedScenario.get("scenario_json")))
        .isEqualTo(scenario.json());
    assertThat(canonical((String) storedScenario.get("config_snapshot_json")))
        .isEqualTo(config.json());

    Map<String, Object> storedRun = jdbcTemplate.queryForMap("""
        select id, report_id, state, queue_sequence, version, total_ticks,
               speed_multiplier, virtual_started_at, virtual_current_at,
               scenario_snapshot_json::text as scenario_snapshot_json,
               config_snapshot_json::text as config_snapshot_json,
               local_calculation_json::text as local_calculation_json,
               config_snapshot_hash, model_version, symbol_config_version, code_version,
               created_by
        from trading_lab.runs
        where scenario_id = ?
        """, scenarioId);
    UUID runId = (UUID) storedRun.get("id");
    UUID reportId = (UUID) storedRun.get("report_id");
    assertThat(runId).isEqualTo(response.id());
    assertThat(reportId).isEqualTo(response.reportId());
    assertThat(storedRun)
        .containsEntry("state", "QUEUED")
        .containsEntry("queue_sequence", response.queueSequence())
        .containsEntry("version", 2L)
        .containsEntry("total_ticks", 2L)
        .containsEntry("speed_multiplier", new BigDecimal("2.500000"))
        .containsEntry("config_snapshot_hash", config.sha256())
        .containsEntry("model_version", MODEL_VERSION)
        .containsEntry("symbol_config_version", SYMBOL_CONFIG_VERSION)
        .containsEntry("code_version", CODE_VERSION)
        .containsEntry("created_by", actorId);
    assertThat(storedRun.get("virtual_started_at"))
        .isEqualTo(storedRun.get("virtual_current_at"));
    assertThat(canonical((String) storedRun.get("scenario_snapshot_json")))
        .isEqualTo(scenario.json());
    assertThat(canonical((String) storedRun.get("config_snapshot_json")))
        .isEqualTo(config.json());
    assertThat(canonical((String) storedRun.get("local_calculation_json")))
        .isEqualTo(localCalculation.json());

    Map<String, Object> storedReport = jdbcTemplate.queryForMap("""
        select id, scenario_id, status, model_version, config_snapshot_hash, code_version,
               metadata_json::text as metadata_json, uncompressed_bytes, compressed_bytes,
               chunk_count, permanent, created_by, version
        from trading_lab.reports
        where id = ?
        """, reportId);
    assertThat(storedReport)
        .containsEntry("id", reportId)
        .containsEntry("scenario_id", scenarioId)
        .containsEntry("status", "PENDING")
        .containsEntry("model_version", MODEL_VERSION)
        .containsEntry("config_snapshot_hash", config.sha256())
        .containsEntry("code_version", CODE_VERSION)
        .containsEntry("uncompressed_bytes", 0L)
        .containsEntry("compressed_bytes", 0L)
        .containsEntry("chunk_count", 0)
        .containsEntry("permanent", false)
        .containsEntry("created_by", actorId)
        .containsEntry("version", 0L);
    assertThat(canonical((String) storedReport.get("metadata_json"))).isEqualTo("{}");

    List<Map<String, Object>> transitions = jdbcTemplate.queryForList("""
        select from_state, to_state, run_version, idempotency_key, virtual_time
        from trading_lab.run_transitions
        where run_id = ?
        order by run_version
        """, runId);
    assertThat(transitions).hasSize(2);
    assertThat(transitions.get(0))
        .containsEntry("from_state", "DRAFT")
        .containsEntry("to_state", "VALIDATING")
        .containsEntry("run_version", 1L)
        .containsEntry("idempotency_key", "api-create:validating");
    assertThat(transitions.get(1))
        .containsEntry("from_state", "VALIDATING")
        .containsEntry("to_state", "QUEUED")
        .containsEntry("run_version", 2L)
        .containsEntry("idempotency_key", "api-create:queued");
    assertThat(transitions.get(0).get("virtual_time"))
        .isEqualTo(transitions.get(1).get("virtual_time"));

    Map<String, Object> labAudit = jdbcTemplate.queryForMap("""
        select actor_id, client_ip, request_id, scenario_id, run_id, action, result,
               details_json::text as details_json
        from trading_lab.audit_events
        where request_id = ?
        """, requestId);
    assertThat(labAudit)
        .containsEntry("actor_id", actorId)
        .containsEntry("client_ip", CLIENT_IP)
        .containsEntry("request_id", requestId)
        .containsEntry("scenario_id", scenarioId)
        .containsEntry("run_id", runId)
        .containsEntry("action", "TRADING_LAB_RUN_CREATE")
        .containsEntry("result", "SUCCESS");
    assertThat((String) labAudit.get("details_json"))
        .contains(reportId.toString(), config.sha256());

    Map<String, Object> genericAudit = jdbcTemplate.queryForMap("""
        select actor_user_id, action, target_type, target_id, request_id,
               details::text as details
        from audit.audit_logs
        where request_id = ?
        """, requestId.toString());
    assertThat(genericAudit)
        .containsEntry("actor_user_id", actorId)
        .containsEntry("action", "TRADING_LAB_RUN_CREATE")
        .containsEntry("target_type", "TRADING_LAB_RUN")
        .containsEntry("target_id", runId.toString())
        .containsEntry("request_id", requestId.toString());
    assertThat((String) genericAudit.get("details"))
        .contains(CLIENT_IP, scenarioId.toString(), runId.toString(), reportId.toString());
    assertThat(count("select count(*) from trading_lab.audit_events where request_id = ?", requestId))
        .isOne();
    assertThat(count("select count(*) from audit.audit_logs where request_id = ?",
        requestId.toString())).isOne();
  }

  @Test
  void highLevelBrowserScenarioCompilesAndQueuesUsingTheRealSpringFactory() {
    TradingLabCanonicalDocument browserConfig = browserConfig();
    TradingLabCanonicalDocument browserScenario =
        browserScenario(browserConfig, false);
    useCurrentConfig(browserConfig);
    UUID actorId = insertUser();
    UUID scenarioId = insertDraftScenario(actorId, browserScenario, browserConfig);
    UUID requestId = UUID.randomUUID();

    var response = service.createRun(
        scenarioId,
        actorId,
        CLIENT_IP,
        requestId,
        new TradingLabRunCreateRequest(
            0L,
            browserConfig.sha256(),
            json.createObjectNode().put("expectedEquity", "100000.00")));

    assertThat(response.state()).isEqualTo("QUEUED");
    assertThat(response.totalTicks()).isEqualTo(2L);
    assertThat(response.speedMultiplier()).isEqualByComparingTo("1.000000");
    assertThat(response.virtualStartedAt().toString())
        .isEqualTo("2026-07-25T00:00:00Z");
    assertThat(jdbcTemplate.queryForMap("""
        select status, version
        from trading_lab.scenarios
        where id = ?
        """, scenarioId))
        .containsEntry("status", "FROZEN")
        .containsEntry("version", 1L);
    assertThat(jdbcTemplate.queryForMap("""
        select state, total_ticks, speed_multiplier
        from trading_lab.runs
        where scenario_id = ?
        """, scenarioId))
        .containsEntry("state", "QUEUED")
        .containsEntry("total_ticks", 2L)
        .containsEntry("speed_multiplier", new BigDecimal("1.000000"));
    assertThat(count(
        "select count(*) from trading_lab.reports where scenario_id = ?",
        scenarioId)).isOne();
    assertThat(count(
        "select count(*) from trading_lab.audit_events where request_id = ?",
        requestId)).isOne();
  }

  @Test
  void browserCompilerFailureLeavesNoFreezeReportRunOrAudit() {
    TradingLabCanonicalDocument browserConfig = browserConfig();
    TradingLabCanonicalDocument invalidScenario =
        browserScenario(browserConfig, true);
    useCurrentConfig(browserConfig);
    UUID actorId = insertUser();
    UUID scenarioId = insertDraftScenario(actorId, invalidScenario, browserConfig);
    UUID requestId = UUID.randomUUID();

    assertThatThrownBy(() -> service.createRun(
        scenarioId,
        actorId,
        CLIENT_IP,
        requestId,
        new TradingLabRunCreateRequest(
            0L,
            browserConfig.sha256(),
            json.createObjectNode().put("expectedEquity", "100000.00"))))
        .isInstanceOf(BusinessException.class)
        .extracting(failure -> ((BusinessException) failure).getCode())
        .isEqualTo("TRADING_LAB_SCENARIO_INVALID");

    assertThat(jdbcTemplate.queryForMap("""
        select status, version
        from trading_lab.scenarios
        where id = ?
        """, scenarioId))
        .containsEntry("status", "DRAFT")
        .containsEntry("version", 0L);
    assertThat(count(
        "select count(*) from trading_lab.runs where scenario_id = ?",
        scenarioId)).isZero();
    assertThat(count(
        "select count(*) from trading_lab.reports where scenario_id = ?",
        scenarioId)).isZero();
    assertThat(count(
        "select count(*) from trading_lab.audit_events where request_id = ?",
        requestId)).isZero();
    assertThat(count(
        "select count(*) from audit.audit_logs where request_id = ?",
        requestId.toString())).isZero();
  }

  @Test
  void tamperedStoredConfigLeavesDraftScenarioAndNoRunArtifactsOrAudits() {
    UUID actorId = insertUser();
    UUID scenarioId = insertDraftScenario(actorId);
    UUID requestId = UUID.randomUUID();
    jdbcTemplate.update("""
        update trading_lab.scenarios
        set config_snapshot_json = '{"tampered":true}'::jsonb
        where id = ?
        """, scenarioId);

    assertThatThrownBy(() -> service.createRun(
        scenarioId,
        actorId,
        CLIENT_IP,
        requestId,
        new TradingLabRunCreateRequest(
            0L,
            config.sha256(),
            json.createObjectNode().put("expectedEquity", "100000.00"))))
        .isInstanceOf(BusinessException.class)
        .extracting(failure -> ((BusinessException) failure).getCode())
        .isEqualTo("TRADING_LAB_CONFIG_HASH_CONFLICT");

    Map<String, Object> storedScenario = jdbcTemplate.queryForMap("""
        select status, version, updated_by
        from trading_lab.scenarios
        where id = ?
        """, scenarioId);
    assertThat(storedScenario)
        .containsEntry("status", "DRAFT")
        .containsEntry("version", 0L)
        .containsEntry("updated_by", actorId);
    assertThat(count("select count(*) from trading_lab.runs where scenario_id = ?", scenarioId))
        .isZero();
    assertThat(count("select count(*) from trading_lab.reports where scenario_id = ?", scenarioId))
        .isZero();
    assertThat(count("""
        select count(*)
        from trading_lab.run_transitions transition
        join trading_lab.runs run on run.id = transition.run_id
        where run.scenario_id = ?
        """, scenarioId)).isZero();
    assertThat(count(
        "select count(*) from trading_lab.audit_events where request_id = ?", requestId))
        .isZero();
    assertThat(count(
        "select count(*) from audit.audit_logs where request_id = ?", requestId.toString()))
        .isZero();
  }

  private void assertV68Applied() {
    assertThat(count("""
        select count(*)
        from public.flyway_schema_history
        where version = '68' and success = true
        """)).isOne();
    Map<String, Object> column = jdbcTemplate.queryForMap("""
        select is_nullable, column_default
        from information_schema.columns
        where table_schema = 'trading_lab'
          and table_name = 'runs'
          and column_name = 'local_calculation_json'
        """);
    assertThat(column.get("is_nullable")).isEqualTo("NO");
    assertThat((String) column.get("column_default")).contains("'{}'::jsonb");
    assertThat(jdbcTemplate.queryForObject("""
        select pg_get_constraintdef(constraint_row.oid)
        from pg_constraint constraint_row
        join pg_namespace namespace_row
          on namespace_row.oid = constraint_row.connamespace
        where namespace_row.nspname = 'trading_lab'
          and constraint_row.conname = 'ck_trading_lab_scenarios_status'
        """, String.class)).contains("DRAFT", "FROZEN");
  }

  private UUID insertUser() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into auth.users (id, email, password_hash, status, role)
        values (?, ?, 'test-hash', 'ACTIVE', 'ADMIN')
        """, id, "task8-run-" + id + "@trading-lab.test");
    return id;
  }

  private UUID insertDraftScenario(UUID actorId) {
    return insertDraftScenario(actorId, scenario, config);
  }

  private UUID insertDraftScenario(
      UUID actorId,
      TradingLabCanonicalDocument scenarioDocument,
      TradingLabCanonicalDocument configDocument
  ) {
    UUID scenarioId = UUID.randomUUID();
    jdbcTemplate.update("""
        insert into trading_lab.scenarios (
          id, name, description, status, negative_mode, seed, model_version,
          scenario_json, config_snapshot_json, config_snapshot_hash,
          symbol_config_version, code_version, created_by, updated_by, version
        )
        values (?, 'Task 8 PostgreSQL run', 'atomic snapshot proof', 'DRAFT',
                false, 'seed-42', ?, cast(? as jsonb), cast(? as jsonb), ?, ?, ?, ?, ?, 0)
        """,
        scenarioId,
        MODEL_VERSION,
        scenarioDocument.json(),
        configDocument.json(),
        configDocument.sha256(),
        SYMBOL_CONFIG_VERSION,
        CODE_VERSION,
        actorId,
        actorId);
    return scenarioId;
  }

  private void useCurrentConfig(TradingLabCanonicalDocument document) {
    when(configProvider.current()).thenReturn(new TradingLabConfigResponse(
        document.value(),
        document.sha256(),
        MODEL_VERSION,
        SYMBOL_CONFIG_VERSION,
        CODE_VERSION));
  }

  private TradingLabCanonicalDocument browserConfig() {
    return canonicalizer.canonicalize("""
        {
          "modelVersion":"%s",
          "symbolConfigVersion":"%s",
          "codeVersion":"%s",
          "executionPolicy":{
            "matchingMode":"SIMPLE",
            "makerFeeRate":"0.0002",
            "takerFeeRate":"0.0005",
            "liquidationFeeRate":"0.001",
            "slippageRate":"0.0001",
            "maxFillQuantityPerTick":"10"
          },
          "instruments":[{
            "symbol":"BTCUSDT",
            "productType":"CRYPTO_SPOT",
            "baseAsset":"BTC",
            "quoteAsset":"USDT",
            "tickSize":"1",
            "stepSize":"1",
            "pricePrecision":0,
            "quantityPrecision":0,
            "minQty":"1",
            "maxQty":"100",
            "minNotional":"1",
            "maxNotional":"1000000",
            "initialMarginRate":"1",
            "maintenanceMarginRate":"0",
            "liquidationFeeRate":"0.001",
            "fixedFundingRate":"0",
            "fixedFundingIntervalMinutes":480,
            "markPriceSource":"LAST",
            "contractSize":"1",
            "maxLeverage":1,
            "defaultLeverage":1,
            "marginAsset":"USDT",
            "settlementAsset":"USDT",
            "riskTier":"T1"
          }]
        }
        """.formatted(MODEL_VERSION, SYMBOL_CONFIG_VERSION, CODE_VERSION));
  }

  private TradingLabCanonicalDocument browserScenario(
      TradingLabCanonicalDocument browserConfig,
      boolean invalidLeverage
  ) {
    return canonicalizer.canonicalize("""
        {
          "id":"postgres-browser",
          "name":"PostgreSQL browser compiler",
          "description":"",
          "negativeMode":false,
          "seed":"seed-42",
          "modelVersion":"%s",
          "configSnapshot":%s,
          "configSnapshotHash":"%s",
          "executionPolicy":{
            "matchingMode":"SIMPLE",
            "makerFeeRate":"0.0002",
            "takerFeeRate":"0.0005",
            "liquidationFeeRate":"0.001",
            "slippageRate":"0.0001",
            "maxFillQuantityPerTick":"10"
          },
          "marketPath":{
            "virtualStart":"2026-07-25T00:00:00Z",
            "realistic":false,
            "instruments":[{
              "mode":"SIMPLE",
              "productType":"CRYPTO_SPOT",
              "symbol":"BTCUSDT",
              "seed":"postgres-path",
              "last":{
                "start":"100",
                "segments":[{
                  "target":"102",
                  "durationSeconds":2,
                  "offsetRangeSteps":0,
                  "volatilitySteps":0,
                  "maxStepPerSecond":1
                }]
              },
              "spreadSteps":2,
              "indexOffsetSteps":0,
              "basisSteps":0
            }]
          },
          "initialBalances":{"USDT":"100000"},
          "defaults":{
            "positionMode":"ONE_WAY",
            "marginMode":"CROSS",
            "leverage":%d
          },
          "symbols":[{"symbol":"BTCUSDT","productType":"CRYPTO_SPOT"}],
          "timeline":[{
            "id":"spot-buy",
            "sequence":0,
            "type":"PLACE_ORDER",
            "symbol":"BTCUSDT",
            "productType":"CRYPTO_SPOT",
            "trigger":{"type":"VIRTUAL_TIME","atSecond":1},
            "parameters":{
              "side":"BUY",
              "orderType":"MARKET",
              "quantity":"10",
              "quantityUnit":"QUOTE"
            }
          }]
        }
        """.formatted(
            MODEL_VERSION,
            browserConfig.json(),
            browserConfig.sha256(),
            invalidLeverage ? 2 : 1));
  }

  private ObjectNode scenarioJson() {
    ObjectNode validation = json.createObjectNode();
    validation.put("virtualStart", "2026-07-24T00:00:00Z");
    validation.put("speedMultiplier", "2.500000");
    validation.putObject("executionPolicy")
        .put("mode", "demo")
        .put("allowMarketOrders", true);
    validation.putObject("initialBalances").put("USDT", "100000.00");
    validation.putObject("accountSettings").put("leverage", "5");
    validation.putArray("ticks")
        .addObject()
        .put("sequence", 1)
        .put("symbol", "BTCUSDT")
        .put("price", "60000.00")
        .put("timestamp", "2026-07-24T00:00:01Z");
    validation.withArray("ticks")
        .addObject()
        .put("sequence", 2)
        .put("symbol", "BTCUSDT")
        .put("price", "60001.00")
        .put("timestamp", "2026-07-24T00:00:02Z");
    validation.putArray("actions")
        .addObject()
        .put("type", "MARKET_BUY")
        .put("symbol", "BTCUSDT")
        .put("quantity", "0.01");
    ObjectNode scenario = json.createObjectNode();
    scenario.put("seed", "seed-42");
    return scenario.set("validationRun", validation);
  }

  private String canonical(String source) {
    return canonicalizer.canonicalize(source).json();
  }

  private Long count(String sql, Object... arguments) {
    return jdbcTemplate.queryForObject(sql, Long.class, arguments);
  }
}
