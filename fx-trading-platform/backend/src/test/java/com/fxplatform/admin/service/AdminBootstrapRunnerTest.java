package com.fxplatform.admin.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapRunnerTest {

  @Mock
  private AdminBootstrapService adminBootstrapService;

  @Mock
  private ApplicationArguments arguments;

  @Test
  void skipsTransactionalBootstrapWhenDisabled() {
    AdminBootstrapProperties properties = new AdminBootstrapProperties(false, "", "");

    new AdminBootstrapRunner(adminBootstrapService, properties).run(arguments);

    verifyNoInteractions(adminBootstrapService);
  }

  @Test
  void delegatesBootstrapWhenEnabled() {
    AdminBootstrapProperties properties = new AdminBootstrapProperties(true, "admin@example.com", "Password123!");

    new AdminBootstrapRunner(adminBootstrapService, properties).run(arguments);

    verify(adminBootstrapService).bootstrap();
  }
}
