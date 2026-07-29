package com.fxplatform.trading.scenario;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fxplatform.account.enums.AccountType;
import com.fxplatform.execution.ExecutionMode;
import com.fxplatform.market.model.ProductType;
import com.fxplatform.trading.enums.MarginMode;
import com.fxplatform.trading.enums.OrderType;
import com.fxplatform.trading.enums.PositionMode;
import com.fxplatform.trading.enums.PositionSide;
import com.fxplatform.trading.enums.QuantityUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ScenarioDefinition(
    String caseId,
    String priority,
    ProductType productType,
    PositionMode positionMode,
    PositionSide positionSide,
    MarginMode marginMode,
    int leverage,
    OrderType orderType,
    QuantityUnit quantityUnit,
    boolean reduceOnly,
    Map<String, String> initialBalances,
    String initialPosition,
    List<String> initialOrders,
    List<ScenarioPriceStep> priceSteps,
    List<ScenarioAction> actions,
    String exitReason,
    String expectedOrder,
    String expectedTrades,
    String expectedPosition,
    String expectedWallet,
    String expectedAccount,
    String expectedLedger,
    String expectedProtections,
    String expectedEvents,
    String expectedError,
    String testClass,
    String testMethod,
    String executionStatus
) {

  public static final List<String> COLUMNS = List.of(
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

  public ScenarioDefinition {
    Objects.requireNonNull(caseId, "caseId");
    Objects.requireNonNull(priority, "priority");
    Objects.requireNonNull(productType, "productType");
    Objects.requireNonNull(positionMode, "positionMode");
    Objects.requireNonNull(positionSide, "positionSide");
    Objects.requireNonNull(marginMode, "marginMode");
    Objects.requireNonNull(orderType, "orderType");
    Objects.requireNonNull(quantityUnit, "quantityUnit");
    initialBalances = Collections.unmodifiableMap(new LinkedHashMap<>(
        Objects.requireNonNull(initialBalances, "initialBalances")));
    Objects.requireNonNull(initialPosition, "initialPosition");
    initialOrders = List.copyOf(Objects.requireNonNull(initialOrders, "initialOrders"));
    priceSteps = List.copyOf(Objects.requireNonNull(priceSteps, "priceSteps"));
    actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
    Objects.requireNonNull(exitReason, "exitReason");
    Objects.requireNonNull(expectedOrder, "expectedOrder");
    Objects.requireNonNull(expectedTrades, "expectedTrades");
    Objects.requireNonNull(expectedPosition, "expectedPosition");
    Objects.requireNonNull(expectedWallet, "expectedWallet");
    Objects.requireNonNull(expectedAccount, "expectedAccount");
    Objects.requireNonNull(expectedLedger, "expectedLedger");
    Objects.requireNonNull(expectedProtections, "expectedProtections");
    Objects.requireNonNull(expectedEvents, "expectedEvents");
    Objects.requireNonNull(expectedError, "expectedError");
    Objects.requireNonNull(testClass, "testClass");
    Objects.requireNonNull(testMethod, "testMethod");
    Objects.requireNonNull(executionStatus, "executionStatus");
  }

  @JsonIgnore
  public ExecutionMode executionMode() {
    return ExecutionMode.DEMO;
  }

  @JsonIgnore
  public AccountType accountType() {
    return AccountType.DEMO;
  }
}
