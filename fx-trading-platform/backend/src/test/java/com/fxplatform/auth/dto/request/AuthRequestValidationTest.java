package com.fxplatform.auth.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

class AuthRequestValidationTest {

  @Test
  void registerRequiresIdentifierAndStrongPassword() {
    Validator validator = validator();

    var violations = validator.validate(new RegisterRequest("", null, "short"));

    assertThat(violations)
        .extracting(violation -> violation.getPropertyPath().toString())
        .contains("identifierPresent", "password");
  }

  @Test
  void registerRejectsInvalidEmailWhenEmailIsProvided() {
    Validator validator = validator();

    var violations = validator.validate(new RegisterRequest("not an email", null, "Password123!"));

    assertThat(violations)
        .extracting(violation -> violation.getPropertyPath().toString())
        .contains("email");
  }

  @Test
  void registerAcceptsPhoneOnlyInputWithStrongPassword() {
    Validator validator = validator();

    var violations = validator.validate(new RegisterRequest(null, "+60123456789", "Password123!"));

    assertThat(violations).isEmpty();
  }

  @Test
  void loginRequiresIdentifierAndPassword() {
    Validator validator = validator();

    var violations = validator.validate(new LoginRequest("", ""));

    assertThat(violations)
        .extracting(violation -> violation.getPropertyPath().toString())
        .contains("email", "password");
  }

  private static Validator validator() {
    ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    return factory.getValidator();
  }
}
