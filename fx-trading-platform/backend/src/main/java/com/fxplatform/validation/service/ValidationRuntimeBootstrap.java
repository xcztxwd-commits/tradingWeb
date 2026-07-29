package com.fxplatform.validation.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Starts the pacer-independent durable-generation monitor after the application is ready. */
@Profile("validation")
@Component
public class ValidationRuntimeBootstrap {

  private final ValidationRuntimeGenerationSynchronizer generationSynchronizer;

  public ValidationRuntimeBootstrap(
      ValidationRuntimeGenerationSynchronizer generationSynchronizer
  ) {
    this.generationSynchronizer = generationSynchronizer;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void restoreDurableGeneration() {
    generationSynchronizer.start();
  }
}
