package com.fxplatform.validation.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxplatform.validation.service.ValidationRunEngine.StartRequest;
import org.junit.jupiter.api.Test;

class ValidationStartRequestJsonTest {

  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  @Test
  void seedRequiresAnExactJsonStringWithoutScalarCoercion() throws Exception {
    StartRequest request = ValidationRunEngineIT.request();
    String valid = json.writeValueAsString(request);
    String numeric = valid.replace(
        "\"seed\":\"seed-validation-engine\"",
        "\"seed\":1");

    assertThat(json.readValue(valid, StartRequest.class).seed())
        .isEqualTo("seed-validation-engine");
    assertThat(numeric).isNotEqualTo(valid);
    assertThatThrownBy(() -> json.readValue(numeric, StartRequest.class))
        .isInstanceOf(Exception.class)
        .hasMessageContaining("seed");
  }
}
