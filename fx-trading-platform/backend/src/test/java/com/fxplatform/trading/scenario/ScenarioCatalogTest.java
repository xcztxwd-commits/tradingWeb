package com.fxplatform.trading.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.common.exception.ErrorCode;
import com.fxplatform.execution.ExecutionMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderSide;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioCatalogTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

  private static final List<String> REQUIRED_COLUMNS = List.of(
      "caseId",
      "priority",
      "productType",
      "positionMode",
      "positionSide",
      "marginMode",
      "leverage",
      "orderType",
      "quantityUnit",
      "reduceOnly",
      "initialBalances",
      "initialPosition",
      "initialOrders",
      "priceSteps",
      "actions",
      "exitReason",
      "expectedOrder",
      "expectedTrades",
      "expectedPosition",
      "expectedWallet",
      "expectedAccount",
      "expectedLedger",
      "expectedProtections",
      "expectedEvents",
      "expectedError",
      "testClass",
      "testMethod",
      "executionStatus");

  private static final Set<String> REQUIRED_PRICE_PATHS = Set.of(
      "UP_UP_UP",
      "UP_UP_DOWN",
      "UP_DOWN_UP",
      "UP_DOWN_DOWN",
      "DOWN_UP_UP",
      "DOWN_UP_DOWN",
      "DOWN_DOWN_UP",
      "DOWN_DOWN_DOWN",
      "FLAT",
      "TOUCH_EXACTLY",
      "CROSS_THRESHOLD",
      "GAP_THROUGH_THRESHOLD",
      "OSCILLATE_AROUND_THRESHOLD",
      "STALE_MARKET",
      "PROVIDER_SWITCH_WITH_GAP");

  private static final Set<String> REQUIRED_SPOT_ACTION_PATHS = Set.of(
      "BUY_BUY",
      "BUY_PARTIAL_SELL",
      "PARTIAL_SELL_BUY",
      "PARTIAL_SELL_PARTIAL_SELL");

  private static final List<String> PERP_CORE_PRICE_PATHS = List.of(
      "UP_UP_UP",
      "UP_UP_DOWN",
      "UP_DOWN_UP",
      "UP_DOWN_DOWN",
      "DOWN_UP_UP",
      "DOWN_UP_DOWN",
      "DOWN_DOWN_UP",
      "DOWN_DOWN_DOWN");

  private static final List<String> PERP_CORE_ACTION_PATHS = List.of(
      "ADD_ADD",
      "ADD_PARTIAL_CLOSE",
      "PARTIAL_CLOSE_ADD",
      "PARTIAL_CLOSE_PARTIAL_CLOSE");

  private static final Set<String> REQUIRED_SUPPLEMENTAL_CASES = Set.of(
      "SPOT_SELL_PROFIT",
      "SPOT_SELL_LOSS",
      "SPOT_SELL_GROSS_BREAKEVEN",
      "SPOT_ADD_UP",
      "SPOT_ADD_DOWN",
      "SPOT_MULTI_BUY_SELL",
      "SPOT_FULL_ZERO_REBUY_RESET",
      "SPOT_LIMIT_IMMEDIATE",
      "SPOT_LIMIT_WAIT",
      "SPOT_LIMIT_MODIFY",
      "SPOT_LIMIT_CANCEL",
      "SPOT_LIMIT_TRIGGER",
      "SPOT_STOP_MARKET_PENDING",
      "SPOT_STOP_MARKET_EXACT",
      "SPOT_STOP_MARKET_CROSS",
      "SPOT_STOP_MARKET_GAP",
      "SPOT_OCO_LIMIT_WIN",
      "SPOT_OCO_STOP_WIN",
      "SPOT_OCO_GROUP_CANCEL",
      "SPOT_OCO_DUAL_TRIGGER_RACE",
      "SPOT_INSUFFICIENT_BALANCE",
      "SPOT_OVERSELL",
      "SPOT_QUANTITY_PRECISION_REJECT",
      "SPOT_PRICE_PRECISION_REJECT",
      "SPOT_MIN_NOTIONAL_REJECT",
      "SPOT_BALANCE_RACE",
      "SPOT_CLIENT_ORDER_REPLAY",
      "SPOT_CLIENT_ORDER_CONFLICT",
      "SPOT_LEGACY_STOP_REJECT",
      "SPOT_LEVERAGE_REJECT",
      "SPOT_REDUCE_ONLY_REJECT",
      "SPOT_PROTECTION_REJECT",
      "PERP_CLOSE_PROFIT",
      "PERP_CLOSE_LOSS",
      "PERP_CLOSE_GROSS_BREAKEVEN",
      "PERP_SAME_SIDE_ADD",
      "PERP_SAME_SIDE_PARTIAL_CLOSE",
      "PERP_SAME_SIDE_FULL_CLOSE",
      "PERP_LONG_TO_SHORT_REVERSAL",
      "PERP_SHORT_TO_LONG_REVERSAL",
      "PERP_REDUCE_ONLY_BELOW",
      "PERP_REDUCE_ONLY_EQUAL",
      "PERP_REDUCE_ONLY_ABOVE_REJECT",
      "PERP_ONE_WAY_NETTING",
      "PERP_HEDGE_INDEPENDENT",
      "PERP_CROSS_MARGIN",
      "PERP_ISOLATED_MARGIN",
      "PERP_LEVERAGE_1X",
      "PERP_LEVERAGE_10X",
      "PERP_LEVERAGE_MAX",
      "PERP_LEVERAGE_OVER_MAX_REJECT",
      "PERP_LEVERAGE_UP",
      "PERP_LEVERAGE_DOWN_SAFE",
      "PERP_LEVERAGE_DOWN_INSUFFICIENT_REJECT",
      "PERP_ISOLATED_MARGIN_ADD",
      "PERP_ISOLATED_MARGIN_REDUCE_SAFE",
      "PERP_ISOLATED_MARGIN_REDUCE_UNSAFE_REJECT",
      "PERP_ORDER_MARKET",
      "PERP_LIMIT_IMMEDIATE",
      "PERP_LIMIT_WAIT",
      "PERP_LIMIT_MODIFY",
      "PERP_LIMIT_CANCEL",
      "PERP_LIMIT_TRIGGER",
      "PERP_STOP_MARKET_PENDING",
      "PERP_STOP_MARKET_EXACT",
      "PERP_STOP_MARKET_CROSS",
      "PERP_STOP_MARKET_GAP",
      "PERP_ATTACHED_TP",
      "PERP_ATTACHED_SL",
      "PERP_INDEPENDENT_TP",
      "PERP_INDEPENDENT_SL",
      "PERP_TP_MARKET",
      "PERP_TP_LIMIT",
      "PERP_SL_MARKET",
      "PERP_SL_LIMIT",
      "PERP_MULTI_PROTECTION_TRIGGER",
      "PERP_PROTECTION_RESIZE",
      "PERP_PROTECTION_EXPIRE",
      "PERP_PROTECTION_LIMIT_10",
      "PERP_PROTECTION_LIMIT_11_REJECT",
      "PERP_FUNDING_POSITIVE_LONG",
      "PERP_FUNDING_POSITIVE_SHORT",
      "PERP_FUNDING_NEGATIVE_LONG",
      "PERP_FUNDING_NEGATIVE_SHORT",
      "PERP_FUNDING_ZERO_LONG",
      "PERP_FUNDING_ZERO_SHORT",
      "PERP_FUNDING_ADD_BEFORE_SETTLEMENT",
      "PERP_FUNDING_REDUCE_BEFORE_SETTLEMENT",
      "PERP_FUNDING_FULL_CLOSE_BEFORE_SETTLEMENT",
      "PERP_FUNDING_CROSS_LIQUIDATION",
      "PERP_FUNDING_ISOLATED_LIQUIDATION",
      "PERP_LIQUIDATION_SAFE",
      "PERP_LIQUIDATION_EXACT_BOUNDARY",
      "PERP_LIQUIDATION_BEYOND_BOUNDARY",
      "PERP_LIQUIDATION_GAP",
      "PERP_LIQUIDATION_BANKRUPTCY_SHORTFALL",
      "PERP_MULTI_POSITION_RECOVERY",
      "PERP_CASCADING_LIQUIDATION",
      "PERP_CANCEL_ALL",
      "PERP_CANCEL_ALL_PARTIAL_FAILURE",
      "PERP_CLOSE_ALL_SUCCESS",
      "PERP_CLOSE_ALL_PARTIAL_FAILURE",
      "PERP_ADMIN_FORCE_CLOSE",
      "PERP_ADMIN_FORCE_CLOSE_PERMISSION_REJECT",
      "PERP_CLOSE_VS_PROTECTION_RACE",
      "PERP_CLOSE_VS_LIQUIDATION_RACE",
      "PERP_CLOSE_VS_BATCH_RACE",
      "PERP_SAME_POSITION_DOUBLE_CLOSE",
      "PERP_STALE_MARKET",
      "PERP_INCOMPLETE_MARKET",
      "PERP_PROVIDER_SWITCH_WITH_GAP",
      "PERP_LOCK_WAIT_EXPIRY",
      "PERP_TRADE_ROLLBACK",
      "PERP_LEDGER_ROLLBACK",
      "PERP_CLIENT_ORDER_REPLAY",
      "PERP_CLIENT_ORDER_CONFLICT",
      "PERP_QUANTITY_BASE",
      "PERP_QUANTITY_QUOTE",
      "PERP_QUANTITY_CONTRACTS",
      "PERP_QUANTITY_STEP_REJECT",
      "PERP_PRICE_PRECISION_REJECT",
      "PERP_MIN_SIZE_REJECT",
      "PERP_PARTIAL_FILL_COMPAT_REJECT");

  private static final Pattern JAVA_CLASS_NAME = Pattern.compile(
      "[A-Za-z_$][A-Za-z\\d_$]*(\\.[A-Za-z_$][A-Za-z\\d_$]*)+");
  private static final Pattern JAVA_METHOD_NAME = Pattern.compile(
      "[A-Za-z_$][A-Za-z\\d_$]*");

  @Test
  void locksTheExactMatrixColumnContract() {
    assertThat(ScenarioDefinition.COLUMNS).containsExactlyElementsOf(REQUIRED_COLUMNS);
    assertThat(List.of(ScenarioDefinition.class.getRecordComponents()).stream()
        .map(RecordComponent::getName))
        .containsExactlyElementsOf(REQUIRED_COLUMNS);
  }

  @Test
  void catalogRowsAreUniqueDeterministicAndExecutable() {
    List<ScenarioDefinition> scenarios = ScenarioCatalog.all();
    List<String> caseIds = scenarios.stream().map(ScenarioDefinition::caseId).toList();

    assertThat(scenarios).isNotEmpty();
    assertThat(caseIds).allSatisfy(caseId -> assertThat(caseId).isNotBlank());
    assertThat(new HashSet<>(caseIds)).hasSameSizeAs(caseIds);
    assertThat(caseIds).isSorted();
    assertThat(ScenarioCatalog.spot().toList())
        .allMatch(scenario -> scenario.productType() == ProductType.CRYPTO_SPOT);
    assertThat(ScenarioCatalog.perpetual().toList())
        .allMatch(scenario -> scenario.productType() == ProductType.LINEAR_PERP);
    assertThat(scenarios).allSatisfy(scenario -> {
      assertThat(scenario.testClass()).matches(JAVA_CLASS_NAME);
      assertThat(scenario.testMethod()).matches(JAVA_METHOD_NAME);
      assertThat(scenario.executionStatus()).isEqualTo("NOT_RUN");
      assertThat(scenario.priceSteps()).isNotEmpty();
      assertThat(scenario.actions()).isNotEmpty();
    });
  }

  @Test
  void bindsEveryScenarioToDemoWithoutAddingSerializedColumns() throws Exception {
    assertThat(List.of(ScenarioDefinition.class.getDeclaredMethods()).stream()
        .map(Method::getName))
        .contains("executionMode", "accountType");

    Method executionMode = ScenarioDefinition.class.getDeclaredMethod("executionMode");
    Method accountType = ScenarioDefinition.class.getDeclaredMethod("accountType");
    for (ScenarioDefinition scenario : ScenarioCatalog.all()) {
      assertThat(executionMode.invoke(scenario)).isEqualTo(ExecutionMode.DEMO);
      assertThat(accountType.invoke(scenario)).isEqualTo(AccountType.DEMO);
      assertThat(fieldNames(OBJECT_MAPPER.valueToTree(scenario)))
          .containsExactlyElementsOf(REQUIRED_COLUMNS);
    }
  }

  @Test
  void excludesUnsupportedProductsAndNormalPathCompatibilityStates() {
    List<ScenarioDefinition> scenarios = ScenarioCatalog.all();
    List<ScenarioDefinition> successful = scenarios.stream()
        .filter(scenario -> scenario.expectedError().isBlank())
        .toList();

    assertThat(scenarios.stream().map(ScenarioDefinition::productType))
        .containsOnly(ProductType.CRYPTO_SPOT, ProductType.LINEAR_PERP);
    assertThat(successful)
        .noneMatch(scenario -> scenario.orderType() == OrderType.STOP)
        .noneMatch(scenario -> scenario.expectedOrder().contains("PARTIALLY_FILLED"));
    assertThat(scenarios.stream()
        .filter(scenario -> scenario.orderType() == OrderType.STOP)
        .map(ScenarioDefinition::caseId))
        .containsExactly("SPOT_LEGACY_STOP_REJECT");
    assertThat(scenarios.stream()
        .filter(scenario -> scenario.expectedOrder().contains("PARTIALLY_FILLED")))
        .allMatch(scenario -> !scenario.expectedError().isBlank());
  }

  @Test
  void coversAllNamedPriceAndCoreActionPaths() {
    List<ScenarioDefinition> scenarios = ScenarioCatalog.all();
    Set<String> pricePaths = scenarios.stream()
        .flatMap(scenario -> scenario.priceSteps().stream())
        .map(step -> step.path().name())
        .collect(Collectors.toSet());
    Set<String> spotActionPaths = ScenarioCatalog.spot()
        .filter(scenario -> scenario.caseId().startsWith("SPOT_CORE_"))
        .map(ScenarioCatalogTest::actionPath)
        .collect(Collectors.toSet());

    assertThat(pricePaths).containsExactlyInAnyOrderElementsOf(REQUIRED_PRICE_PATHS);
    assertThat(spotActionPaths).containsExactlyInAnyOrderElementsOf(REQUIRED_SPOT_ACTION_PATHS);
  }

  @Test
  void coversTheExact64PerpetualCoreChains() {
    Set<String> expected = new LinkedHashSet<>();
    for (String path : PERP_CORE_PRICE_PATHS) {
      for (String actionPath : PERP_CORE_ACTION_PATHS) {
        for (PositionSide direction : List.of(PositionSide.LONG, PositionSide.SHORT)) {
          expected.add(path + "|" + actionPath + "|" + direction);
        }
      }
    }

    List<ScenarioDefinition> core = ScenarioCatalog.perpetual()
        .filter(scenario -> scenario.caseId().startsWith("PERP_CORE_"))
        .toList();
    Set<String> actual = core.stream()
        .map(scenario -> scenario.priceSteps().getFirst().path().name()
            + "|" + actionPath(scenario)
            + "|" + scenario.actions().getFirst().direction())
        .collect(Collectors.toSet());

    assertThat(core).hasSize(64);
    assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
  }

  @Test
  void corePartialCloseAndSemanticSiblingCasesUseDistinctExecutableInputs() {
    assertThat(ScenarioCatalog.perpetual()
        .filter(scenario -> scenario.caseId().startsWith("PERP_CORE_"))
        .filter(scenario -> scenario.actions().getFirst().type()
            == ScenarioAction.Type.PARTIAL_CLOSE))
        .allSatisfy(scenario -> assertThat(scenario.initialPosition())
            .as(scenario.caseId())
            .isNotEqualTo("NONE"));

    assertThat(actionParameterDecimal("SPOT_LIMIT_IMMEDIATE", "price"))
        .isGreaterThan(scenario("SPOT_LIMIT_IMMEDIATE").priceSteps().getFirst().ask());
    assertThat(actionParameterDecimal("SPOT_LIMIT_WAIT", "price"))
        .isLessThan(scenario("SPOT_LIMIT_WAIT").priceSteps().getFirst().bid());
    assertThat(scenario("SPOT_OCO_LIMIT_WIN").priceSteps().getFirst().path())
        .isNotEqualTo(scenario("SPOT_OCO_STOP_WIN").priceSteps().getFirst().path());

    assertThat(scenario("PERP_REDUCE_ONLY_BELOW").actions().get(1).quantity())
        .isLessThan(scenario("PERP_REDUCE_ONLY_BELOW").actions().getFirst().quantity());
    assertThat(scenario("PERP_REDUCE_ONLY_EQUAL").actions().get(1).quantity())
        .isEqualByComparingTo(scenario("PERP_REDUCE_ONLY_EQUAL").actions().getFirst().quantity());
    assertThat(scenario("PERP_REDUCE_ONLY_ABOVE_REJECT").actions().get(1).quantity())
        .isGreaterThan(scenario("PERP_REDUCE_ONLY_ABOVE_REJECT").actions().getFirst().quantity());

    assertThat(actionParameterDecimal("PERP_FUNDING_POSITIVE_LONG", "fundingRate"))
        .isPositive();
    assertThat(actionParameterDecimal("PERP_FUNDING_NEGATIVE_LONG", "fundingRate"))
        .isNegative();
    assertThat(actionParameterDecimal("PERP_FUNDING_ZERO_LONG", "fundingRate"))
        .isZero();

    List<ScenarioDefinition> liquidationCases = List.of(
        scenario("PERP_LIQUIDATION_SAFE"),
        scenario("PERP_LIQUIDATION_BEYOND_BOUNDARY"),
        scenario("PERP_LIQUIDATION_BANKRUPTCY_SHORTFALL"));
    assertThat(liquidationCases.stream()
        .map(caseDefinition -> caseDefinition.priceSteps().getFirst().path()))
        .doesNotHaveDuplicates();
    assertThat(liquidationCases.stream()
        .flatMap(caseDefinition -> caseDefinition.actions().stream())
        .filter(action -> action.type() == ScenarioAction.Type.LIQUIDATE)
        .map(ScenarioCatalogTest::json)
        .map(action -> action.path("parameters").path("condition").asText()))
        .doesNotHaveDuplicates()
        .allSatisfy(condition -> assertThat(condition).isNotBlank());
  }

  @Test
  void coversAllRequiredDimensionsAndSupplementalRules() {
    List<ScenarioDefinition> scenarios = ScenarioCatalog.all();
    List<ScenarioDefinition> successfulSpot = ScenarioCatalog.spot()
        .filter(scenario -> scenario.expectedError().isBlank())
        .toList();

    assertThat(scenarios.stream().map(ScenarioDefinition::orderType))
        .contains(OrderType.MARKET, OrderType.LIMIT, OrderType.STOP_MARKET);
    assertThat(scenarios.stream().map(ScenarioDefinition::marginMode))
        .contains(MarginMode.CASH, MarginMode.CROSS, MarginMode.ISOLATED);
    assertThat(scenarios.stream().map(ScenarioDefinition::positionMode))
        .contains(PositionMode.ONE_WAY, PositionMode.HEDGE);
    assertThat(scenarios.stream().map(ScenarioDefinition::positionSide))
        .contains(PositionSide.BOTH, PositionSide.LONG, PositionSide.SHORT);
    assertThat(scenarios.stream().map(ScenarioDefinition::quantityUnit))
        .contains(QuantityUnit.BASE, QuantityUnit.QUOTE, QuantityUnit.CONTRACTS);
    assertThat(scenarios.stream().map(ScenarioDefinition::reduceOnly))
        .contains(true, false);
    assertThat(scenarios.stream().map(ScenarioDefinition::leverage))
        .contains(1, 10, 100, 101);
    assertThat(successfulSpot).allSatisfy(scenario -> {
      assertThat(scenario.marginMode()).isEqualTo(MarginMode.CASH);
      assertThat(scenario.positionMode()).isEqualTo(PositionMode.ONE_WAY);
      assertThat(scenario.positionSide()).isEqualTo(PositionSide.BOTH);
      assertThat(scenario.leverage()).isEqualTo(1);
      assertThat(scenario.reduceOnly()).isFalse();
      scenario.actions().stream()
          .filter(action -> action.parameters().orderType() == OrderType.MARKET)
          .forEach(action -> {
            if (action.type() == ScenarioAction.Type.BUY) {
              assertThat(action.parameters().quantityUnit())
                  .as(scenario.caseId())
                  .isEqualTo(QuantityUnit.QUOTE);
            }
            if (action.type() == ScenarioAction.Type.SELL
                || action.type() == ScenarioAction.Type.PARTIAL_SELL) {
              assertThat(action.parameters().quantityUnit())
                  .as(scenario.caseId())
                  .isEqualTo(QuantityUnit.BASE);
            }
          });
      scenario.actions().stream()
          .filter(action -> action.parameters().orderType() == OrderType.LIMIT
              || action.parameters().orderType() == OrderType.STOP_MARKET)
          .forEach(action -> assertThat(action.parameters().quantityUnit())
              .as(scenario.caseId())
              .isEqualTo(QuantityUnit.BASE));
    });
    assertThat(scenarios.stream().map(ScenarioDefinition::caseId))
        .containsAll(REQUIRED_SUPPLEMENTAL_CASES);
    assertThat(scenarios).filteredOn(scenario ->
        Set.of("PERP_QUANTITY_BASE", "PERP_QUANTITY_QUOTE", "PERP_QUANTITY_CONTRACTS")
            .contains(scenario.caseId()))
        .allMatch(scenario -> scenario.expectedError().isBlank());
    assertThat(scenarios).filteredOn("caseId", "PERP_PROVIDER_SWITCH_WITH_GAP")
        .singleElement()
        .satisfies(scenario -> {
          assertThat(scenario.expectedError()).isBlank();
          assertThat(scenario.expectedEvents()).contains("MARKET_SOURCE_CHANGED");
        });
    assertThat(scenarios).filteredOn(scenario ->
        Set.of("SPOT_CLIENT_ORDER_CONFLICT", "PERP_CLIENT_ORDER_CONFLICT")
            .contains(scenario.caseId()))
        .allSatisfy(scenario -> assertThat(scenario.actions().stream()
            .map(ScenarioAction::type))
            .containsExactly(ScenarioAction.Type.PLACE_ORDER, ScenarioAction.Type.REPLAY));
    assertThat(ScenarioCatalog.spot())
        .anyMatch(scenario -> scenario.expectedError().isBlank())
        .anyMatch(scenario -> !scenario.expectedError().isBlank());
    assertThat(ScenarioCatalog.perpetual())
        .anyMatch(scenario -> scenario.expectedError().isBlank())
        .anyMatch(scenario -> !scenario.expectedError().isBlank());
  }

  @Test
  void supplementalCasesExposeStructuredExecutableInputs() {
    List<ScenarioDefinition> supplemental = ScenarioCatalog.all().stream()
        .filter(scenario -> !scenario.caseId().startsWith("SPOT_CORE_"))
        .filter(scenario -> !scenario.caseId().startsWith("PERP_CORE_"))
        .toList();

    assertThat(supplemental).allSatisfy(scenario -> {
      assertThat(scenario.quantityUnit()).isNotNull();
      assertThat(scenario.priceSteps()).isNotEmpty();
      assertThat(scenario.actions()).isNotEmpty();
      for (ScenarioAction action : scenario.actions()) {
        assertThat(action.quantity() == null || action.quantity().signum() > 0).isTrue();
        JsonNode parameters = OBJECT_MAPPER.valueToTree(action).path("parameters");
        assertThat(parameters.isObject()).as(scenario.caseId()).isTrue();
        assertThat(parameters.path("actionId").asText()).as(scenario.caseId()).isNotBlank();
        switch (action.type()) {
          case BUY, SELL, PARTIAL_SELL, ADD, PARTIAL_CLOSE, FULL_CLOSE, PLACE_ORDER, REVERSE -> {
            assertThat(action.quantity()).as(scenario.caseId()).isPositive();
            assertThat(parameters.path("clientOrderId").asText())
                .as(scenario.caseId())
                .isNotBlank();
            assertThat(parameters.path("quantityUnit").asText())
                .as(scenario.caseId())
                .isNotBlank();
            assertThat(parameters.path("orderType").asText())
                .as(scenario.caseId())
                .isNotBlank();
            assertThat(parameters.path("feeRate").isNumber()).as(scenario.caseId()).isTrue();
            assertThat(parameters.path("makerFeeRate").decimalValue())
                .as(scenario.caseId())
                .isEqualByComparingTo("0.0002");
            assertThat(parameters.path("takerFeeRate").decimalValue())
                .as(scenario.caseId())
                .isEqualByComparingTo("0.0005");
            assertThat(parameters.path("worstFeeRate").isNumber())
                .as(scenario.caseId())
                .isTrue();
            assertThat(parameters.path("slippageRate").isNumber())
                .as(scenario.caseId())
                .isTrue();
            if (scenario.productType() == ProductType.LINEAR_PERP) {
              assertThat(parameters.path("maintenanceMarginRate").isNumber())
                  .as(scenario.caseId())
                  .isTrue();
              assertThat(parameters.path("liquidationFeeRate").isNumber())
                  .as(scenario.caseId())
                  .isTrue();
            }
          }
          case MODIFY, CANCEL, REPLAY -> assertThat(parameters.path("orderId").asText())
              .as(scenario.caseId())
              .isNotBlank();
          case TRIGGER -> {
            assertThat(parameters.path("orderId").asText()).as(scenario.caseId()).isNotBlank();
            assertThat(parameters.path("triggerExecutionType").asText())
                .as(scenario.caseId())
                .isNotBlank();
          }
          case CREATE_OCO -> {
            assertThat(parameters.path("orderId").asText()).as(scenario.caseId()).isNotBlank();
            assertThat(parameters.path("competingOrderId").asText())
                .as(scenario.caseId())
                .isNotBlank();
            assertThat(parameters.path("price").isNumber()).as(scenario.caseId()).isTrue();
            assertThat(parameters.path("triggerPrice").isNumber()).as(scenario.caseId()).isTrue();
          }
          case CHANGE_LEVERAGE -> assertThat(parameters.path("leverage").asInt())
              .as(scenario.caseId())
              .isPositive();
          case ADJUST_MARGIN -> assertThat(parameters.path("marginDelta").decimalValue())
              .as(scenario.caseId())
              .isNotZero();
          case SET_PROTECTION -> {
            assertThat(parameters.path("protectionId").asText())
                .as(scenario.caseId())
                .isNotBlank();
            assertThat(parameters.path("triggerPrice").isNumber()).as(scenario.caseId()).isTrue();
            assertThat(parameters.path("protectionType").asText())
                .as(scenario.caseId())
                .isNotBlank();
            assertThat(parameters.path("triggerExecutionType").asText())
                .as(scenario.caseId())
                .isNotBlank();
            assertThat(parameters.path("triggerPriceType").asText())
                .as(scenario.caseId())
                .isNotBlank();
          }
          case SETTLE_FUNDING -> assertThat(parameters.path("fundingRate").isNumber())
              .as(scenario.caseId())
              .isTrue();
          default -> {
            // actionId is the complete typed input for actions without order-specific fields.
          }
        }
      }
      if (scenario.orderType() == OrderType.LIMIT) {
        assertThat(scenario.actions().stream()
            .map(ScenarioCatalogTest::json)
            .map(action -> action.path("parameters").path("price"))
            .anyMatch(JsonNode::isNumber))
            .as(scenario.caseId())
            .isTrue();
      }
      if (scenario.orderType() == OrderType.STOP_MARKET) {
        assertThat(scenario.actions().stream()
            .map(ScenarioCatalogTest::json)
            .map(action -> action.path("parameters").path("triggerPrice"))
            .anyMatch(JsonNode::isNumber))
            .as(scenario.caseId())
            .isTrue();
      }
      if (!scenario.expectedError().isBlank()) {
        boolean actionCondition = scenario.actions().stream()
            .map(ScenarioCatalogTest::json)
            .map(action -> action.path("parameters").path("failureCondition").asText())
            .anyMatch(condition -> !condition.isBlank());
        boolean marketCondition = scenario.priceSteps().stream()
            .map(ScenarioCatalogTest::json)
            .map(step -> step.path("missingFields"))
            .anyMatch(fields -> fields.isArray() && !fields.isEmpty());
        assertThat(actionCondition || marketCondition).as(scenario.caseId()).isTrue();
      }
    });
  }

  @Test
  void everyOrderCreatingActionDeclaresItsExchangeSide() {
    Set<ScenarioAction.Type> orderCreatingTypes = Set.of(
        ScenarioAction.Type.BUY,
        ScenarioAction.Type.SELL,
        ScenarioAction.Type.PARTIAL_SELL,
        ScenarioAction.Type.ADD,
        ScenarioAction.Type.PARTIAL_CLOSE,
        ScenarioAction.Type.FULL_CLOSE,
        ScenarioAction.Type.PLACE_ORDER,
        ScenarioAction.Type.REPLAY,
        ScenarioAction.Type.REVERSE,
        ScenarioAction.Type.CREATE_OCO);

    ScenarioCatalog.all().forEach(scenario ->
        scenario.actions().stream()
            .filter(action -> orderCreatingTypes.contains(action.type()))
            .forEach(action -> assertThat(
                json(action).path("parameters").path("side").asText())
                .as(scenario.caseId() + ":" + action.parameters().actionId())
                .isNotBlank()));
  }

  @Test
  void concreteRegressionCasesContainInputsThatCanTriggerTheirRules() {
    ScenarioDefinition insufficient = scenario("SPOT_INSUFFICIENT_BALANCE");
    ScenarioAction oversizedBuy = insufficient.actions().getFirst();
    assertThat(insufficient.initialBalances().get("USDT")).isEqualTo("100.00000000");
    assertThat(oversizedBuy.quantity()).isEqualByComparingTo("101.00000000");

    ScenarioDefinition oversell = scenario("SPOT_OVERSELL");
    ScenarioAction sell = oversell.actions().stream()
        .filter(action -> action.type() == ScenarioAction.Type.SELL)
        .findFirst()
        .orElseThrow();
    assertThat(sell.quantity())
        .isGreaterThan(new BigDecimal(oversell.initialBalances().get("BTC")));
    assertThat(sell.quantity().remainder(new BigDecimal("0.00010000"))).isZero();

    ScenarioCatalog.spot().forEach(spot -> {
      for (int index = 0; index < spot.actions().size(); index++) {
        ScenarioAction action = spot.actions().get(index);
        if (action.type() != ScenarioAction.Type.CREATE_OCO) {
          continue;
        }
        ScenarioPriceStep actionMarket = spot.priceSteps().get(
            Math.min(index + 1, spot.priceSteps().size() - 1));
        assertThat(action.parameters().side()).as(spot.caseId()).isEqualTo(OrderSide.SELL);
        assertThat(action.parameters().price())
            .as(spot.caseId() + " limit > last")
            .isGreaterThan(actionMarket.last());
        assertThat(actionMarket.last())
            .as(spot.caseId() + " last > stop")
            .isGreaterThan(action.parameters().triggerPrice());
      }
    });

    ScenarioCatalog.perpetual()
        .filter(perpetual -> !perpetual.caseId().equals("PERP_QUANTITY_STEP_REJECT"))
        .forEach(perpetual -> perpetual.actions().stream()
            .filter(action -> action.quantity() != null)
            .filter(action -> action.parameters().quantityUnit() == QuantityUnit.CONTRACTS)
            .forEach(action -> assertThat(action.quantity().stripTrailingZeros().scale())
                .as(perpetual.caseId() + ":" + action.parameters().actionId())
                .isLessThanOrEqualTo(0)));

    ScenarioDefinition overclose = scenario("PERP_REDUCE_ONLY_ABOVE_REJECT");
    assertThat(overclose.reduceOnly()).isTrue();
    assertThat(overclose.actions()).extracting(ScenarioAction::type)
        .containsExactly(ScenarioAction.Type.ADD, ScenarioAction.Type.PARTIAL_CLOSE);
    assertThat(overclose.actions().get(1).quantity())
        .isGreaterThan(overclose.actions().getFirst().quantity());
    assertThat(json(overclose.actions().getFirst()).path("parameters").path("reduceOnly")
        .asBoolean()).isFalse();
    assertThat(json(overclose.actions().get(1)).path("parameters").path("reduceOnly")
        .asBoolean()).isTrue();
    assertPriorSuccessWasPreserved(overclose);

    ScenarioDefinition protectionLimit = scenario("PERP_PROTECTION_LIMIT_11_REJECT");
    List<JsonNode> protectionParameters = protectionLimit.actions().stream()
        .filter(action -> action.type() == ScenarioAction.Type.SET_PROTECTION)
        .map(ScenarioCatalogTest::json)
        .map(action -> action.path("parameters"))
        .toList();
    assertThat(protectionParameters).hasSize(11);
    assertThat(protectionParameters.stream()
        .map(parameters -> parameters.path("protectionId").asText()))
        .doesNotHaveDuplicates()
        .allSatisfy(protectionId -> assertThat(protectionId).isNotBlank());
    assertPriorSuccessWasPreserved(protectionLimit);

    ScenarioDefinition incomplete = scenario("PERP_INCOMPLETE_MARKET");
    assertThat(incomplete.priceSteps()).anySatisfy(step -> {
      JsonNode json = OBJECT_MAPPER.valueToTree(step);
      assertThat(json.path("missingFields"))
          .anySatisfy(field -> assertThat(field.asText()).isEqualTo("MARK"));
      assertThat(json.get("mark").isNull()).isTrue();
    });

    assertThat(scenario("PERP_CROSS_MARGIN").priceSteps())
        .allMatch(step -> step.path() == ScenarioPriceStep.PricePath.FLAT);
  }

  @Test
  void modelsFundingAndOcoRacesInExecutionOrder() {
    for (String caseId : List.of(
        "PERP_FUNDING_CROSS_LIQUIDATION",
        "PERP_FUNDING_ISOLATED_LIQUIDATION")) {
      assertThat(scenario(caseId).actions()).extracting(ScenarioAction::type)
          .containsExactly(
              ScenarioAction.Type.ADD,
              ScenarioAction.Type.SETTLE_FUNDING,
              ScenarioAction.Type.LIQUIDATE);
    }

    ScenarioDefinition ocoRace = scenario("SPOT_OCO_DUAL_TRIGGER_RACE");
    assertThat(ocoRace.actions()).extracting(ScenarioAction::type)
        .containsExactly(ScenarioAction.Type.CREATE_OCO, ScenarioAction.Type.RACE);
    JsonNode raceParameters = OBJECT_MAPPER.valueToTree(ocoRace.actions().get(1))
        .path("parameters");
    assertThat(raceParameters.path("orderId").asText()).isNotBlank();
    assertThat(raceParameters.path("competingOrderId").asText())
        .isNotBlank()
        .isNotEqualTo(raceParameters.path("orderId").asText());
  }

  @Test
  void liquidationAndProtectionScenariosUseExecutableInputs() {
    for (String caseId : List.of(
        "PERP_FUNDING_CROSS_LIQUIDATION",
        "PERP_FUNDING_ISOLATED_LIQUIDATION",
        "PERP_LIQUIDATION_BANKRUPTCY_SHORTFALL")) {
      assertThat(scenario(caseId).actions().getFirst().quantity())
          .as(caseId)
          .isEqualByComparingTo("100");
    }
    assertThat(scenario("PERP_FUNDING_CROSS_LIQUIDATION").initialBalances().get("USDT"))
        .isEqualTo("1015.00000000");
    assertThat(scenario("PERP_FUNDING_ISOLATED_LIQUIDATION").initialBalances().get("USDT"))
        .isEqualTo("1015.00000000");
    assertThat(scenario("PERP_LIQUIDATION_BANKRUPTCY_SHORTFALL")
        .initialBalances().get("USDT")).isEqualTo("2000.00000000");

    ScenarioDefinition cascade = scenario("PERP_CASCADING_LIQUIDATION");
    assertThat(cascade.actions()).extracting(ScenarioAction::type)
        .containsExactly(
            ScenarioAction.Type.ADD,
            ScenarioAction.Type.ADD,
            ScenarioAction.Type.LIQUIDATE);
    assertThat(cascade.priceSteps().get(1).mark())
        .isEqualByComparingTo(cascade.priceSteps().get(2).mark());
    assertThat(cascade.priceSteps().get(3).mark()).isEqualByComparingTo("70.1");
    assertThat(cascade.leverage()).isEqualTo(100);
    assertThat(cascade.initialBalances().get("USDT")).isEqualTo("200.00000000");

    ScenarioDefinition resize = scenario("PERP_PROTECTION_RESIZE");
    assertThat(resize.actions()).extracting(ScenarioAction::type)
        .containsExactly(
            ScenarioAction.Type.ADD,
            ScenarioAction.Type.SET_PROTECTION,
            ScenarioAction.Type.PARTIAL_CLOSE);
  }

  @Test
  void conflictCasesKeepTheSuccessfulFirstMutationAndUseBackendErrorCodes() {
    for (String caseId : List.of(
        "SPOT_CLIENT_ORDER_CONFLICT",
        "PERP_CLIENT_ORDER_CONFLICT")) {
      ScenarioDefinition conflict = scenario(caseId);
      assertPriorSuccessWasPreserved(conflict);
      JsonNode first = OBJECT_MAPPER.valueToTree(conflict.actions().getFirst())
          .path("parameters");
      JsonNode second = OBJECT_MAPPER.valueToTree(conflict.actions().get(1))
          .path("parameters");
      assertThat(first.path("clientOrderId").asText())
          .isNotBlank()
          .isEqualTo(second.path("clientOrderId").asText());
      assertThat(conflict.actions().getFirst().quantity())
          .isNotEqualByComparingTo(conflict.actions().get(1).quantity());
    }

    assertThat(ScenarioCatalog.all().stream()
        .map(ScenarioDefinition::expectedError)
        .filter(error -> !error.isBlank()))
        .allMatch(ErrorCode.standardCodes()::contains);
  }

  @Test
  void precisionAndMinimumCasesUseTheExactCanonicalRuleFailure() {
    assertThat(scenario("SPOT_QUANTITY_PRECISION_REJECT").expectedError())
        .isEqualTo(ErrorCode.QUANTITY_STEP_MISMATCH);
    assertThat(scenario("SPOT_PRICE_PRECISION_REJECT").expectedError())
        .isEqualTo(ErrorCode.PRICE_TICK_MISMATCH);
    assertThat(scenario("SPOT_MIN_NOTIONAL_REJECT").expectedError())
        .isEqualTo(ErrorCode.ORDER_NOTIONAL_TOO_SMALL);
    assertThat(scenario("PERP_QUANTITY_STEP_REJECT").expectedError())
        .isEqualTo(ErrorCode.CONTRACT_QUANTITY_NOT_INTEGRAL);
    assertThat(scenario("PERP_PRICE_PRECISION_REJECT").expectedError())
        .isEqualTo(ErrorCode.PRICE_TICK_MISMATCH);
    assertThat(scenario("PERP_MIN_SIZE_REJECT")).satisfies(minimum -> {
      assertThat(minimum.quantityUnit()).isEqualTo(QuantityUnit.BASE);
      assertThat(minimum.actions().getFirst().parameters().quantityUnit())
          .isEqualTo(QuantityUnit.BASE);
      assertThat(minimum.expectedError()).isEqualTo(ErrorCode.QUANTITY_CONVERTS_TO_ZERO);
    });
  }

  @Test
  void staleAndProviderSwitchCasesExposeTheConditionOnTheActionStep() {
    ScenarioDefinition stale = scenario("PERP_STALE_MARKET");
    ScenarioPriceStep staleActionStep = stale.priceSteps().get(1);
    assertThat(staleActionStep.expiresAt()).isBefore(staleActionStep.asOf());

    ScenarioDefinition switched = scenario("PERP_PROVIDER_SWITCH_WITH_GAP");
    assertThat(switched.actions()).hasSize(2);
    assertThat(switched.priceSteps().get(1).source())
        .isNotEqualTo(switched.priceSteps().get(2).source());
    assertThat(switched.priceSteps().get(2).mark())
        .isNotEqualByComparingTo(switched.priceSteps().get(1).mark());
  }

  @Test
  void pendingAndConditionalOrdersUseExecutableExchangePriceRelations() {
    for (String caseId : List.of("SPOT_LIMIT_TRIGGER", "PERP_LIMIT_TRIGGER")) {
      ScenarioDefinition limit = scenario(caseId);
      BigDecimal price = limit.actions().getFirst().parameters().price();
      assertThat(price).as(caseId + " waits on placement")
          .isLessThan(limit.priceSteps().get(1).ask());
      assertThat(price).as(caseId + " becomes marketable later")
          .isGreaterThanOrEqualTo(limit.priceSteps().get(2).ask());
    }

    assertConditionalPath(
        "SPOT_STOP_MARKET_EXACT", false, 0);
    assertConditionalPath(
        "PERP_STOP_MARKET_EXACT", true, 0);
    assertConditionalPath(
        "SPOT_STOP_MARKET_CROSS", false, 1);
    assertConditionalPath(
        "PERP_STOP_MARKET_CROSS", true, 1);
    assertConditionalPath(
        "SPOT_STOP_MARKET_GAP", false, 1);
    assertConditionalPath(
        "PERP_STOP_MARKET_GAP", true, 1);

    ScenarioDefinition modified = scenario("PERP_LIMIT_MODIFY");
    ScenarioAction pending = modified.actions().getFirst();
    ScenarioAction modification = modified.actions().get(1);
    assertThat(modification.parameters().price())
        .as("modified BUY remains below the controlled ask")
        .isLessThan(modified.priceSteps().get(2).ask());
    assertThat(modification.parameters().price())
        .as("the modify is observable and changes the price")
        .isNotEqualByComparingTo(pending.parameters().price());
    assertThat(modified.expectedTrades()).isEqualTo("NONE");
  }

  @Test
  void protectionInputsAreActiveBeforeTheyTriggerAndLimitChildrenAreMarketable() {
    for (String caseId : List.of("PERP_ATTACHED_TP", "PERP_ATTACHED_SL")) {
      ScenarioDefinition attached = scenario(caseId);
      assertThat(attached.actions()).hasSize(1);
      ScenarioAction entry = attached.actions().getFirst();
      assertThat(entry.type()).isEqualTo(ScenarioAction.Type.ADD);
      assertThat(entry.parameters().condition()).isEqualTo("ATTACHED_TO_ENTRY");
      assertProtectionSafeAt(entry, attached.priceSteps().get(1).mark(), caseId);
    }

    assertProtectionSafeAt(
        scenario("PERP_INDEPENDENT_TP").actions().getFirst(),
        scenario("PERP_INDEPENDENT_TP").priceSteps().get(1).mark(),
        "PERP_INDEPENDENT_TP");
    assertProtectionSafeAt(
        scenario("PERP_INDEPENDENT_SL").actions().getFirst(),
        scenario("PERP_INDEPENDENT_SL").priceSteps().get(1).mark(),
        "PERP_INDEPENDENT_SL");

    for (String caseId : List.of(
        "PERP_TP_MARKET",
        "PERP_TP_LIMIT",
        "PERP_SL_MARKET",
        "PERP_SL_LIMIT")) {
      ScenarioDefinition scenario = scenario(caseId);
      ScenarioAction protection = scenario.actions().get(1);
      BigDecimal creationMark = scenario.priceSteps().get(2).mark();
      BigDecimal triggerMark = scenario.priceSteps().get(3).mark();
      assertProtectionSafeAt(protection, creationMark, caseId);
      if (protection.parameters().protectionType().name().equals("TAKE_PROFIT")) {
        assertThat(triggerMark).as(caseId).isGreaterThanOrEqualTo(
            protection.parameters().triggerPrice());
      } else {
        assertThat(triggerMark).as(caseId).isLessThanOrEqualTo(
            protection.parameters().triggerPrice());
      }
      if (protection.parameters().triggerExecutionType().name().equals("LIMIT")) {
        assertThat(protection.parameters().price()).as(caseId + " SELL limit")
            .isLessThanOrEqualTo(scenario.priceSteps().get(3).bid());
      }
    }
  }

  @Test
  void liquidationAndFundingCasesStartFromReachableRiskStates() {
    ScenarioCatalog.perpetual().forEach(candidate -> {
      for (int actionIndex = 0; actionIndex < candidate.actions().size(); actionIndex++) {
        ScenarioAction action = candidate.actions().get(actionIndex);
        if (action.type() == ScenarioAction.Type.LIQUIDATE) {
          BigDecimal executableMark = candidate.priceSteps().get(
              Math.min(actionIndex + 1, candidate.priceSteps().size() - 1)).mark();
          assertThat(action.parameters().triggerPrice())
              .as(candidate.caseId() + " liquidation Oracle mark matches provider mark")
              .isEqualByComparingTo(executableMark);
        }
      }
    });

    assertThat(crossRiskGap(scenario("PERP_LIQUIDATION_SAFE"))).isPositive();
    assertThat(crossRiskGap(scenario("PERP_LIQUIDATION_EXACT_BOUNDARY"))).isZero();
    assertThat(crossRiskGap(scenario("PERP_LIQUIDATION_BEYOND_BOUNDARY"))).isNegative();
    assertThat(crossRiskGap(scenario("PERP_LIQUIDATION_GAP"))).isNegative();

    ScenarioDefinition shortfall = scenario("PERP_LIQUIDATION_BANKRUPTCY_SHORTFALL");
    assertThat(crossRiskGap(shortfall)).isNegative();
    assertThat(shortfall.priceSteps().get(2).mark())
        .isEqualByComparingTo(shortfall.actions().get(1).parameters().triggerPrice());

    for (String caseId : List.of(
        "PERP_FUNDING_CROSS_LIQUIDATION",
        "PERP_FUNDING_ISOLATED_LIQUIDATION")) {
      ScenarioDefinition funding = scenario(caseId);
      assertThat(new BigDecimal(funding.initialBalances().get("USDT")))
          .as(caseId)
          .isLessThan(new BigDecimal("20000"));
      assertThat(funding.actions().get(1).parameters().fundingRate())
          .as(caseId)
          .isEqualByComparingTo("0.2000");
    }
  }

  @Test
  void batchPermissionRaceAndLeverageRowsDescribeObservableBackendOutcomes() {
    ScenarioDefinition spotBalanceRace = scenario("SPOT_BALANCE_RACE");
    assertThat(spotBalanceRace.actions()).singleElement().satisfies(action -> {
      assertThat(action.type()).isEqualTo(ScenarioAction.Type.RACE);
      assertThat(action.quantity()).isEqualByComparingTo("6000");
      assertThat(action.parameters().quantityUnit()).isEqualTo(QuantityUnit.QUOTE);
      assertThat(action.parameters().side()).isEqualTo(OrderSide.BUY);
      assertThat(action.parameters().orderType()).isEqualTo(OrderType.MARKET);
      assertThat(action.parameters().orderId()).isNotBlank();
      assertThat(action.parameters().competingOrderId())
          .isNotBlank()
          .isNotEqualTo(action.parameters().orderId());
      assertThat(action.parameters().failureCondition())
          .isEqualTo("SECOND_RESERVATION_LOSES_BALANCE_RACE");
    });
    assertThat(spotBalanceRace.initialBalances().get("USDT")).isEqualTo("10000.00000000");
    assertThat(spotBalanceRace.actions().getFirst().quantity())
        .isLessThan(new BigDecimal(spotBalanceRace.initialBalances().get("USDT")));
    assertThat(spotBalanceRace.actions().getFirst().quantity().multiply(new BigDecimal("2")))
        .isGreaterThan(new BigDecimal(spotBalanceRace.initialBalances().get("USDT")));
    assertThat(spotBalanceRace.actions().getFirst().quantity())
        .isLessThanOrEqualTo(
            spotBalanceRace.priceSteps().get(1).ask().multiply(new BigDecimal("100")));
    assertPriorSuccessWasPreserved(spotBalanceRace);
    assertThat(scenario("SPOT_LEGACY_STOP_REJECT").actions().getFirst()
        .parameters().orderType()).isEqualTo(OrderType.STOP);

    ScenarioDefinition cancelPartial = scenario("PERP_CANCEL_ALL_PARTIAL_FAILURE");
    assertThat(cancelPartial.expectedError()).isBlank();
    assertThat(cancelPartial.actions()).extracting(ScenarioAction::type)
        .containsExactly(
            ScenarioAction.Type.PLACE_ORDER,
            ScenarioAction.Type.PLACE_ORDER,
            ScenarioAction.Type.CANCEL_ALL);

    ScenarioDefinition closePartial = scenario("PERP_CLOSE_ALL_PARTIAL_FAILURE");
    assertThat(closePartial.expectedError()).isBlank();
    assertThat(closePartial.expectedOrder()).contains("ITEM_FAILED");

    assertThat(scenario("PERP_ADMIN_FORCE_CLOSE_PERMISSION_REJECT").expectedError())
        .isEqualTo(ErrorCode.FORBIDDEN);
    assertThat(scenario("PERP_SAME_POSITION_DOUBLE_CLOSE").expectedError())
        .isEqualTo(ErrorCode.POSITION_NOT_FOUND);

    assertThat(scenario("PERP_LEVERAGE_UP").initialPosition()).contains("MARGIN=10");
    assertThat(scenario("PERP_LEVERAGE_DOWN_SAFE").initialPosition()).contains("MARGIN=10");
    assertThat(scenario("PERP_LEVERAGE_DOWN_INSUFFICIENT_REJECT").initialPosition())
        .contains("MARGIN=10000");
    assertThat(scenario("PERP_LEVERAGE_DOWN_INSUFFICIENT_REJECT").expectedError())
        .isEqualTo(ErrorCode.INSUFFICIENT_MARGIN);
    assertThat(new BigDecimal(
        scenario("PERP_LEVERAGE_DOWN_INSUFFICIENT_REJECT")
            .initialBalances().get("USDT"))).isLessThan(new BigDecimal("100000"));
    assertThat(scenario("PERP_PARTIAL_FILL_COMPAT_REJECT").actions())
        .singleElement()
        .extracting(action -> action.parameters().condition())
        .isEqualTo("PARTIAL_FILL_INPUT");
  }

  @Test
  void writesStableUtf8LfJsonAndRfc4180Csv(@TempDir Path tempDir) throws Exception {
    List<ScenarioDefinition> scenarios = ScenarioCatalog.all();
    Path first = tempDir.resolve("first");
    Path second = tempDir.resolve("second");
    Path checkedIn = Path.of("..", "docs", "testing");

    ScenarioMatrixArtifacts.write(first, scenarios);
    ScenarioMatrixArtifacts.write(second, scenarios);
    if (Boolean.getBoolean("scenario.writeArtifacts")) {
      ScenarioMatrixArtifacts.write(checkedIn, scenarios);
    }

    byte[] firstJson = Files.readAllBytes(first.resolve(ScenarioMatrixArtifacts.JSON_FILE));
    byte[] firstCsv = Files.readAllBytes(first.resolve(ScenarioMatrixArtifacts.CSV_FILE));
    assertThat(firstJson)
        .isEqualTo(Files.readAllBytes(second.resolve(ScenarioMatrixArtifacts.JSON_FILE)))
        .isEqualTo(Files.readAllBytes(checkedIn.resolve(ScenarioMatrixArtifacts.JSON_FILE)));
    assertThat(firstCsv)
        .isEqualTo(Files.readAllBytes(second.resolve(ScenarioMatrixArtifacts.CSV_FILE)))
        .isEqualTo(Files.readAllBytes(checkedIn.resolve(ScenarioMatrixArtifacts.CSV_FILE)));
    assertUtf8Lf(firstJson);
    assertUtf8Lf(firstCsv);

    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    JsonNode json = objectMapper.readTree(firstJson);
    assertThat(json).hasSize(scenarios.size());
    assertThat(json).allSatisfy(row -> assertThat(fieldNames(row))
        .containsExactlyElementsOf(REQUIRED_COLUMNS));

    List<List<String>> csvRows = parseCsv(new String(firstCsv, StandardCharsets.UTF_8));
    assertThat(csvRows).hasSize(scenarios.size() + 1);
    assertThat(csvRows.getFirst()).containsExactlyElementsOf(REQUIRED_COLUMNS);
    assertThat(csvRows.stream().skip(1)).allSatisfy(row -> assertThat(row).hasSize(28));
    assertThat(csvRows.stream().skip(1).map(row -> row.get(14)))
        .anyMatch(actions -> actions.contains("\",\""));
  }

  private static String actionPath(ScenarioDefinition scenario) {
    return scenario.actions().stream()
        .filter(action -> action.type() != ScenarioAction.Type.REVALUE)
        .map(action -> action.type().name())
        .collect(Collectors.joining("_"));
  }

  private static ScenarioDefinition scenario(String caseId) {
    return ScenarioCatalog.all().stream()
        .filter(scenario -> scenario.caseId().equals(caseId))
        .findFirst()
        .orElseThrow();
  }

  private static JsonNode json(Object value) {
    return OBJECT_MAPPER.valueToTree(value);
  }

  private static BigDecimal actionParameterDecimal(String caseId, String parameter) {
    return scenario(caseId).actions().stream()
        .map(ScenarioCatalogTest::json)
        .map(action -> action.path("parameters").path(parameter))
        .filter(JsonNode::isNumber)
        .map(JsonNode::decimalValue)
        .findFirst()
        .orElseThrow();
  }

  private static void assertConditionalPath(
      String caseId,
      boolean markPrice,
      int expectedComparison
  ) {
    ScenarioDefinition scenario = scenario(caseId);
    BigDecimal trigger = scenario.actions().getFirst().parameters().triggerPrice();
    BigDecimal placement = markPrice
        ? scenario.priceSteps().get(1).mark()
        : scenario.priceSteps().get(1).last();
    BigDecimal execution = markPrice
        ? scenario.priceSteps().get(2).mark()
        : scenario.priceSteps().get(2).last();
    assertThat(placement).as(caseId + " placement").isLessThan(trigger);
    assertThat(execution.compareTo(trigger)).as(caseId + " trigger")
        .isEqualTo(expectedComparison);
  }

  private static void assertProtectionSafeAt(
      ScenarioAction protection,
      BigDecimal mark,
      String caseId
  ) {
    assertThat(protection.parameters().protectionType()).as(caseId).isNotNull();
    assertThat(protection.parameters().triggerPriceType()).as(caseId).isNotNull();
    assertThat(protection.parameters().triggerExecutionType()).as(caseId).isNotNull();
    if (protection.parameters().protectionType().name().equals("TAKE_PROFIT")) {
      assertThat(mark).as(caseId).isLessThan(protection.parameters().triggerPrice());
    } else {
      assertThat(mark).as(caseId).isGreaterThan(protection.parameters().triggerPrice());
    }
  }

  private static BigDecimal crossRiskGap(ScenarioDefinition scenario) {
    ScenarioAction entry = scenario.actions().getFirst();
    BigDecimal quantity = entry.quantity();
    ScenarioPriceStep entryMarket = scenario.priceSteps().get(1);
    BigDecimal entryPrice = entry.parameters().side() == OrderSide.BUY
        ? entryMarket.ask().multiply(new BigDecimal("1.0001"))
        : entryMarket.bid().multiply(new BigDecimal("0.9999"));
    entryPrice = entryPrice.setScale(8, java.math.RoundingMode.HALF_UP);
    BigDecimal openingFee = quantity.multiply(entryPrice)
        .multiply(new BigDecimal("0.0005"));
    ScenarioPriceStep riskMarket = scenario.priceSteps().get(2);
    BigDecimal unrealized = entry.parameters().side() == OrderSide.BUY
        ? riskMarket.mark().subtract(entryPrice).multiply(quantity)
        : entryPrice.subtract(riskMarket.mark()).multiply(quantity);
    BigDecimal threshold = quantity.multiply(riskMarket.mark())
        .multiply(new BigDecimal("0.0055"));
    return new BigDecimal(scenario.initialBalances().get("USDT"))
        .subtract(openingFee)
        .add(unrealized)
        .subtract(threshold)
        .setScale(8, java.math.RoundingMode.HALF_UP);
  }

  private static void assertPriorSuccessWasPreserved(ScenarioDefinition scenario) {
    assertThat(scenario.expectedOrder()).doesNotContain("WITHOUT_MUTATION");
    assertThat(scenario.expectedTrades()).isNotEqualTo("NONE");
    assertThat(scenario.expectedPosition()).isNotEqualTo("UNCHANGED");
    assertThat(scenario.expectedWallet()).isNotEqualTo("UNCHANGED");
    assertThat(scenario.expectedAccount()).isNotEqualTo("UNCHANGED");
    assertThat(scenario.expectedLedger()).isNotEqualTo("NONE");
  }

  private static List<String> fieldNames(JsonNode row) {
    List<String> names = new ArrayList<>();
    row.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static void assertUtf8Lf(byte[] bytes) {
    String text = new String(bytes, StandardCharsets.UTF_8);
    assertThat(text).doesNotStartWith("\uFEFF");
    assertThat(text).doesNotContain("\r");
    assertThat(text).endsWith("\n");
  }

  private static List<List<String>> parseCsv(String csv) throws IOException {
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    for (int index = 0; index < csv.length(); index++) {
      char current = csv.charAt(index);
      if (quoted) {
        if (current == '"' && index + 1 < csv.length() && csv.charAt(index + 1) == '"') {
          field.append('"');
          index++;
        } else if (current == '"') {
          quoted = false;
        } else {
          field.append(current);
        }
      } else if (current == '"') {
        quoted = true;
      } else if (current == ',') {
        row.add(field.toString());
        field.setLength(0);
      } else if (current == '\n') {
        row.add(field.toString());
        field.setLength(0);
        rows.add(List.copyOf(row));
        row.clear();
      } else {
        field.append(current);
      }
    }
    if (quoted) {
      throw new IOException("Unclosed quoted CSV field");
    }
    if (!row.isEmpty() || !field.isEmpty()) {
      row.add(field.toString());
      rows.add(List.copyOf(row));
    }
    return rows;
  }
}
