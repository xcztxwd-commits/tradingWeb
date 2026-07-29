package com.fxplatform.trading.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.market.provider.MarketBundleResolver;
import com.fxplatform.trading.scenario.oracle.PerpetualScenarioOracle;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest(properties = {
    "spring.task.scheduling.enabled=false",
    "trading.funding.enabled=false",
    "trading.liquidation.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("scenario-it")
@ContextConfiguration(initializers = ScenarioDatabaseGuard.class)
@Import({ScenarioFixture.class, ScenarioResultReader.class, ScenarioExecutor.class})
@EnabledIfSystemProperty(named = "scenario.it.enabled", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PerpetualScenarioMatrixIT {

  @Autowired ScenarioFixture fixture;
  @Autowired ScenarioExecutor executor;
  @Autowired ObjectMapper objectMapper;
  @MockBean MarketBundleResolver marketBundleResolver;

  private final PerpetualScenarioOracle oracle = new PerpetualScenarioOracle();

  Stream<Arguments> scenarios() {
    return ScenarioCatalog.perpetual()
        .filter(scenario -> scenario.testClass().equals(getClass().getName()))
        .map(scenario -> Arguments.of(scenario.caseId(), scenario));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scenarios")
  void executesScenario(String caseId, ScenarioDefinition scenario) {
    assertThat(caseId).isEqualTo(scenario.caseId());
    List<ExpectedScenarioResult> expectations = oracle.calculateAll(scenario);
    ScenarioContext context = null;
    try {
      context = fixture.create(scenario);
      ActualScenarioResult actual = executor.execute(context, scenario);
      ScenarioTestArtifacts.write(objectMapper, scenario, expectations, actual);
      ScenarioAssertions.assertScenarioEquals(scenario, expectations, actual);
    } finally {
      fixture.cleanup(context);
    }
  }
}
