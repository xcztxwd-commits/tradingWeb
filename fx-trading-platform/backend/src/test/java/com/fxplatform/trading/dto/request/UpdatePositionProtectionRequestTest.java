package com.fxplatform.trading.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class UpdatePositionProtectionRequestTest {

  @Test
  void acceptsNullProtectionPricesSoUsersCanClearThem() {
    Validator validator = validator();

    assertThat(validator.validate(new UpdatePositionProtectionRequest(null, null))).isEmpty();
  }

  @Test
  void rejectsNonPositiveProtectionPrices() {
    Validator validator = validator();

    var violations = validator.validate(new UpdatePositionProtectionRequest(BigDecimal.ZERO, new BigDecimal("-1")));

    assertThat(violations).hasSize(2);
    assertThat(violations).extracting("propertyPath").map(Object::toString)
        .containsExactlyInAnyOrder("stopLoss", "takeProfit");
  }

  private static Validator validator() {
    ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    return factory.getValidator();
  }
}
