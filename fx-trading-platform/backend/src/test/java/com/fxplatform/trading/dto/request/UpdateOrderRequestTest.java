package com.fxplatform.trading.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class UpdateOrderRequestTest {

  @Test
  void acceptsPositiveSpotQuantityBelowGenericOneCentThreshold() {
    UpdateOrderRequest request = new UpdateOrderRequest(
        new BigDecimal("0.0001"),
        new BigDecimal("60000"),
        null,
        null);

    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      assertThat(factory.getValidator().validate(request)).isEmpty();
    }
  }
}
