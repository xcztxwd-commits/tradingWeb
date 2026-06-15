package com.fxplatform.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fxplatform.admin.dto.request.AdminAssignUserRoleRequest;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class AdminRbacControllerTest {

  @Test
  void requiresAdminRoleForRbacEndpoints() {
    PreAuthorize preAuthorize = AdminRbacController.class.getAnnotation(PreAuthorize.class);

    assertThat(preAuthorize).isNotNull();
    assertThat(preAuthorize.value()).isEqualTo("hasRole('ADMIN')");
  }

  @Test
  void assignUserRoleRequestRejectsMissingUserIdAtValidationBoundary() throws NoSuchFieldException {
    assertThat(AdminAssignUserRoleRequest.class.getDeclaredField("userId").isAnnotationPresent(NotNull.class))
        .isTrue();
  }
}
