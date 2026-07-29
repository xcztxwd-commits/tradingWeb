package com.fxplatform.validation.service;

/** Process-local validation state that must be cleared before publishing a new generation. */
public interface ValidationRuntimeResetParticipant {

  void clearForGeneration(long generation);
}
