package com.fxplatform.auth.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

class AuthRequestValidationTest {

  @Test
  void registerAcceptsArbitraryIdentifierAndPasswordInput() {
    Validator validator = validator();

    var violations = validator.validate(new RegisterRequest("not an email", null, "1"));

    assertThat(violations).isEmpty();
  }

  @Test
  void registerAcceptsBlankFormInputSoTheServiceCanPersistIt() {
    Validator validator = validator();

    var violations = validator.validate(new RegisterRequest("", null, ""));

    assertThat(violations).isEmpty();
  }

  @Test
  void loginAcceptsArbitraryIdentifierAndPasswordInput() {
    Validator validator = validator();

    var violations = validator.validate(new LoginRequest("+60 123", ""));

    assertThat(violations).isEmpty();
  }

  private static Validator validator() {
    ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    return factory.getValidator();
  }
}
