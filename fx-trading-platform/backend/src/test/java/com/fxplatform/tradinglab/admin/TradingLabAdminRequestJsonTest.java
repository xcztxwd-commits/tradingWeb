package com.fxplatform.tradinglab.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.tradinglab.admin.dto.TradingLabRunCreateRequest;
import com.fxplatform.tradinglab.admin.dto.TradingLabScenarioWriteRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TradingLabAdminRequestJsonTest {

  private static final String HASH = "0".repeat(64);

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void scenarioRequestRejectsDuplicateCanonicalObjectKeys() {
    String request = """
        {
          "name":"scenario",
          "description":"",
          "negativeMode":false,
          "seed":7,
          "modelVersion":"model-v1",
          "scenario":{"ticks":[],"ticks":[]},
          "configSnapshot":{},
          "configSnapshotHash":"%s"
        }
        """.formatted(HASH);

    assertThatThrownBy(() -> json.readValue(request, TradingLabScenarioWriteRequest.class))
        .isInstanceOf(Exception.class)
        .hasMessageContaining("Duplicate field 'ticks'");
  }

  @Test
  void runRequestRejectsDuplicateLocalCalculationKeysAndUnknownFields() {
    String duplicate = """
        {"scenarioVersion":0,"configSnapshotHash":"%s",
         "localCalculation":{"equity":"1","equity":"2"}}
        """.formatted(HASH);
    String unknown = """
        {"scenarioVersion":0,"configSnapshotHash":"%s",
         "localCalculation":{},"internalToken":"forbidden"}
        """.formatted(HASH);

    assertThatThrownBy(() -> json.readValue(duplicate, TradingLabRunCreateRequest.class))
        .isInstanceOf(Exception.class)
        .hasMessageContaining("Duplicate field 'equity'");
    assertThatThrownBy(() -> json.readValue(unknown, TradingLabRunCreateRequest.class))
        .isInstanceOf(Exception.class)
        .hasMessageContaining("Unknown Trading Lab run request property");
  }

  @Test
  void runRequestRejectsDuplicateTopLevelCasFieldsBeforeLegalLastValuesCanWin() {
    String duplicateVersion = """
        {"scenarioVersion":-1,"scenarioVersion":0,"configSnapshotHash":"%s",
         "localCalculation":{}}
        """.formatted(HASH);
    String duplicateHash = """
        {"scenarioVersion":0,
         "configSnapshotHash":"malicious",
         "configSnapshotHash":"%s",
         "localCalculation":{}}
        """.formatted(HASH);

    assertDuplicateRejected(
        duplicateVersion,
        TradingLabRunCreateRequest.class,
        "scenarioVersion");
    assertDuplicateRejected(
        duplicateHash,
        TradingLabRunCreateRequest.class,
        "configSnapshotHash");
  }

  @Test
  void scenarioRequestRejectsDuplicateTopLevelFieldsBeforeLegalLastValuesCanWin() {
    String duplicateName = """
        {"name":"<script>malicious</script>","name":"scenario",
         "description":"","negativeMode":false,"seed":7,"modelVersion":"model-v1",
         "scenario":{},"configSnapshot":{},"configSnapshotHash":"%s"}
        """.formatted(HASH);
    String duplicateScenario = """
        {"name":"scenario","description":"","negativeMode":false,"seed":7,
         "modelVersion":"model-v1",
         "scenario":{"internalToken":"malicious"},"scenario":{},
         "configSnapshot":{},"configSnapshotHash":"%s"}
        """.formatted(HASH);

    assertDuplicateRejected(
        duplicateName,
        TradingLabScenarioWriteRequest.class,
        "name");
    assertDuplicateRejected(
        duplicateScenario,
        TradingLabScenarioWriteRequest.class,
        "scenario");
  }

  @Test
  void requestLevelReaderRejectsDuplicatesAtAnyNestedDepth() {
    String request = """
        {"name":"scenario","description":"","negativeMode":false,"seed":7,
         "modelVersion":"model-v1","scenario":{},
         "configSnapshot":{"instruments":[
           {"symbol":"malicious","symbol":"BTCUSDT"}
         ]},
         "configSnapshotHash":"%s"}
        """.formatted(HASH);

    assertDuplicateRejected(
        request,
        TradingLabScenarioWriteRequest.class,
        "symbol");
  }

  @Test
  void validJsonNodeShapesAndMissingBeanValidatedFieldsKeepTheirPreviousBindingBehavior()
      throws Exception {
    TradingLabRunCreateRequest run = json.readValue("""
        {"scenarioVersion":7,"configSnapshotHash":"%s",
         "localCalculation":{"equity":"100.00","steps":[1,2]}}
        """.formatted(HASH), TradingLabRunCreateRequest.class);
    TradingLabScenarioWriteRequest scenario = json.readValue("""
        {"name":"scenario","description":"description","negativeMode":true,
         "seed":"seed-9","modelVersion":"model-v1",
         "scenario":{"ticks":[]},"configSnapshot":{"instruments":[]},
         "configSnapshotHash":"%s","expectedVersion":3}
        """.formatted(HASH), TradingLabScenarioWriteRequest.class);
    TradingLabRunCreateRequest missingRun =
        json.readValue("{}", TradingLabRunCreateRequest.class);
    TradingLabScenarioWriteRequest missingScenario =
        json.readValue("{}", TradingLabScenarioWriteRequest.class);

    assertThat(run.scenarioVersion()).isEqualTo(7L);
    assertThat(run.localCalculation().path("equity").asText()).isEqualTo("100.00");
    assertThat(run.localCalculation().path("steps")).hasSize(2);
    assertThat(scenario.negativeMode()).isTrue();
    assertThat(scenario.seed()).isEqualTo("seed-9");
    assertThat(scenario.scenario().path("ticks")).isEmpty();
    assertThat(scenario.configSnapshot().path("instruments")).isEmpty();
    assertThat(scenario.expectedVersion()).isEqualTo(3L);
    assertThat(missingRun.scenarioVersion()).isNull();
    assertThat(missingRun.configSnapshotHash()).isNull();
    assertThat(missingRun.localCalculation()).isNull();
    assertThat(missingScenario.name()).isNull();
    assertThat(missingScenario.negativeMode()).isFalse();
    assertThat(missingScenario.scenario()).isNull();
  }

  @Test
  void scenarioSeedRequiresAnExactJsonStringWithoutNumericCoercion() throws Exception {
    String valid = """
        {"name":"scenario","description":"","negativeMode":false,
         "seed":"001","modelVersion":"model-v1",
         "scenario":{},"configSnapshot":{},"configSnapshotHash":"%s"}
        """.formatted(HASH);
    String numeric = valid.replace("\"seed\":\"001\"", "\"seed\":1");

    assertThat(json.readValue(valid, TradingLabScenarioWriteRequest.class).seed())
        .isEqualTo("001");
    assertThatThrownBy(() -> json.readValue(numeric, TradingLabScenarioWriteRequest.class))
        .isInstanceOf(Exception.class)
        .hasMessageContaining("seed");
  }

  @Test
  void duplicateRootKeyReturnsBadRequestBeforeTheAdminServiceIsInvoked() throws Exception {
    TradingLabAdminService service = mock(TradingLabAdminService.class);
    TradingLabAdminRunControlFacade controls = mock(TradingLabAdminRunControlFacade.class);
    MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new TradingLabAdminController(service, controls))
        .setControllerAdvice(new TradingLabAdminExceptionHandler())
        .build();
    String request = """
        {"scenarioVersion":-1,"scenarioVersion":0,"configSnapshotHash":"%s",
         "localCalculation":{}}
        """.formatted(HASH);

    mvc.perform(post(
            "/api/admin/trading-lab/scenarios/"
                + "10000000-0000-0000-0000-000000000802/runs")
            .contentType(MediaType.APPLICATION_JSON)
            .content(request))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TRADING_LAB_REQUEST_INVALID"));
    verifyNoInteractions(service, controls);
  }

  @Test
  void requestLevelReaderRejectsExcessiveDepthAndNodeCountBeforeDtoBinding() {
    String tooDeep = """
        {"scenarioVersion":0,"configSnapshotHash":"%s","localCalculation":
        """.formatted(HASH)
        + "[".repeat(65)
        + "0"
        + "]".repeat(65)
        + "}";
    String tooManyUnknownNodes = """
        {"scenarioVersion":0,"configSnapshotHash":"%s","localCalculation":{},
         "junk":[
        """.formatted(HASH)
        + "null,".repeat(100_000)
        + "null]}";

    assertThatThrownBy(() -> json.readValue(
        tooDeep,
        TradingLabRunCreateRequest.class))
        .isInstanceOf(Exception.class)
        .hasMessageContaining("JSON budget");
    assertThatThrownBy(() -> json.readValue(
        tooManyUnknownNodes,
        TradingLabRunCreateRequest.class))
        .isInstanceOf(Exception.class)
        .hasMessageContaining("JSON budget");
  }

  @Test
  void requestLevelReaderRejectsTrailingRootContent() {
    String request = """
        {"scenarioVersion":0,"configSnapshotHash":"%s","localCalculation":{}}
        {"ignored":{"internalToken":"forbidden"}}
        """.formatted(HASH);

    assertThatThrownBy(() -> json.readValue(
        request,
        TradingLabRunCreateRequest.class))
        .isInstanceOf(Exception.class)
        .hasMessageContaining("Trailing Trading Lab JSON content");
  }

  private void assertDuplicateRejected(
      String request,
      Class<?> requestType,
      String field
  ) {
    assertThatThrownBy(() -> json.readValue(request, requestType))
        .isInstanceOf(Exception.class)
        .hasMessageContaining("Duplicate field '" + field + "'");
  }
}
