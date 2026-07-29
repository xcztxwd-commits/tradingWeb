package com.fxplatform.trading.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fxplatform.common.exception.BusinessException;
import com.fxplatform.common.exception.ErrorCode;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

class ScenarioExecutorContractTest {

  private static final Path PACKAGE = Path.of(
      "src/test/java/com/fxplatform/trading/scenario");

  @Test
  void executorExposesThePlanInterfaceAndUsesEveryRequiredProductionActionBoundary()
      throws Exception {
    assertThat(ScenarioExecutor.class.getDeclaredMethod(
        "execute", ScenarioContext.class, ScenarioDefinition.class)).isNotNull();

    String source = Files.readString(PACKAGE.resolve("ScenarioExecutor.java"));
    assertThat(source).contains(
        "OrderService",
        "OcoOrderService",
        "OrderFillService",
        "PendingOrderExecutionProcessor",
        "PendingOrderExecutionService",
        "SystemCloseOrderService",
        "TradingSettingsService",
        "PositionMarginService",
        "ProtectionOrderService",
        "ProtectiveOrderExecutionService",
        "FundingService",
        "LiquidationService",
        "CancelAllOrderService",
        "CloseAllPositionService");
    assertThat(source).contains(
        "BusinessException",
        "readSnapshot(context)",
        "Math.min(actionIndex + 1",
        "MARKET_SOURCE_CHANGED",
        "REVALUE",
        "MockMvc",
        "/api/admin/trading/positions/{positionId}/force-close",
        "ROLE_ADMIN",
        "trading:position:force-close",
        "CONFIRM_FORCE_CLOSE");
  }

  @Test
  void resilienceActionsEnterTheProductionFreshnessAndFullFillGuards()
      throws Exception {
    String source = Files.readString(PACKAGE.resolve("ScenarioExecutor.java"));

    assertThat(source).contains(
        "LOCK_WAIT_EXCEEDS_MARKET_BUNDLE_TTL",
        "core.trading_accounts",
        "FOR UPDATE",
        "DataSource",
        "PARTIALLY_FILLED_INPUT_IS_COMPAT_ONLY",
        "orderFillService.fillPerpetual(",
        "new FullFillResult(");
  }

  @Test
  void initialPerpetualPositionIsRevaluedAtTheBaselineMarketBeforeActions()
      throws Exception {
    String source = Files.readString(PACKAGE.resolve("ScenarioExecutor.java"));
    int configure = source.indexOf("configureMarket(context, resolvedSource)");
    int baseline = source.indexOf("revalue(context, scenario)", configure);
    int actions = source.indexOf("for (int actionIndex = 0;", configure);

    assertThat(source.substring(configure, actions)).contains(
        "scenario.productType() == ProductType.LINEAR_PERP",
        "!\"NONE\".equalsIgnoreCase(scenario.initialPosition())");
    assertThat(baseline).isGreaterThan(configure).isLessThan(actions);
  }

  @Test
  void perpetualRacesBindEachRealWinnerToItsDeclaredCatalogReference()
      throws Exception {
    String source = Files.readString(PACKAGE.resolve("ScenarioExecutor.java"));
    String race = source.substring(
        source.indexOf("private void race("),
        source.indexOf("private void raceOco("));

    assertThat(race).contains(
        "bindRaceBatchWinnerAndRequireSuccess(",
        "liquidate(context, action, action.parameters().competingOrderId())",
        "bindRaceClose(",
        "action.parameters().orderId()",
        "action.parameters().competingOrderId()");
    assertThat(source).contains("bindLiquidationOrders(");
  }

  @Test
  void ocoRaceMakesBothLegsExecutableAndTreatsFalseAsALegalLoss()
      throws Exception {
    String source = Files.readString(PACKAGE.resolve("ScenarioExecutor.java"));
    String oco = source.substring(
        source.indexOf("private void raceOco("),
        source.indexOf("private void raceSpotBalance("));

    assertThat(oco).contains(
        "ScenarioPriceStep executableLimitMarket = executableSellLimitStep(",
        "ScenarioPriceStep executableStopMarket = executableSellStopStep(",
        "spotBundle(context, executableLimitMarket)",
        "spotBundle(context, executableStopMarket)",
        "RaceOutcome.fromCommit(",
        "market.last().min(triggerPrice)");
    assertThat(oco).doesNotContain(
        "OCO LIMIT competitor did not reach",
        "spotBundle(context, context.currentPriceStep())");
  }

  @Test
  void executorBindsRandomDatabaseReferencesToDeterministicScenarioReferences()
      throws Exception {
    String source = Files.readString(PACKAGE.resolve("ScenarioExecutor.java"));

    assertThat(source).contains(
        "bindBatchOrders(",
        "item.positionId()",
        "logicalPrefix + \"-\" + slot",
        "bindLiquidationOrders(",
        "parent_position_id",
        "ORDER_MODIFICATION",
        "trading.funding_settlements",
        "CROSS_LIQUIDATION_SETTLEMENT",
        "action.parameters().actionId()");
  }

  @Test
  void racesWithDifferentEconomicOutcomesShareOneStartBarrier()
      throws Exception {
    String source = Files.readString(PACKAGE.resolve("ScenarioExecutor.java"));
    String race = source.substring(
        source.indexOf("private void race("),
        source.indexOf("private void raceSpotBalance("));
    String runner = source.substring(
        source.indexOf("private void runRace("),
        source.indexOf("private static void await("));

    assertThat(race).contains(
        "runRace(",
        "OCO_DUAL_TRIGGER",
        "SINGLE_CLOSE_COMPETES_WITH_CLOSE_ALL",
        "USER_CLOSE_COMPETES_WITH_LIQUIDATION",
        "USER_CLOSE_COMPETES_WITH_STOP_LOSS");
    assertThat(runner).contains(
        "CountDownLatch ready = new CountDownLatch(competitors.size())",
        "CountDownLatch start = new CountDownLatch(1)",
        "workers.submit",
        "ready.countDown()",
        "await(start)",
        "ready.await(20, TimeUnit.SECONDS)",
        "finally",
        "future.cancel(true)",
        "start.countDown()",
        "workers.shutdownNow()");
    assertThat(source).doesNotContain(
        "runPreferredRace(",
        "preferredCompleted",
        "await(preferredCompleted)");
  }

  @Test
  void closeAllPartialFailureUsesASecondSlotDatabaseFailureInsteadOfARace()
      throws Exception {
    String source = Files.readString(PACKAGE.resolve("ScenarioExecutor.java"));
    String closeAll = source.substring(
        source.indexOf("private void closeAll("),
        source.indexOf("private void adminForceClose("));

    assertThat(closeAll).contains(
        "ONE_SLOT_VERSION_BECOMES_STALE",
        "installPositionUpdateFailureTrigger(",
        "closeAllPositionService.closeUser(",
        "bindBatchOrders(",
        "requireOneBatchSuccessAndOneFailure(",
        "finally",
        "dropFailureTriggers()");
    assertThat(closeAll).doesNotContain("runRace(");
  }

  @Test
  void matrixClassesExposeExactlyTheCatalogMethodWithCaseIdAsDisplayName()
      throws Exception {
    assertMatrixContract(SpotScenarioMatrixIT.class);
    assertMatrixContract(PerpetualScenarioMatrixIT.class);
    assertMatrixContract(ScenarioResilienceIT.class);
  }

  @Test
  void spotBalanceRaceSubmitsBothOrdersTogetherAndRequiresOneWinnerOneLoser()
      throws Exception {
    String source = Files.readString(PACKAGE.resolve("ScenarioExecutor.java"));
    String balanceRace = source.substring(
        source.indexOf("private void raceSpotBalance("),
        source.indexOf("private void revalue("));
    String raceRunner = source.substring(
        source.indexOf("private void runRace("),
        source.indexOf("private static void await("));

    assertThat(balanceRace).contains(
        "runRace(",
        "orderService.createOrder(",
        "action.quantity()",
        "parameters.orderId()",
        "parameters.competingOrderId()",
        "parameters.clientOrderId()");
    assertThat(balanceRace.lines()
        .filter(line -> line.contains(
            "bindOrder(context, parameters.orderId(), response)"))
        .count()).isEqualTo(2);
    assertThat(raceRunner).contains(
        "List<Supplier<RaceOutcome>> competitors",
        "committed != 1 || legalLosses != 1");
  }

  @Test
  void raceRunnerUsesOnlyTypedOutcomesAndNeverClassifiesThrownFailures()
      throws Exception {
    String source = Files.readString(PACKAGE.resolve("ScenarioExecutor.java"));
    String runner = source.substring(
        source.indexOf("private void runRace("),
        source.indexOf("private static void await("));

    assertThat(runner).contains(
        "List<Supplier<RaceOutcome>> competitors",
        "List<Future<RaceOutcome>> futures",
        "RaceStatus.COMMITTED",
        "RaceStatus.LEGAL_LOSS",
        "throw unwrap(exception)",
        "committed != 1 || legalLosses != 1");
    assertThat(runner).doesNotContain(
        "successes",
        "failures.add",
        "instanceof BusinessException");
  }

  @Test
  void raceCompetitorWhitelistIsExplicitAndRethrowsEverythingElse()
      throws Exception {
    String source = Files.readString(PACKAGE.resolve("ScenarioExecutor.java"));
    String wrapper = source.substring(
        source.indexOf("private static Supplier<RaceOutcome> allowLegalRaceLoss("),
        source.indexOf("private void runRace("));

    assertThat(wrapper).contains(
        "catch (BusinessException failure)",
        "TWO_6000_USDT_ORDERS_COMPETE_FOR_10000",
        "ErrorCode.INSUFFICIENT_BALANCE",
        "TWO_FULL_CLOSES_COMPETE_FOR_ONE_POSITION",
        "SINGLE_CLOSE_COMPETES_WITH_CLOSE_ALL",
        "USER_CLOSE_COMPETES_WITH_LIQUIDATION",
        "ErrorCode.POSITION_NOT_FOUND",
        "USER_CLOSE_COMPETES_WITH_STOP_LOSS",
        "ErrorCode.PROTECTION_NOT_EXECUTABLE",
        "throw failure",
        "RaceOutcome.legalLoss(failure)");
    assertThat(wrapper).doesNotContain(
        "catch (RuntimeException",
        "catch (Throwable");
  }

  @Test
  void liquidationRaceAcceptsOnlyItsOwnPreparedSnapshotStalenessAsALegalLoss()
      throws Exception {
    var method = ScenarioExecutor.class.getDeclaredMethod(
        "isLegalRaceLoss", String.class, String.class);
    method.setAccessible(true);

    assertThat(method.invoke(
        null,
        "USER_CLOSE_COMPETES_WITH_LIQUIDATION",
        ErrorCode.MARKET_DATA_STALE)).isEqualTo(true);
    assertThat(method.invoke(
        null,
        "TWO_FULL_CLOSES_COMPETE_FOR_ONE_POSITION",
        ErrorCode.MARKET_DATA_STALE)).isEqualTo(false);
    assertThat(method.invoke(
        null,
        "USER_CLOSE_COMPETES_WITH_STOP_LOSS",
        ErrorCode.MARKET_DATA_STALE)).isEqualTo(false);
  }

  @Test
  void raceRunnerPropagatesNullPointerFailuresEvenWhenItsPeerReturns()
      throws Exception {
    NullPointerException failure = new NullPointerException("race-npe");

    assertThatThrownBy(() -> invokeRaceRunner(
        raceAction("OCO_DUAL_TRIGGER"),
        List.of(
            () -> { throw failure; },
            () -> Boolean.TRUE)))
        .isSameAs(failure);
  }

  @Test
  void raceRunnerPropagatesSqlFailuresEvenWhenItsPeerReturns()
      throws Exception {
    RuntimeException failure = new RuntimeException(
        "race-sql", new SQLException("deadlock"));

    assertThatThrownBy(() -> invokeRaceRunner(
        raceAction("OCO_DUAL_TRIGGER"),
        List.of(
            () -> { throw failure; },
            () -> Boolean.TRUE)))
        .isSameAs(failure);
  }

  @Test
  void raceRunnerPropagatesNonWhitelistedBusinessFailuresEvenWhenItsPeerReturns()
      throws Exception {
    BusinessException failure = new BusinessException(
        ErrorCode.EXECUTION_UNAVAILABLE, "unexpected race failure");

    assertThatThrownBy(() -> invokeRaceRunner(
        raceAction("OCO_DUAL_TRIGGER"),
        List.of(
            () -> { throw failure; },
            () -> Boolean.TRUE)))
        .isSameAs(failure);
  }

  @Test
  void spotProtectionRejectionUsesTheRealSpotOrderInputBoundary()
      throws Exception {
    String source = Files.readString(PACKAGE.resolve("ScenarioExecutor.java"));
    String protection = source.substring(
        source.indexOf("private void setProtection("),
        source.indexOf("private void settleFunding("));

    assertThat(protection).contains(
        "ProductType.CRYPTO_SPOT",
        "spotProtectionRejectRequest(",
        "orderService.createOrder(");
    assertThat(protection).doesNotContain("UUID.randomUUID()");
  }

  @Test
  void spotLeverageRejectionUsesTheRealSpotOrderInputBoundary()
      throws Exception {
    String source = Files.readString(PACKAGE.resolve("ScenarioExecutor.java"));
    String leverage = source.substring(
        source.indexOf("private void changeLeverage("),
        source.indexOf("private void adjustMargin("));

    assertThat(leverage).contains(
        "ProductType.CRYPTO_SPOT",
        "spotLeverageRejectRequest(",
        "orderService.createOrder(",
        "tradingSettingsService.updateSymbolSettings(");
  }

  @Test
  void attachedPerpetualProtectionDoesNotLeakTriggerFieldsOntoTheMarketEntry()
      throws Exception {
    String source = Files.readString(PACKAGE.resolve("ScenarioExecutor.java"));
    String request = source.substring(
        source.indexOf("private CreateOrderRequest createOrderRequest("),
        source.indexOf("private void closePosition("));

    assertThat(request).contains(
        "boolean attachedToEntry =",
        "BigDecimal orderPrice = attachedToEntry ? null : parameters.price()",
        "BigDecimal orderTriggerPrice = attachedToEntry ? null : parameters.triggerPrice()",
        "TriggerPriceType orderTriggerPriceType = attachedToEntry ? null : triggerPriceType");
  }

  @Test
  void matrixExecutionCalculatesOracleBeforeCreatingAnyProductionFixture()
      throws Exception {
    for (String file : new String[]{
        "SpotScenarioMatrixIT.java",
        "PerpetualScenarioMatrixIT.java",
        "ScenarioResilienceIT.java"}) {
      String source = Files.readString(PACKAGE.resolve(file));
      int oracle = source.indexOf("calculateAll(scenario)");
      if (oracle < 0) {
        oracle = source.indexOf("calculate(scenario)");
      }
      int fixture = source.indexOf("fixture.create(scenario)");
      int executor = source.indexOf("executor.execute(context, scenario)");
      assertThat(oracle).as(file + " oracle").isGreaterThanOrEqualTo(0);
      assertThat(fixture).as(file + " fixture").isGreaterThan(oracle);
      assertThat(executor).as(file + " executor").isGreaterThan(fixture);
      assertThat(source).contains("finally", "fixture.cleanup(context)");
    }
  }

  private static void assertMatrixContract(Class<?> type) throws Exception {
    assertThat(type.getAnnotation(AutoConfigureMockMvc.class)).isNotNull();
    var method = type.getDeclaredMethod(
        "executesScenario", String.class, ScenarioDefinition.class);
    assertThat(method.getAnnotation(ParameterizedTest.class))
        .extracting(ParameterizedTest::name)
        .isEqualTo("{0}");
    assertThat(method.getAnnotation(MethodSource.class))
        .extracting(MethodSource::value)
        .isEqualTo(new String[]{"scenarios"});
    assertThat(type.getDeclaredMethod("scenarios").getReturnType())
        .isEqualTo(Stream.class);
  }

  private static ScenarioAction raceAction(String condition) {
    return ScenarioCatalog.all().stream()
        .flatMap(scenario -> scenario.actions().stream())
        .filter(action -> condition.equals(action.parameters().condition()))
        .findFirst()
        .orElseThrow();
  }

  private static void invokeRaceRunner(
      ScenarioAction action,
      List<Supplier<?>> competitors
  ) throws Exception {
    var constructor = ScenarioExecutor.class.getDeclaredConstructors()[0];
    ScenarioExecutor executor = (ScenarioExecutor) constructor.newInstance(
        new Object[constructor.getParameterCount()]);
    var method = ScenarioExecutor.class.getDeclaredMethod(
        "runRace", ScenarioAction.class, List.class);
    method.setAccessible(true);
    try {
      method.invoke(executor, action, competitors);
    } catch (InvocationTargetException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof Exception checked) {
        throw checked;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new RuntimeException(cause);
    }
  }
}
